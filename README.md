<p align="center">
  <img src="docs/logo.svg" width="120" height="120" alt="어디가지 로고" />
</p>

<h1 align="center">어디가지 (Eodigaji)</h1>

<p align="center">
  <b>목적지를 봉인한 채 떠나고, 도착 후 <code>[P]</code>단 주차 시 정체를 공개하는 Pleos Connect 차량용 블라인드 드라이브 IVI 앱</b>
</p>

<p align="center">
  <a href="https://github.com/sbzmxn/eodigaji-app/releases/tag/v1.0.0"><img src="https://img.shields.io/badge/Release-v1.0.0-blue.svg" alt="Release v1.0.0" /></a>
  <img src="https://img.shields.io/badge/Android-14%20(API%2034)-green.svg" alt="Android 14" />
  <img src="https://img.shields.io/badge/Platform-Pleos%20Connect%20AAOS-orange.svg" alt="Pleos Connect" />
</p>

---

**어디가지**는 원하는 편도 주행 시간과 풍경 키워드만 고르면 목적지의 실제 이름과 위치를 숨긴 채 **감성 가명과 힌트만으로 길을 안내**하고, **목적지에 도착하여 차량 기어가 `[P]`(주차)로 전환되면 정체를 언박싱**해 주는 Android Automotive OS(Pleos Connect) 기반 인포테인먼트(IVI) 애플리케이션입니다.

---

## 목차
1. [주요 기능](#주요-기능)
2. [APK 다운로드 & 파일 위치](#apk-다운로드--파일-위치)
3. [개발 및 테스트 환경](#개발-및-테스트-환경)
4. [환경 설정 (local.properties)](#환경-설정-localproperties)
5. [빌드 및 실행 방법](#빌드-및-실행-방법)
6. [백엔드 API 서버 안내](#백엔드-api-서버-안내)
7. [기술 스택 & 아키텍처](#기술-스택--아키텍처)
8. [화면 흐름](#화면-흐름)

---

## 주요 기능

### 1. 블라인드 목적지 추천 & 실측 ETA 검증
- **편도 운전 시간 선택**: `15분 ~ 120분` 범위(5분 단위 슬라이더 및 `±5분` 스테퍼)와 예상 왕복 주행 시간 안내
- **풍경 & 조건 필터**: `바다 · 호수·강 · 숲 · 산책 · 전망 · 고즈넉 · 일출 · 노을 · 야경` 중 최대 3개 선택(미선택 시 전체 랜덤) + `주차 가능한 곳만`, `유료 체험 제외` 토글
- **실시간 도달 가능 여부 반영**: 서버 API(`/api/v1/keywords`)를 통해 현재 시각·일출/일몰·주행 시간 기준 도달 불가능한 키워드를 실시간 비활성화하고 사유 표시
- **Pleos 내비 실측 ETA 검증 파이프라인**: 추천 후보 수신 후 `NaviHelper.requestTrip()`으로 실제 도로 주행 예상 시간(ETA)을 측정하고 서버(`/api/v1/eta-check`)로 검증 및 재추천 자동 수행

### 2. 목적지 봉인 & Pleos 내비게이션 연동
- **봉인 티켓**: 실제 장소명 대신 감성 가명(`BlindNames`)과 주차·요금·운영 여부 힌트만 제공 (하루 2회 다시 뽑기 지원)
- **블라인드 경로 안내**: `NaviHelper.requestRoute()`에 가명을 전달해 내비게이션 화면에서도 목적지 스포일러 없이 경로 안내
- **안내 상태 동기화**: 내비게이션의 실제 안내 상태(`NavigationStatus`, `NaviMode`)를 추적해 중복 취소 및 비정상 오류를 방지하고, 중단/재개 및 여정 복구(`ActiveTripRepository`)를 안전하게 처리

### 3. 도착 & 기어 `[P]` 주차 언박싱 + 비밀 도감
- **자동 정체 공개**: 목적지 도착(`onDestinationArrived` / 반경 300m 이내 근접) + 차량 기어 `GEAR_PARK` 감지 시 앱 자동 복귀 및 장소 정보 공개(사진, 개요, 주소)
- **비밀 도감**: 방문한 장소, 가명, 평가(`좋아요` / `별로`), 날짜를 차량 내 보안 저장소(`EncryptedSharedPreferences`)에 최대 50곳까지 영구 보관하고 재안내 시작·삭제 지원

### 4. 멀티 테마 & 차량용 옵션
- **테마 4종**: `자동(차량 시스템 연동)` / `라이트` / `다크` / `가을 픽셀 모드` (`Neo둥근모` 폰트 + 계단형 픽셀 아트 `PixelRoundedDrawable` + 가을 웜톤 팔레트)
- **글자 크기 4단계**: `작게(1.0x)` / `보통(1.12x)` / `크게(1.25x)` / `아주 크게(1.38x)` 실시간 UI 스케일링

---

## APK 다운로드 & 파일 위치

빌드 완료된 APK 파일은 저장소 내 `apk/` 디렉토리에 포함되어 있으며, GitHub Releases에서도 다운로드할 수 있습니다.

- **GitHub Release 다운로드**: [GitHub Releases v1.0.0](https://github.com/sbzmxn/eodigaji-app/releases/tag/v1.0.0)
- **저장소 내 파일 경로**: [`apk/오늘은_어디가지_release.apk`](./apk/오늘은_어디가지_release.apk)
- **파일 크기**: 약 10.64 MB (11,162,714 바이트)

---

## 개발 및 테스트 환경

본 애플리케이션은 현대자동차그룹 Pleos Connect 기반 Android Automotive OS(AAOS) IVI 환경을 기준으로 설계 및 검증되었습니다.

| 항목 | 규격 및 버전 |
| --- | --- |
| **개발/테스트 에뮬레이터** | **Pleos Connect Playground Emulator** |
| **운영체제(OS)** | Android Automotive OS (AAOS) / Android 14 (API 34) |
| **Pleos Connect SDK** | `ai.pleos.playground:NaviHelper:3.0.5`<br>`ai.pleos.playground:Vehicle:2.3.2` |
| **전체 디스플레이 해상도** | **`2560 × 1440` (Landscape, 16:9 가로형 와이드 디스플레이)** |
| **앱 윈도우 표시 규격** | **`1682 × 1210`** (ConnectL 12.9인치 Split-Dock 환경, 좌측 글로벌 내비게이션 바 및 상하 시스템 바 인셋 제외 영역) |
| **Android 실행 유저** | **`--user 10`** (Pleos 멀티 유저 인포테인먼트 환경) |

---

## 환경 설정 (`local.properties`)

보안을 위해 추천 API 서버 주소와 인증 키 등 로컬 설정 파일(`local.properties`)은 Git 추적에서 제외되어 있습니다.  
프로젝트 루트의 `example.local.properties` 파일을 복사하여 `local.properties`로 변환한 후 필요한 값을 입력하세요.

### 1) 변환 명령어
```bash
# Windows (PowerShell)
Copy-Item example.local.properties local.properties

# Windows (CMD)
copy example.local.properties local.properties

# macOS / Linux
cp example.local.properties local.properties
```

### 2) `local.properties` 설정 내용
```properties
# 1. Android SDK 경로 (각 OS 개발 환경에 맞게 지정)
sdk.dir=/path/to/your/Android/Sdk

# 2. 어디가지 추천 서버 (eodigaji-api) 연동 클라이언트 API KEY (헤더: X-API-KEY)
#    서버의 EODIGAJI_CLIENT_API_KEY 환경변수와 일치해야 합니다. (미설정 시 인증 생략 개발 모드 동작)
EODIGAJI_API_KEY=your_secret_api_key_here

# 3. 어디가지 추천 서버 주소 (API Base URL, 필수)
#    - 기본 제공 주소(Default URL)가 없으므로 연결할 추천 서버 주소를 직접 입력해야 합니다.
#    - Retrofit 규격상 주소 끝에 반드시 '/'를 포함해야 합니다.
#    - 예시: EODIGAJI_API_BASE_URL=https://your-api-server.com/
EODIGAJI_API_BASE_URL=
```

---

## 빌드 및 실행 방법

### 사전 요구 사항
- Android Studio (Ladybug / Koala Feature Drop 이상, AGP `9.5.1` 지원 버전) 및 JDK 11+
- Pleos Connect 에뮬레이터 또는 실기기 (Pleos 내비게이션 / Vehicle 서비스 포함 환경)
- `settings.gradle.kts`에 구성된 Pleos Playground Maven 저장소 접근 가능 네트워크

### 방법 1: 사전 빌드된 APK 바로 설치 및 실행
```bash
# Pleos 에뮬레이터 (보조 유저 --user 10)에 설치
adb install -r --user 10 apk/오늘은_어디가지_release.apk

# 앱 실행
adb shell am start --user 10 -n com.example.eodigaji/.MainActivity
```

### 방법 2: 소스코드 직접 빌드 후 실행
```bash
# 디버그 APK 빌드
./gradlew :app:assembleDebug

# 단위 테스트 실행
./gradlew test

# 에뮬레이터에 빌드 결과물 설치 및 실행
adb install -r -t --user 10 app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user 10 -n com.example.eodigaji/.MainActivity
```

> [!TIP]
> **상세 기능 및 시나리오별 테스트 방법 (GPS 모의 좌표, 디버그 좌표 확인, 원클릭 도착 테스트 등)** 은 [**TEST_GUIDE.md**](./TEST_GUIDE.md) 문서를 참조하세요.
> - **Debug 빌드**: 목적지/차량 좌표가 화면(옵션창/주행화면) 및 Logcat에 실시간 표시되며 `🎯 도착 테스트` 버튼이 활성화됩니다.
> - **Release 빌드**: 목적지 좌표 및 장소명이 완전 은닉됩니다.

---

## 백엔드 API 서버 안내

‘어디가지’ 앱의 목적지 추천, 실측 ETA 검증, 키워드 가능 여부 계산을 수행하는 백엔드 서버 소스코드 및 상세 API 규격 문서는 아래 별도 저장소에 공개되어 있습니다.

- **API 서버 저장소**: [https://github.com/sbzmxn/eodigaji-api](https://github.com/sbzmxn/eodigaji-api)
- **주요 엔드포인트**:
  - `GET /api/v1/keywords`: 현재 위치/시간/조건 기준 선택 가능한 풍경 키워드 및 일출·일몰 정보 반환
  - `POST /api/v1/recommend`: 편도 운전 시간 및 선호 조건 기반 최적의 블라인드 후보지 추천
  - `POST /api/v1/eta-check`: Pleos 내비에서 실측한 주행 예상 시간(ETA)을 검증하여 확정/재추천 판단
  - `GET /healthz`: 서버 헬스체크

---

## 기술 스택 & 아키텍처

| 구분 | 내용 |
| --- | --- |
| **Language / Architecture** | Kotlin, Single-Activity ViewBinding, State Machine Pattern |
| **UI / Layout** | XML Split-Dock IVI Layout (`landscape`), Custom Pixel Art Engine (`PixelRoundedDrawable`) |
| **Android SDK** | `minSdk 28` · `targetSdk 34` · `compileSdk 37` · AGP `9.5.1` |
| **Pleos Connect SDK** | `ai.pleos.playground:NaviHelper:3.0.5`, `ai.pleos.playground:Vehicle:2.3.2` |
| **Security** | AndroidX Security Crypto `1.1.0` (`EncryptedSharedPreferences`), Network Security Config (HTTPS 강제, Cleartext 차단), 로컬 백업 전면 차단 |
| **Network & Concurrency** | Retrofit `2.11.0`, OkHttp `4.12.0` (`X-API-KEY` Interceptor), Gson, Kotlin Coroutines `1.8.1` |
| **Image Loading** | Glide `4.16.0` (공공누리 제3유형 규격 준수: `fitCenter`, 비왜곡 로드) |

---

## 화면 흐름

```text
[스플래시] → [위치기반 약관 동의] → [홈]
                                    │
                                    ▼
  [편도 시간 설정] → [풍경·조건 선택] → [로딩 & ETA 검증]
                                            │
                                            ▼
[비밀 도감] ◀── [도착 언박싱] ◀── [블라인드 주행] ◀── [봉인 티켓]
                 (도착 + P단 주차)   (Pleos 내비 연동)
```

---

## 주요 권한 (`AndroidManifest.xml`)
- `INTERNET`: 추천 서버 및 한국관광공사 이미지 통신
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`: 현재 차량 위치 기반 출발지 좌표 확인
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`: 블라인드 주행 모니터링 서비스 유지
- `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`: 도착 및 권한 설정 복귀 알림
- `SYSTEM_ALERT_WINDOW`, `REORDER_TASKS`: 설정창에서 어디가지 앱으로 안전 자동 복귀
- `pleos.car.permission.NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`: Pleos 내비게이션 경로 탐색 및 제어
- `pleos.car.permission.CAR_DRIVE`: 차량 기어(`GEAR_PARK`) 및 주행 상태 감지
