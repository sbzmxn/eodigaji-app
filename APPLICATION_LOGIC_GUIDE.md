# Eodigaji(어디가지) 애플리케이션 구현 로직 가이드 (For AI Agent)

> **목적**: 이 문서는 새로운 세션의 AI 에이전트(Gemini)가 `eodigaji` 안드로이드 프로젝트의 기존 UI(`MainActivity.kt` 및 `res/layout/view_*.xml`)에 **어디가지 추천 서버 API**와 **Pleos Connect NaviHelper / Vehicle SDK**를 연동하여 실제 비즈니스 로직을 즉시 구현할 수 있도록 작성된 설계 명세서입니다.

---

## 1. 프로젝트 환경 및 의존성

### 1-1. 현재 구성된 SDK (`gradle/libs.versions.toml` 및 `app/build.gradle.kts`)
- `namespace` / `applicationId`: `com.example.eodigaji`
- `viewBinding = true` 활성화됨 (`ActivityMainBinding` 및 각 `<include>` 뷰 바인딩 사용 중)
- **Pleos SDK (이미 포함됨)**:
  - `ai.pleos.playground:NaviHelper:3.0.5`
  - `ai.pleos.playground:Vehicle:2.3.2`
- **추가 필요한 의존성 (`app/build.gradle.kts`)**:
  - Retrofit2 + Gson Converter (`com.squareup.retrofit2:retrofit`, `com.squareup.retrofit2:converter-gson`)
  - OkHttp3 (`com.squareup.okhttp3:okhttp`)
  - Kotlin Coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-android`, `androidx.lifecycle:lifecycle-runtime-ktx`)
  - 이미지 로더 (Coil 또는 Glide — `reveal.image_url` 로드용)
- **AndroidManifest.xml 설정 확인**:
  - 이미 선언된 권한: `pleos.car.permission.NAVI_ROUTE`, `pleos.car.permission.NAVI_ROUTE_SEARCH`, `pleos.car.permission.NAVI_CUSTOM_MAP`, `pleos.car.permission.CAR_DRIVE`
  - **추가 필요**: 인터넷 권한(`android.permission.INTERNET`), 위치 권한(`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`), 에뮬레이터 로컬 서버(`http://10.0.2.2:8000/`) 통신을 위한 `<application android:usesCleartextTraffic="true">`

---

## 2. 추천 서버 API 명세 및 DTO / Retrofit 인터페이스

- **Base URL**: `http://10.0.2.2:8000/` (에뮬레이터 → 호스트 노트북 서버 기준. 끝에 `/` 필수)
- **권장 타임아웃**:
  - `/api/v1/keywords`: 5초
  - `/api/v1/recommend`, `/api/v1/eta-check`: 단일 호출 10초
  - 추천 + ETA 검증 루프 전체 제한 시간: **12초** (최소 로딩 연출 시간 **1.5초** 보장)

### 2-1. Kotlin 데이터 모델 및 Retrofit 인터페이스 (`network/EodigajiApi.kt`)

```kotlin
package com.example.eodigaji.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// ---- 1. GET /api/v1/keywords ----
data class KeywordsResponse(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("arrival_time") val arrivalTime: String,     // "HH:mm" (현재 + drive_minutes)
    @SerializedName("sunset_time") val sunsetTime: String?,      // "HH:mm" (도착일 일몰 시각)
    @SerializedName("keywords") val keywords: List<KeywordStatus>,
)

data class KeywordStatus(
    @SerializedName("id") val id: String,          // sea, water, forest, walk, view, quiet, sunrise, sunset, night
    @SerializedName("label") val label: String,    // 바다, 호수·강, 숲, 산책, 전망, 고즈넉, 일출, 노을, 야경
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("reason") val reason: String?, // enabled=false 일 때 비활성 사유 문구 (그대로 화면에 표시)
)

// ---- 2. POST /api/v1/recommend ----
data class RecommendRequest(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("drive_minutes") val driveMinutes: Int,                    // 15~120 (편도 운전 시간, 분)
    @SerializedName("keyword_ids") val keywordIds: List<String> = emptyList(), // 0~3개 (0개 = 전체 랜덤)
    @SerializedName("exclude_content_ids") val excludeContentIds: List<String> = emptyList(), // [다시 뽑기] 누적 제외 ID
    @SerializedName("retry_context") val retryContext: RetryContext? = null,   // 호환용 (새 코드는 /eta-check 사용)
    @SerializedName("require_parking") val requireParking: Boolean = false,    // [주차 가능한 곳만]
    @SerializedName("exclude_paid") val excludePaid: Boolean = false,          // [유료 체험 제외]
)

data class RetryContext(
    @SerializedName("previous_content_ids") val previousContentIds: List<String>,
    @SerializedName("observed_eta_seconds") val observedEtaSeconds: Int?,
)

data class RecommendResponse(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("blind_name") val blindName: String,      // 호환용 — 앱은 BlindNames.pick() 사용 권장
    @SerializedName("destination") val destination: Destination,
    @SerializedName("hint") val hint: Hint,
    @SerializedName("reveal") val reveal: Reveal,
    @SerializedName("alias_source") val aliasSource: String,  // 항상 "pool"
    @SerializedName("straight_km") val straightKm: Double,
    @SerializedName("estimated_drive_seconds") val estimatedDriveSeconds: Int,
)

data class Destination(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double,
)

data class Hint(
    @SerializedName("parking") val parking: String?,     // "가능" | "불가" | null
    @SerializedName("fee") val fee: String?,             // "무료" | "유료" | null
    @SerializedName("open_now") val openNow: Boolean?,   // 도착 예상 시각 기준 운영 여부 (true | false | null)
)

data class Reveal(
    @SerializedName("content_id") val contentId: String,
    @SerializedName("real_name") val realName: String,
    @SerializedName("address") val address: String,
    @SerializedName("overview_short") val overviewShort: String?,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("theme") val theme: String?,
    @SerializedName("matched_keywords") val matchedKeywords: List<String>,
)

// ---- 3. POST /api/v1/eta-check ----
data class EtaCheckRequest(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("drive_minutes") val driveMinutes: Int,
    @SerializedName("keyword_ids") val keywordIds: List<String> = emptyList(),
    @SerializedName("exclude_content_ids") val excludeContentIds: List<String> = emptyList(),
    @SerializedName("require_parking") val requireParking: Boolean = false,
    @SerializedName("exclude_paid") val excludePaid: Boolean = false,
    @SerializedName("content_id") val contentId: String,                    // 방금 ETA를 측정한 후보의 contentId
    @SerializedName("observed_eta_seconds") val observedEtaSeconds: Int,    // requestTrip으로 측정한 실제 ETA(초), 1~21600
    @SerializedName("previous_content_ids") val previousContentIds: List<String>, // 이번 뽑기에서 나온 모든 후보 ID (마지막 원소 == contentId 필수)
)

data class EtaCheckResponse(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("decision") val decision: String,          // "accept" | "retry" | "fallback"
    @SerializedName("attempt") val attempt: Int,               // 1~3
    @SerializedName("max_attempts") val maxAttempts: Int,      // 3
    @SerializedName("reason") val reason: String,              // within_limit | over_limit | max_attempts | no_candidates 등
    @SerializedName("route_content_id") val routeContentId: String?,          // accept 또는 fallback 일 때 최종 안내할 content_id
    @SerializedName("recommendation") val recommendation: RecommendResponse?, // decision == "retry" 일 때 새 추천 결과
)

fun RecommendRequest.toEtaCheck(contentId: String, etaSec: Int, previous: List<String>) = EtaCheckRequest(
    latitude = latitude,
    longitude = longitude,
    driveMinutes = driveMinutes,
    keywordIds = keywordIds,
    excludeContentIds = excludeContentIds,
    requireParking = requireParking,
    excludePaid = excludePaid,
    contentId = contentId,
    observedEtaSeconds = etaSec,
    previousContentIds = previous,
)

// ---- 4. 에러 응답 ----
data class ErrorResponse(@SerializedName("error") val error: ErrorBody)

data class ErrorBody(
    @SerializedName("code") val code: String,
    @SerializedName("message") val message: String,
    @SerializedName("request_id") val requestId: String,
)

fun <T> Response<T>.apiError(gson: Gson): ErrorBody? =
    errorBody()?.charStream()?.use { runCatching { gson.fromJson(it, ErrorResponse::class.java).error }.getOrNull() }

interface EodigajiApi {
    @GET("api/v1/keywords")
    suspend fun keywords(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("drive_minutes") driveMinutes: Int,
        @Query("require_parking") requireParking: Boolean = false,
        @Query("exclude_paid") excludePaid: Boolean = false,
    ): Response<KeywordsResponse>

    @POST("api/v1/recommend")
    suspend fun recommend(@Body body: RecommendRequest): Response<RecommendResponse>

    @POST("api/v1/eta-check")
    suspend fun etaCheck(@Body body: EtaCheckRequest): Response<EtaCheckResponse>
}
```

### 2-2. 에러 코드별 처리 규칙

| 에러 `code` | HTTP | 발생 상황 | 사용자 표시 문구 및 앱 동작 |
|---|---|---|---|
| `INVALID_REQUEST` | 400 | 파라미터 범위 오류(`drive_minutes` 15~120 외, `observed_eta_seconds <= 0` 등) | `"요청을 처리하지 못했어요. 앱을 최신 버전으로 업데이트해 주세요."` (로그 기록) |
| `INVALID_LOCATION` | 400 | 대한민국 범위 밖 좌표 | `"현재 위치를 확인할 수 없어요. 잠시 후 다시 시도해 주세요."` (위치 재조회) |
| `INVALID_KEYWORD` | 400 | 키워드 4개 이상, 중복, 미정의 키워드 | `"키워드를 다시 골라 주세요."` |
| `KEYWORD_UNAVAILABLE` | 400 | 선택한 키워드가 **모두** 도착 예상 시각에 사용 불가(일출·노을·야경 시간 경과) | `"지금 출발하면 볼 수 없는 키워드예요. 다른 키워드를 골라 주세요."` → 즉시 `GET /api/v1/keywords` 재호출하여 UI 갱신 |
| `NO_CANDIDATES` | 404 | 조건에 맞는 장소 없음 / 모두 휴무 / `exclude_content_ids`로 모두 소진 | `"조건에 맞는 곳이 없어요. 운전 시간을 늘리거나 키워드를 바꿔 보세요."` |
| `CONDITION_NOT_MET` | 404 | `require_parking`·`exclude_paid` 조건 통과 장소 없음 | `"조건을 끄거나 운전 시간을 늘려 보세요."` → 키워드 화면으로 복귀 |
| `UPSTREAM_TOUR_API_ERROR` | 502 | 관광공사 API 장애 | `"장소 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."` |
| `INTERNAL_ERROR` | 500 | 서버 내부 오류 | `"잠시 후 다시 시도해 주세요."` |
| 네트워크 실패/타임아웃 | - | 연결 실패 또는 12초 초과 | 받은 후보가 있으면 마지막 후보로 안내, 하나도 없으면 `"서버에 연결할 수 없어요. 네트워크를 확인해 주세요."` |

---

## 3. Pleos Connect SDK (`NaviHelper` & `Vehicle`) 연동 규칙

### 3-1. 생명주기 및 초기화 (`NaviHelper`, `Vehicle`)
- 현재 `MainActivity.kt`에는 `NaviHelper(this)` 생성 후 `initialize()` 호출이 누락되어 있습니다. 반드시 아래와 같이 `initialize()` 성공 콜백 이후에 리스너를 등록하고 API를 사용해야 합니다.

```kotlin
naviHelper = NaviHelper(this)
naviHelper.initialize(
    onConnected = {
        naviHelper.addListener(naviEventListener)
    },
    onFailed = { throwable ->
        Log.e("Eodigaji", "NaviHelper connection failed", throwable)
    }
)

vehicle = Vehicle(applicationContext)
vehicle.initialize()
vehicle.getGear().registerGearSelection(gearSelectionListener)
```
- `onDestroy()`에서는 `vehicle.getGear().unregisterGearSelection(gearSelectionListener)`, `naviHelper.removeListener(naviEventListener)`, `naviHelper.release()`를 호출합니다.

### 3-2. 핵심 SDK 메서드 및 리스너 콜백
1. **현재 위치 수신 (`onCurrentLocationInfo`)**:
   - `NaviHelperEventListener`의 `override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo)`를 통해 `currentLocationInfo.latitude`, `currentLocationInfo.longitude`를 지속적으로 갱신합니다.
   - 콜백 수신 전 초기값(폴백)은 강남역(`37.4979, 127.0276`)으로 설정합니다.
2. **실제 ETA 측정 (`requestTrip` → `onTripInfo`)**:
   - 추천 서버가 반환한 `destination(lat, lng)`까지의 실제 도로 주행 소요 시간(초)을 측정합니다.
   - 요청 객체:
     ```kotlin
     val tripRequest = TripRequest(
         latitude = destLat,
         longitude = destLng,
         poiId = "",
         poiName = blindName, // 스포일러 방지를 위해 가명 사용
         poiSubId = "0"
     )
     naviHelper.requestTrip(tripRequest)
     ```
   - 응답 콜백: `NaviHelperEventListener.onTripInfo(tripInfo: TripInfo)`에서 `tripInfo.eta` (단위: **초**, `Int`)와 `tripInfo.distance` (단위: **미터**, `Int`)를 획득합니다.
   - **코루틴 래핑 팁**: `CompletableDeferred<TripInfo>` 변수를 두고 `requestTrip()` 호출 후 `withTimeoutOrNull(3000) { deferred.await() }`로 대기하도록 구성하면 `/recommend` → `requestTrip` → `/eta-check` 루프를 순차적인 코루틴 코드로 깔끔하게 작성할 수 있습니다. (만약 에뮬레이터 환경에서 `onTripInfo`가 타임아웃되면 서버의 `estimatedDriveSeconds`를 폴백으로 사용하거나 즉시 확정 처리)
3. **실제 경로 안내 시작 (`requestRoute`)**:
   - 사용자가 `[출발하기]`를 눌렀을 때 호출합니다.
   - **주의사항 (필수 준수)**:
     - 반드시 **이름 붙은 인자(Named Arguments)** 로 `RouteInfo`를 생성합니다 (`latitude`가 먼저).
     - `poiName`에는 **앱이 뽑은 가명(`blindName`)** 을 넣고, `address`에는 **빈 문자열(`""`)** 을 넣어야 내비게이션 화면에서 실제 목적지가 노출(스포일러)되지 않습니다.
     ```kotlin
     val routeInfo = RouteInfo(
         latitude = res.destination.lat,
         longitude = res.destination.lng,
         displayLatitude = null,
         displayLongitude = null,
         poiName = blindName,   // 실제 장소명(reveal.realName) 절대 금지
         poiId = "",
         poiSubId = "",
         address = ""           // 실주소(reveal.address) 절대 금지
     )
     naviHelper.requestRoute(routeInfo)
     naviHelper.requestNavigationScreen(NavigationScreenRequest.Foreground)
     ```
4. **주행 중 상태 및 도착/주차 감지**:
   - `onRouteStarted(routeStartInfo: RouteStartInfo)`: 초기 주행 시간(`routeStartInfo.duration` 초) 및 거리(`routeStartInfo.distance` 미터) 수신.
   - `onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo)`: 목적지 도착 시 호출됨 (`isArrived = true`).
   - `GearSelectionListener.onSelected(gear: VehicleGear?)`: 기어가 `VehicleGear.P`(주차)일 때 `isParked = true`.

---

## 4. 화면(`ScreenState`)별 상세 로직 및 ViewBinding ID 매핑

`MainActivity.kt`의 `ScreenState` 열거형 순서에 따른 화면별 구현 명세입니다.

### 4-1. `SPLASH` & `HOME` (`view_splash.xml`, `view_home.xml`)
- 스플래시(1.8초) 후 `HOME` 전환 및 최초 1회 약관 동의 모달(`viewTermsDialog`) 표시.
- `binding.viewHome.btnHomeStart` 클릭 → `ScreenState.TIME_ROUTE`로 이동.
- `binding.viewHome.btnHomeArchive` 클릭 → `ScreenState.SECRET_ARCHIVE`로 이동.
- `binding.viewHome.tvHomeOrbitText`: 선택된 편도 시간(`driveMinutes`)에 맞춰 `"편도 ${driveMinutes}분이면\n반경 약 ${driveMinutes / 2}km 안 어딘가"` 등으로 동적 표시 가능.

### 4-2. `TIME_ROUTE` (`view_step_time_route.xml` — 시간 및 경로 설정)
- **중요: `drive_minutes` (편도 운전 시간, 15~120분) 매핑**
  - 서버는 **편도 운전 시간 `drive_minutes` (15~120분)** 만 허용합니다 (`available_minutes` 사용 시 400 에러).
  - 현재 UI의 시간 증감 버튼(`btnTimeMinus`, `btnTimePlus`)과 빠른 선택 칩(`chipTime30m`, `chipTime1h`, `chipTime2h`, `chipTime3h`, `chipTime4h`)을 다룰 때:
    - 총 가용 시간(예: 30분~4시간)에서 편도 운전 시간을 계산(`예: 총 시간(분) * 0.3 ~ 0.5`, 최소 15분 ~ 최대 120분으로 `coerceIn(15, 120)`)하거나,
    - 또는 입력 시간 자체를 편도 운전 시간(15분~120분)으로 제한(`coerceIn(15, 120)`)하여 `driveMinutes` 변수에 저장합니다.
  - 하단 안내 텍스트 `binding.viewStepTimeRoute.tvTimeBreakdown`에 계산된 `"편도 약 ${driveMinutes}분"`을 실시간 반영합니다.
- **경로 옵션 선택**:
  - `cardRouteBest` ("최적 경로"), `cardRouteFree` ("무료 도로"), `cardRouteScenic` ("해안 도로").
- `binding.viewStepTimeRoute.btnTimeNext` 클릭 → `ScreenState.METHOD`로 전환.

### 4-3. `METHOD` (`view_step_method.xml` — 뽑기 방식 선택)
- 카드 선택:
  - `cardMethodKeyword`: `"취향 랜덤"` (고른 키워드 안에서 뽑기)
  - `cardMethodTotal`: `"전체 랜덤"` (키워드 없이 `keyword_ids = emptyList()`로 뽑기)
- 우측 티켓 패널의 `binding.viewStepMethod.incTicketMethod.btnTicketNext` ([다음]) 클릭 시:
  - `selectedMethod == "취향 랜덤"` 이면 → `ScreenState.KEYWORDS` 화면으로 이동하면서 **즉시 `GET /api/v1/keywords` 호출**.
  - `selectedMethod == "전체 랜덤"` 이면 → `keywordIds = emptyList()`로 **즉시 추천 파이프라인(4-5절) 실행** 후 `ScreenState.DESTINATION_SEALED`로 이동.

### 4-4. `KEYWORDS` (`view_step_keywords.xml` — 키워드 및 조건 토글 선택)
- **키워드 ID ↔ ViewBinding ID 매핑 표**:

| 서버 Keyword `id` | 기본 `label` | 카드 View ID | 제목 TextView ID | 서브/사유 TextView ID (`reason` 표시 위치) | 기본 서브 문구 (`enabled=true` 일 때) |
|---|---|---|---|---|---|
| `"sea"` | 바다 | `cardKwSea` | `tvKwSeaTitle` | `tvKwSeaSub` | `"파도 소리로 머리\n비우기"` |
| `"water"` | 호수·강 | `cardKwLake` | `tvKwLakeTitle` | `tvKwLakeSub` | `"잔잔한 물가에서\n멍때리기"` |
| `"forest"` | 숲 | `cardKwForest` | `tvKwForestTitle` | `tvKwForestSub` | `"깊은 초록 속 숨\n고르기"` |
| `"walk"` | 산책 | `cardKwWalk` | `tvKwWalkTitle` | `tvKwWalkSub` | `"가볍게 걸으며\n기분 전환"` |
| `"view"` | 전망 | `cardKwView` | `tvKwViewTitle` | `tvKwViewSub` | `"탁 트인 풍경으로\n답답함 풀기"` |
| `"quiet"` | 고즈넉 | `cardKwQuiet` | `tvKwQuietTitle` | `tvKwQuietSub` | `"조용한 곳에서\n마음 내려놓기"` |
| `"sunrise"` | 일출 | `cardKwSunrise` | `tvKwSunriseTitle` | `tvKwSunriseSub` | `"떠오르는 해로\n하루 열기"` |
| `"sunset"` | 노을 | `cardKwSunset` | `tvKwSunsetTitle` | `tvKwSunsetSub` | `"하루를 천천히\n닫기"` |
| `"night"` | 야경 | `cardKwNight` | `tvKwNightTitle` | `tvKwNightSub` | `"밤공기와 불빛 속\n드라이브 끝"` |

- **`GET /api/v1/keywords` 호출 및 렌더링 로직**:
  1. `KEYWORDS` 화면 진입 시, 그리고 우측 하단 조건 토글(`btnToggleParking`, `btnToggleFee`) 변경 시 **300ms 디바운스(Debounce)** 로 `api.keywords(currentLat, currentLng, driveMinutes, requireParking, excludePaid)`를 호출합니다.
  2. 응답의 `keywords` 배열을 순회하며 각 카드를 업데이트합니다:
     - `status.enabled == true`:
       - 카드 활성화 (`card.alpha = 1.0f`, 클릭 허용).
       - 서브 TextView(`tvKw*Sub`)를 위 표의 **기본 서브 문구**로 복원합니다.
     - `status.enabled == false`:
       - 카드 비활성화 (`card.alpha = 0.4f`).
       - 서브 TextView(`tvKw*Sub`)에 **`status.reason` (예: `"지금 시간으론 멀어요"`, `"일몰은 17:25이에요"`)을 그대로 표시**합니다.
       - 만약 이미 선택된 키워드(`selectedKeywordIds`)에 포함되어 있었다면 선택 해제하고 상단 `tvKeywordCount` (`"${selectedKeywordIds.size} / 3 선택"`) 및 티켓 요약을 갱신합니다.
       - 비활성 카드를 사용자가 탭하면 `status.reason`을 Toast로 안내하고 선택되지 않게 막습니다.
- **조건 토글(`btnToggleParking`, `btnToggleFee`) 주의사항**:
  - `btnToggleParking` ("주차가능"): ON일 때 `requireParking = true`, OFF일 때 `requireParking = false`.
  - `btnToggleFee` ("체험비용"):
    - 레이아웃 기본 텍스트가 `"체험비용"` / `"ON ✓"`(`isExperienceFeeAllowed = true`)로 되어 있습니다.
    - 서버 필드 `exclude_paid`는 **`true`일 때 유료 체험을 제외**합니다.
    - 따라서 `excludePaid = !isExperienceFeeAllowed` (체험비용 ON = 유료 허용 = `exclude_paid: false` / 체험비용 OFF = 유료 제외 = `exclude_paid: true`)로 연결합니다.
- `binding.viewStepKeywords.btnKwDraw` ([뽑기]) 클릭 시 → **추천 파이프라인(4-5절)** 실행.

### 4-5. 목적지 추천 + ETA 검증 루프 파이프라인 (`recommend` → `requestTrip` → `eta-check`)
`[뽑기]`, `[전체 랜덤 다음]`, `[다시 뽑기]` 클릭 시 실행되는 핵심 코루틴 로직입니다.

```kotlin
suspend fun executeRecommendationPipeline(): Result<Pair<RecommendResponse, Int>> = withContext(Dispatchers.Main) {
    val startTime = System.currentTimeMillis()
    val attemptIds = mutableListOf<String>()
    val candidateMap = mutableMapOf<String, RecommendResponse>()
    var lastEtaSec = 0

    val keywordList = if (selectedMethod == "취향 랜덤") selectedKeywordIds.toList() else emptyList()
    val baseReq = RecommendRequest(
        latitude = currentLat,
        longitude = currentLng,
        driveMinutes = driveMinutes,
        keywordIds = keywordList,
        excludeContentIds = sessionExcludedIds.toList(),
        requireParking = requireParking,
        excludePaid = excludePaid
    )

    // 전체 제한 시간 12초
    val finalCandidate: RecommendResponse? = withTimeoutOrNull(12_000L) {
        // 1. 첫 추천 호출
        val firstRes = api.recommend(baseReq)
        if (!firstRes.isSuccessful || firstRes.body() == null) {
            handleApiError(firstRes.apiError(gson))
            return@withTimeoutOrNull null
        }

        var currentRec = firstRes.body()!!
        candidateMap[currentRec.reveal.contentId] = currentRec
        attemptIds.add(currentRec.reveal.contentId)

        // 2. requestTrip -> eta-check 루프 (서버가 최대 3회까지 제어)
        while (true) {
            val contentId = currentRec.reveal.contentId
            // NaviHelper requestTrip으로 실제 ETA(초) 측정 (타임아웃 시 서버 추정치 폴백)
            val measuredEtaSec = measureTripEtaWithNavi(
                lat = currentRec.destination.lat,
                lng = currentRec.destination.lng,
                blindName = activeBlindName
            ) ?: currentRec.estimatedDriveSeconds.coerceAtLeast(60)

            lastEtaSec = measuredEtaSec

            // 서버에 ETA 판단 요청
            val etaReq = baseReq.toEtaCheck(
                contentId = contentId,
                etaSec = measuredEtaSec,
                previous = attemptIds.toList()
            )
            val etaRes = api.etaCheck(etaReq)
            if (!etaRes.isSuccessful || etaRes.body() == null) {
                // 네트워크/검증 오류 시 방금 후보로 즉시 안내
                return@withTimeoutOrNull currentRec
            }

            val body = etaRes.body()!!
            when (body.decision) {
                "accept", "fallback" -> {
                    val targetId = body.routeContentId ?: contentId
                    return@withTimeoutOrNull candidateMap[targetId] ?: currentRec
                }
                "retry" -> {
                    // 로딩 UI 텍스트 업데이트: "가까운 곳을 다시 찾는 중… (${body.attempt}/${body.maxAttempts})"
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

    // 최소 로딩 연출 시간 1.5초 보장
    val elapsed = System.currentTimeMillis() - startTime
    if (elapsed < 1500L) {
        delay(1500L - elapsed)
    }

    // 12초 타임아웃 발생 시 그때까지 받은 마지막 후보라도 있으면 반환
    val chosen = finalCandidate ?: attemptIds.lastOrNull()?.let { candidateMap[it] }
    if (chosen != null) {
        sessionExcludedIds.add(chosen.reveal.contentId)
        Result.success(chosen to lastEtaSec)
    } else {
        Result.failure(Exception("추천 결과를 가져오지 못했습니다."))
    }
}
```

### 4-6. `DESTINATION_SEALED` (`view_destination_sealed.xml` — 목적지 봉인 화면)
- **블라인드 보안 원칙**:
  - `reveal` 객체(`realName`, `address`, `overviewShort`, `imageUrl`)는 **절대 이 화면이나 로그에 출력하지 않습니다.**
- **가명(`activeBlindName`) 선정**:
  - `BlindNames.pick(RecentBlindNames.load(prefs))`로 뽑은 가명(예: `"숨겨진 평온"`)을 사용합니다.
- **봉인 카드(`cardSealedInfo`) 바인딩**:
  - **이동 시간**: `observedEtaSeconds / 60`분을 `"편도 ${etaMin}분"`으로 표시.
  - **진행 방향**: 현재 좌표(`currentLat, currentLng`)에서 목적지(`destination.lat, destination.lng`)까지의 방위각을 계산하여 `"동쪽"`, `"서쪽"`, `"남동쪽"` 등으로 표시.
  - **출발 전 힌트 표시 규칙**:
    - 주차: `hint.parking == "가능"` → `"주차 가능"` / 그 외(`"불가"`, `null`) → `"주차 불가 또는 알 수 없음"`
    - 비용: `hint.fee == "유료"` → `"유료 체험"` / 그 외(`"무료"`, `null`) → `"무료 체험 또는 알 수 없음"`
    - 운영 여부: `hint.openNow == true` → `"영업 중"`, `false` → `"영업 종료"`, `null` → 숨김
- **버튼 액션**:
  - `btnSealedDepart` ([출발하기]): `startBlindDriveNavigation()` 호출 후 `ScreenState.BLIND_DRIVE`로 전환.
  - `btnSealedRedraw` ([다시 뽑기]): `sessionExcludedIds`가 유지된 상태로 4-5절 파이프라인 재실행.

### 4-7. `BLIND_DRIVE` (`view_blind_drive.xml` — 블라인드 드라이브 주행 화면)
- **경로 안내 시작**:
  - `naviHelper.requestRoute(routeInfo)` 호출 (`poiName = activeBlindName`, `address = ""`).
- **주행 정보 실시간 업데이트**:
  - `binding.viewBlindDrive.tvDriveEtaMinutes`: 남은 시간(분) 표시.
  - `binding.viewBlindDrive.tvDriveSubInfo`: 남은 거리(km) 및 도착 예정 시각 표시.
- **3단계 힌트 타임라인 (`llDriveHints`)**:
  - 힌트 1 (출발 시 공개): 테마(`reveal.theme`) 및 주차/비용 힌트 (`hint.parking`, `hint.fee`)
  - 힌트 2 (절반 지점 공개): 매칭된 키워드(`reveal.matchedKeywords`) 기반 문구
  - 힌트 3 (도착 5분 전 공개): 운영 여부(`hint.openNow`) 또는 장소 소개 힌트
- **버튼 액션**:
  - `btnSimulateArrival` (`🎯 도착 테스트`): 시연용 버튼으로, 클릭 시 즉시 도착+주차 완료로 간주하여 `ScreenState.ARRIVED`로 이동 및 언박싱 실행.
  - `btnDriveReveal` (`👁 목적지 보기`): `"🔒 봉인 안내: 도착 전까지 목적지 명칭은 비밀로 유지됩니다."` 토스트 출력.
  - `btnDriveCancel` (`✕ 그만 가기`): `naviHelper.cancelRoute()` 호출 후 `ScreenState.HOME`으로 복귀.

### 4-8. `ARRIVED` (`view_arrived.xml` — 도착 및 언박싱 화면)
- **진입 조건**:
  - 실제 주행 시: `onDestinationArrived` 수신(`isArrived == true`) **AND** 차량 기어 P단(`isParked == true`) 충족 시 자동 전환.
  - 시연/테스트 시: `btnSimulateArrival` 클릭 시 즉시 전환.
- **언박싱(`reveal`) 데이터 바인딩**:
  - 장소명: `reveal.realName`
  - 주소 및 테마: `reveal.address`, `reveal.theme`
  - 소개글: `reveal.overviewShort` (예: `tvArrivedSubtitle`에 표시)
  - 주차 안내 버튼(`btnArrivedParking`): 클릭 시 `hint.parking` 상태 및 주변 주차 안내 토스트 표시.
- **이미지(`binding.viewArrived.ivArrivedPostcard`) 표시 필수 규칙 (공공누리 제3유형 준수)**:
  - **절대 이미지를 자르거나(`centerCrop` 금지) 이미지 위에 텍스트/그라데이션 오버레이를 덮지 않습니다.**
  - 코드에서 `binding.viewArrived.ivArrivedPostcard.scaleType = ImageView.ScaleType.FIT_CENTER`로 변경하여 원본 비율 그대로 축소 표시합니다.
  - `reveal.imageUrl != null`이면 이미지 로더로 불러오고, **출처 `"사진 출처: 한국관광공사"`** 문구를 함께 표시합니다 (`imageUrl == null`이면 기본 이미지 `R.drawable.img_yeouido_card` 표시).

### 4-9. `SATISFACTION` & `SECRET_ARCHIVE` (`view_satisfaction.xml`, `view_secret_archive.xml` — 만족도 및 비밀 도감)
- **만족도 화면 (`SATISFACTION`)**:
  - 감정 카드 4종(`cardEmoGreat` "최고예요", `cardEmoGood` "좋았어요", `cardEmoNormal` "보통이에요", `cardEmoBad` "아쉬워요") 선택.
  - 우측 패널:
    - `ivSatisfactionThumb`: `ScaleType.FIT_CENTER`로 `reveal.imageUrl` 표시.
    - `tvSatisfactionBadge`: `"No. %03d · 오늘 발견 (가명: %s)".format(nextIndex, activeBlindName)`
    - `tvSatisfactionName`: `reveal.realName`
    - `tvSatisfactionTags`: `"${reveal.address} · ${selectedCompanion} · ${reveal.theme ?: ""}"`
  - `btnSatisfactionSave` ([도감에 저장]) 클릭 시:
    - 로컬 저장소(`SharedPreferences` JSON 배열)에 도감 아이템 저장 (`contentId`, `blindName`, `realName`, `address`, `overviewShort`, `imageUrl`, `theme`, `lat`, `lng`, `selectedMethod`, `selectedEmotion`, `date`).
    - `RecentBlindNames`에 `activeBlindName` 추가 (최근 10개 유지).
    - `ScreenState.SECRET_ARCHIVE`로 전환.
- **비밀 도감 화면 (`SECRET_ARCHIVE`)**:
  - 상단 필터 칩(`filterAll`, `filterKeyword`, `filterTotal`) 선택에 따라 저장된 도감 목록을 필터링하여 표시 (`전체` / `취향 랜덤` / `전체 랜덤`).
  - 리스트 아이템 클릭 시 우측 상세 패널(`ivArchiveDetailThumb`, `tvArchiveDetailBadge`, `tvArchiveDetailName`, `tvArchiveDetailTags`, `tvArchiveDetailRating`) 갱신 (`ivArchiveDetailThumb` 역시 `FIT_CENTER` 적용).
  - `btnArchiveGoAgain` ([다시 가기]) 클릭 시: 선택된 도감 아이템의 `lat`, `lng`로 `naviHelper.requestRoute()` 호출.

---

## 5. 가명 목록 유틸리티 (`util/BlindNames.kt` & `RecentBlindNames`)

서버의 `config/alias_pool.yaml`과 동일한 가명 선택 유틸리티 구조입니다. 프로젝트에 `BlindNames.kt` 파일이 없다면 아래 구조로 생성하여 사용합니다 (`서버 응답의 blind_name`을 폴백으로 함께 활용해도 무방합니다).

```kotlin
package com.example.eodigaji.util

import android.content.SharedPreferences
import kotlin.random.Random

object RecentBlindNames {
    private const val KEY = "recent_blind_names"
    private const val MAX_SIZE = 10

    fun load(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    fun save(prefs: SharedPreferences, name: String) {
        val current = load(prefs).toMutableList()
        current.remove(name)
        current.add(0, name)
        val trimmed = current.take(MAX_SIZE)
        prefs.edit().putString(KEY, trimmed.joinToString("|")).apply()
    }
}
```
> **참고**: `BlindNames.pick(recent)` 호출이 필요할 때 별도 100개 목록 파일이 아직 복사되지 않았다면, 서버가 동일한 100개 목록에서 골라 내려주는 `RecommendResponse.blindName`을 우선 사용하고 `RecentBlindNames`에 기록하도록 구현하면 100% 호환됩니다.

---

## 6. 구현 순서 체크리스트 (Next Session Task List)

다음 세션에서 작업을 시작할 때 아래 순서대로 구현을 진행하세요:

1. **[설정]** `app/build.gradle.kts`에 Retrofit, Gson, OkHttp, Coroutines, Glide/Coil 의존성 추가 및 `AndroidManifest.xml`에 `INTERNET` 권한과 `android:usesCleartextTraffic="true"` 추가.
2. **[네트워크 계층]** `network/EodigajiApi.kt` 생성 (DTO 클래스 전체 + Retrofit 싱글톤 클라이언트).
3. **[내비게이션 컨트롤러]** `navi/PleosNaviController.kt` 생성 (`NaviHelper.initialize()`, `onCurrentLocationInfo` 위치 추적, `requestTrip` ↔ `onTripInfo` suspend 래퍼, `requestRoute` 호출, `VehicleGear.P` 주차 감지).
4. **[도감 저장소]** `data/ArchiveRepository.kt` 생성 (`SharedPreferences` + Gson 기반 도감 저장/불러오기 및 `RecentBlindNames` 관리).
5. **[UI & 비즈니스 로직 연동]** `MainActivity.kt` 수정:
   - `TIME_ROUTE` 편도 운전 시간(`drive_minutes`, 15~120분) 연동
   - `KEYWORDS` 진입 및 토글 변경 시 300ms 디바운스 `GET /api/v1/keywords` 호출 및 9개 카드 활성/비활성(`reason`) 렌더링
   - `[뽑기]` / `[다시 뽑기]` 클릭 시 `/recommend` → `requestTrip` → `/eta-check` 루프(최소 1.5초, 최대 12초 타임아웃) 실행
   - `DESTINATION_SEALED`에 실제 ETA, 방위각(8방위), 힌트(`parking`, `fee`, `open_now`) 바인딩 (실제 장소명/주소 비노출)
   - `BLIND_DRIVE`에 가명(`poiName = blindName`, `address = ""`)으로 `requestRoute` 실행 및 주행 중 3단계 힌트 업데이트
   - `ARRIVED`에 도착+주차(또는 테스트 버튼) 시 언박싱 정보(`reveal.*`) 바인딩 및 이미지 `fitCenter` + `"한국관광공사"` 출처 표시
   - `SATISFACTION` & `SECRET_ARCHIVE`에 실제 도감 저장 및 필터링/다시 가기 연동
