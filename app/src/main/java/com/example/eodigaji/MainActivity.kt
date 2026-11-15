package com.example.eodigaji

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.eodigaji.data.ActiveTripPhase
import com.example.eodigaji.data.ActiveTripRepository
import com.example.eodigaji.data.ActiveTripState
import com.example.eodigaji.data.ArchiveItem
import com.example.eodigaji.data.ArchiveRepository
import com.example.eodigaji.databinding.ActivityMainBinding
import com.example.eodigaji.navi.OverlayReturnMonitorService
import com.example.eodigaji.navi.PleosNaviController
import com.example.eodigaji.network.EodigajiApiClient
import com.example.eodigaji.network.ErrorBody
import com.example.eodigaji.network.KeywordStatus
import com.example.eodigaji.network.RecommendRequest
import com.example.eodigaji.network.RecommendResponse
import com.example.eodigaji.network.apiError
import com.example.eodigaji.network.toEtaCheck
import com.example.eodigaji.util.BlindNames
import com.example.eodigaji.util.RecentBlindNames
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), PleosNaviController.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var naviController: PleosNaviController
    private lateinit var archiveRepository: ArchiveRepository
    private lateinit var activeTripRepository: ActiveTripRepository

    private val handler = Handler(Looper.getMainLooper())
    private var toastHideRunnable: Runnable? = null
    private val api = EodigajiApiClient.api
    private val gson = EodigajiApiClient.gson

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        completeOverlaySettingsReturnIfPending()
    }

    enum class ScreenState {
        SPLASH,
        CONSENT,
        TERMS,
        HOME,
        TIME_ROUTE,
        KEYWORDS,
        LOADING,
        DESTINATION_SEALED,
        BLIND_DRIVE,
        ARRIVED,
        SECRET_ARCHIVE
    }

    enum class ThemeMode { LIGHT, DARK, PIXEL }

    private var currentScreen = ScreenState.SPLASH
    private var termsReturnScreen = ScreenState.CONSENT
    private var archiveReturnScreen = ScreenState.TIME_ROUTE
    private var currentTheme = ThemeMode.LIGHT
    private var isThemeInitialized = false
    private val pixelTypeface: Typeface? by lazy {
        ResourcesCompat.getFont(this, R.font.neodgm)
    }

    // User Selections
    private var isConsentChecked = false
    private var driveMinutes = 30
    private val selectedKeywordIds = mutableSetOf<String>()
    private var requireParking = false
    private var excludePaid = false
    private var hasConfiguredScenery = false
    private var remainingRedraws = 2
    private var hasCheckedPendingRatingOnLaunch = false

    // API / Session State
    private var keywordStatusMap: Map<String, KeywordStatus> = emptyMap()
    private val sessionExcludedIds = mutableListOf<String>()
    private var currentRecommendation: RecommendResponse? = null
    private var activeBlindName: String = "숨겨진 평온"
    private var initialEtaSeconds: Int = 0
    private var initialDistanceMeters: Int = 0
    private var observedEtaSeconds: Int = 0
    private var observedDistanceMeters: Int = 0
    private var activeTripStartedAtMillis: Long = 0L
    private var lastPersistedProgressAtMillis: Long = 0L
    private var isCurrentArrivalSaved: Boolean = false
    private var isActivityInForeground: Boolean = false
    private var hasAutoReturnedForCurrentTrip: Boolean = false
    private var hasPostedParkHintForCurrentTrip: Boolean = false
    private var pendingAutoReturnToast: String? = null
    private var notificationFallbackRunnable: Runnable? = null
    private var overlayHintView: View? = null
    private var overlayHintDismissRunnable: Runnable? = null
    private var awaitingOverlaySettingsReturn: Boolean = false
    private var overlayWasGrantedBeforeSettings: Boolean = false
    private var overlayReturnPollRunnable: Runnable? = null
    private var overlayReturnPillView: View? = null
    private var routeMaskView: View? = null
    private var routeMaskRevealRunnable: Runnable? = null
    private var routeMaskShownAtMs: Long = 0L
    private var currentOriginLabel: String = "현위치"
    private var keywordsDebounceJob: Job? = null
    private var recommendationJob: Job? = null

    private data class KeywordCardBinding(
        val id: String,
        val label: String,
        val card: LinearLayout,
        val iconView: ImageView,
        val titleText: TextView,
        val subText: TextView
    )

    private lateinit var keywordCardBindings: List<KeywordCardBinding>

    private data class MonochromePalette(
        val canvas: Int,
        val surface: Int,
        val sub: Int,
        val selectedBg: Int,
        val selectedStroke: Int,
        val disabledBg: Int,
        val disabledStroke: Int,
        val heroDark: Int,
        val ctaBg: Int,
        val ctaText: Int,
        val disabledCtaBg: Int,
        val disabledCtaText: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textTertiary: Int,
        val textUnselected: Int,
        val heroTextPrimary: Int,
        val heroTextMuted: Int,
        val border: Int,
        val modalBg: Int,
        val toastBg: Int,
        val toastText: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        archiveRepository = ArchiveRepository(this)
        activeTripRepository = ActiveTripRepository(this)
        naviController = PleosNaviController(this, this)
        naviController.initialize()

        driveMinutes = snapDriveMinutes(archiveRepository.getLastDriveMinutes(30))

        initKeywordBindings()
        setupTopBarAndNavigation()
        setupConsentAndTerms()
        setupOptionsDialog()
        setupScreenInteractions()

        applyFontScale(archiveRepository.getFontScaleLevel())
        currentTheme = resolveEffectiveTheme()
        applyTheme(currentTheme)
        createArrivalNotificationChannel()

        val savedTrip = activeTripRepository.load()
        if (!archiveRepository.isTermsAccepted()) {
            switchScreen(ScreenState.SPLASH)
            handler.postDelayed({
                if (currentScreen == ScreenState.SPLASH) {
                    renderConsentUi()
                    switchScreen(ScreenState.CONSENT)
                }
            }, 600L)
        } else if (savedTrip != null) {
            ensureNotificationPermissionIfNeeded()
            restoreActiveTrip(savedTrip)
        } else {
            ensureNotificationPermissionIfNeeded()
            switchScreen(ScreenState.SPLASH)
            handler.postDelayed({
                if (currentScreen == ScreenState.SPLASH) {
                    switchScreen(ScreenState.HOME)
                    checkAndShowPendingRatingOnLaunch()
                }
            }, 700L)
        }
    }

    override fun onStart() {
        super.onStart()
        isActivityInForeground = true
        notificationFallbackRunnable?.let { handler.removeCallbacks(it) }
        notificationFallbackRunnable = null
        if (routeMaskView != null && SystemClock.elapsedRealtime() - routeMaskShownAtMs > 800L) {
            dismissRouteMaskCurtain(animated = false)
        }
        dismissOverlayArrivalHintBanner()
        dismissOverlayReturnPill()
        cancelArrivalReturnNotification()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cancelArrivalReturnNotification()
        if (intent.getBooleanExtra(EXTRA_AUTO_RETURNED_ON_ARRIVAL, false)) {
            val unboxed = intent.getBooleanExtra(EXTRA_AUTO_RETURNED_UNBOXED, false)
            pendingAutoReturnToast = null
            showPleosToast(autoReturnToastMessage(unboxed))
        }
    }

    override fun onResume() {
        super.onResume()
        cancelArrivalReturnNotification()
        completeOverlaySettingsReturnIfPending()
        pendingAutoReturnToast?.let { msg ->
            pendingAutoReturnToast = null
            showPleosToast(msg)
        }
        if (binding.viewOptionsDialog.root.visibility == View.VISIBLE) {
            renderOptionsDialog()
            reapplyThemeIfNeeded()
        }
        if (currentScreen == ScreenState.BLIND_DRIVE) {
            naviController.syncNavigationState()
        }
    }

    override fun onStop() {
        isActivityInForeground = false
        super.onStop()
        persistCurrentActiveTripIfNeeded(immediate = true)
    }

    private fun snapDriveMinutes(minutes: Int): Int {
        val clamped = minutes.coerceIn(15, 120)
        return (((clamped - 15) + 2) / 5) * 5 + 15
    }

    private fun initKeywordBindings() {
        val kw = binding.viewStepKeywords
        keywordCardBindings = listOf(
            KeywordCardBinding("sea", "바다", kw.cardKwSea, kw.ivKwSeaIcon, kw.tvKwSeaTitle, kw.tvKwSeaSub),
            KeywordCardBinding("water", "호수·강", kw.cardKwLake, kw.ivKwLakeIcon, kw.tvKwLakeTitle, kw.tvKwLakeSub),
            KeywordCardBinding("forest", "숲", kw.cardKwForest, kw.ivKwForestIcon, kw.tvKwForestTitle, kw.tvKwForestSub),
            KeywordCardBinding("walk", "산책", kw.cardKwWalk, kw.ivKwWalkIcon, kw.tvKwWalkTitle, kw.tvKwWalkSub),
            KeywordCardBinding("view", "전망", kw.cardKwView, kw.ivKwViewIcon, kw.tvKwViewTitle, kw.tvKwViewSub),
            KeywordCardBinding("quiet", "고즈넉", kw.cardKwQuiet, kw.ivKwQuietIcon, kw.tvKwQuietTitle, kw.tvKwQuietSub),
            KeywordCardBinding("sunrise", "일출", kw.cardKwSunrise, kw.ivKwSunriseIcon, kw.tvKwSunriseTitle, kw.tvKwSunriseSub),
            KeywordCardBinding("sunset", "노을", kw.cardKwSunset, kw.ivKwSunsetIcon, kw.tvKwSunsetTitle, kw.tvKwSunsetSub),
            KeywordCardBinding("night", "야경", kw.cardKwNight, kw.ivKwNightIcon, kw.tvKwNightTitle, kw.tvKwNightSub)
        )
    }

    private fun switchScreen(screen: ScreenState) {
        currentScreen = screen
        val screens = listOf(
            binding.viewSplash.root,
            binding.viewConsent.root,
            binding.viewTerms.root,
            binding.viewHome.root,
            binding.viewStepTimeRoute.root,
            binding.viewStepKeywords.root,
            binding.viewLoading.root,
            binding.viewDestinationSealed.root,
            binding.viewBlindDrive.root,
            binding.viewArrived.root,
            binding.viewSecretArchive.root
        )
        screens.forEach { it.visibility = View.GONE }

        val target = when (screen) {
            ScreenState.SPLASH -> binding.viewSplash.root
            ScreenState.CONSENT -> binding.viewConsent.root
            ScreenState.TERMS -> binding.viewTerms.root
            ScreenState.HOME -> binding.viewHome.root
            ScreenState.TIME_ROUTE -> binding.viewStepTimeRoute.root
            ScreenState.KEYWORDS -> binding.viewStepKeywords.root
            ScreenState.LOADING -> binding.viewLoading.root
            ScreenState.DESTINATION_SEALED -> binding.viewDestinationSealed.root
            ScreenState.BLIND_DRIVE -> binding.viewBlindDrive.root
            ScreenState.ARRIVED -> binding.viewArrived.root
            ScreenState.SECRET_ARCHIVE -> binding.viewSecretArchive.root
        }
        target.visibility = View.VISIBLE

        updateTopBar()

        when (screen) {
            ScreenState.CONSENT -> renderConsentUi()
            ScreenState.TERMS -> {
                binding.viewTerms.svTermsRoot.scrollTo(0, 0)
                binding.viewTerms.btnTermsWithdraw.visibility =
                    if (archiveRepository.isTermsAccepted()) View.VISIBLE else View.GONE
            }
            ScreenState.HOME -> renderHomeScreen()
            ScreenState.TIME_ROUTE -> renderTimeScreen()
            ScreenState.KEYWORDS -> {
                hasConfiguredScenery = true
                renderKeywordCardsSelection()
                renderConditionToggles()
                updateKeywordsDock()
                scheduleKeywordsFetch(immediate = true)
            }
            ScreenState.BLIND_DRIVE -> renderBlindDriveScreen()
            ScreenState.SECRET_ARCHIVE -> renderArchiveScreen()
            else -> Unit
        }
        reapplyThemeIfNeeded()
    }

    private fun updateTopBar() {
        val archiveCount = archiveRepository.getAll().size
        when (currentScreen) {
            ScreenState.SPLASH, ScreenState.CONSENT, ScreenState.LOADING, ScreenState.BLIND_DRIVE -> {
                binding.btnTopBack.visibility = View.GONE
                binding.ivTopBrand.visibility = View.VISIBLE
                binding.tvTopTitle.text = "어디가지"
                binding.btnTopArchive.visibility = View.GONE
                binding.btnTopOptions.visibility = View.GONE
                binding.tvTopClearAll.visibility = View.GONE
            }
            ScreenState.TERMS -> {
                binding.btnTopBack.visibility = View.VISIBLE
                binding.ivTopBrand.visibility = View.GONE
                binding.tvTopTitle.text = "위치기반 서비스 이용약관"
                binding.btnTopArchive.visibility = View.GONE
                binding.btnTopOptions.visibility = View.GONE
                binding.tvTopClearAll.visibility = View.GONE
            }
            ScreenState.HOME -> {
                binding.btnTopBack.visibility = View.GONE
                binding.ivTopBrand.visibility = View.VISIBLE
                binding.tvTopTitle.text = "어디가지"
                binding.btnTopArchive.visibility = if (archiveCount > 0) View.VISIBLE else View.GONE
                binding.btnTopOptions.visibility = View.VISIBLE
                binding.tvTopClearAll.visibility = View.GONE
            }
            ScreenState.TIME_ROUTE, ScreenState.KEYWORDS, ScreenState.ARRIVED -> {
                binding.btnTopBack.visibility = View.VISIBLE
                binding.ivTopBrand.visibility = View.VISIBLE
                binding.tvTopTitle.text = "어디가지"
                binding.btnTopArchive.visibility = View.VISIBLE
                binding.btnTopOptions.visibility = View.VISIBLE
                binding.tvTopClearAll.visibility = View.GONE
            }
            ScreenState.DESTINATION_SEALED -> {
                binding.btnTopBack.visibility = View.VISIBLE
                binding.ivTopBrand.visibility = View.VISIBLE
                binding.tvTopTitle.text = "어디가지"
                binding.btnTopArchive.visibility = View.GONE
                binding.btnTopOptions.visibility = View.VISIBLE
                binding.tvTopClearAll.visibility = View.GONE
            }
            ScreenState.SECRET_ARCHIVE -> {
                binding.btnTopBack.visibility = View.VISIBLE
                binding.ivTopBrand.visibility = View.GONE
                binding.tvTopTitle.text = "비밀 도감"
                binding.btnTopArchive.visibility = View.GONE
                binding.btnTopOptions.visibility = View.GONE
                binding.tvTopClearAll.visibility = if (archiveCount > 0) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupTopBarAndNavigation() {
        binding.btnTopBack.setOnClickListener {
            when (currentScreen) {
                ScreenState.TERMS -> switchScreen(termsReturnScreen)
                ScreenState.TIME_ROUTE -> switchScreen(ScreenState.HOME)
                ScreenState.KEYWORDS -> switchScreen(ScreenState.TIME_ROUTE)
                ScreenState.DESTINATION_SEALED -> switchScreen(ScreenState.KEYWORDS)
                ScreenState.ARRIVED -> switchScreen(ScreenState.HOME)
                ScreenState.SECRET_ARCHIVE -> switchScreen(archiveReturnScreen)
                else -> Unit
            }
        }

        binding.btnTopArchive.setOnClickListener {
            archiveReturnScreen = when (currentScreen) {
                ScreenState.HOME, ScreenState.TIME_ROUTE, ScreenState.KEYWORDS, ScreenState.ARRIVED -> currentScreen
                else -> ScreenState.TIME_ROUTE
            }
            switchScreen(ScreenState.SECRET_ARCHIVE)
        }

        binding.btnTopOptions.setOnClickListener {
            showOptionsDialog()
        }

        binding.tvTopClearAll.setOnClickListener {
            val count = archiveRepository.getAll().size
            if (count == 0) return@setOnClickListener
            showModal(
                title = "비밀 도감을 모두 비울까요?",
                message = "저장된 ${count}곳의 기록이 모두 삭제되며 되돌릴 수 없어요.",
                primaryLabel = "전체 삭제",
                onPrimary = {
                    archiveRepository.clearAll()
                    renderArchiveScreen()
                    updateTopBar()
                    reapplyThemeIfNeeded()
                    showPleosToast("비밀 도감 기록을 모두 삭제했습니다.")
                },
                secondaryLabel = "닫기"
            )
        }
    }

    private fun setupConsentAndTerms() {
        val consent = binding.viewConsent
        consent.llConsentAgreeRow.setOnClickListener {
            isConsentChecked = !isConsentChecked
            renderConsentUi()
            reapplyThemeIfNeeded()
        }

        consent.btnConsentAccept.setOnClickListener {
            if (!isConsentChecked) {
                showPleosToast("위치기반 서비스 이용약관 동의에 체크해 주세요.")
                return@setOnClickListener
            }
            archiveRepository.setTermsAccepted(true)
            ensureNotificationPermissionIfNeeded()
            val savedTrip = activeTripRepository.load()
            if (savedTrip != null) {
                restoreActiveTrip(savedTrip)
            } else {
                switchScreen(ScreenState.HOME)
                checkAndShowPendingRatingOnLaunch()
            }
        }

        consent.btnConsentDecline.setOnClickListener {
            showModal(
                title = "동의하지 않으면 목적지 추천과\n길 안내를 쓸 수 없어요",
                message = null,
                primaryLabel = null,
                onPrimary = null,
                secondaryLabel = "닫기"
            )
        }

        consent.btnConsentViewTerms.setOnClickListener {
            termsReturnScreen = ScreenState.CONSENT
            switchScreen(ScreenState.TERMS)
        }

        binding.viewTerms.btnTermsWithdraw.setOnClickListener {
            showModal(
                title = "동의를 철회할까요?",
                message = "철회하면 다시 동의하기 전까지 목적지 추천과 길 안내를 쓸 수 없어요. 비밀 도감은 그대로 남아요.",
                primaryLabel = "철회",
                onPrimary = {
                    archiveRepository.setTermsAccepted(false)
                    naviController.cancelRoute()
                    activeTripRepository.clear()
                    currentRecommendation = null
                    isConsentChecked = false
                    switchScreen(ScreenState.CONSENT)
                    showPleosToast("위치정보 이용 동의가 철회되었습니다.")
                },
                secondaryLabel = "닫기"
            )
        }
    }

    private fun renderConsentUi() {
        val consent = binding.viewConsent
        consent.llConsentAgreeRow.setWireBackground(
            if (isConsentChecked) R.drawable.bg_ui_selected else R.drawable.bg_ui_sub,
            if (isConsentChecked) "selected" else "sub"
        )
        consent.tvConsentCheckLabel.setTag(
            R.id.tag_text_role,
            if (isConsentChecked) "primary" else "unselected"
        )
        consent.ivConsentCheckbox.setImageResource(
            if (isConsentChecked) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked
        )
        consent.ivConsentCheckbox.setTag(
            R.id.tag_icon_role,
            if (isConsentChecked) "check_on" else "check_off"
        )
        consent.btnConsentAccept.setWireBackground(
            if (isConsentChecked) R.drawable.bg_ui_cta else R.drawable.bg_ui_sub,
            if (isConsentChecked) "cta" else "disabled_cta"
        )
        consent.btnConsentAccept.setTag(
            R.id.tag_text_role,
            if (isConsentChecked) "cta" else "disabled"
        )
        reapplyThemeIfNeeded()
    }

    private fun setupScreenInteractions() {
        // Home
        binding.viewHome.btnHomeResumeDrive.setOnClickListener {
            val active = activeTripRepository.load()
            if (active != null) {
                restoreActiveTrip(active)
            } else {
                renderHomeScreen()
            }
        }
        binding.viewHome.btnHomeStart.setOnClickListener {
            val active = activeTripRepository.load()
            if (active != null && active.phase == ActiveTripPhase.DRIVING) {
                confirmNewTripWhileDriving(active)
            } else {
                switchScreen(ScreenState.TIME_ROUTE)
            }
        }

        // Time Selection (Slider + 5-min Stepper)
        val tr = binding.viewStepTimeRoute
        tr.sliderDriveTime.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val nextMinutes = snapDriveMinutes(value.roundToInt())
                if (nextMinutes != driveMinutes) {
                    driveMinutes = nextMinutes
                    updateTimeReadoutAndDock()
                }
            }
        }
        tr.sliderDriveTime.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                archiveRepository.saveLastDriveMinutes(driveMinutes)
            }
        })
        tr.btnTimeMinus.setOnClickListener {
            val next = snapDriveMinutes(driveMinutes - 5)
            if (next != driveMinutes) {
                driveMinutes = next
                archiveRepository.saveLastDriveMinutes(driveMinutes)
                renderTimeScreen()
            }
        }
        tr.btnTimePlus.setOnClickListener {
            val next = snapDriveMinutes(driveMinutes + 5)
            if (next != driveMinutes) {
                driveMinutes = next
                archiveRepository.saveLastDriveMinutes(driveMinutes)
                renderTimeScreen()
            }
        }
        tr.btnTimeNext.setOnClickListener {
            switchScreen(ScreenState.KEYWORDS)
        }

        // Scenery & Conditions
        keywordCardBindings.forEach { item ->
            item.card.setOnClickListener {
                val status = keywordStatusMap[item.id]
                if (status != null && !status.enabled) {
                    showPleosToast(status.reason ?: "현재 조건에서는 이용할 수 없어요.")
                    return@setOnClickListener
                }
                if (selectedKeywordIds.contains(item.id)) {
                    selectedKeywordIds.remove(item.id)
                } else {
                    if (selectedKeywordIds.size >= 3) {
                        showPleosToast("풍경 키워드는 최대 3개까지 고를 수 있어요.")
                        return@setOnClickListener
                    }
                    selectedKeywordIds.add(item.id)
                }
                renderKeywordCardsSelection()
                updateKeywordsDock()
                reapplyThemeIfNeeded()
            }
        }

        binding.viewStepKeywords.btnToggleParking.setOnClickListener {
            requireParking = !requireParking
            renderConditionToggles()
            updateKeywordsDock()
            reapplyThemeIfNeeded()
            scheduleKeywordsFetch(immediate = false)
        }

        binding.viewStepKeywords.btnToggleFee.setOnClickListener {
            excludePaid = !excludePaid
            renderConditionToggles()
            updateKeywordsDock()
            reapplyThemeIfNeeded()
            scheduleKeywordsFetch(immediate = false)
        }

        binding.viewStepKeywords.btnKwDraw.setOnClickListener {
            startRecommendationFlow()
        }

        // Loading cancel
        binding.viewLoading.btnLoadingCancel.setOnClickListener {
            recommendationJob?.cancel()
            recommendationJob = null
            switchScreen(ScreenState.KEYWORDS)
        }

        // Ticket (Sealed)
        binding.viewDestinationSealed.btnSealedDepart.setOnClickListener {
            val rec = currentRecommendation ?: return@setOnClickListener
            RecentBlindNames.save(archiveRepository.getPrefs(), activeBlindName)
            initialEtaSeconds = observedEtaSeconds.coerceAtLeast(60)
            initialDistanceMeters = observedDistanceMeters.coerceAtLeast(1000)
            activeTripStartedAtMillis = System.currentTimeMillis()
            isCurrentArrivalSaved = false
            hasAutoReturnedForCurrentTrip = false
            hasPostedParkHintForCurrentTrip = false

            showRouteMaskCurtain()
            naviController.startBlindRoute(
                destLat = rec.destination.lat,
                destLng = rec.destination.lng,
                blindName = activeBlindName
            )
            persistActiveTrip(phase = ActiveTripPhase.DRIVING, isArrived = false, immediate = true)
            bindBlindDriveScreen(observedEtaSeconds)
            switchScreen(ScreenState.BLIND_DRIVE)
        }

        binding.viewDestinationSealed.btnSealedRedraw.setOnClickListener {
            if (remainingRedraws <= 0) {
                showPleosToast("오늘 다시 뽑기 횟수를 모두 사용했어요.")
                return@setOnClickListener
            }
            remainingRedraws--
            startRecommendationFlow()
        }

        // Blind Drive (Parked controls)
        binding.viewBlindDrive.btnDriveResumeNavi.setOnClickListener {
            val rec = currentRecommendation ?: return@setOnClickListener
            if (naviController.isNaviRouting) {
                naviController.openNavigationScreen()
            } else {
                hasAutoReturnedForCurrentTrip = false
                hasPostedParkHintForCurrentTrip = false
                showRouteMaskCurtain()
                naviController.startBlindRoute(
                    destLat = rec.destination.lat,
                    destLng = rec.destination.lng,
                    blindName = activeBlindName
                )
                updateBlindDriveUi()
                showPleosToast("‘$activeBlindName’ 경로로 내비게이션 안내를 시작합니다.")
            }
        }

        binding.viewBlindDrive.btnDriveCancel.setOnClickListener {
            naviController.cancelRoute()
            dismissRouteMaskCurtain(animated = false)
            dismissOverlayArrivalHintBanner()
            cancelArrivalReturnNotification()
            activeTripRepository.clear()
            currentRecommendation = null
            showPleosToast("안내를 종료했습니다.")
            switchScreen(ScreenState.HOME)
        }

        // Arrived (Unbox)
        binding.viewArrived.btnArrivedSave.setOnClickListener {
            if (!isCurrentArrivalSaved) {
                saveCurrentArrivalIfNeeded()
                renderArrivedSaveButton()
                reapplyThemeIfNeeded()
                showPleosToast("비밀 도감에 저장했어요. 다음 실행 시 평가를 남길 수 있어요.")
            }
        }

        // Archive
        binding.viewSecretArchive.btnArchiveNewDrive.setOnClickListener {
            switchScreen(ScreenState.TIME_ROUTE)
        }
        binding.viewSecretArchive.btnArchiveViewTerms.setOnClickListener {
            termsReturnScreen = ScreenState.SECRET_ARCHIVE
            switchScreen(ScreenState.TERMS)
        }
    }

    private fun renderHomeScreen() {
        val home = binding.viewHome
        val activeTrip = activeTripRepository.load()
        if (activeTrip != null && activeTrip.phase == ActiveTripPhase.DRIVING) {
            home.btnHomeResumeDrive.visibility = View.VISIBLE
            home.tvHomeResumeLabel.text = "진행 중인 ‘${activeTrip.blindName}’ 드라이브 이어하기"
        } else if (activeTrip != null && activeTrip.phase == ActiveTripPhase.ARRIVED && !activeTrip.isCurrentArrivalSaved) {
            home.btnHomeResumeDrive.visibility = View.VISIBLE
            home.tvHomeResumeLabel.text = "도착한 ‘${activeTrip.blindName}’ 정체 열어보기"
        } else {
            home.btnHomeResumeDrive.visibility = View.GONE
        }
    }

    private fun renderTimeScreen() {
        val tr = binding.viewStepTimeRoute
        val targetValue = driveMinutes.toFloat()
        if (tr.sliderDriveTime.value != targetValue) {
            tr.sliderDriveTime.value = targetValue
        }
        updateTimeReadoutAndDock()

        if (selectedKeywordIds.isNotEmpty()) {
            val kwNames = selectedKeywordIds.mapNotNull { id ->
                keywordCardBindings.firstOrNull { it.id == id }?.label
            }.joinToString(", ")
            tr.tvTimeDockScenery.text = kwNames
            tr.tvTimeDockScenery.setTag(R.id.tag_text_role, "primary")
        } else {
            tr.tvTimeDockScenery.text = "아직 고르지 않았어요"
            tr.tvTimeDockScenery.setTag(R.id.tag_text_role, "secondary")
        }

        if (hasConfiguredScenery || requireParking || excludePaid) {
            val pText = if (requireParking) "주차 가능한 곳만" else "주차 상관없음"
            val fText = if (excludePaid) "유료 체험 제외" else "유료 체험 포함"
            tr.tvTimeDockCondition.text = "$pText · $fText"
            tr.tvTimeDockCondition.setTag(R.id.tag_text_role, "primary")
        } else {
            tr.tvTimeDockCondition.text = "아직 고르지 않았어요"
            tr.tvTimeDockCondition.setTag(R.id.tag_text_role, "secondary")
        }
    }

    private fun updateTimeReadoutAndDock() {
        val tr = binding.viewStepTimeRoute
        tr.tvTimeSliderValue.text = "${driveMinutes}분"
        tr.tvTimeDockDrive.text = "${driveMinutes}분"

        val roundTripMinutes = driveMinutes * 2
        val rtHours = roundTripMinutes / 60
        val rtMins = roundTripMinutes % 60
        val roundTripLabel = when {
            rtHours > 0 && rtMins > 0 -> "${rtHours}시간 ${rtMins}분"
            rtHours > 0 -> "${rtHours}시간"
            else -> "${rtMins}분"
        }
        tr.tvTimeSliderRoundTrip.text = "왕복 약 $roundTripLabel 드라이브"

        tr.btnTimeMinus.isEnabled = driveMinutes > 15
        tr.btnTimeMinus.alpha = if (driveMinutes > 15) 1f else 0.4f
        tr.btnTimePlus.isEnabled = driveMinutes < 120
        tr.btnTimePlus.alpha = if (driveMinutes < 120) 1f else 0.4f
    }

    private fun renderKeywordCardsSelection() {
        keywordCardBindings.forEach { item ->
            val status = keywordStatusMap[item.id]
            val enabled = status?.enabled ?: true
            val reason = status?.reason
            val isSelected = enabled && selectedKeywordIds.contains(item.id)

            if (!enabled) {
                item.card.setWireBackground(R.drawable.bg_ui_disabled, "disabled")
                item.iconView.setTag(R.id.tag_icon_role, "tertiary")
                item.titleText.setTag(R.id.tag_text_role, "tertiary")
                item.subText.visibility = View.VISIBLE
                item.subText.text = reason ?: "지금은 선택할 수 없어요"
                item.subText.setTag(R.id.tag_text_role, "tertiary")
            } else {
                item.card.setWireBackground(
                    if (isSelected) R.drawable.bg_ui_selected else R.drawable.bg_ui_sub,
                    if (isSelected) "selected" else "sub"
                )
                item.iconView.setTag(R.id.tag_icon_role, if (isSelected) "primary" else "unselected")
                item.titleText.setTag(R.id.tag_text_role, if (isSelected) "primary" else "unselected")
                if (!reason.isNullOrBlank()) {
                    item.subText.visibility = View.VISIBLE
                    item.subText.text = reason
                    item.subText.setTag(R.id.tag_text_role, "secondary")
                } else {
                    item.subText.visibility = View.GONE
                }
            }
        }
    }

    private fun renderConditionToggles() {
        val kw = binding.viewStepKeywords
        kw.btnToggleParking.setWireBackground(
            if (requireParking) R.drawable.bg_ui_selected else R.drawable.bg_ui_sub,
            if (requireParking) "selected" else "sub"
        )
        kw.ivToggleParkingIcon.setTag(R.id.tag_icon_role, if (requireParking) "primary" else "unselected")
        kw.tvToggleParkingLabel.setTag(R.id.tag_text_role, if (requireParking) "primary" else "unselected")

        kw.btnToggleFee.setWireBackground(
            if (excludePaid) R.drawable.bg_ui_selected else R.drawable.bg_ui_sub,
            if (excludePaid) "selected" else "sub"
        )
        kw.ivToggleFeeIcon.setTag(R.id.tag_icon_role, if (excludePaid) "primary" else "unselected")
        kw.tvToggleFeeLabel.setTag(R.id.tag_text_role, if (excludePaid) "primary" else "unselected")
    }

    private fun updateKeywordsDock() {
        val kw = binding.viewStepKeywords
        val kwNames = selectedKeywordIds.mapNotNull { id ->
            keywordCardBindings.firstOrNull { it.id == id }?.label
        }.joinToString(", ")

        kw.tvKwSelectedCount.text = "${selectedKeywordIds.size}/3 선택"
        kw.tvKwDockTime.text = "${driveMinutes}분"
        if (kwNames.isNotEmpty()) {
            kw.tvKwDockScenery.text = kwNames
            kw.tvKwDockScenery.setTag(R.id.tag_text_role, "primary")
        } else {
            kw.tvKwDockScenery.text = "전체 랜덤 (무작위)"
            kw.tvKwDockScenery.setTag(R.id.tag_text_role, "secondary")
        }
        kw.tvKwDockParking.text = if (requireParking) "주차 가능한 곳만" else "주차 상관없음"
        kw.tvKwDockFee.text = if (excludePaid) "유료 체험 제외" else "유료 체험 포함"
    }

    private fun buildLoadingSummary(): String {
        val parts = mutableListOf("${driveMinutes}분")
        val kwNames = selectedKeywordIds.mapNotNull { id ->
            keywordCardBindings.firstOrNull { it.id == id }?.label
        }.joinToString(", ")
        parts.add(if (kwNames.isNotEmpty()) kwNames else "전체 랜덤")
        if (requireParking) parts.add("주차 가능한 곳만")
        if (excludePaid) parts.add("유료 체험 제외")
        return parts.joinToString(" · ")
    }

    // ---- Keywords API (300ms Debounce) ----
    private fun scheduleKeywordsFetch(immediate: Boolean) {
        keywordsDebounceJob?.cancel()
        keywordsDebounceJob = lifecycleScope.launch {
            if (!immediate) delay(300L)
            fetchKeywordsFromServer()
        }
    }

    private suspend fun fetchKeywordsFromServer() {
        val response = withTimeoutOrNull(5_000L) {
            runCatching {
                api.keywords(
                    lat = naviController.currentLat,
                    lng = naviController.currentLng,
                    driveMinutes = driveMinutes,
                    requireParking = requireParking,
                    excludePaid = excludePaid
                )
            }.onFailure { e ->
                Log.e("Eodigaji", "keywords network error: ${e.message}")
            }.getOrNull()
        }

        if (response == null) {
            if (currentScreen == ScreenState.KEYWORDS) {
                showModal(
                    title = "서버에 연결할 수 없어요",
                    message = "네트워크를 확인해 주세요",
                    primaryLabel = "다시 시도",
                    onPrimary = { scheduleKeywordsFetch(immediate = true) },
                    secondaryLabel = "닫기"
                )
            }
            return
        }

        if (response.isSuccessful) {
            val body = response.body() ?: return
            keywordStatusMap = body.keywords.associateBy { it.id }

            var disabledRemoved = false
            keywordCardBindings.forEach { kw ->
                val status = keywordStatusMap[kw.id]
                if (status != null && !status.enabled && selectedKeywordIds.remove(kw.id)) {
                    disabledRemoved = true
                }
            }
            renderKeywordCardsSelection()
            if (disabledRemoved) {
                updateKeywordsDock()
            }
            reapplyThemeIfNeeded()
        } else {
            handleApiError(
                error = response.apiError(gson),
                onRetry = { scheduleKeywordsFetch(immediate = true) }
            )
        }
    }

    // ---- Recommendation + ETA Check Pipeline ----
    private fun startRecommendationFlow() {
        if (recommendationJob?.isActive == true) return
        binding.viewLoading.tvLoadingTitle.text = "비밀 장소를 고르는 중… (1/3)"
        binding.viewLoading.tvLoadingSummary.text = buildLoadingSummary()
        switchScreen(ScreenState.LOADING)

        recommendationJob = lifecycleScope.launch {
            val result = executeRecommendationPipeline()
            if (currentScreen != ScreenState.LOADING) return@launch

            result.onSuccess { (rec, etaSec) ->
                currentRecommendation = rec
                observedEtaSeconds = etaSec
                observedDistanceMeters = (rec.straightKm * 1250).roundToInt().coerceAtLeast(1000)
                val recent = RecentBlindNames.load(archiveRepository.getPrefs())
                activeBlindName = BlindNames.pick(recent, rec.blindName)
                bindSealedScreen(rec, etaSec)
                switchScreen(ScreenState.DESTINATION_SEALED)
            }.onFailure { err ->
                Log.w("Eodigaji", "Recommendation failed: ${err.message}")
                if (currentScreen == ScreenState.LOADING) {
                    switchScreen(ScreenState.KEYWORDS)
                }
            }
        }
    }

    private suspend fun executeRecommendationPipeline(): Result<Pair<RecommendResponse, Int>> {
        val startTime = System.currentTimeMillis()
        val attemptIds = mutableListOf<String>()
        val candidateMap = mutableMapOf<String, RecommendResponse>()
        var lastEtaSec = 0
        var handledError = false

        val keywordList = selectedKeywordIds.toList()
        val baseReq = RecommendRequest(
            latitude = naviController.currentLat,
            longitude = naviController.currentLng,
            driveMinutes = driveMinutes,
            keywordIds = keywordList,
            excludeContentIds = sessionExcludedIds.toList(),
            requireParking = requireParking,
            excludePaid = excludePaid
        )

        val finalCandidate: RecommendResponse? = withTimeoutOrNull(12_000L) {
            val firstRes = try {
                api.recommend(baseReq)
            } catch (e: Exception) {
                Log.e("Eodigaji", "recommend network error: ${e.message}")
                null
            }

            if (firstRes == null) {
                if (currentScreen == ScreenState.LOADING) switchScreen(ScreenState.KEYWORDS)
                showModal(
                    title = "서버에 연결할 수 없어요",
                    message = "네트워크를 확인해 주세요",
                    primaryLabel = "다시 시도",
                    onPrimary = { startRecommendationFlow() },
                    secondaryLabel = "닫기"
                )
                handledError = true
                return@withTimeoutOrNull null
            }

            if (!firstRes.isSuccessful || firstRes.body() == null) {
                handleApiError(
                    error = firstRes.apiError(gson),
                    onRetry = { startRecommendationFlow() }
                )
                handledError = true
                return@withTimeoutOrNull null
            }

            var currentRec = firstRes.body()!!
            candidateMap[currentRec.reveal.contentId] = currentRec
            attemptIds.add(currentRec.reveal.contentId)

            while (true) {
                val contentId = currentRec.reveal.contentId
                val measuredEtaSec = naviController.measureTripEtaSeconds(
                    destLat = currentRec.destination.lat,
                    destLng = currentRec.destination.lng,
                    blindName = activeBlindName
                ) ?: currentRec.estimatedDriveSeconds.coerceAtLeast(60)

                lastEtaSec = measuredEtaSec

                val etaReq = baseReq.toEtaCheck(
                    contentId = contentId,
                    etaSec = measuredEtaSec,
                    previous = attemptIds.toList()
                )
                val etaRes = try {
                    api.etaCheck(etaReq)
                } catch (e: Exception) {
                    Log.w("Eodigaji", "etaCheck network error, using current candidate: ${e.message}")
                    return@withTimeoutOrNull currentRec
                }

                if (!etaRes.isSuccessful || etaRes.body() == null) {
                    return@withTimeoutOrNull currentRec
                }

                val body = etaRes.body()!!
                when (body.decision) {
                    "accept", "fallback" -> {
                        val targetId = body.routeContentId ?: contentId
                        return@withTimeoutOrNull candidateMap[targetId] ?: currentRec
                    }
                    "retry" -> {
                        val step = body.attempt.coerceIn(1, body.maxAttempts.coerceAtLeast(1))
                        val total = body.maxAttempts.coerceAtLeast(3)
                        binding.viewLoading.tvLoadingTitle.text = "비밀 장소를 고르는 중… ($step/$total)"
                        val nextRec = body.recommendation ?: return@withTimeoutOrNull currentRec
                        currentRec = nextRec
                        candidateMap[currentRec.reveal.contentId] = currentRec
                        attemptIds.add(currentRec.reveal.contentId)
                    }
                    else -> return@withTimeoutOrNull currentRec
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed < 900L) {
            delay(900L - elapsed)
        }

        val chosen = finalCandidate ?: attemptIds.lastOrNull()?.let { candidateMap[it] }
        return if (chosen != null) {
            sessionExcludedIds.add(chosen.reveal.contentId)
            Result.success(chosen to lastEtaSec.coerceAtLeast(60))
        } else {
            if (!handledError) {
                if (currentScreen == ScreenState.LOADING) switchScreen(ScreenState.KEYWORDS)
                showModal(
                    title = "서버에 연결할 수 없어요",
                    message = "네트워크를 확인해 주세요",
                    primaryLabel = "다시 시도",
                    onPrimary = { startRecommendationFlow() },
                    secondaryLabel = "닫기"
                )
            }
            Result.failure(Exception("추천 결과를 가져오지 못했습니다."))
        }
    }

    private fun handleApiError(error: ErrorBody?, onRetry: (() -> Unit)? = null) {
        val code = error?.code
        if (currentScreen == ScreenState.LOADING) {
            switchScreen(ScreenState.KEYWORDS)
        }

        when (code) {
            "CONDITION_NOT_MET", "NO_CANDIDATES" -> {
                showModal(
                    title = "조건에 맞는 곳을 못 찾았어요",
                    message = "조건을 끄거나 운전 시간을 늘려 보세요",
                    primaryLabel = "조건 바꾸기",
                    onPrimary = {
                        if (requireParking || excludePaid) {
                            requireParking = false
                            excludePaid = false
                            renderConditionToggles()
                            updateKeywordsDock()
                            reapplyThemeIfNeeded()
                            scheduleKeywordsFetch(immediate = true)
                        } else {
                            switchScreen(ScreenState.TIME_ROUTE)
                        }
                    },
                    secondaryLabel = "닫기"
                )
            }
            "KEYWORD_UNAVAILABLE" -> {
                scheduleKeywordsFetch(immediate = true)
                showModal(
                    title = "조건에 맞는 곳을 못 찾았어요",
                    message = "지금 출발하면 볼 수 없는 풍경이에요. 다른 풍경을 골라 주세요.",
                    primaryLabel = "조건 바꾸기",
                    onPrimary = null,
                    secondaryLabel = "닫기"
                )
            }
            else -> {
                val msg = when (code) {
                    "INVALID_LOCATION" -> "현재 위치를 확인할 수 없어요. 잠시 후 다시 시도해 주세요."
                    "INVALID_KEYWORD" -> "풍경 키워드를 다시 골라 주세요."
                    "UPSTREAM_TOUR_API_ERROR" -> "장소 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."
                    else -> "네트워크를 확인해 주세요"
                }
                showModal(
                    title = "서버에 연결할 수 없어요",
                    message = msg,
                    primaryLabel = if (onRetry != null) "다시 시도" else null,
                    onPrimary = onRetry,
                    secondaryLabel = "닫기"
                )
            }
        }
    }

    // ---- Unified Monochrome Modal Dialog (Popups A/B/C/D & Confirmations) ----
    private fun showModal(
        title: String,
        message: String?,
        subInfo: String? = null,
        primaryLabel: String?,
        onPrimary: (() -> Unit)?,
        secondaryLabel: String = "닫기",
        onSecondary: (() -> Unit)? = null
    ) {
        val dlg = binding.viewModalDialog
        dlg.tvModalTitle.text = title

        if (!message.isNullOrBlank()) {
            dlg.tvModalMessage.text = message
            dlg.tvModalMessage.visibility = View.VISIBLE
        } else {
            dlg.tvModalMessage.visibility = View.GONE
        }

        if (!subInfo.isNullOrBlank()) {
            dlg.tvModalSubInfo.text = subInfo
            dlg.tvModalSubInfo.visibility = View.VISIBLE
        } else {
            dlg.tvModalSubInfo.visibility = View.GONE
        }

        if (!primaryLabel.isNullOrBlank()) {
            dlg.btnModalPrimary.text = primaryLabel
            dlg.btnModalPrimary.visibility = View.VISIBLE
            dlg.btnModalPrimary.setOnClickListener {
                hideModal()
                onPrimary?.invoke()
            }
        } else {
            dlg.btnModalPrimary.visibility = View.GONE
        }

        dlg.btnModalSecondary.text = secondaryLabel
        dlg.btnModalSecondary.setOnClickListener {
            hideModal()
            onSecondary?.invoke()
        }
        dlg.root.setOnClickListener { hideModal() }
        dlg.cardModalDialog.setOnClickListener { /* consume */ }

        reapplyThemeIfNeeded()

        dlg.root.visibility = View.VISIBLE
        dlg.root.alpha = 0f
        dlg.root.animate().alpha(1f).setDuration(180).start()

        dlg.cardModalDialog.alpha = 0f
        dlg.cardModalDialog.scaleX = 0.94f
        dlg.cardModalDialog.scaleY = 0.94f
        dlg.cardModalDialog.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(210)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideModal() {
        binding.viewModalDialog.root.visibility = View.GONE
    }

    // ---- Deferred Post-Visit Rating Dialog (Shown on Next App Launch) ----
    private fun checkAndShowPendingRatingOnLaunch() {
        if (hasCheckedPendingRatingOnLaunch) return
        hasCheckedPendingRatingOnLaunch = true
        val pendingItem = archiveRepository.getPendingRatingItem() ?: return
        showRatingDialog(pendingItem, fromArchive = false)
    }

    private fun showRatingDialog(item: ArchiveItem, fromArchive: Boolean = false) {
        val dlg = binding.viewRatingDialog
        dlg.tvRatingTitle.text = if (fromArchive) {
            "이 장소는 어떠셨나요?"
        } else {
            "지난 드라이브는 어떠셨나요?"
        }
        dlg.tvRatingSubtitle.text = "도감에 저장한 장소가 마음에 드셨는지 알려 주세요."
        dlg.tvRatingBlindName.text = "가명 ‘${item.blindName}’"
        dlg.tvRatingRealName.text = item.realName
        dlg.tvRatingMeta.text = "${item.address} · ${formatKoreanMonthDay(item.date)}"
        dlg.btnRatingLater.text = if (fromArchive) "닫기" else "건너뛰기"

        val submitRating: (String) -> Unit = { emotion ->
            archiveRepository.updateItemEmotion(item.number, emotion)
            hideRatingDialog()
            if (currentScreen == ScreenState.SECRET_ARCHIVE) {
                renderArchiveScreen()
                reapplyThemeIfNeeded()
            }
            showPleosToast("‘${item.realName}’ 평가를 '$emotion'(으)로 저장했어요.")
        }

        dlg.btnRatingLike.setOnClickListener { submitRating("좋아요") }
        dlg.btnRatingDislike.setOnClickListener { submitRating("별로") }

        val dismissAction = {
            if (!fromArchive) {
                archiveRepository.setPendingRatingNumber(null)
            }
            hideRatingDialog()
        }
        dlg.btnRatingLater.setOnClickListener { dismissAction() }
        dlg.root.setOnClickListener { dismissAction() }
        dlg.cardRatingDialog.setOnClickListener { /* consume */ }

        reapplyThemeIfNeeded()

        dlg.root.visibility = View.VISIBLE
        dlg.root.alpha = 0f
        dlg.root.animate().alpha(1f).setDuration(180).start()

        dlg.cardRatingDialog.alpha = 0f
        dlg.cardRatingDialog.scaleX = 0.94f
        dlg.cardRatingDialog.scaleY = 0.94f
        dlg.cardRatingDialog.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(210)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideRatingDialog() {
        binding.viewRatingDialog.root.visibility = View.GONE
    }

    // ---- Options Dialog (Theme, Font Scale, Guide, Terms) ----
    private fun setupOptionsDialog() {
        val opt = binding.viewOptionsDialog
        opt.root.setOnClickListener { hideOptionsDialog() }
        opt.cardOptionsDialog.setOnClickListener { /* consume */ }
        opt.btnOptionsTopClose.setOnClickListener { hideOptionsDialog() }
        opt.btnOptionsClose.setOnClickListener { hideOptionsDialog() }

        val themeButtons = listOf(
            "auto" to opt.btnOptThemeAuto,
            "light" to opt.btnOptThemeLight,
            "dark" to opt.btnOptThemeDark,
            "pixel" to opt.btnOptThemePixel
        )
        themeButtons.forEach { (mode, btn) ->
            btn.setOnClickListener {
                archiveRepository.saveThemePreference(mode)
                applyTheme(resolveEffectiveTheme())
                renderOptionsDialog()
                reapplyThemeIfNeeded()
            }
        }

        val fontButtons = listOf(
            0 to opt.btnOptFontSmall,
            1 to opt.btnOptFontNormal,
            2 to opt.btnOptFontLarge,
            3 to opt.btnOptFontXLarge
        )
        fontButtons.forEach { (level, btn) ->
            btn.setOnClickListener {
                archiveRepository.saveFontScaleLevel(level)
                applyFontScale(level)
                renderOptionsDialog()
                reapplyThemeIfNeeded()
            }
        }

        opt.btnOptGuide.setOnClickListener {
            hideOptionsDialog()
            showModal(
                title = "어디가지 이용 안내",
                message = "1. 편도 운전 시간과 풍경 키워드를 고르세요.\n" +
                    "2. 목적지는 가명과 힌트만 보여준 채 봉인돼요.\n" +
                    "3. 도착 후 [P]단 주차 시 정체가 열리고, 비밀 도감은 차량 내에만 최대 50곳 저장돼요.",
                primaryLabel = null,
                onPrimary = null,
                secondaryLabel = "확인"
            )
        }

        opt.btnOptOverlay.setOnClickListener {
            hideOptionsDialog()
            if (Settings.canDrawOverlays(this)) {
                showModal(
                    title = "내비 위 도착 안내가 켜져 있어요",
                    message = "내비게이션 전환 시 목적지 가림막과 주행 중 도착 배너가 정상 작동해요.",
                    primaryLabel = "권한 설정 열기",
                    onPrimary = { openOverlayPermissionSettings() },
                    secondaryLabel = "닫기"
                )
            } else {
                showOverlayPermissionGuideModal(markAsAsked = false)
            }
        }

        opt.btnOptTerms.setOnClickListener {
            hideOptionsDialog()
            termsReturnScreen = currentScreen
            switchScreen(ScreenState.TERMS)
        }

        if (BuildConfig.DEBUG) {
            opt.btnOptDebugSimulateArrival.setOnClickListener {
                if (currentRecommendation != null) {
                    hideOptionsDialog()
                    onArrivalAndParkSatisfied()
                } else {
                    showPleosToast("먼저 목적지를 뽑아주세요.")
                }
            }
        }
    }

    private fun showOptionsDialog() {
        val opt = binding.viewOptionsDialog
        renderOptionsDialog()
        reapplyThemeIfNeeded()

        opt.root.visibility = View.VISIBLE
        opt.root.alpha = 0f
        opt.root.animate().alpha(1f).setDuration(180).start()

        opt.cardOptionsDialog.alpha = 0f
        opt.cardOptionsDialog.scaleX = 0.94f
        opt.cardOptionsDialog.scaleY = 0.94f
        opt.cardOptionsDialog.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(210)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideOptionsDialog() {
        binding.viewOptionsDialog.root.visibility = View.GONE
    }

    private fun renderOptionsDialog() {
        val opt = binding.viewOptionsDialog
        val themePref = archiveRepository.getThemePreference()
        val fontLevel = archiveRepository.getFontScaleLevel()

        listOf(
            "auto" to opt.btnOptThemeAuto,
            "light" to opt.btnOptThemeLight,
            "dark" to opt.btnOptThemeDark,
            "pixel" to opt.btnOptThemePixel
        ).forEach { (mode, btn) ->
            val selected = themePref == mode
            btn.setWireBackground(
                if (selected) R.drawable.bg_ui_selected else R.drawable.bg_ui_sub,
                if (selected) "selected" else "modal_sub"
            )
            btn.setTag(R.id.tag_text_role, if (selected) "primary" else "unselected")
        }

        listOf(
            0 to opt.btnOptFontSmall,
            1 to opt.btnOptFontNormal,
            2 to opt.btnOptFontLarge,
            3 to opt.btnOptFontXLarge
        ).forEach { (level, btn) ->
            val selected = fontLevel == level
            btn.setWireBackground(
                if (selected) R.drawable.bg_ui_selected else R.drawable.bg_ui_sub,
                if (selected) "selected" else "modal_sub"
            )
            btn.setTag(R.id.tag_text_role, if (selected) "primary" else "unselected")
        }

        val overlayGranted = Settings.canDrawOverlays(this)
        opt.tvOptOverlayStatus.text = if (overlayGranted) "허용됨" else "꺼짐 · 설정"
        opt.tvOptOverlayStatus.setTag(
            R.id.tag_text_role,
            if (overlayGranted) "primary" else "secondary"
        )

        val accepted = archiveRepository.isTermsAccepted()
        opt.tvOptTermsStatus.text = if (accepted) "동의 완료" else "미동의"
        opt.tvOptTermsStatus.setTag(R.id.tag_text_role, "secondary")

        if (BuildConfig.DEBUG) {
            opt.llOptDebugSection.visibility = View.VISIBLE
            val currentLat = naviController.currentLat
            val currentLng = naviController.currentLng
            opt.tvOptDebugCurrentCoords.text = String.format(Locale.US, "현재 차량 좌표: %.5f, %.5f", currentLat, currentLng)

            val rec = currentRecommendation
            if (rec != null) {
                opt.tvOptDebugDestCoords.text = String.format(
                    Locale.US,
                    "추천 목적지 좌표: %.5f, %.5f\n(실제 장소명: %s)",
                    rec.destination.lat,
                    rec.destination.lng,
                    rec.reveal.realName
                )
                opt.btnOptDebugSimulateArrival.isEnabled = true
                opt.btnOptDebugSimulateArrival.alpha = 1.0f
            } else {
                opt.tvOptDebugDestCoords.text = "추천 목적지 좌표: (미설정 - 탐색 전)"
                opt.btnOptDebugSimulateArrival.isEnabled = false
                opt.btnOptDebugSimulateArrival.alpha = 0.5f
            }
        } else {
            opt.llOptDebugSection.visibility = View.GONE
        }
    }

    private fun applyFontScale(level: Int) {
        val factor = when (level.coerceIn(0, 3)) {
            0 -> 1.0f
            2 -> 1.25f
            3 -> 1.38f
            else -> 1.12f
        }
        applyFontScaleToView(binding.rootContainer, factor)
    }

    private fun applyFontScaleToView(view: View, factor: Float) {
        if (view is TextView) {
            val pxPerSp = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                1f,
                resources.displayMetrics
            ).coerceAtLeast(1f)
            val baseSp = (view.getTag(R.id.tag_base_text_size) as? Float)
                ?: (view.textSize / pxPerSp).also {
                    view.setTag(R.id.tag_base_text_size, it)
                }
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * factor)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyFontScaleToView(view.getChildAt(i), factor)
            }
        }
    }

    // ---- Screen Data Binding ----
    private fun bindSealedScreen(rec: RecommendResponse, etaSeconds: Int) {
        val sealed = binding.viewDestinationSealed
        val etaMin = (etaSeconds / 60).coerceAtLeast(1)

        sealed.tvSealedParking.text = when (rec.hint.parking) {
            "가능" -> "주차 가능"
            "불가" -> "주차 불가"
            else -> "주차 정보 확인 필요"
        }

        val feeText = rec.hint.fee
        sealed.tvSealedFee.text = if (!feeText.isNullOrBlank() &&
            (feeText.contains("유료") || feeText.contains("원")) &&
            !feeText.contains("무료")
        ) {
            "유료 체험"
        } else {
            "무료 체험 또는 알 수 없음"
        }

        sealed.tvSealedOpen.text = if (rec.hint.openNow == false) {
            "운영 시간 확인 필요"
        } else {
            "도착 때 운영 중"
        }

        sealed.tvSealedAlias.text = activeBlindName
        sealed.tvSealedEta.text = "편도 약 ${etaMin}분"

        if (BuildConfig.DEBUG) {
            sealed.tvSealedDebugCoords.visibility = View.VISIBLE
            sealed.tvSealedDebugCoords.text = String.format(
                Locale.US,
                "🧪 [DEBUG] 목적지: %.4f, %.4f (%s)",
                rec.destination.lat,
                rec.destination.lng,
                rec.reveal.realName
            )
        } else {
            sealed.tvSealedDebugCoords.visibility = View.GONE
        }
    }

    private fun bindBlindDriveScreen(etaSeconds: Int) {
        val drive = binding.viewBlindDrive
        drive.tvDriveTitle.text = "‘$activeBlindName’을 향해 가고 있어요"
        drive.tvDriveDockAlias.text = activeBlindName
        updateDriveProgressUi(etaSeconds, observedDistanceMeters)
        updateBlindDriveUi()
    }

    private fun renderBlindDriveScreen() {
        updateBlindDriveUi()
    }

    private fun updateDriveProgressUi(etaSeconds: Int, distanceMeters: Int) {
        val drive = binding.viewBlindDrive
        val etaMin = (etaSeconds / 60).coerceAtLeast(1)
        drive.tvDriveEtaMinutes.text = "남은 시간 약 ${etaMin}분"

        val progress = when {
            naviController.isArrived -> 95
            initialDistanceMeters > 0 -> {
                val ratio = 1.0 - (distanceMeters.toDouble() / initialDistanceMeters.toDouble())
                (15 + (ratio * 80.0)).roundToInt().coerceIn(15, 95)
            }
            else -> 20
        }
        drive.pbDriveProgress.progress = progress

        if (BuildConfig.DEBUG) {
            drive.tvDriveDebugCoords.visibility = View.VISIBLE
            val rec = currentRecommendation
            if (rec != null) {
                drive.tvDriveDebugCoords.text = String.format(
                    Locale.US,
                    "🧪 [DEBUG] 현재: %.4f, %.4f ➔ 목적지: %.4f, %.4f (%s)",
                    naviController.currentLat,
                    naviController.currentLng,
                    rec.destination.lat,
                    rec.destination.lng,
                    rec.reveal.realName
                )
            } else {
                drive.tvDriveDebugCoords.text = String.format(
                    Locale.US,
                    "🧪 [DEBUG] 현재: %.4f, %.4f",
                    naviController.currentLat,
                    naviController.currentLng
                )
            }
        } else {
            drive.tvDriveDebugCoords.visibility = View.GONE
        }
    }

    private fun updateBlindDriveUi() {
        val drive = binding.viewBlindDrive
        if (!naviController.isParked) {
            drive.llDriveLocked.visibility = View.VISIBLE
            drive.llDriveParked.visibility = View.GONE
            return
        }

        drive.llDriveLocked.visibility = View.GONE
        drive.llDriveParked.visibility = View.VISIBLE

        drive.tvDriveNaviStatus.text = when {
            naviController.isArrived -> "목적지 부근에 도착했어요"
            naviController.isRoutePreparing -> "비밀 경로를 준비하고 있어요..."
            naviController.isNaviRouting -> "Pleos 내비게이션 안내 중"
            else -> "내비 안내가 멈춰 있어요"
        }
        drive.tvDriveGearStatus.text = if (naviController.isArrived) {
            "기어 [P] 주차 확인 · 잠시 후 정체가 열려요"
        } else {
            "기어 [P] 주차 중 · 도착 후 정체가 열려요"
        }
        drive.btnDriveResumeNavi.text = when {
            naviController.isRoutePreparing -> "경로 준비 중..."
            naviController.isNaviRouting -> "내비게이션 열기"
            else -> "내비 안내 시작"
        }
    }

    private fun bindArrivedScreen(rec: RecommendResponse) {
        val arr = binding.viewArrived
        val reveal = rec.reveal

        arr.tvArrivedOverline.text = "$activeBlindName, 오늘의 비밀 장소는"
        arr.tvArrivedPlaceName.text = reveal.realName
        arr.tvArrivedOverview.text = reveal.overviewShort?.takeIf { it.isNotBlank() }
            ?: "목적지에 도착했습니다. 드라이브의 여운과 함께 즐거운 시간 보내세요!"
        arr.tvArrivedAddress.text = reveal.address

        // KOGL Type 3: fitCenter without crop or overlay
        arr.ivArrivedPostcard.scaleType = ImageView.ScaleType.FIT_CENTER
        Glide.with(this).clear(arr.ivArrivedPostcard)
        arr.ivArrivedPostcard.setImageDrawable(null)
        arr.tvArrivedPhotoCredit.visibility = View.GONE
        val imageUrl = reveal.imageUrl?.takeIf { it.isNotBlank() }
        if (imageUrl != null) {
            val secureImageUrl = if (imageUrl.startsWith("http://", ignoreCase = true)) {
                "https://" + imageUrl.substring(7)
            } else {
                imageUrl
            }
            Glide.with(this)
                .load(secureImageUrl)
                .fitCenter()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        arr.tvArrivedPhotoCredit.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        arr.tvArrivedPhotoCredit.visibility = View.VISIBLE
                        return false
                    }
                })
                .into(arr.ivArrivedPostcard)
        }

        renderArrivedSaveButton()
    }

    private fun renderArrivedSaveButton() {
        val btn = binding.viewArrived.btnArrivedSave
        if (isCurrentArrivalSaved) {
            btn.setWireBackground(R.drawable.bg_ui_sub, "sub")
            btn.setTag(R.id.tag_text_role, "secondary")
            btn.text = "저장했어요"
        } else {
            btn.setWireBackground(R.drawable.bg_ui_cta, "cta")
            btn.setTag(R.id.tag_text_role, "cta")
            btn.text = "도감에 저장"
        }
    }

    private fun saveCurrentArrivalIfNeeded() {
        val rec = currentRecommendation ?: return
        if (!isCurrentArrivalSaved) {
            val method = if (selectedKeywordIds.isNotEmpty()) "취향 랜덤" else "전체 랜덤"
            val itemNumber = archiveRepository.nextIndex()
            val item = ArchiveItem(
                number = itemNumber,
                contentId = rec.reveal.contentId,
                blindName = activeBlindName,
                realName = rec.reveal.realName,
                address = rec.reveal.address,
                overviewShort = rec.reveal.overviewShort.orEmpty(),
                imageUrl = rec.reveal.imageUrl,
                theme = rec.reveal.theme.orEmpty(),
                lat = rec.destination.lat,
                lng = rec.destination.lng,
                selectedMethod = method,
                selectedEmotion = ArchiveRepository.EMOTION_UNRATED,
                date = archiveRepository.todayDateString()
            )
            archiveRepository.saveItem(item)
            archiveRepository.setPendingRatingNumber(itemNumber)
            isCurrentArrivalSaved = true
            activeTripRepository.clear()
        } else {
            activeTripRepository.clear()
        }
    }

    private fun formatKoreanMonthDay(dateStr: String): String {
        val parsed = runCatching {
            SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).parse(dateStr)
        }.getOrNull() ?: return dateStr
        return SimpleDateFormat("M월 d일", Locale.KOREA).format(parsed)
    }

    private fun renderArchiveScreen() {
        val arch = binding.viewSecretArchive
        val allItems = archiveRepository.getAll()
        val container = arch.llArchiveItems
        container.removeAllViews()

        if (allItems.isEmpty()) {
            arch.tvArchiveCount.visibility = View.GONE
            arch.svArchiveGrid.visibility = View.GONE
            arch.llArchiveEmpty.visibility = View.VISIBLE
            updateTopBar()
            return
        }

        arch.tvArchiveCount.visibility = View.VISIBLE
        arch.tvArchiveCount.text = "최근 방문 순 · ${allItems.size}곳"
        arch.svArchiveGrid.visibility = View.VISIBLE
        arch.llArchiveEmpty.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        val density = resources.displayMetrics.density
        val gapPx = (14f * density).roundToInt()

        val rows = allItems.chunked(3)
        rows.forEachIndexed { rowIdx, rowItems ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIdx < rows.lastIndex) bottomMargin = gapPx
                }
            }

            for (col in 0 until 3) {
                val item = rowItems.getOrNull(col)
                if (item != null) {
                    val card = inflater.inflate(R.layout.item_archive_entry, rowLayout, false)
                    card.setWireBackground(R.drawable.bg_ui_surface, "surface")
                    card.layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (col < 2) marginEnd = gapPx
                    }

                    val tvAlias = card.findViewById<TextView>(R.id.tvArchiveItemAlias)
                    val ivEmotion = card.findViewById<ImageView>(R.id.ivArchiveItemEmotion)
                    val tvEmotion = card.findViewById<TextView>(R.id.tvArchiveItemRating)
                    val tvMeta = card.findViewById<TextView>(R.id.tvArchiveItemMeta)

                    tvAlias.text = item.blindName
                    tvAlias.setTag(R.id.tag_text_role, "primary")

                    when {
                        archiveRepository.isUnratedEmotion(item.selectedEmotion) -> {
                            ivEmotion.setImageResource(R.drawable.ic_pleos_info)
                            ivEmotion.setTag(R.id.tag_icon_role, "tertiary")
                            tvEmotion.text = ArchiveRepository.EMOTION_UNRATED
                            tvEmotion.setTag(R.id.tag_text_role, "tertiary")
                        }
                        item.selectedEmotion == "별로" || item.selectedEmotion == "아쉬워요" -> {
                            ivEmotion.setImageResource(R.drawable.ic_thumb_down)
                            ivEmotion.setTag(R.id.tag_icon_role, "secondary")
                            tvEmotion.text = "별로"
                            tvEmotion.setTag(R.id.tag_text_role, "secondary")
                        }
                        else -> {
                            ivEmotion.setImageResource(R.drawable.ic_heart_outline)
                            ivEmotion.setTag(R.id.tag_icon_role, "secondary")
                            tvEmotion.text = "좋아요"
                            tvEmotion.setTag(R.id.tag_text_role, "secondary")
                        }
                    }

                    tvMeta.text = "${item.realName} · ${formatKoreanMonthDay(item.date)}"
                    tvMeta.setTag(R.id.tag_text_role, "secondary")

                    card.setOnClickListener {
                        showArchiveItemModal(item)
                    }
                    rowLayout.addView(card)
                } else {
                    val spacer = View(this).apply {
                        visibility = View.INVISIBLE
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            if (col < 2) marginEnd = gapPx
                        }
                    }
                    rowLayout.addView(spacer)
                }
            }
            container.addView(rowLayout)
        }
        applyFontScale(archiveRepository.getFontScaleLevel())
        updateTopBar()
    }

    private fun showArchiveItemModal(item: ArchiveItem) {
        val isUnrated = archiveRepository.isUnratedEmotion(item.selectedEmotion)
        val emotionLabel = when {
            isUnrated -> ArchiveRepository.EMOTION_UNRATED
            item.selectedEmotion == "별로" || item.selectedEmotion == "아쉬워요" -> "별로"
            else -> "좋아요"
        }
        showModal(
            title = item.realName,
            message = item.address,
            subInfo = "가명 ‘${item.blindName}’ · $emotionLabel · ${formatKoreanMonthDay(item.date)}",
            primaryLabel = if (isUnrated) "평가하기" else "다시 안내 시작",
            onPrimary = {
                if (isUnrated) {
                    showRatingDialog(item, fromArchive = true)
                } else {
                    naviController.startBlindRoute(
                        destLat = item.lat,
                        destLng = item.lng,
                        blindName = item.realName
                    )
                    showPleosToast("‘${item.realName}’ 경로로 안내를 시작합니다.")
                }
            },
            secondaryLabel = "삭제",
            onSecondary = {
                archiveRepository.deleteItem(item.number)
                renderArchiveScreen()
                reapplyThemeIfNeeded()
                showPleosToast("‘${item.realName}’ 기록을 삭제했습니다.")
            }
        )
    }

    // ---- Active Trip Persistence & Recovery ----
    private fun persistActiveTrip(
        phase: ActiveTripPhase,
        isArrived: Boolean,
        immediate: Boolean
    ) {
        val rec = currentRecommendation ?: return
        val now = System.currentTimeMillis()
        if (activeTripStartedAtMillis == 0L) {
            activeTripStartedAtMillis = now
        }
        val method = if (selectedKeywordIds.isNotEmpty()) "취향 랜덤" else "전체 랜덤"
        val state = ActiveTripState(
            phase = phase,
            recommendation = rec,
            blindName = activeBlindName,
            selectedMethod = method,
            selectedRoute = "",
            driveMinutes = driveMinutes,
            initialEtaSeconds = initialEtaSeconds.coerceAtLeast(60),
            initialDistanceMeters = initialDistanceMeters.coerceAtLeast(1000),
            observedEtaSeconds = observedEtaSeconds.coerceAtLeast(60),
            observedDistanceMeters = observedDistanceMeters.coerceAtLeast(0),
            originLabel = currentOriginLabel,
            isArrived = isArrived,
            isCurrentArrivalSaved = isCurrentArrivalSaved,
            startedAtMillis = activeTripStartedAtMillis,
            updatedAtMillis = now
        )
        activeTripRepository.save(state, immediate = immediate)
    }

    private fun persistCurrentActiveTripIfNeeded(immediate: Boolean) {
        if (currentRecommendation == null) return
        when (currentScreen) {
            ScreenState.BLIND_DRIVE -> persistActiveTrip(
                phase = ActiveTripPhase.DRIVING,
                isArrived = naviController.isArrived,
                immediate = immediate
            )
            ScreenState.ARRIVED -> if (!isCurrentArrivalSaved) {
                persistActiveTrip(
                    phase = ActiveTripPhase.ARRIVED,
                    isArrived = true,
                    immediate = immediate
                )
            }
            else -> Unit
        }
    }

    private fun restoreActiveTrip(state: ActiveTripState) {
        currentRecommendation = state.recommendation
        activeBlindName = state.blindName
        driveMinutes = snapDriveMinutes(state.driveMinutes)
        initialEtaSeconds = state.initialEtaSeconds
        initialDistanceMeters = state.initialDistanceMeters
        observedEtaSeconds = state.observedEtaSeconds
        observedDistanceMeters = state.observedDistanceMeters
        activeTripStartedAtMillis = state.startedAtMillis
        isCurrentArrivalSaved = state.isCurrentArrivalSaved
        hasAutoReturnedForCurrentTrip = state.isArrived
        hasPostedParkHintForCurrentTrip = state.isArrived
        if (state.originLabel.isNotBlank()) {
            currentOriginLabel = state.originLabel
        }

        if (state.phase == ActiveTripPhase.ARRIVED) {
            naviController.clearActiveBlindTarget()
            bindArrivedScreen(state.recommendation)
            switchScreen(ScreenState.ARRIVED)
            showPleosToast("도착 완료된 ‘${state.blindName}’ 장소를 복구했습니다.")
        } else {
            naviController.setActiveBlindTarget(
                destLat = state.recommendation.destination.lat,
                destLng = state.recommendation.destination.lng,
                blindName = state.blindName,
                alreadyArrived = state.isArrived
            )
            bindBlindDriveScreen(observedEtaSeconds)
            switchScreen(ScreenState.BLIND_DRIVE)
            naviController.syncNavigationState()
            showPleosToast("진행 중이던 ‘${state.blindName}’ 여정을 복구했습니다.")
        }
    }

    private fun confirmNewTripWhileDriving(active: ActiveTripState) {
        showModal(
            title = "진행 중인 여정이 있어요",
            message = "‘${active.blindName}’ 안내를 그만두고 새로 고를까요?",
            primaryLabel = "새로 고르기",
            onPrimary = {
                naviController.cancelRoute()
                activeTripRepository.clear()
                currentRecommendation = null
                switchScreen(ScreenState.TIME_ROUTE)
            },
            secondaryLabel = "이어서 가기",
            onSecondary = {
                restoreActiveTrip(active)
            }
        )
    }

    // ---- Theme Engine (Light / Dark / Autumn Pixel Art) ----
    private fun resolveEffectiveTheme(config: Configuration = resources.configuration): ThemeMode {
        return when (archiveRepository.getThemePreference()) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            "pixel" -> ThemeMode.PIXEL
            else -> detectPleosThemeMode(config)
        }
    }

    private fun detectPleosThemeMode(config: Configuration = resources.configuration): ThemeMode {
        val nightMask = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightMask == Configuration.UI_MODE_NIGHT_YES) return ThemeMode.DARK
        if (nightMask == Configuration.UI_MODE_NIGHT_NO) return ThemeMode.LIGHT
        val uiModeManager = getSystemService(UiModeManager::class.java)
        return if (uiModeManager?.nightMode == UiModeManager.MODE_NIGHT_YES) ThemeMode.DARK else ThemeMode.LIGHT
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val effectiveTheme = resolveEffectiveTheme(newConfig)
        if (effectiveTheme != currentTheme) {
            applyTheme(effectiveTheme)
        }
    }

    private fun reapplyThemeIfNeeded() {
        if (isThemeInitialized) {
            applyTheme(currentTheme)
        }
    }

    private fun View.setWireBackground(resId: Int, wireType: String? = null) {
        setTag(R.id.tag_wire_bg, wireType)
        setTag(R.id.tag_base_corner_radius, null)
        setTag(R.id.tag_base_background, null)
        if (resId == 0) {
            background = null
        } else {
            setBackgroundResource(resId)
        }
    }

    private fun applyTheme(theme: ThemeMode) {
        isThemeInitialized = true
        currentTheme = theme
        val isDark = theme == ThemeMode.DARK
        val isPixel = theme == ThemeMode.PIXEL

        val p = when (theme) {
            ThemeMode.DARK -> MonochromePalette(
                canvas = Color.parseColor("#131417"),
                surface = Color.parseColor("#1D1E21"),
                sub = Color.parseColor("#1F1F22"),
                selectedBg = Color.parseColor("#424345"),
                selectedStroke = Color.parseColor("#E0E1E6"),
                disabledBg = Color.parseColor("#131417"),
                disabledStroke = Color.parseColor("#2B2B2E"),
                heroDark = Color.parseColor("#000000"),
                ctaBg = Color.parseColor("#E0E1E6"),
                ctaText = Color.parseColor("#131417"),
                disabledCtaBg = Color.parseColor("#1F1F22"),
                disabledCtaText = Color.parseColor("#676769"),
                textPrimary = Color.parseColor("#FFFFFF"),
                textSecondary = Color.parseColor("#ADADAE"),
                textTertiary = Color.parseColor("#676769"),
                textUnselected = Color.parseColor("#DADBDB"),
                heroTextPrimary = Color.parseColor("#FFFFFF"),
                heroTextMuted = Color.parseColor("#8A8B8E"),
                border = Color.parseColor("#2B2B2E"),
                modalBg = Color.parseColor("#313236"),
                toastBg = Color.parseColor("#E0E1E6"),
                toastText = Color.parseColor("#131417")
            )
            ThemeMode.PIXEL -> MonochromePalette(
                canvas = Color.parseColor("#F5EAD6"),
                surface = Color.parseColor("#FFF9EE"),
                sub = Color.parseColor("#E8D5B7"),
                selectedBg = Color.parseColor("#FFE0B2"),
                selectedStroke = Color.parseColor("#B84318"),
                disabledBg = Color.parseColor("#ECE0CC"),
                disabledStroke = Color.parseColor("#C7B296"),
                heroDark = Color.parseColor("#2B1810"),
                ctaBg = Color.parseColor("#C84B1E"),
                ctaText = Color.parseColor("#FFF9F0"),
                disabledCtaBg = Color.parseColor("#DFCEB4"),
                disabledCtaText = Color.parseColor("#947B63"),
                textPrimary = Color.parseColor("#331B0E"),
                textSecondary = Color.parseColor("#745039"),
                textTertiary = Color.parseColor("#9E7E63"),
                textUnselected = Color.parseColor("#4B2E1C"),
                heroTextPrimary = Color.parseColor("#FFF3DF"),
                heroTextMuted = Color.parseColor("#D4AF87"),
                border = Color.parseColor("#5A351E"),
                modalBg = Color.parseColor("#FFF8EB"),
                toastBg = Color.parseColor("#362013"),
                toastText = Color.parseColor("#FFF5E4")
            )
            ThemeMode.LIGHT -> MonochromePalette(
                canvas = Color.parseColor("#F7F8FA"),
                surface = Color.parseColor("#FFFFFF"),
                sub = Color.parseColor("#EBECEE"),
                selectedBg = Color.parseColor("#FFFFFF"),
                selectedStroke = Color.parseColor("#131417"),
                disabledBg = Color.parseColor("#F7F8FA"),
                disabledStroke = Color.parseColor("#E5E6E9"),
                heroDark = Color.parseColor("#131417"),
                ctaBg = Color.parseColor("#44464E"),
                ctaText = Color.parseColor("#FFFFFF"),
                disabledCtaBg = Color.parseColor("#EBECEE"),
                disabledCtaText = Color.parseColor("#9A9B9F"),
                textPrimary = Color.parseColor("#131417"),
                textSecondary = Color.parseColor("#656769"),
                textTertiary = Color.parseColor("#9A9B9F"),
                textUnselected = Color.parseColor("#55575C"),
                heroTextPrimary = Color.parseColor("#FFFFFF"),
                heroTextMuted = Color.parseColor("#A5A6A9"),
                border = Color.parseColor("#E6E7EA"),
                modalBg = Color.parseColor("#FFFFFF"),
                toastBg = Color.parseColor("#313236"),
                toastText = Color.parseColor("#FFFFFF")
            )
        }

        window.statusBarColor = p.canvas
        window.navigationBarColor = p.canvas
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        binding.rootContainer.setBackgroundColor(p.canvas)

        // Swap pixel-art / dark / light brand badge & themed icons
        val brandBadgeRes = when {
            isPixel -> R.drawable.ic_brand_badge_pixel
            isDark -> R.drawable.ic_brand_badge_dark
            else -> R.drawable.ic_brand_badge
        }
        binding.ivTopBrand.setImageResource(brandBadgeRes)
        binding.viewSplash.ivSplashBrand.setImageResource(brandBadgeRes)
        binding.viewHome.ivHomeStep1.setImageResource(
            if (isPixel) R.drawable.ic_pixel_clock else R.drawable.ic_pleos_clock
        )
        binding.viewHome.ivHomeStep2.setImageResource(
            if (isPixel) R.drawable.ic_pixel_landscape else R.drawable.ic_kw_view
        )
        binding.viewHome.ivHomeStep3.setImageResource(
            if (isPixel) R.drawable.ic_pixel_lock_mono else R.drawable.ic_pleos_lock
        )
        if (isPixel) {
            binding.viewHome.ivHomeHeroLock.setImageResource(R.drawable.ic_pixel_lock)
            binding.viewHome.ivHomeHeroLock.setTag(R.id.tag_icon_role, "none")
            binding.viewHome.ivHomeHeroLock.imageTintList = null
        } else {
            binding.viewHome.ivHomeHeroLock.setImageResource(R.drawable.ic_pleos_lock)
            binding.viewHome.ivHomeHeroLock.setTag(R.id.tag_icon_role, "hero_primary")
        }

        // Tag key structural surfaces once
        binding.btnTopArchive.setTag(R.id.tag_wire_bg, "pill")
        binding.btnTopOptions.setTag(R.id.tag_wire_bg, "pill")
        binding.viewOptionsDialog.cardOptionsDialog.setTag(R.id.tag_wire_bg, "modal")
        binding.viewOptionsDialog.btnOptionsTopClose.setTag(R.id.tag_wire_bg, "modal_sub")
        binding.viewOptionsDialog.ivOptionsTopCloseIcon.setTag(R.id.tag_icon_role, "secondary")
        binding.viewOptionsDialog.btnOptGuide.setTag(R.id.tag_wire_bg, "modal_sub")
        binding.viewOptionsDialog.btnOptOverlay.setTag(R.id.tag_wire_bg, "modal_sub")
        binding.viewOptionsDialog.btnOptTerms.setTag(R.id.tag_wire_bg, "modal_sub")
        binding.viewOptionsDialog.btnOptionsClose.setTag(R.id.tag_wire_bg, "cta")
        binding.viewOptionsDialog.btnOptionsClose.setTag(R.id.tag_text_role, "cta")
        binding.viewConsent.cardConsentInfo.setTag(R.id.tag_wire_bg, "surface")
        binding.viewConsent.cardConsentDock.setTag(R.id.tag_wire_bg, "surface")
        binding.viewHome.cardHomeSteps.setTag(R.id.tag_wire_bg, "surface")
        binding.viewHome.btnHomeResumeDrive.setTag(R.id.tag_wire_bg, "selected")
        binding.viewHome.cardHomeHeroDock.setTag(R.id.tag_wire_bg, "hero_dark")
        binding.viewHome.btnHomeStart.setTag(R.id.tag_wire_bg, "cta")
        binding.viewHome.btnHomeStart.setTag(R.id.tag_text_role, "cta")
        binding.viewHome.tvHomeHeroOverline.setTag(R.id.tag_text_role, "hero_muted")

        binding.viewStepTimeRoute.cardTimeSlider.setTag(R.id.tag_wire_bg, "surface")
        binding.viewStepTimeRoute.btnTimeMinus.setTag(R.id.tag_wire_bg, "sub")
        binding.viewStepTimeRoute.btnTimeMinus.setTag(R.id.tag_text_role, "primary")
        binding.viewStepTimeRoute.btnTimePlus.setTag(R.id.tag_wire_bg, "sub")
        binding.viewStepTimeRoute.btnTimePlus.setTag(R.id.tag_text_role, "primary")
        binding.viewStepTimeRoute.cardTimeDock.setTag(R.id.tag_wire_bg, "surface")
        binding.viewStepTimeRoute.btnTimeNext.setTag(R.id.tag_wire_bg, "cta")
        binding.viewStepTimeRoute.btnTimeNext.setTag(R.id.tag_text_role, "cta")

        val timeSlider = binding.viewStepTimeRoute.sliderDriveTime
        val sliderActiveColor = if (isPixel) p.ctaBg else p.textPrimary
        val sliderThumbColor = if (isPixel) Color.parseColor("#9E3411") else p.textPrimary
        val inactiveTrack = when (theme) {
            ThemeMode.DARK -> Color.parseColor("#2B2C30")
            ThemeMode.PIXEL -> Color.parseColor("#DFCBB0")
            ThemeMode.LIGHT -> Color.parseColor("#E3E4E8")
        }
        timeSlider.trackActiveTintList = ColorStateList.valueOf(sliderActiveColor)
        timeSlider.trackInactiveTintList = ColorStateList.valueOf(inactiveTrack)
        timeSlider.thumbTintList = ColorStateList.valueOf(sliderThumbColor)
        timeSlider.haloTintList = ColorStateList.valueOf(
            Color.argb(36, Color.red(sliderActiveColor), Color.green(sliderActiveColor), Color.blue(sliderActiveColor))
        )

        binding.viewStepKeywords.cardKwDock.setTag(R.id.tag_wire_bg, "surface")
        binding.viewStepKeywords.btnKwDraw.setTag(R.id.tag_wire_bg, "cta")
        binding.viewStepKeywords.btnKwDraw.setTag(R.id.tag_text_role, "cta")

        binding.viewLoading.btnLoadingCancel.setTag(R.id.tag_wire_bg, "sub")
        binding.viewLoading.btnLoadingCancel.setTag(R.id.tag_text_role, "primary")

        binding.viewDestinationSealed.cardSealedClues.setTag(R.id.tag_wire_bg, "surface")
        binding.viewDestinationSealed.cardSealedHeroDock.setTag(R.id.tag_wire_bg, "hero_dark")
        binding.viewDestinationSealed.tvSealedOverline.setTag(R.id.tag_text_role, "hero_muted")
        binding.viewDestinationSealed.tvSealedAlias.setTag(R.id.tag_text_role, "hero_primary")
        binding.viewDestinationSealed.tvSealedEta.setTag(R.id.tag_text_role, "hero_primary")
        binding.viewDestinationSealed.tvSealedUnlockHint.setTag(R.id.tag_text_role, "hero_muted")
        binding.viewDestinationSealed.btnSealedDepart.setTag(R.id.tag_wire_bg, "cta")
        binding.viewDestinationSealed.tvSealedDepartLabel.setTag(R.id.tag_text_role, "cta")
        binding.viewDestinationSealed.ivSealedDepartIcon.setTag(R.id.tag_icon_role, "cta")
        binding.viewDestinationSealed.btnSealedRedraw.setTag(R.id.tag_wire_bg, "sub")
        binding.viewDestinationSealed.btnSealedRedraw.setTag(R.id.tag_text_role, "primary")

        binding.viewBlindDrive.cardDriveStatus.setTag(R.id.tag_wire_bg, "surface")
        binding.viewBlindDrive.cardDriveHeroDock.setTag(R.id.tag_wire_bg, "hero_dark")
        binding.viewBlindDrive.tvDriveDockAlias.setTag(R.id.tag_text_role, "hero_primary")
        binding.viewBlindDrive.btnDriveResumeNavi.setTag(R.id.tag_wire_bg, "cta")
        binding.viewBlindDrive.btnDriveResumeNavi.setTag(R.id.tag_text_role, "cta")
        binding.viewBlindDrive.btnDriveCancel.setTag(R.id.tag_wire_bg, "sub")
        binding.viewBlindDrive.btnDriveCancel.setTag(R.id.tag_text_role, "primary")

        binding.viewArrived.cardArrivedPhoto.setTag(R.id.tag_wire_bg, "surface")
        binding.viewArrived.cardArrivedDock.setTag(R.id.tag_wire_bg, "surface")

        binding.viewSecretArchive.btnArchiveNewDrive.setTag(R.id.tag_wire_bg, "sub")
        binding.viewSecretArchive.btnArchiveNewDrive.setTag(R.id.tag_text_role, "primary")

        binding.viewTerms.btnTermsWithdraw.setTag(R.id.tag_wire_bg, "sub")
        binding.viewTerms.btnTermsWithdraw.setTag(R.id.tag_text_role, "secondary")

        binding.viewConsent.btnConsentDecline.setTag(R.id.tag_wire_bg, "sub")
        binding.viewConsent.btnConsentDecline.setTag(R.id.tag_text_role, "primary")

        binding.viewModalDialog.cardModalDialog.setTag(R.id.tag_wire_bg, "modal")
        binding.viewModalDialog.btnModalPrimary.setTag(R.id.tag_wire_bg, "cta")
        binding.viewModalDialog.btnModalPrimary.setTag(R.id.tag_text_role, "cta")
        binding.viewModalDialog.btnModalSecondary.setTag(R.id.tag_wire_bg, "modal_sub")
        binding.viewModalDialog.btnModalSecondary.setTag(R.id.tag_text_role, "primary")

        binding.viewRatingDialog.cardRatingDialog.setTag(R.id.tag_wire_bg, "modal")
        binding.viewRatingDialog.tvRatingTitle.setTag(R.id.tag_text_role, "primary")
        binding.viewRatingDialog.tvRatingSubtitle.setTag(R.id.tag_text_role, "secondary")
        binding.viewRatingDialog.cardRatingPlace.setTag(R.id.tag_wire_bg, "modal_sub")
        binding.viewRatingDialog.tvRatingBlindName.setTag(R.id.tag_text_role, "secondary")
        binding.viewRatingDialog.tvRatingRealName.setTag(R.id.tag_text_role, "primary")
        binding.viewRatingDialog.tvRatingMeta.setTag(R.id.tag_text_role, "secondary")
        binding.viewRatingDialog.btnRatingLike.setTag(R.id.tag_wire_bg, "modal_sub")
        binding.viewRatingDialog.ivRatingLike.setTag(R.id.tag_icon_role, "primary")
        binding.viewRatingDialog.tvRatingLike.setTag(R.id.tag_text_role, "primary")
        binding.viewRatingDialog.btnRatingDislike.setTag(R.id.tag_wire_bg, "modal_sub")
        binding.viewRatingDialog.ivRatingDislike.setTag(R.id.tag_icon_role, "primary")
        binding.viewRatingDialog.tvRatingDislike.setTag(R.id.tag_text_role, "primary")
        binding.viewRatingDialog.btnRatingLater.setTag(R.id.tag_text_role, "secondary")

        val density = resources.displayMetrics.density
        val strokePx = (1f * density).roundToInt().coerceAtLeast(1)
        val pixelBlockPx = (2.5f * density).roundToInt().coerceAtLeast(4)

        // Toast pill
        val baseToastBg = (binding.llPleosToast.getTag(R.id.tag_base_background) as? GradientDrawable)
            ?: (binding.llPleosToast.background?.mutate() as? GradientDrawable)?.also {
                binding.llPleosToast.setTag(R.id.tag_base_background, it)
            }
        if (isPixel) {
            binding.llPleosToast.background = PixelRoundedDrawable(
                fillColor = p.toastBg,
                strokeColor = Color.parseColor("#8A502E"),
                pixelSize = pixelBlockPx,
                maxCornerSteps = 5,
                highlightColor = Color.parseColor("#4F2D1D")
            )
        } else if (baseToastBg != null) {
            baseToastBg.setColor(p.toastBg)
            baseToastBg.setStroke(0, Color.TRANSPARENT)
            binding.llPleosToast.background = baseToastBg
        }
        binding.tvPleosToast.setTextColor(p.toastText)
        applyTypefaceForTheme(binding.tvPleosToast, isPixel)

        tintHierarchy(binding.rootContainer, p, strokePx, pixelBlockPx, isPixel)
        binding.rootContainer.invalidate()
    }

    private fun applyTypefaceForTheme(view: TextView, isPixel: Boolean) {
        val baseTypeface = (view.getTag(R.id.tag_base_typeface) as? Typeface)
            ?: (view.typeface ?: Typeface.DEFAULT).also {
                view.setTag(R.id.tag_base_typeface, it)
            }
        if (isPixel) {
            val pxFont = pixelTypeface
            if (pxFont != null && view.typeface !== pxFont) {
                view.typeface = pxFont
            }
        } else if (view.typeface !== baseTypeface) {
            view.typeface = baseTypeface
        }
    }

    private fun tintHierarchy(
        view: View,
        p: MonochromePalette,
        strokePx: Int,
        pixelBlockPx: Int,
        isPixel: Boolean
    ) {
        if (view.id == R.id.llPleosToast || view.id == R.id.tvPleosToast) return

        val wireType = view.getTag(R.id.tag_wire_bg) as? String
        if (wireType != null) {
            val baseBg = (view.getTag(R.id.tag_base_background) as? GradientDrawable)
                ?: (view.background?.mutate() as? GradientDrawable)?.also {
                    view.setTag(R.id.tag_base_background, it)
                }

            if (isPixel) {
                val pixelDrawable = when (wireType) {
                    "surface" -> PixelRoundedDrawable(
                        fillColor = p.surface,
                        strokeColor = p.border,
                        pixelSize = pixelBlockPx,
                        maxCornerSteps = 7,
                        highlightColor = Color.WHITE,
                        shadeColor = Color.parseColor("#EFE0C6")
                    )
                    "sub", "pill" -> PixelRoundedDrawable(
                        fillColor = p.sub,
                        strokeColor = Color.parseColor("#7A4E31"),
                        pixelSize = pixelBlockPx,
                        maxCornerSteps = 5,
                        shadeColor = Color.parseColor("#D8C09B")
                    )
                    "modal_sub" -> PixelRoundedDrawable(
                        fillColor = Color.parseColor("#EAD8BA"),
                        strokeColor = Color.parseColor("#7A4E31"),
                        pixelSize = pixelBlockPx,
                        maxCornerSteps = 5,
                        shadeColor = Color.parseColor("#D7BF99")
                    )
                    "selected" -> PixelRoundedDrawable(
                        fillColor = p.selectedBg,
                        strokeColor = p.selectedStroke,
                        pixelSize = pixelBlockPx,
                        maxCornerSteps = 5,
                        highlightColor = Color.parseColor("#FFF0D6"),
                        shadeColor = Color.parseColor("#F2C488")
                    )
                    "disabled" -> PixelRoundedDrawable(
                        fillColor = p.disabledBg,
                        strokeColor = p.disabledStroke,
                        pixelSize = pixelBlockPx,
                        maxCornerSteps = 5
                    )
                    "hero_dark" -> PixelRoundedDrawable(
                        fillColor = p.heroDark,
                        strokeColor = Color.parseColor("#1A0E09"),
                        pixelSize = pixelBlockPx,
                        maxCornerSteps = 7,
                        highlightColor = Color.parseColor("#42271B"),
                        shadeColor = Color.parseColor("#20110B")
                    )
                    "cta" -> PixelRoundedDrawable(
                        fillColor = p.ctaBg,
                        strokeColor = Color.parseColor("#5E1D08"),
                        pixelSize = pixelBlockPx,
                        maxCornerSteps = 5,
                        highlightColor = Color.parseColor("#E06938"),
                        shadeColor = Color.parseColor("#9E3411")
                    )
                    "disabled_cta" -> PixelRoundedDrawable(
                        fillColor = p.disabledCtaBg,
                        strokeColor = Color.parseColor("#9E7E63"),
                        pixelSize = pixelBlockPx,
                        maxCornerSteps = 5
                    )
                    "modal" -> PixelRoundedDrawable(
                        fillColor = p.modalBg,
                        strokeColor = Color.parseColor("#4A2B18"),
                        pixelSize = pixelBlockPx,
                        maxCornerSteps = 7,
                        highlightColor = Color.WHITE,
                        shadeColor = Color.parseColor("#E8D5B7")
                    )
                    else -> null
                }
                if (pixelDrawable != null) {
                    view.background = pixelDrawable
                }
            } else if (baseBg != null) {
                val baseRadius = (view.getTag(R.id.tag_base_corner_radius) as? Float)
                    ?: baseBg.cornerRadius.also {
                        view.setTag(R.id.tag_base_corner_radius, it)
                    }
                baseBg.cornerRadius = baseRadius

                when (wireType) {
                    "surface" -> {
                        baseBg.setColor(p.surface)
                        baseBg.setStroke(strokePx, p.border)
                    }
                    "sub", "pill" -> {
                        baseBg.setColor(p.sub)
                        baseBg.setStroke(0, Color.TRANSPARENT)
                    }
                    "modal_sub" -> {
                        val modalSubColor = when (currentTheme) {
                            ThemeMode.DARK -> Color.parseColor("#3B3C40")
                            ThemeMode.PIXEL -> Color.parseColor("#EAD8BA")
                            ThemeMode.LIGHT -> Color.parseColor("#EBECEE")
                        }
                        baseBg.setColor(modalSubColor)
                        baseBg.setStroke(0, Color.TRANSPARENT)
                    }
                    "selected" -> {
                        val selectedStrokePx = (resources.displayMetrics.density * 2.5f).roundToInt()
                            .coerceAtLeast(strokePx + 1)
                        baseBg.setColor(p.selectedBg)
                        baseBg.setStroke(selectedStrokePx, p.selectedStroke)
                    }
                    "disabled" -> {
                        baseBg.setColor(p.disabledBg)
                        baseBg.setStroke(strokePx, p.disabledStroke)
                    }
                    "hero_dark" -> {
                        baseBg.setColor(p.heroDark)
                        baseBg.setStroke(0, Color.TRANSPARENT)
                    }
                    "cta" -> {
                        baseBg.setColor(p.ctaBg)
                        baseBg.setStroke(0, Color.TRANSPARENT)
                    }
                    "disabled_cta" -> {
                        baseBg.setColor(p.disabledCtaBg)
                        baseBg.setStroke(0, Color.TRANSPARENT)
                    }
                    "modal" -> {
                        baseBg.setColor(p.modalBg)
                        baseBg.setStroke(0, Color.TRANSPARENT)
                    }
                }
                view.background = baseBg
            }
        }

        if (view is ProgressBar) {
            val spinnerTint = if (isPixel) p.ctaBg else p.textPrimary
            if (view.isIndeterminate) {
                view.indeterminateTintList = ColorStateList.valueOf(spinnerTint)
            } else {
                view.progressTintList = ColorStateList.valueOf(spinnerTint)
                view.progressBackgroundTintList = ColorStateList.valueOf(p.sub)
            }
        }

        if (view is ImageView &&
            view !== binding.ivTopBrand &&
            view !== binding.viewSplash.ivSplashBrand &&
            view !== binding.viewArrived.ivArrivedPostcard
        ) {
            when (view.getTag(R.id.tag_icon_role) as? String) {
                "none" -> view.imageTintList = null
                "primary" -> view.imageTintList = ColorStateList.valueOf(p.textPrimary)
                "secondary" -> view.imageTintList = ColorStateList.valueOf(p.textSecondary)
                "tertiary" -> view.imageTintList = ColorStateList.valueOf(p.textTertiary)
                "unselected" -> view.imageTintList = ColorStateList.valueOf(p.textUnselected)
                "hero_primary" -> view.imageTintList = ColorStateList.valueOf(p.heroTextPrimary)
                "cta" -> view.imageTintList = ColorStateList.valueOf(p.ctaText)
                "check_on" -> view.imageTintList = ColorStateList.valueOf(p.ctaBg)
                "check_off" -> view.imageTintList = ColorStateList.valueOf(p.textTertiary)
                else -> {
                    val cur = view.imageTintList?.defaultColor
                    if (cur != null) {
                        val mapped = when (cur) {
                            Color.parseColor("#131417"), Color.parseColor("#FFFFFF"), Color.parseColor("#331B0E") -> p.textPrimary
                            Color.parseColor("#656769"), Color.parseColor("#ADADAE"), Color.parseColor("#745039") -> p.textSecondary
                            Color.parseColor("#36373A"), Color.parseColor("#DADBDB"), Color.parseColor("#4B2E1C") -> p.textUnselected
                            Color.parseColor("#9A9B9F"), Color.parseColor("#676769"), Color.parseColor("#9E7E63") -> p.textTertiary
                            else -> p.textPrimary
                        }
                        view.imageTintList = ColorStateList.valueOf(mapped)
                    }
                }
            }
        }

        if (view is TextView) {
            applyTypefaceForTheme(view, isPixel)
            when (view.getTag(R.id.tag_text_role) as? String) {
                "primary" -> view.setTextColor(p.textPrimary)
                "secondary" -> view.setTextColor(p.textSecondary)
                "tertiary" -> view.setTextColor(p.textTertiary)
                "unselected" -> view.setTextColor(p.textUnselected)
                "hero_primary" -> view.setTextColor(p.heroTextPrimary)
                "hero_muted" -> view.setTextColor(p.heroTextMuted)
                "cta" -> view.setTextColor(p.ctaText)
                "disabled" -> view.setTextColor(p.disabledCtaText)
                else -> {
                    val cur = view.currentTextColor
                    val mapped = when (cur) {
                        Color.parseColor("#131417"), Color.parseColor("#FFFFFF"), Color.parseColor("#331B0E") -> p.textPrimary
                        Color.parseColor("#656769"), Color.parseColor("#ADADAE"), Color.parseColor("#745039") -> p.textSecondary
                        Color.parseColor("#36373A"), Color.parseColor("#DADBDB"), Color.parseColor("#4B2E1C") -> p.textUnselected
                        Color.parseColor("#9A9B9F"), Color.parseColor("#676769"), Color.parseColor("#9E7E63") -> p.textTertiary
                        Color.parseColor("#A5A6A9"), Color.parseColor("#8A8B8E"), Color.parseColor("#D4AF87") -> p.heroTextMuted
                        else -> null
                    }
                    if (mapped != null) {
                        view.setTextColor(mapped)
                    }
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                tintHierarchy(view.getChildAt(i), p, strokePx, pixelBlockPx, isPixel)
            }
        }
    }

    // ---- Toast ----
    private fun showPleosToast(message: String) {
        toastHideRunnable?.let { handler.removeCallbacks(it) }
        binding.tvPleosToast.text = message
        binding.llPleosToast.visibility = View.VISIBLE
        binding.llPleosToast.alpha = 0f
        binding.llPleosToast.animate().alpha(1f).setDuration(180).start()
        val runnable = Runnable {
            binding.llPleosToast.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { binding.llPleosToast.visibility = View.GONE }
                .start()
        }
        toastHideRunnable = runnable
        handler.postDelayed(runnable, 2800L)
    }

    // ---- PleosNaviController.Callbacks ----
    override fun onLocationUpdated(lat: Double, lng: Double) {
        runOnUiThread {
            currentOriginLabel = String.format(Locale.US, "%.4f, %.4f", lat, lng)
            if (currentScreen == ScreenState.KEYWORDS) {
                scheduleKeywordsFetch(immediate = false)
            }
        }
    }

    override fun onRouteStarted(durationSeconds: Int, distanceMeters: Int) {
        runOnUiThread {
            if (initialEtaSeconds <= 0) initialEtaSeconds = durationSeconds.coerceAtLeast(60)
            if (initialDistanceMeters <= 0 || distanceMeters > initialDistanceMeters) {
                initialDistanceMeters = distanceMeters.coerceAtLeast(1000)
            }
            observedEtaSeconds = durationSeconds
            observedDistanceMeters = distanceMeters
            updateDriveProgressUi(durationSeconds, distanceMeters)
            updateBlindDriveUi()
            persistCurrentActiveTripIfNeeded(immediate = false)
        }
    }

    override fun onDrivingProgressUpdated(remainingEtaSeconds: Int, remainingDistanceMeters: Int) {
        runOnUiThread {
            observedEtaSeconds = remainingEtaSeconds.coerceAtLeast(0)
            observedDistanceMeters = remainingDistanceMeters.coerceAtLeast(0)
            if (currentScreen == ScreenState.BLIND_DRIVE) {
                updateDriveProgressUi(observedEtaSeconds, observedDistanceMeters)
            }
            val now = System.currentTimeMillis()
            if (now - lastPersistedProgressAtMillis >= 5_000L) {
                lastPersistedProgressAtMillis = now
                persistCurrentActiveTripIfNeeded(immediate = false)
            }
        }
    }

    override fun onNaviRoutingStateChanged(
        isRouting: Boolean,
        destName: String?,
        destLat: Double?,
        destLng: Double?
    ) {
        runOnUiThread {
            if (currentScreen == ScreenState.BLIND_DRIVE) {
                updateBlindDriveUi()
            }
            if (isRouting && destLat != null && destLng != null &&
                !naviController.isMatchingActiveTarget(destName, destLat, destLng)
            ) {
                Log.d("Eodigaji", "Navigation destination changed externally: $destName")
            }
        }
    }

    override fun onArrivalStateChanged(isArrived: Boolean) {
        runOnUiThread {
            if (currentScreen == ScreenState.BLIND_DRIVE) {
                updateBlindDriveUi()
            }
            persistCurrentActiveTripIfNeeded(immediate = true)
        }
    }

    override fun onGearStateChanged(isParked: Boolean) {
        runOnUiThread {
            if (currentScreen == ScreenState.BLIND_DRIVE) {
                updateBlindDriveUi()
                reapplyThemeIfNeeded()
            }
        }
    }

    override fun onNaviArrived() {
        runOnUiThread {
            if (currentScreen != ScreenState.BLIND_DRIVE || naviController.isParked) {
                return@runOnUiThread
            }
            persistCurrentActiveTripIfNeeded(immediate = true)
            updateBlindDriveUi()
            if (isActivityInForeground) {
                showPleosToast("도착했어요. 주차하면 비밀 장소가 열려요.")
            } else if (!hasPostedParkHintForCurrentTrip) {
                hasPostedParkHintForCurrentTrip = true
                if (!showOverlayArrivalHintBanner()) {
                    postArrivalParkHintNotification()
                }
            }
        }
    }

    override fun onArrivalAndParkSatisfied() {
        runOnUiThread {
            val rec = currentRecommendation ?: return@runOnUiThread
            val wasBackgrounded = !isActivityInForeground
            dismissRouteMaskCurtain(animated = false)
            dismissOverlayArrivalHintBanner()
            cancelArrivalReturnNotification()
            naviController.cancelRoute()
            persistActiveTrip(phase = ActiveTripPhase.ARRIVED, isArrived = true, immediate = true)
            bindArrivedScreen(rec)
            switchScreen(ScreenState.ARRIVED)
            if (wasBackgrounded) {
                hasAutoReturnedForCurrentTrip = true
                bringAppToForeground(unboxed = true)
            }
            handler.postDelayed({
                if (currentScreen == ScreenState.ARRIVED && !isActivityInForeground) {
                    bringAppToForeground(unboxed = true)
                }
            }, 350L)
        }
    }

    override fun onRouteReadyForDisplay() {
        runOnUiThread {
            if (currentScreen == ScreenState.BLIND_DRIVE) {
                updateBlindDriveUi()
            }
            if (routeMaskView != null) {
                routeMaskRevealRunnable?.let { handler.removeCallbacks(it) }
                val revealTask = Runnable {
                    dismissRouteMaskCurtain(animated = true)
                }
                routeMaskRevealRunnable = revealTask
                handler.postDelayed(revealTask, 850L)
            }
        }
    }

    override fun onRouteCancelled() {
        runOnUiThread {
            dismissRouteMaskCurtain(animated = false)
            if (currentScreen == ScreenState.BLIND_DRIVE) {
                updateBlindDriveUi()
            }
        }
    }

    override fun onRouteEnded() {
        runOnUiThread {
            Log.d("Eodigaji", "Route ended")
        }
    }

    // ---- Notifications, Location Permission & Auto-Return ----
    private fun ensureNotificationPermissionIfNeeded() {
        val permsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val prefs = archiveRepository.getPrefs()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !prefs.getBoolean(KEY_ASKED_NOTIFICATION_PERM, false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean(KEY_ASKED_NOTIFICATION_PERM, true).apply()
            permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permsToRequest.toTypedArray(),
                REQ_POST_NOTIFICATIONS
            )
        } else {
            naviController.refreshLocationPermissionAndStartUpdates()
            ensureOverlayPermissionIfNeeded()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            naviController.refreshLocationPermissionAndStartUpdates()
            ensureOverlayPermissionIfNeeded()
        }
    }

    private fun ensureOverlayPermissionIfNeeded() {
        if (Settings.canDrawOverlays(this)) return
        val prefs = archiveRepository.getPrefs()
        if (prefs.getBoolean(KEY_ASKED_OVERLAY_PERM, false)) return
        showOverlayPermissionGuideModal(markAsAsked = true)
    }

    private fun showOverlayPermissionGuideModal(markAsAsked: Boolean) {
        if (markAsAsked) {
            archiveRepository.getPrefs().edit().putBoolean(KEY_ASKED_OVERLAY_PERM, true).apply()
        }
        showModal(
            title = "내비 위 도착 안내를 켜 볼까요?",
            message = "내비게이션으로 넘어갈 때 목적지를 가려 주고, 주행 중 도착 시 상단 배너로 알려 드려요.",
            subInfo = "설정 방법\n1. ‘설정 열기’를 눌러 권한 화면으로 이동하세요. (앱 목록이 나오면 ‘어디가지’ 선택)\n2. ‘다른 앱 위에 표시’ 스위치를 켜면 자동으로 어디가지로 돌아와요.",
            primaryLabel = "설정 열기",
            onPrimary = { openOverlayPermissionSettings() },
            secondaryLabel = "나중에"
        )
    }

    private fun openOverlayPermissionSettings() {
        val overlayIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        val fallbackIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri()
        )
        val target = when {
            overlayIntent.resolveActivity(packageManager) != null -> overlayIntent
            fallbackIntent.resolveActivity(packageManager) != null -> fallbackIntent
            else -> null
        }
        if (target == null) {
            showPleosToast("이 기기에서는 권한 설정 화면을 열 수 없어요.")
            return
        }
        overlayWasGrantedBeforeSettings = Settings.canDrawOverlays(this)
        awaitingOverlaySettingsReturn = true
        OverlayReturnMonitorService.start(this)
        startOverlaySettingsReturnPolling()
        runCatching {
            overlayPermissionLauncher.launch(target)
        }.onFailure {
            awaitingOverlaySettingsReturn = false
            cancelOverlaySettingsReturnPolling()
            OverlayReturnMonitorService.stop(this)
            showPleosToast("권한 설정 화면을 열지 못했어요.")
        }
    }

    private fun startOverlaySettingsReturnPolling() {
        cancelOverlaySettingsReturnPolling()
        var checks = 0
        val poll = object : Runnable {
            override fun run() {
                if (!awaitingOverlaySettingsReturn || isFinishing || isDestroyed) return
                checks++
                if (!isActivityInForeground) {
                    val nowGranted = Settings.canDrawOverlays(this@MainActivity)
                    if (!nowGranted) {
                        overlayWasGrantedBeforeSettings = false
                        dismissOverlayReturnPill()
                    } else if (!overlayWasGrantedBeforeSettings) {
                        returnToAppFromOverlaySettings()
                        handler.postDelayed({
                            if (awaitingOverlaySettingsReturn && !isActivityInForeground && !isFinishing && !isDestroyed) {
                                returnToAppFromOverlaySettings()
                            }
                        }, 250L)
                        handler.postDelayed({
                            if (awaitingOverlaySettingsReturn && !isActivityInForeground && !isFinishing && !isDestroyed) {
                                showOverlayReturnPill()
                            }
                        }, 600L)
                        return
                    } else if (checks >= 3 && overlayReturnPillView == null) {
                        showOverlayReturnPill()
                    }
                } else if (checks > 4) {
                    completeOverlaySettingsReturnIfPending()
                    return
                }
                if (checks < 360) {
                    handler.postDelayed(this, 350L)
                } else {
                    awaitingOverlaySettingsReturn = false
                    dismissOverlayReturnPill()
                    OverlayReturnMonitorService.stop(this@MainActivity)
                }
            }
        }
        overlayReturnPollRunnable = poll
        handler.postDelayed(poll, 450L)
    }

    private fun cancelOverlaySettingsReturnPolling() {
        overlayReturnPollRunnable?.let { handler.removeCallbacks(it) }
        overlayReturnPollRunnable = null
    }

    private fun returnToAppFromOverlaySettings() {
        cancelOverlaySettingsReturnPolling()
        dismissOverlayReturnPill()
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            am?.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
        }
        runCatching {
            startActivity(returnIntent)
        }.onFailure {
            Log.w("Eodigaji", "Return from overlay settings failed: ${it.message}")
            showOverlayReturnPill()
        }
    }

    private fun completeOverlaySettingsReturnIfPending() {
        OverlayReturnMonitorService.stop(this)
        if (!awaitingOverlaySettingsReturn) return
        awaitingOverlaySettingsReturn = false
        cancelOverlaySettingsReturnPolling()
        dismissOverlayReturnPill()
        if (binding.viewOptionsDialog.root.visibility == View.VISIBLE) {
            renderOptionsDialog()
            reapplyThemeIfNeeded()
        }
        if (Settings.canDrawOverlays(this)) {
            if (!overlayWasGrantedBeforeSettings) {
                showPleosToast("내비 위 도착 안내·경로 가림이 켜졌어요!")
            }
        } else {
            showPleosToast("아직 꺼져 있어요. 옵션에서 언제든 켤 수 있어요.")
        }
    }

    private fun showOverlayReturnPill() {
        if (overlayReturnPillView != null || !Settings.canDrawOverlays(this)) return
        val wm = applicationContext.getSystemService(WindowManager::class.java) ?: return

        val density = resources.displayMetrics.density
        val isPixel = currentTheme == ThemeMode.PIXEL
        val (bgColor, textColor) = when (currentTheme) {
            ThemeMode.DARK -> Color.parseColor("#E0E1E6") to Color.parseColor("#131417")
            ThemeMode.PIXEL -> Color.parseColor("#362013") to Color.parseColor("#FFF5E4")
            ThemeMode.LIGHT -> Color.parseColor("#131417") to Color.parseColor("#FFFFFF")
        }

        val pill = LinearLayout(applicationContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (22f * density).roundToInt()
            val padV = (13f * density).roundToInt()
            setPadding(padH, padV, padH, padV)
            elevation = 10f * density
            isClickable = true
            isFocusable = true
            background = if (isPixel) {
                val pixelBlockPx = (2.5f * density).roundToInt().coerceAtLeast(4)
                PixelRoundedDrawable(
                    fillColor = bgColor,
                    strokeColor = Color.parseColor("#8A502E"),
                    pixelSize = pixelBlockPx,
                    maxCornerSteps = 5,
                    highlightColor = Color.parseColor("#4F2D1D")
                )
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 28f * density
                    setColor(bgColor)
                }
            }
            setOnClickListener { returnToAppFromOverlaySettings() }
        }

        val iconSize = (22f * density).roundToInt()
        val icon = ImageView(applicationContext).apply {
            setImageResource(R.drawable.ic_brand_mark)
            imageTintList = ColorStateList.valueOf(textColor)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (10f * density).roundToInt()
            }
        }
        val label = TextView(applicationContext).apply {
            text = "← 어디가지로 돌아가기"
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(if (isPixel) pixelTypeface else Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
        }
        pill.addView(icon)
        pill.addView(label)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (24f * density).roundToInt()
            y = (76f * density).roundToInt()
        }

        runCatching {
            wm.addView(pill, params)
            overlayReturnPillView = pill
        }.onFailure {
            Log.w("Eodigaji", "Overlay return pill failed: ${it.message}")
            overlayReturnPillView = null
        }
    }

    private fun dismissOverlayReturnPill() {
        val view = overlayReturnPillView ?: return
        overlayReturnPillView = null
        runCatching {
            val wm = applicationContext.getSystemService(WindowManager::class.java)
            if (view.isAttachedToWindow) {
                wm?.removeViewImmediate(view)
            } else {
                wm?.removeView(view)
            }
        }
    }

    private fun createArrivalNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ARRIVAL_RETURN) != null) return
        val channel = NotificationChannel(
            CHANNEL_ARRIVAL_RETURN,
            "목적지 도착 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "목적지 도착 시 어디가지 앱으로 자동 복귀 및 알림을 표시합니다."
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun autoReturnToastMessage(unboxed: Boolean): String {
        return if (unboxed) {
            "도착 및 주차가 완료되어 비밀 장소가 열렸어요!"
        } else {
            "목적지에 도착했어요. 주차하면 비밀 장소가 열려요."
        }
    }

    private fun bringAppToForeground(unboxed: Boolean) {
        if (isActivityInForeground) return

        pendingAutoReturnToast = autoReturnToastMessage(unboxed)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_AUTO_RETURNED_ON_ARRIVAL, true)
            putExtra(EXTRA_AUTO_RETURNED_UNBOXED, unboxed)
        }

        try {
            val am = getSystemService(ActivityManager::class.java)
            am?.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
        } catch (e: Exception) {
            Log.w("Eodigaji", "moveTaskToFront failed: ${e.message}")
        }

        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.w("Eodigaji", "Background startActivity failed: ${e.message}")
        }

        notificationFallbackRunnable?.let { handler.removeCallbacks(it) }
        val fallback = Runnable {
            if (!isActivityInForeground) {
                postArrivalReturnNotification(launchIntent)
            }
        }
        notificationFallbackRunnable = fallback
        handler.postDelayed(fallback, 350L)
    }

    private fun postArrivalReturnNotification(launchIntent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createArrivalNotificationChannel()
        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIF_ID_ARRIVAL_RETURN,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ARRIVAL_RETURN)
            .setSmallIcon(R.drawable.ic_brand_mark)
            .setContentTitle("도착했어요")
            .setContentText("${activeBlindName}의 정체를 열어 볼까요?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .addAction(0, "열기", pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_ARRIVAL_RETURN, notification)
        }.onFailure { e ->
            Log.w("Eodigaji", "Failed to post arrival notification: ${e.message}")
        }
    }

    private fun postArrivalParkHintNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createArrivalNotificationChannel()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIF_ID_ARRIVAL_PARK_HINT,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ARRIVAL_RETURN)
            .setSmallIcon(R.drawable.ic_brand_mark)
            .setContentTitle("목적지에 도착했어요")
            .setContentText("주차(P)하면 ‘${activeBlindName}’의 정체가 열려요.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_ARRIVAL_PARK_HINT, notification)
        }.onFailure { e ->
            Log.w("Eodigaji", "Failed to post arrival park hint notification: ${e.message}")
        }
    }

    private fun showOverlayArrivalHintBanner(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        val wm = applicationContext.getSystemService(WindowManager::class.java) ?: return false

        dismissOverlayArrivalHintBanner()

        val density = resources.displayMetrics.density
        val isPixel = currentTheme == ThemeMode.PIXEL
        val (bgColor, textColor) = when (currentTheme) {
            ThemeMode.DARK -> Color.parseColor("#E0E1E6") to Color.parseColor("#131417")
            ThemeMode.PIXEL -> Color.parseColor("#362013") to Color.parseColor("#FFF5E4")
            ThemeMode.LIGHT -> Color.parseColor("#313236") to Color.parseColor("#FFFFFF")
        }

        val banner = LinearLayout(applicationContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (24f * density).roundToInt()
            val padV = (14f * density).roundToInt()
            setPadding(padH, padV, padH, padV)
            elevation = 10f * density
            background = if (isPixel) {
                val pixelBlockPx = (2.5f * density).roundToInt().coerceAtLeast(4)
                PixelRoundedDrawable(
                    fillColor = bgColor,
                    strokeColor = Color.parseColor("#8A502E"),
                    pixelSize = pixelBlockPx,
                    maxCornerSteps = 5,
                    highlightColor = Color.parseColor("#4F2D1D")
                )
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 28f * density
                    setColor(bgColor)
                }
            }
            alpha = 0f
        }

        val iconSize = (24f * density).roundToInt()
        val icon = ImageView(applicationContext).apply {
            setImageResource(R.drawable.ic_brand_mark)
            imageTintList = ColorStateList.valueOf(textColor)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (12f * density).roundToInt()
            }
        }
        val label = TextView(applicationContext).apply {
            text = "도착했어요. 주차(P)하면 ‘${activeBlindName}’의 정체가 열려요."
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(if (isPixel) pixelTypeface else Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
        }
        banner.addView(icon)
        banner.addView(label)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (28f * density).roundToInt()
        }

        return try {
            wm.addView(banner, params)
            overlayHintView = banner
            banner.animate().alpha(1f).setDuration(200L).start()
            val dismissTask = Runnable {
                val current = overlayHintView ?: return@Runnable
                current.animate()
                    .alpha(0f)
                    .setDuration(220L)
                    .withEndAction { dismissOverlayArrivalHintBanner() }
                    .start()
            }
            overlayHintDismissRunnable = dismissTask
            handler.postDelayed(dismissTask, 5_000L)
            true
        } catch (e: Exception) {
            Log.w("Eodigaji", "Overlay arrival banner failed: ${e.message}")
            overlayHintView = null
            false
        }
    }

    private fun dismissOverlayArrivalHintBanner() {
        overlayHintDismissRunnable?.let { handler.removeCallbacks(it) }
        overlayHintDismissRunnable = null
        val view = overlayHintView ?: return
        overlayHintView = null
        runCatching {
            val wm = applicationContext.getSystemService(WindowManager::class.java)
            if (view.isAttachedToWindow) {
                wm?.removeViewImmediate(view)
            } else {
                wm?.removeView(view)
            }
        }
    }

    private fun showRouteMaskCurtain() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = applicationContext.getSystemService(WindowManager::class.java) ?: return

        dismissRouteMaskCurtain(animated = false)

        val density = resources.displayMetrics.density
        val isPixel = currentTheme == ThemeMode.PIXEL
        val colors = when (currentTheme) {
            ThemeMode.DARK -> intArrayOf(
                Color.parseColor("#131417"),
                Color.parseColor("#1D1E21"),
                Color.parseColor("#2B2B2E"),
                Color.parseColor("#313236"),
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#ADADAE"),
                Color.parseColor("#E0E1E6")
            )
            ThemeMode.PIXEL -> intArrayOf(
                Color.parseColor("#F5EAD6"),
                Color.parseColor("#FFF9EE"),
                Color.parseColor("#5A351E"),
                Color.parseColor("#E8D5B7"),
                Color.parseColor("#331B0E"),
                Color.parseColor("#745039"),
                Color.parseColor("#C84B1E")
            )
            ThemeMode.LIGHT -> intArrayOf(
                Color.parseColor("#F7F8FA"),
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#E6E7EA"),
                Color.parseColor("#EBECEE"),
                Color.parseColor("#131417"),
                Color.parseColor("#656769"),
                Color.parseColor("#131417")
            )
        }
        val canvasColor = colors[0]
        val surfaceColor = colors[1]
        val borderColor = colors[2]
        val subColor = colors[3]
        val textPrimary = colors[4]
        val textSecondary = colors[5]
        val ctaBg = colors[6]

        val root = FrameLayout(applicationContext).apply {
            setBackgroundColor(canvasColor)
            alpha = 0f
        }

        val card = LinearLayout(applicationContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = (44f * density).roundToInt()
            val padV = (36f * density).roundToInt()
            setPadding(padH, padV, padH, padV)
            background = if (isPixel) {
                val pixelBlockPx = (2.5f * density).roundToInt().coerceAtLeast(4)
                PixelRoundedDrawable(
                    fillColor = surfaceColor,
                    strokeColor = borderColor,
                    pixelSize = pixelBlockPx,
                    maxCornerSteps = 7,
                    highlightColor = Color.WHITE,
                    shadeColor = Color.parseColor("#EFE0C6")
                )
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 24f * density
                    setColor(surfaceColor)
                    setStroke((1f * density).roundToInt().coerceAtLeast(1), borderColor)
                }
            }
        }

        val brandBadgeRes = when (currentTheme) {
            ThemeMode.DARK -> R.drawable.ic_brand_badge_dark
            ThemeMode.PIXEL -> R.drawable.ic_brand_badge_pixel
            ThemeMode.LIGHT -> R.drawable.ic_brand_badge
        }
        val iconSize = (68f * density).roundToInt()
        val lockIcon = ImageView(applicationContext).apply {
            setImageResource(brandBadgeRes)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                bottomMargin = (20f * density).roundToInt()
            }
        }

        val title = TextView(applicationContext).apply {
            text = "‘${activeBlindName}’ 경로를 준비하고 있어요"
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(if (isPixel) pixelTypeface else Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(applicationContext).apply {
            text = "목적지가 보이지 않도록 내비게이션 시점을 맞추는 중이에요"
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            if (isPixel) typeface = pixelTypeface
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10f * density).roundToInt()
                bottomMargin = (24f * density).roundToInt()
            }
        }

        val spinner = ProgressBar(applicationContext).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ctaBg)
            val size = (42f * density).roundToInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                bottomMargin = (24f * density).roundToInt()
            }
        }

        val skipBtn = TextView(applicationContext).apply {
            text = "바로 보기"
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(if (isPixel) pixelTypeface else Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            val btnPadH = (24f * density).roundToInt()
            val btnPadV = (12f * density).roundToInt()
            setPadding(btnPadH, btnPadV, btnPadH, btnPadV)
            background = if (isPixel) {
                val pixelBlockPx = (2.5f * density).roundToInt().coerceAtLeast(4)
                PixelRoundedDrawable(
                    fillColor = subColor,
                    strokeColor = Color.parseColor("#7A4E31"),
                    pixelSize = pixelBlockPx,
                    maxCornerSteps = 5,
                    shadeColor = Color.parseColor("#D8C09B")
                )
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f * density
                    setColor(subColor)
                }
            }
            setOnClickListener { dismissRouteMaskCurtain(animated = true) }
        }

        card.addView(lockIcon)
        card.addView(title)
        card.addView(subtitle)
        card.addView(spinner)
        card.addView(skipBtn)

        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        runCatching {
            wm.addView(root, params)
            routeMaskView = root
            routeMaskShownAtMs = SystemClock.elapsedRealtime()
            root.animate().alpha(1f).setDuration(140L).start()
        }.onFailure { e ->
            Log.w("Eodigaji", "Route mask curtain failed: ${e.message}")
            routeMaskView = null
        }
    }

    private fun dismissRouteMaskCurtain(animated: Boolean) {
        routeMaskRevealRunnable?.let { handler.removeCallbacks(it) }
        routeMaskRevealRunnable = null
        val view = routeMaskView ?: return
        routeMaskView = null

        val removeNow = {
            runCatching {
                val wm = applicationContext.getSystemService(WindowManager::class.java)
                if (view.isAttachedToWindow) {
                    wm?.removeViewImmediate(view)
                } else {
                    wm?.removeView(view)
                }
            }
            Unit
        }

        if (animated && view.isAttachedToWindow) {
            view.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction { removeNow() }
                .start()
        } else {
            removeNow()
        }
    }

    private fun cancelArrivalReturnNotification() {
        runCatching {
            NotificationManagerCompat.from(this).apply {
                cancel(NOTIF_ID_ARRIVAL_RETURN)
                cancel(NOTIF_ID_ARRIVAL_PARK_HINT)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        toastHideRunnable?.let { handler.removeCallbacks(it) }
        notificationFallbackRunnable?.let { handler.removeCallbacks(it) }
        cancelOverlaySettingsReturnPolling()
        dismissOverlayReturnPill()
        OverlayReturnMonitorService.stop(this)
        dismissRouteMaskCurtain(animated = false)
        dismissOverlayArrivalHintBanner()
        keywordsDebounceJob?.cancel()
        recommendationJob?.cancel()
        naviController.release()
    }

    companion object {
        private const val KEY_ASKED_NOTIFICATION_PERM = "asked_notification_perm"
        private const val KEY_ASKED_OVERLAY_PERM = "asked_overlay_perm"
        private const val REQ_POST_NOTIFICATIONS = 2001
        private const val CHANNEL_ARRIVAL_RETURN = "arrival_return_channel"
        private const val NOTIF_ID_ARRIVAL_RETURN = 1001
        private const val NOTIF_ID_ARRIVAL_PARK_HINT = 1003
        private const val EXTRA_AUTO_RETURNED_ON_ARRIVAL = "extra_auto_returned_on_arrival"
        private const val EXTRA_AUTO_RETURNED_UNBOXED = "extra_auto_returned_unboxed"
    }
}

private class PixelRoundedDrawable(
    fillColor: Int,
    strokeColor: Int,
    private val pixelSize: Int,
    private val maxCornerSteps: Int,
    highlightColor: Int? = null,
    shadeColor: Int? = null
) : Drawable() {

    private val fillPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
        color = fillColor
    }

    private val strokePaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
        color = strokeColor
    }

    private val highlightPaint = highlightColor?.let {
        Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
            color = it
        }
    }

    private val shadePaint = shadeColor?.let {
        Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
            color = it
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val left = b.left.toFloat()
        val top = b.top.toFloat()
        val right = b.right.toFloat()
        val bottom = b.bottom.toFloat()
        val p = pixelSize.coerceAtLeast(1).toFloat()

        val maxFitSteps = (min(w, h) / (2f * p)).toInt().coerceAtLeast(1)
        val steps = min(maxCornerSteps, maxFitSteps)
        val insets = cornerInsetsFor(steps)

        for (r in insets.indices) {
            val cur = insets[r] * p
            val yTopStart = top + r * p
            val yTopEnd = yTopStart + p
            val yBotEnd = bottom - r * p
            val yBotStart = yBotEnd - p

            if (r == 0) {
                if (right - cur > left + cur) {
                    canvas.drawRect(left + cur, yTopStart, right - cur, yTopEnd, strokePaint)
                    canvas.drawRect(left + cur, yBotStart, right - cur, yBotEnd, strokePaint)
                }
            } else {
                val prev = insets[r - 1] * p
                val borderW = if (prev > cur) prev - cur else p
                val innerLeft = left + cur + borderW
                val innerRight = right - cur - borderW

                if (innerRight > innerLeft) {
                    canvas.drawRect(innerLeft, yTopStart, innerRight, yTopEnd, fillPaint)
                    if (r == 1 && highlightPaint != null) {
                        val hiLeft = left + insets[0] * p
                        val hiRight = right - insets[0] * p
                        if (hiRight > hiLeft) {
                            canvas.drawRect(hiLeft, yTopStart, hiRight, yTopEnd, highlightPaint)
                        }
                    }
                }
                canvas.drawRect(left + cur, yTopStart, left + cur + borderW, yTopEnd, strokePaint)
                canvas.drawRect(right - cur - borderW, yTopStart, right - cur, yTopEnd, strokePaint)

                if (innerRight > innerLeft) {
                    canvas.drawRect(innerLeft, yBotStart, innerRight, yBotEnd, fillPaint)
                    if (r == 1 && shadePaint != null) {
                        val shLeft = left + insets[0] * p
                        val shRight = right - insets[0] * p
                        if (shRight > shLeft) {
                            canvas.drawRect(shLeft, yBotStart, shRight, yBotEnd, shadePaint)
                        }
                    }
                }
                canvas.drawRect(left + cur, yBotStart, left + cur + borderW, yBotEnd, strokePaint)
                canvas.drawRect(right - cur - borderW, yBotStart, right - cur, yBotEnd, strokePaint)
            }
        }

        val midTop = top + insets.size * p
        val midBottom = bottom - insets.size * p
        if (midBottom > midTop) {
            if (right - p > left + p) {
                canvas.drawRect(left + p, midTop, right - p, midBottom, fillPaint)
            }
            canvas.drawRect(left, midTop, left + p, midBottom, strokePaint)
            canvas.drawRect(right - p, midTop, right, midBottom, strokePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        highlightPaint?.alpha = alpha
        shadePaint?.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        highlightPaint?.colorFilter = colorFilter
        shadePaint?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private fun cornerInsetsFor(steps: Int): IntArray = when {
            steps >= 7 -> intArrayOf(6, 4, 3, 2, 1, 1, 0)
            steps == 6 -> intArrayOf(5, 3, 2, 1, 1, 0)
            steps == 5 -> intArrayOf(4, 2, 1, 1, 0)
            steps == 4 -> intArrayOf(3, 1, 1, 0)
            steps == 3 -> intArrayOf(2, 1, 0)
            steps == 2 -> intArrayOf(1, 0)
            else -> intArrayOf(0)
        }
    }
}
