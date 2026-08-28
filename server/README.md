# SafeWay Push Server

SafeWay 앱에서 보호자 휴대폰으로 FCM 푸시 알림을 보내는 최소 서버입니다.

## 준비

1. Firebase Console에서 Android 앱 패키지 이름을 `com.safeway.app`으로 등록합니다.
2. Android 앱 설정 파일 `google-services.json`을 내려받아 `app/google-services.json`에 둡니다.
3. Firebase Console의 서비스 계정에서 서버용 JSON 키를 발급합니다.
4. 이 폴더에서 환경 파일을 만듭니다.

```bash
cp .env.example .env
```

`GOOGLE_APPLICATION_CREDENTIALS`에 서비스 계정 JSON의 절대 경로를 넣습니다.
앱 안 지도 경로 계산까지 쓰려면 Kakao Developers REST API 키를 `KAKAO_REST_API_KEY`에 넣습니다.
서버는 도보 길찾기를 먼저 시도하고 권한이 없으면 공개 문서에 있는 Kakao Mobility 자동차 길찾기 경로로 표시합니다.
도보 경로를 앱 안에 실제 경로선으로 표시하려면 Kakao Mobility 도보 길찾기 API 권한이 필요합니다.
AI 안심 동행 통화의 실제 GPT 응답과 요약을 쓰려면 `OPENAI_API_KEY`에 OpenAI API 키를 넣습니다.
모델은 기본값으로 `gpt-5.2`를 쓰며, 필요하면 `OPENAI_MODEL` 값을 바꿉니다.
AI 통화 음성은 서버에서 OpenAI TTS로 생성합니다. 기본 TTS 모델은 `gpt-4o-mini-tts`, 기본 voice는 `marin`입니다.
필요하면 `OPENAI_TTS_MODEL`, `OPENAI_TTS_VOICE` 값을 바꿉니다.
AI 통화 모드별 voice도 바꿀 수 있습니다. 기본값은 보호자 `cedar`, 친구 `marin`, 남자친구 `onyx`, 여자친구 `nova`, 안내 `sage`입니다.

```env
OPENAI_TTS_VOICE_GUARDIAN=cedar
OPENAI_TTS_VOICE_FRIEND=marin
OPENAI_TTS_VOICE_BOYFRIEND=onyx
OPENAI_TTS_VOICE_GIRLFRIEND=nova
OPENAI_TTS_VOICE_GUIDE=sage

OPENAI_TTS_SPEED_GUARDIAN=0.95
OPENAI_TTS_SPEED_FRIEND=1.03
OPENAI_TTS_SPEED_BOYFRIEND=0.96
OPENAI_TTS_SPEED_GIRLFRIEND=1.02
OPENAI_TTS_SPEED_GUIDE=0.98
```

## 실행

```bash
npm install
npm run dev
```

서버가 로컬에서 뜨면 Android 에뮬레이터의 SafeWay 앱에는 푸시 서버 주소를 아래처럼 저장합니다.

```text
http://10.0.2.2:8080
```

실제 휴대폰에서 테스트할 때는 같은 와이파이의 PC IP 또는 HTTPS 배포 주소를 사용합니다.
단, 현재 Android 네트워크 설정은 보안을 위해 임의의 HTTP IP를 막고 있으므로 실제 휴대폰 테스트는 `ngrok` 같은 HTTPS 주소를 권장합니다.

## API

```http
POST /guardians/pairing-code
Content-Type: application/json
```

보호자 기기에서 6자리 연동 코드를 만듭니다. 코드는 서버 메모리에 10분 동안만 보관됩니다.

```json
{
  "guardianName": "엄마",
  "guardianPhone": "01012345678",
  "guardianRelation": "부모님",
  "guardianToken": "guardian-device-fcm-token"
}
```

```http
POST /guardians/link
Content-Type: application/json
```

자녀 기기에서 6자리 코드를 입력해 보호자 토큰을 자동 저장할 때 사용합니다.

```json
{
  "code": "123456"
}
```

```http
POST /routes/compute
Content-Type: application/json
```

```json
{
  "origin": { "latitude": 37.5665, "longitude": 126.978 },
  "destination": { "latitude": 37.5701, "longitude": 126.982 }
}
```

응답:

```json
{
  "ok": true,
  "encodedPolyline": "...",
  "distanceMeters": 780,
  "duration": "620s"
}
```

```http
POST /alerts/return-started
Content-Type: application/json
```

```json
{
  "guardianToken": "guardian-device-fcm-token",
  "title": "SafeWay 안심귀가 알림",
  "body": "안심귀가가 시작되었습니다. 알림을 눌러 현재 위치를 확인하세요.",
  "mapsLink": "http://m.map.kakao.com/scheme/look?p=37.5665%2C126.9780",
  "routeLink": "http://m.map.kakao.com/scheme/route?sp=37.5665%2C126.9780&ep=37.5701%2C126.9820&by=foot",
  "destination": "우리 집",
  "latitude": "37.5665000",
  "longitude": "126.9780000"
}
```

```http
POST /alerts/return-location-update
Content-Type: application/json
```

안심귀가 진행 중 사용자 기기가 주기적으로 현재 위치를 저장합니다. 이 API는 보호자 폰에 푸시 알림을 계속 띄우지 않고, `/guardians/status` 조회 시 최신 위치만 갱신합니다.

```json
{
  "guardianToken": "guardian-device-fcm-token",
  "mapsLink": "http://m.map.kakao.com/scheme/look?p=37.5665%2C126.9780",
  "routeLink": "http://m.map.kakao.com/scheme/route?sp=37.5665%2C126.9780&ep=37.5701%2C126.9820&by=foot",
  "destination": "우리 집",
  "latitude": "37.5665000",
  "longitude": "126.9780000",
  "status": "active"
}
```

```http
POST /ai/chat
Content-Type: application/json
```

```json
{
  "mode": "보호자",
  "userText": "누가 계속 따라오는 것 같아요",
  "messages": [
    { "role": "assistant", "content": "지금 어디쯤이야? 주변은 밝아?" },
    { "role": "user", "content": "누가 계속 따라오는 것 같아요" }
  ]
}
```

응답:

```json
{
  "ok": true,
  "reply": "가까운 편의점이나 사람이 많은 곳으로 바로 이동하세요. 위험하면 즉시 112 또는 보호자에게 연락하세요.",
  "danger": true,
  "safetyAction": "call_112",
  "summary": "귀가 중 뒤따라오는 사람에 대한 불안을 표현해 밝은 장소 이동과 긴급 연락을 안내함"
}
```

```http
POST /ai/summary
Content-Type: application/json
```

```json
{
  "messages": [
    { "role": "assistant", "content": "지금 어디쯤이야? 주변은 밝아?" },
    { "role": "user", "content": "골목이 어두워서 무서워요" }
  ]
}
```
