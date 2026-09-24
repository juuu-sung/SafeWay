# SafeWay

여성·학생 안심귀가를 위한 Android 안전 동행 앱입니다. 사용자가 귀가를 시작하면 현재 위치와 도보 경로를 기록하고, 보호자는 별도 모니터 화면에서 사용자의 실시간 위치와 경로 상태를 확인할 수 있습니다.

<p align="center">
  <a href="https://github.com/juuu-sung/SafeWay">
    <img src="https://img.shields.io/badge/GitHub-SafeWay-24292f?style=for-the-badge&logo=github&logoColor=white" alt="GitHub repository">
  </a>
  <a href="#실행-준비">
    <img src="https://img.shields.io/badge/Setup-Guide-6C63FF?style=for-the-badge" alt="Setup guide">
  </a>
  <a href="server/README.md">
    <img src="https://img.shields.io/badge/Server-API-16a36f?style=for-the-badge&logo=node.js&logoColor=white" alt="Server API">
  </a>
  <a href="#동작-흐름">
    <img src="https://img.shields.io/badge/Workflow-Diagram-2f80ed?style=for-the-badge" alt="Workflow diagram">
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android API 26+">
  <img src="https://img.shields.io/badge/Java-17-007396?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/Kotlin-Android-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin Android">
  <img src="https://img.shields.io/badge/Node.js-Express-339933?style=flat-square&logo=node.js&logoColor=white" alt="Node.js Express">
  <img src="https://img.shields.io/badge/Firebase-FCM-FFCA28?style=flat-square&logo=firebase&logoColor=black" alt="Firebase Cloud Messaging">
  <img src="https://img.shields.io/badge/Kakao-Maps-FFCD00?style=flat-square&logo=kakao&logoColor=black" alt="Kakao Maps">
  <img src="https://img.shields.io/badge/OpenAI-API-412991?style=flat-square&logo=openai&logoColor=white" alt="OpenAI API">
  <img src="https://img.shields.io/badge/SQLite-Local%20DB-003B57?style=flat-square&logo=sqlite&logoColor=white" alt="SQLite local database">
</p>

<p align="center">
  <img src="design/safeway-android-ui.png" alt="SafeWay Android UI preview" width="720">
</p>

## 주요 기능

- 안심귀가 시작/완료 및 예상 귀가 시간 표시
- 보호자 연동 코드 생성과 FCM 토큰 기반 보호자 연결
- 안심귀가 중 사용자 실시간 위치 업데이트
- 보호자 모니터에서 현재 위치, 도착지, 경로, 최근 알림 확인
- 경로 이탈 감지 시 보호자에게 위험 알림 전송
- AI 안심 동행 통화 화면, 음성 입력, TTS 응답
- 위험 키워드 감지 및 112/보호자 연락 유도
- 카카오 지도 기반 도착지 검색, 현재 위치 표시, 도보 경로 표시
- 앱 내 도보 안내: 구간별 안내·한국어 음성·남은 경로·이탈 시 자동 재탐색
- 귀가 기록과 위험 지역 메모를 SQLite에 저장

## 앱 내 도보 안내

실제 카카오 도보 경로를 계산하고 귀가를 시작하면 홈 화면과 도보 안내 화면이 동일한 안내 상태를 표시합니다. 이전에 저장한 경로는 한 번 다시 계산해야 합니다. 직선 참고선은 내비게이션 경로로 사용하지 않습니다.

- 안내 화면에서 **음성 켜기/끄기**, **경로 다시 찾기**를 사용할 수 있습니다. 한국어 음성 데이터가 없는 기기에서는 화면 안내를 사용합니다.
- 귀가 추적 서비스가 위치·안내 상태를 관리하므로 앱이 뒤로 가도 계속 동작합니다. 알림에서 안내 화면을 열거나 음성을 전환할 수 있습니다.
- 오래되거나 부정확한 위치, 갑작스러운 GPS 이동은 방향 안내에서 제외합니다. 이탈과 도착은 여러 위치 표본으로 확인하며, 도착 후 귀가 완료는 사용자가 직접 누릅니다.
- 재탐색은 기존 경로를 보존하면서 수행합니다. 실패하면 재시도 간격을 늘리고, 도착지 변경·귀가 종료 뒤 도착한 응답은 반영하지 않습니다. 네트워크 없이는 새 경로를 계산할 수 없습니다.
- 위험 메모 주변 우회 후보를 비교하되 모든 위험 지역 회피나 실제 도로의 안전성을 보장하지 않습니다.

빌드·정적 검사·경로 계산 회귀 테스트:

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:navigationEngineTest
```

전용 에뮬레이터에서 모의 위치 권한과 위치·알림 권한을 부여한 뒤 Android 통합 테스트를 실행할 수 있습니다. 테스트는 공개 좌표와 로컬 응답 서버를 사용하며 카카오 요청이나 보호자 메시지를 보내지 않습니다. 앱 설정은 테스트 후 복원합니다.

```bash
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.safeway.app android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.safeway.app android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.safeway.app android.permission.POST_NOTIFICATIONS
adb shell appops set com.safeway.app android:mock_location allow
adb shell am instrument -w com.safeway.app.test/com.safeway.app.NavigationInstrumentation
adb shell appops set com.safeway.app android:mock_location default
```

출시 전에는 실제 휴대폰에서 교차로·평행 보도·GPS 음영·화면 잠금·통화 중 음성·장시간 배터리 사용을 별도로 검증해야 합니다. 시뮬레이션 통과만으로 상용 내비게이션 수준의 정확성을 보장하지 않습니다.

## 동작 흐름

```mermaid
flowchart LR
    classDef mobile fill:#eaf3ff,stroke:#2f80ed,stroke-width:1.5px,color:#102a43
    classDef server fill:#eef8f2,stroke:#16a36f,stroke-width:1.5px,color:#123524
    classDef alert fill:#fff4df,stroke:#f59e0b,stroke-width:1.5px,color:#3f2a05
    classDef danger fill:#ffe8e8,stroke:#e5484d,stroke-width:1.5px,color:#451a1a
    classDef store fill:#f3efff,stroke:#7c3aed,stroke-width:1.5px,color:#24124d
    classDef external fill:#f5f7fa,stroke:#64748b,stroke-width:1.5px,color:#1f2937

    subgraph UserApp["사용자 앱"]
        RouteSetup["도착지 설정<br/>Kakao Local 검색 또는 지도 선택"]
        AiCall["AI 안심 동행 통화<br/>음성 입력과 TTS 재생"]
        ReturnStart["안심귀가 시작"]
        LocationService["ReturnLocationService<br/>3초 기준 위치 수집·도보 안내"]
        LocalRecord["실제 이동 경로 기록<br/>SharedPreferences"]
        DeviationCheck{"경로 이탈 또는<br/>위험 신호 감지"}
        Complete["귀가 완료"]
    end

    subgraph SafeWayServer["SafeWay 서버"]
        RouteApi["/routes/compute<br/>도보 경로 계산"]
        AiApi["/ai/chat · /ai/speech<br/>AI 응답과 음성 생성"]
        StartedApi["/alerts/return-started<br/>시작 상태 저장"]
        LiveApi["/alerts/return-location-update<br/>최신 위치 조용히 갱신"]
        DangerApi["/alerts/route-deviation<br/>/alerts/ai-danger"]
        CompleteApi["/alerts/return-completed<br/>완료 상태 저장"]
        StateStore[("guardian-return-states<br/>최신 상태 + 히스토리")]
        StatusApi["/guardians/status<br/>보호자 상태 조회"]
    end

    subgraph GuardianApp["보호자 앱"]
        PushReceiver["SafeWayMessagingService<br/>시작/위험/완료 알림 수신"]
        Monitor["GuardianMonitorActivity<br/>지도와 상태 카드 표시"]
        AutoRefresh["5초마다 자동 새로고침"]
    end

    subgraph External["외부 서비스"]
        Kakao["Kakao Maps / Local / Mobility"]
        Firebase["Firebase Cloud Messaging"]
        OpenAI["OpenAI API<br/>AI 동행 대화와 TTS"]
    end

    RouteSetup --> ReturnStart
    RouteSetup -.-> RouteApi
    RouteApi -.-> Kakao
    AiCall -.-> AiApi
    AiApi -.-> OpenAI
    AiCall --> DeviationCheck
    ReturnStart --> LocationService
    LocationService --> LocalRecord
    LocationService -->|5초/5m 제한| LiveApi
    LocationService --> DeviationCheck
    LocationService --> Complete
    DeviationCheck -->|정상 이동| LiveApi
    DeviationCheck -->|경로 이탈·위험 감지| DangerApi
    ReturnStart --> StartedApi
    Complete --> CompleteApi

    StartedApi --> StateStore
    LiveApi --> StateStore
    DangerApi --> StateStore
    CompleteApi --> StateStore
    StateStore --> StatusApi
    StatusApi --> AutoRefresh
    AutoRefresh --> Monitor

    StartedApi -->|FCM data push| Firebase
    DangerApi -->|FCM data push| Firebase
    CompleteApi -->|FCM data push| Firebase
    Firebase --> PushReceiver
    PushReceiver --> Monitor

    RouteSetup -.-> Kakao
    DangerApi -.-> Kakao

    class RouteSetup,AiCall,ReturnStart,LocationService,LocalRecord,Complete mobile
    class RouteApi,AiApi,StartedApi,LiveApi,DangerApi,CompleteApi,StatusApi server
    class PushReceiver,Monitor,AutoRefresh mobile
    class DeviationCheck danger
    class StateStore store
    class Firebase alert
    class Kakao,OpenAI external
```

실시간 위치는 보호자 휴대폰에 푸시 알림을 계속 띄우지 않습니다. 사용자 기기가 위치를 서버에 갱신하고, 보호자 화면이 최신 상태를 자동 조회해 지도 위치를 갱신합니다.

| 흐름 | 처리 방식 |
| --- | --- |
| 시작/위험/완료 알림 | Firebase Cloud Messaging으로 보호자에게 즉시 전송 |
| 실시간 위치 | 사용자 앱이 서버에 조용히 갱신하고 보호자 앱이 5초마다 조회 |
| 이동 경로 기록 | 사용자 앱 내부에 실제 이동 좌표를 누적 저장 |
| 경로 이탈 | 기준 경로와 현재 위치의 거리 차이를 계산해 보호자에게 위험 알림 |

## 기술 스택

| 영역 | 사용 기술 |
| --- | --- |
| Android | Java, Kotlin, Android XML Layout, ViewBinding |
| 지도/경로 | Kakao Maps SDK v2, Kakao Local API, Kakao Map Walking Route API |
| 백그라운드 위치 | Android Foreground Service, LocationManager |
| 보호자 알림 | Firebase Cloud Messaging, firebase-admin |
| 서버 | Node.js, Express |
| 데이터 저장 | SharedPreferences, SQLiteOpenHelper, JSON runtime state |
| 음성/AI | SpeechRecognizer, TextToSpeech, OpenAI API 연동용 서버 코드 |

## 프로젝트 구조

```text
SafeWay/
├── app/                  # Android 앱
│   ├── src/main/java/    # Activity, Service, client logic
│   ├── src/main/res/     # XML layouts, colors, drawables
│   ├── build.gradle
│   └── google-services.example.json
├── server/               # FCM, 경로 계산, AI 통화 API 서버
│   ├── src/index.js
│   ├── package.json
│   ├── .env.example
│   └── README.md
├── design/               # UI 시안과 기술 구성 이미지
├── gradle/               # Gradle wrapper
├── build.gradle
└── settings.gradle
```

## 실행 준비

### 1. Android 설정

Android Studio에서 프로젝트 루트 폴더를 열고 Gradle Sync를 실행합니다.

앱용 카카오 키는 루트의 `local.properties`에 넣습니다.

```properties
KAKAO_NATIVE_APP_KEY=your-kakao-native-app-key
KAKAO_REST_API_KEY=your-kakao-rest-api-key
```

Firebase Cloud Messaging을 사용하려면 Firebase Console에서 Android 앱 패키지명 `com.safeway.app`을 등록한 뒤, 내려받은 설정 파일을 아래 경로에 둡니다.

```text
app/google-services.json
```

저장소에는 실제 파일 대신 `app/google-services.example.json`만 포함합니다.

### 2. 서버 설정

```bash
cd server
npm install
cp .env.example .env
```

`server/.env`에 필요한 값을 채웁니다.

```env
PORT=8080
KAKAO_REST_API_KEY=your-kakao-rest-api-key
GOOGLE_APPLICATION_CREDENTIALS=/absolute/path/to/firebase-service-account.json
OPENAI_API_KEY=your-openai-api-key
```

Firebase Admin SDK는 서비스 계정 JSON 파일 경로나 `FIREBASE_SERVICE_ACCOUNT_JSON` 환경 변수 중 하나로 설정할 수 있습니다.

### 3. 실행

서버:

```bash
cd server
npm start
```

Android 앱 빌드:

```bash
./gradlew :app:assembleDebug
```

생성 APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

실제 휴대폰에서 로컬 서버를 테스트할 때는 `10.0.2.2` 대신 PC의 LAN IP 또는 `ngrok` 같은 HTTPS 터널 주소를 앱의 푸시 서버 주소에 입력합니다.

## 주요 API

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `POST` | `/guardians/pairing-code` | 보호자 기기에서 6자리 연동 코드 생성 |
| `POST` | `/guardians/link` | 사용자 기기에서 보호자 연동 코드 등록 |
| `POST` | `/guardians/status` | 보호자 모니터가 최신 귀가 상태 조회 |
| `POST` | `/routes/compute` | 카카오 기반 도보 경로 계산 |
| `POST` | `/alerts/return-started` | 안심귀가 시작 알림 전송 |
| `POST` | `/alerts/return-location-update` | 안심귀가 중 실시간 위치 갱신 |
| `POST` | `/alerts/route-deviation` | 경로 이탈 알림 전송 |
| `POST` | `/alerts/return-completed` | 귀가 완료 알림 전송 |
| `POST` | `/ai/chat` | AI 안심 동행 대화 응답 |
| `POST` | `/ai/speech` | TTS 음성 응답 생성 |
| `POST` | `/ai/summary` | AI 통화 기록 요약 |

자세한 요청 예시는 `server/README.md`에 정리되어 있습니다.

## Android 권한

- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`: 현재 위치, 도보 경로, 실시간 위치 공유
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`: 안심귀가 중 백그라운드 위치 추적
- `POST_NOTIFICATIONS`: 보호자 알림과 안심귀가 진행 알림
- `INTERNET`, `ACCESS_NETWORK_STATE`: 서버, Firebase, Kakao API 통신
- `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`: AI 안심 동행 통화와 음성 입력

## 보안 주의

- `local.properties`
- `app/google-services.json`
- `server/.env`
- Firebase 서비스 계정 JSON
- `server/data/*.json`
- 빌드 산출물, 압축본, 힙 덤프, `node_modules`
