package com.example.eodigaji.navi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.eodigaji.BuildConfig
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PleosNaviController(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onLocationUpdated(lat: Double, lng: Double)
        fun onRouteStarted(durationSeconds: Int, distanceMeters: Int)
        fun onDrivingProgressUpdated(remainingEtaSeconds: Int, remainingDistanceMeters: Int)
        fun onNaviRoutingStateChanged(
            isRouting: Boolean,
            destName: String?,
            destLat: Double?,
            destLng: Double?,
        )
        fun onArrivalStateChanged(isArrived: Boolean)
        fun onGearStateChanged(isParked: Boolean)
        fun onNaviArrived()
        fun onArrivalAndParkSatisfied()
        fun onRouteReadyForDisplay()
        fun onRouteCancelled()
        fun onRouteEnded()
    }

    companion object {
        private const val TAG = "PleosNaviController"
        const val DEFAULT_LAT = 37.4979
        const val DEFAULT_LNG = 127.0276
        private const val ARRIVAL_PROXIMITY_METERS = 300.0
    }

    var currentLat: Double = DEFAULT_LAT
        private set
    var currentLng: Double = DEFAULT_LNG
        private set
    var hasLiveLocation: Boolean = false
        private set

    var isNaviConnected: Boolean = false
        private set
    var isNaviRouting: Boolean = false
        private set
    var isRoutePreparing: Boolean = false
        private set

    var isArrived: Boolean = false
        private set
    var isParked: Boolean = false
        private set

    var activeTargetLat: Double? = null
        private set
    var activeTargetLng: Double? = null
        private set
    var activeTargetName: String? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var naviHelper: NaviHelper? = null
    private var vehicle: Vehicle? = null
    private var locationManager: LocationManager? = null
    private var isSystemLocationRegistered: Boolean = false
    private var pendingTripDeferred: CompletableDeferred<TripInfo>? = null
    private var hasConfirmedActiveRoute: Boolean = false
    private var pendingForegroundTransition: Boolean = false
    private var foregroundFallbackRunnable: Runnable? = null
    private var lastNavigationStatus: NavigationStatus? = null
    private var lastNaviMode: NaviMode? = null
    private var lastCancelRequestElapsedMs: Long = 0L

    private val systemLocationListener = LocationListener { location ->
        handleSystemLocation(location)
    }

    private val gearSelectionListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            Log.d(TAG, "Vehicle Gear: $gear")
            val parked = (gear == VehicleGear.GEAR_PARK)
            isParked = parked
            callbacks.onGearStateChanged(parked)
            refreshLocationPermissionAndStartUpdates()
            checkArrivalCondition()
        }

        override fun onFailed(e: Exception) {
            Log.e(TAG, "Gear listener failed: ${e.message}")
        }
    }

    private val naviEventListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val lat = currentLocationInfo.latitude
            val lng = currentLocationInfo.longitude
            if (lat != 0.0 && lng != 0.0) {
                currentLat = lat
                currentLng = lng
                hasLiveLocation = true
                callbacks.onLocationUpdated(lat, lng)
                checkProximityArrival(lat, lng)
            }
        }

        override fun onTripInfo(tripInfo: TripInfo) {
            Log.d(TAG, "onTripInfo: eta=${tripInfo.eta}s, distance=${tripInfo.distance}m")
            pendingTripDeferred?.complete(tripInfo)
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.d(TAG, "onRouteStarted: duration=${routeStartInfo.duration}s, distance=${routeStartInfo.distance}m")
            isArrived = false
            isRoutePreparing = false
            hasConfirmedActiveRoute = true
            isNaviRouting = true
            lastNavigationStatus = null
            lastNaviMode = null
            centerMapOnCurrentLocation()
            completePendingForegroundTransition()
            callbacks.onRouteStarted(routeStartInfo.duration, routeStartInfo.distance)
            callbacks.onNaviRoutingStateChanged(
                isRouting = true,
                destName = activeTargetName,
                destLat = activeTargetLat,
                destLng = activeTargetLng
            )
        }

        @SuppressLint("MissingPermission")
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            Log.d(TAG, "onRouteStateInfo: isRouting=${routeStateInfo.isRouting}, mode=${routeStateInfo.naviMode}")
            lastNaviMode = routeStateInfo.naviMode
            val wasRouting = isNaviRouting
            val guiding = routeStateInfo.isRouting ||
                routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING ||
                routeStateInfo.naviMode == NaviMode.SIMULATION_GUIDING
            hasConfirmedActiveRoute = guiding
            isNaviRouting = guiding
            callbacks.onNaviRoutingStateChanged(
                isRouting = guiding,
                destName = null,
                destLat = null,
                destLng = null
            )
            if (guiding) {
                try {
                    naviHelper?.getDestinationInfo()
                } catch (e: Exception) {
                    Log.w(TAG, "getDestinationInfo failed: ${e.message}")
                }
            } else if (wasRouting && isArrived) {
                callbacks.onNaviArrived()
            }
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            Log.d(TAG, "onNavigationStatus: $status")
            lastNavigationStatus = status
            val wasRouting = isNaviRouting
            val routing = (status == NavigationStatus.ROUTE_DRIVE)
            hasConfirmedActiveRoute = routing
            if (routing && pendingForegroundTransition) {
                isRoutePreparing = false
                centerMapOnCurrentLocation()
                completePendingForegroundTransition()
            }
            if (isNaviRouting != routing) {
                isNaviRouting = routing
                callbacks.onNaviRoutingStateChanged(
                    isRouting = routing,
                    destName = null,
                    destLat = null,
                    destLng = null
                )
                if (wasRouting && !routing && isArrived) {
                    callbacks.onNaviArrived()
                }
            }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.d(
                TAG,
                "onDestinationInfo: name=${destinationInfo.name}, remainTime=${destinationInfo.remainTime}s, remainDist=${destinationInfo.remainDistance}m"
            )
            val matchesTarget = isMatchingActiveTarget(
                destName = destinationInfo.name,
                destLat = destinationInfo.latitude,
                destLng = destinationInfo.longitude
            )
            if (matchesTarget && (destinationInfo.remainTime > 0 || destinationInfo.remainDistance > 0)) {
                callbacks.onDrivingProgressUpdated(
                    destinationInfo.remainTime,
                    destinationInfo.remainDistance
                )
            }
            callbacks.onNaviRoutingStateChanged(
                isRouting = isNaviRouting,
                destName = destinationInfo.name,
                destLat = destinationInfo.latitude,
                destLng = destinationInfo.longitude
            )
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val dest = drivingInfo.destination
            if (isNaviRouting && (dest.duration > 0 || dest.distance > 0)) {
                callbacks.onDrivingProgressUpdated(dest.duration, dest.distance)
            }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.d(TAG, "onDestinationArrived: $destinationArrivedInfo")
            isArrived = true
            hasConfirmedActiveRoute = false
            isNaviRouting = false
            callbacks.onArrivalStateChanged(true)
            callbacks.onNaviRoutingStateChanged(false, null, null, null)
            callbacks.onNaviArrived()
            checkArrivalCondition()
        }

        override fun onRouteCancelled() {
            Log.d(TAG, "onRouteCancelled")
            clearPendingForegroundTransition()
            isRoutePreparing = false
            hasConfirmedActiveRoute = false
            isNaviRouting = false
            callbacks.onRouteReadyForDisplay()
            callbacks.onRouteCancelled()
            callbacks.onNaviRoutingStateChanged(false, null, null, null)
        }

        @Deprecated("Deprecated in NaviHelperEventListener")
        override fun onRouteEnded() {
            Log.d(TAG, "onRouteEnded (route search completed)")
            callbacks.onRouteEnded()
        }

        override fun onRoutingCompleted() {
            Log.d(TAG, "onRoutingCompleted (route search completed)")
            callbacks.onRouteEnded()
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "onError: $errorCode")
            clearPendingForegroundTransition()
            isRoutePreparing = false
            callbacks.onRouteReadyForDisplay()
            if (!hasConfirmedActiveRoute && isNaviRouting) {
                isNaviRouting = false
                callbacks.onNaviRoutingStateChanged(false, null, null, null)
            }
        }
    }

    fun initialize() {
        try {
            val helper = NaviHelper(context)
            naviHelper = helper
            helper.initialize(
                onConnected = {
                    Log.d(TAG, "NaviHelper connected")
                    isNaviConnected = true
                    helper.addListener(naviEventListener)
                    syncNavigationState()
                },
                onFailed = { throwable ->
                    Log.e(TAG, "NaviHelper connection failed", throwable)
                    isNaviConnected = false
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "NaviHelper init exception: ${e.message}")
        }

        try {
            val v = Vehicle(context.applicationContext)
            vehicle = v
            v.initialize()
            v.getGear().registerGearSelection(gearSelectionListener)
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle SDK init failed: ${e.message}")
        }

        locationManager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        refreshLocationPermissionAndStartUpdates()
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun refreshLocationPermissionAndStartUpdates() {
        val lm = locationManager
            ?: (context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.also { locationManager = it }
            ?: return
        if (!hasLocationPermission()) return

        if (!isSystemLocationRegistered) {
            val candidateProviders = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(LocationManager.FUSED_PROVIDER)
                }
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }
            var registeredAny = false
            candidateProviders.forEach { provider ->
                if (runCatching { lm.allProviders.contains(provider) && lm.isProviderEnabled(provider) }.getOrDefault(false)) {
                    runCatching {
                        lm.requestLocationUpdates(
                            provider,
                            1_500L,
                            5f,
                            systemLocationListener,
                            Looper.getMainLooper()
                        )
                        registeredAny = true
                        Log.d(TAG, "Registered system location updates on provider=$provider")
                    }.onFailure { e ->
                        Log.w(TAG, "Failed to register location updates on $provider: ${e.message}")
                    }
                }
            }
            isSystemLocationRegistered = registeredAny
        }

        readLastKnownSystemLocation(lm)
    }

    @SuppressLint("MissingPermission")
    private fun readLastKnownSystemLocation(lm: LocationManager) {
        if (!hasLocationPermission()) return
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.PASSIVE_PROVIDER)
        }
        val latest = providers.mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        if (latest != null) {
            handleSystemLocation(latest)
        }
    }

    private fun handleSystemLocation(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        if (lat == 0.0 && lng == 0.0) return
        if (BuildConfig.DEBUG) {
            Log.d("EodigajiDebug", "현재 차량 위치 수신 (${location.provider}): lat=$lat, lng=$lng")
        }
        currentLat = lat
        currentLng = lng
        hasLiveLocation = true
        callbacks.onLocationUpdated(lat, lng)
        checkProximityArrival(lat, lng)
    }

    @SuppressLint("MissingPermission")
    fun syncNavigationState() {
        refreshLocationPermissionAndStartUpdates()
        val helper = naviHelper ?: return
        if (!isNaviConnected) return
        try {
            helper.getCurrentLocationInfo()
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentLocationInfo failed: ${e.message}")
        }
        try {
            helper.getRouteStateInfo()
        } catch (e: Exception) {
            Log.w(TAG, "getRouteStateInfo failed: ${e.message}")
        }
    }

    fun setActiveBlindTarget(
        destLat: Double,
        destLng: Double,
        blindName: String,
        alreadyArrived: Boolean = false,
    ) {
        activeTargetLat = destLat
        activeTargetLng = destLng
        activeTargetName = blindName
        isArrived = alreadyArrived
        if (!alreadyArrived) {
            BlindDriveMonitorService.start(context.applicationContext, blindName)
        } else {
            BlindDriveMonitorService.stop(context.applicationContext)
        }
        if (hasLiveLocation && !alreadyArrived) {
            checkProximityArrival(currentLat, currentLng)
        }
        checkArrivalCondition()
    }

    fun clearActiveBlindTarget() {
        activeTargetLat = null
        activeTargetLng = null
        activeTargetName = null
        isArrived = false
        BlindDriveMonitorService.stop(context.applicationContext)
    }

    fun isMatchingActiveTarget(destName: String?, destLat: Double?, destLng: Double?): Boolean {
        val targetLat = activeTargetLat
        val targetLng = activeTargetLng
        if (targetLat == null || targetLng == null) return false
        if (!destName.isNullOrBlank() && destName == activeTargetName) return true
        if (destLat != null && destLng != null && destLat != 0.0 && destLng != 0.0) {
            return distanceMeters(targetLat, targetLng, destLat, destLng) <= 300.0
        }
        return false
    }

    private fun checkProximityArrival(lat: Double, lng: Double) {
        val targetLat = activeTargetLat ?: return
        val targetLng = activeTargetLng ?: return
        if (isArrived) return

        val dist = distanceMeters(lat, lng, targetLat, targetLng)
        if (dist <= ARRIVAL_PROXIMITY_METERS) {
            Log.d(TAG, "Proximity arrival detected: ${dist.toInt()}m <= ${ARRIVAL_PROXIMITY_METERS.toInt()}m")
            isArrived = true
            callbacks.onArrivalStateChanged(true)
            if (!isParked) {
                callbacks.onNaviArrived()
            }
            checkArrivalCondition()
        }
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * requestTrip -> onTripInfo 코루틴 래퍼.
     * 연결되지 않았거나 3초 내 응답이 없으면 null 반환 (호출측에서 서버 추정치 폴백).
     */
    suspend fun measureTripEtaSeconds(
        destLat: Double,
        destLng: Double,
        blindName: String,
    ): Int? {
        val helper = naviHelper ?: return null
        if (!isNaviConnected) return null

        val deferred = CompletableDeferred<TripInfo>()
        pendingTripDeferred = deferred

        return try {
            val tripRequest = TripRequest(
                latitude = destLat,
                longitude = destLng,
                poiId = "",
                poiName = blindName,
                poiSubId = "0"
            )
            helper.requestTrip(tripRequest)
            val info = withTimeoutOrNull(3_000L) { deferred.await() }
            info?.eta?.takeIf { it > 0 }
        } catch (e: Exception) {
            Log.e(TAG, "requestTrip failed: ${e.message}")
            null
        } finally {
            if (pendingTripDeferred === deferred) {
                pendingTripDeferred = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startBlindRoute(
        destLat: Double,
        destLng: Double,
        blindName: String,
    ) {
        refreshLocationPermissionAndStartUpdates()
        setActiveBlindTarget(destLat, destLng, blindName, alreadyArrived = false)
        val routeInfo = RouteInfo(
            latitude = destLat,
            longitude = destLng,
            displayLatitude = null,
            displayLongitude = null,
            poiName = blindName,
            poiId = "",
            poiSubId = "",
            address = ""
        )

        try {
            clearPendingForegroundTransition()
            hasConfirmedActiveRoute = false
            lastNavigationStatus = null
            lastNaviMode = null
            isRoutePreparing = true
            pendingForegroundTransition = true
            naviHelper?.requestRoute(routeInfo)
            isNaviRouting = true

            // Keep mask curtain up while Navi calculates route & opens in background;
            // reveal once onRouteStarted fires and map centers on current location (9.5s safety fallback).
            val fallback = Runnable {
                if (pendingForegroundTransition) {
                    Log.d(TAG, "Route start timeout fallback -> centering map & revealing Navi")
                    isRoutePreparing = false
                    centerMapOnCurrentLocation()
                    completePendingForegroundTransition()
                }
            }
            foregroundFallbackRunnable = fallback
            mainHandler.postDelayed(fallback, 9_500L)

            if (BuildConfig.DEBUG) {
                Log.d("EodigajiDebug", "블라인드 경로 요청: alias='$blindName' (목적지 좌표: lat=$destLat, lng=$destLng)")
            }
        } catch (e: Exception) {
            clearPendingForegroundTransition()
            isRoutePreparing = false
            isNaviRouting = false
            callbacks.onRouteReadyForDisplay()
            Log.e(TAG, "requestRoute failed: ${e.message}")
        }
    }

    private fun completePendingForegroundTransition() {
        if (!pendingForegroundTransition) return
        clearPendingForegroundTransition()
        callbacks.onRouteReadyForDisplay()
    }

    private fun clearPendingForegroundTransition() {
        pendingForegroundTransition = false
        foregroundFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        foregroundFallbackRunnable = null
    }

    @SuppressLint("MissingPermission")
    fun centerMapOnCurrentLocation() {
        try {
            naviHelper?.requestNavigationScreen(NavigationScreenRequest.MoveToCurrentLocationOnMap)
            mainHandler.postDelayed({
                runCatching {
                    naviHelper?.requestNavigationScreen(NavigationScreenRequest.MoveToCurrentLocationOnMap)
                }
            }, 300L)
            mainHandler.postDelayed({
                runCatching {
                    naviHelper?.requestNavigationScreen(NavigationScreenRequest.MoveToCurrentLocationOnMap)
                }
            }, 750L)
        } catch (e: Exception) {
            Log.e(TAG, "MoveToCurrentLocationOnMap failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun openNavigationScreen() {
        try {
            naviHelper?.requestNavigationScreen(NavigationScreenRequest.Foreground)
            centerMapOnCurrentLocation()
        } catch (e: Exception) {
            Log.e(TAG, "requestNavigationScreen failed: ${e.message}")
        }
    }

    private fun canCancelRouteInNavi(): Boolean {
        if (!isNaviConnected) return false
        if (!hasConfirmedActiveRoute) return false
        if (lastNavigationStatus == NavigationStatus.FREE_DRIVE ||
            lastNavigationStatus == NavigationStatus.INACTIVE ||
            lastNavigationStatus == NavigationStatus.INITIALIZING
        ) {
            return false
        }
        if (lastNaviMode == NaviMode.SAFE_GUIDING || lastNaviMode == NaviMode.NOT_WORKING) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastCancelRequestElapsedMs < 2_000L) {
            return false
        }
        return true
    }

    fun cancelRoute() {
        val shouldCancelInNavi = canCancelRouteInNavi()
        clearPendingForegroundTransition()
        isRoutePreparing = false
        clearActiveBlindTarget()
        isNaviRouting = false
        hasConfirmedActiveRoute = false
        if (!shouldCancelInNavi) {
            Log.d(
                TAG,
                "cancelRoute skipped Navi IPC (connected=$isNaviConnected, status=$lastNavigationStatus, mode=$lastNaviMode)"
            )
            return
        }
        lastCancelRequestElapsedMs = SystemClock.elapsedRealtime()
        try {
            naviHelper?.cancelRoute()
            Log.d(TAG, "cancelRoute sent RequestRouteCancel to Navi")
        } catch (e: Exception) {
            Log.e(TAG, "cancelRoute failed: ${e.message}")
        }
    }

    fun resetArrivalState() {
        isArrived = false
    }

    private fun checkArrivalCondition() {
        if (isArrived && isParked) {
            BlindDriveMonitorService.stop(context.applicationContext)
            callbacks.onArrivalAndParkSatisfied()
        }
    }

    fun release() {
        clearPendingForegroundTransition()
        mainHandler.removeCallbacksAndMessages(null)
        BlindDriveMonitorService.stop(context.applicationContext)
        pendingTripDeferred?.cancel()
        pendingTripDeferred = null
        if (isSystemLocationRegistered) {
            runCatching {
                locationManager?.removeUpdates(systemLocationListener)
            }
            isSystemLocationRegistered = false
        }
        try {
            vehicle?.getGear()?.unregisterGearSelection(gearSelectionListener)
        } catch (_: Exception) {
        }
        try {
            naviHelper?.removeListener(naviEventListener)
            naviHelper?.release()
        } catch (_: Exception) {
        }
        isNaviConnected = false
    }
}
