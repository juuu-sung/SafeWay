package com.safeway.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.kakao.vectormap.LatLng;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AiCallActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    private static final int REQUEST_RECORD_AUDIO = 20;
    private static final int REQUEST_DANGER_MEMO_LOCATION = 21;
    private static final String TTS_UTTERANCE_REPLY = "safeway_reply";
    private static final float NATURAL_SPEECH_RATE = 0.92f;
    private static final float NATURAL_PITCH = 1.03f;
    private static final List<String> DANGER_KEYWORDS = Arrays.asList(
            "도와주세요", "무서워요", "무서", "따라와", "따라", "도와", "위험", "쫓아",
            "쫓아와", "이상해", "불안", "못 가", "살려", "납치", "폭행", "위협", "스토킹"
    );

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat memoDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA);
    private final long startedAt = System.currentTimeMillis();
    private final ExecutorService memoExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private AppDatabase db;
    private TextToSpeech tts;
    private MediaPlayer speechPlayer;
    private SpeechRecognizer speechRecognizer;
    private LinearLayout chatList;
    private ScrollView chatScroll;
    private TextView callStatusText;
    private TextView modeTitleText;
    private TextView guardianModeButton;
    private TextView friendModeButton;
    private TextView boyfriendModeButton;
    private TextView girlfriendModeButton;
    private TextView calmModeButton;
    private TextView speakButton;
    private TextView autoConversationButton;
    private View riskPanel;
    private final ArrayList<AiClient.ChatMessage> transcript = new ArrayList<>();
    private String mode = "보호자";
    private String lastSummary = "AI 안심 동행 통화를 사용함";
    private boolean finishingWithSummary;
    private boolean autoConversationEnabled;
    private boolean listening;
    private boolean waitingForAi;
    private boolean speaking;
    private boolean ttsReady;
    private boolean aiDangerAlertSent;
    private boolean aiDangerAlertInFlight;
    private String pendingDangerMemoText = "";

    private final Runnable timer = new Runnable() {
        @Override
        public void run() {
            long seconds = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000L);
            callStatusText.setText(String.format(Locale.KOREA, "%02d:%02d · %s 모드", seconds / 60, seconds % 60, mode));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SafeWayTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_call);

        prefs = SafeWayPrefs.get(this);
        db = new AppDatabase(this);
        db.seedDefaultMemosIfEmpty();
        prefs.edit().putBoolean(SafeWayPrefs.USED_AI_CALL, true).apply();
        tts = new TextToSpeech(this, this);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                handler.post(() -> {
                    speaking = true;
                    speakButton.setText("AI 말하는 중");
                });
            }

            @Override
            public void onDone(String utteranceId) {
                handler.post(() -> {
                    speaking = false;
                    speakButton.setText(autoConversationEnabled ? "자동 듣기 대기" : "말하기");
                    scheduleAutoListen();
                });
            }

            @Override
            public void onError(String utteranceId) {
                handler.post(() -> {
                    speaking = false;
                    speakButton.setText(autoConversationEnabled ? "자동 듣기 대기" : "말하기");
                    scheduleAutoListen();
                });
            }
        });

        chatList = findViewById(R.id.chatList);
        chatScroll = findViewById(R.id.chatScroll);
        callStatusText = findViewById(R.id.callStatusText);
        modeTitleText = findViewById(R.id.modeTitleText);
        guardianModeButton = findViewById(R.id.guardianModeButton);
        friendModeButton = findViewById(R.id.friendModeButton);
        boyfriendModeButton = findViewById(R.id.boyfriendModeButton);
        girlfriendModeButton = findViewById(R.id.girlfriendModeButton);
        calmModeButton = findViewById(R.id.calmModeButton);
        speakButton = findViewById(R.id.speakButton);
        autoConversationButton = findViewById(R.id.autoConversationButton);
        riskPanel = findViewById(R.id.riskPanel);

        guardianModeButton.setOnClickListener(v -> setMode("보호자"));
        friendModeButton.setOnClickListener(v -> setMode("친구"));
        boyfriendModeButton.setOnClickListener(v -> setMode("남자친구"));
        girlfriendModeButton.setOnClickListener(v -> setMode("여자친구"));
        calmModeButton.setOnClickListener(v -> setMode("안내"));
        speakButton.setOnClickListener(v -> startListening());
        autoConversationButton.setOnClickListener(v -> toggleAutoConversation());
        findViewById(R.id.aiCall112Button).setOnClickListener(v -> PhoneUtils.dial(this, "112"));
        findViewById(R.id.aiGuardianButton).setOnClickListener(v -> dialGuardian());
        findViewById(R.id.endCallButton).setOnClickListener(v -> finishCall());

        setMode("보호자");
        updateAutoConversationButton();
        String openingMessage = "지금 어디쯤이야? 주변은 밝아?";
        addMessage("AI", openingMessage, false);
        transcript.add(new AiClient.ChatMessage("assistant", openingMessage));
        handler.post(timer);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        releaseSpeechPlayer();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        memoExecutor.shutdownNow();
        saveAiSummary();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            configureNaturalKoreanVoice();
            ttsReady = true;
        }
    }

    private void configureNaturalKoreanVoice() {
        if (tts == null) {
            return;
        }
        int languageResult = tts.setLanguage(Locale.KOREAN);
        if (languageResult == TextToSpeech.LANG_MISSING_DATA
                || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
        }
        Voice naturalVoice = findNaturalKoreanVoice();
        if (naturalVoice != null) {
            tts.setVoice(naturalVoice);
        }
        tts.setSpeechRate(NATURAL_SPEECH_RATE);
        tts.setPitch(NATURAL_PITCH);
        tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
    }

    private Voice findNaturalKoreanVoice() {
        if (tts == null) {
            return null;
        }
        Set<Voice> voices = tts.getVoices();
        if (voices == null || voices.isEmpty()) {
            return null;
        }

        Voice bestVoice = null;
        int bestScore = Integer.MIN_VALUE;
        for (Voice voice : voices) {
            if (voice == null || !isKoreanVoice(voice)) {
                continue;
            }
            int score = voice.getQuality() * 10 - voice.getLatency();
            Locale locale = voice.getLocale();
            if (locale != null && "KR".equalsIgnoreCase(locale.getCountry())) {
                score += 400;
            }
            if (!voice.isNetworkConnectionRequired()) {
                score += 250;
            }
            String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.US);
            if (name.contains("ko-kr") || name.contains("korean")) {
                score += 100;
            }
            if (score > bestScore) {
                bestScore = score;
                bestVoice = voice;
            }
        }
        return bestVoice;
    }

    private boolean isKoreanVoice(Voice voice) {
        Locale locale = voice.getLocale();
        return locale != null && "ko".equalsIgnoreCase(locale.getLanguage());
    }

    private void setMode(String selectedMode) {
        mode = selectedMode;
        modeTitleText.setText("AI " + mode + " 모드");
        styleModeButton(guardianModeButton, "보호자".equals(mode));
        styleModeButton(friendModeButton, "친구".equals(mode));
        styleModeButton(boyfriendModeButton, "남자친구".equals(mode));
        styleModeButton(girlfriendModeButton, "여자친구".equals(mode));
        styleModeButton(calmModeButton, "안내".equals(mode));
    }

    private void styleModeButton(TextView button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_primary : android.R.color.transparent);
        button.setTextColor(getColor(selected ? R.color.white : R.color.safeway_muted));
    }

    private void startListening() {
        if (finishingWithSummary || waitingForAi || listening) {
            return;
        }
        if (speaking) {
            stopSpeechPlayback();
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "이 기기에서는 음성 인식을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        listening = true;
        speakButton.setEnabled(false);
        speakButton.setText("듣는 중");
        if (speechRecognizer != null) speechRecognizer.destroy();
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { speakButton.setText("처리 중"); }
            @Override public void onError(int error) {
                listening = false;
                speakButton.setEnabled(true);
                speakButton.setText("말하기");
                if (autoConversationEnabled && !finishingWithSummary) {
                    speakButton.setText("자동 듣기 대기");
                    scheduleAutoListen();
                } else {
                    Toast.makeText(AiCallActivity.this, "음성을 인식하지 못했습니다. 다시 말해주세요.", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onResults(Bundle results) {
                listening = false;
                speakButton.setEnabled(true);
                speakButton.setText("말하기");
                ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (values == null || values.isEmpty()) {
                    scheduleAutoListen();
                    return;
                }
                handleUserText(values.get(0));
            }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "지금 상황을 말해주세요.");
        speechRecognizer.startListening(intent);
    }

    private void handleUserText(String userText) {
        addMessage("사용자", userText, true);
        transcript.add(new AiClient.ChatMessage("user", userText));
        boolean localDanger = containsDangerKeyword(userText);
        riskPanel.setVisibility(localDanger ? View.VISIBLE : View.GONE);
        if (localDanger) {
            sendAiDangerAlertIfNeeded(userText);
        }
        handleExplicitCallCommand(userText);
        if (handleDangerMemoCommand(userText)) {
            return;
        }

        waitingForAi = true;
        speakButton.setEnabled(false);
        speakButton.setText("AI 응답 중");
        AiClient.sendChat(getAiServerUrl(), userText, mode, transcript, (ok, result, message) -> {
            waitingForAi = false;
            speakButton.setEnabled(true);
            speakButton.setText("말하기");
            if (ok && result != null && !result.reply.trim().isEmpty()) {
                applyAiResult(userText, result, localDanger);
            } else {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                applyFallbackReply(userText, localDanger);
            }
        });
    }

    private boolean containsDangerKeyword(String text) {
        String normalized = text.replace(" ", "").toLowerCase(Locale.KOREA);
        for (String keyword : DANGER_KEYWORDS) {
            if (normalized.contains(keyword.replace(" ", "").toLowerCase(Locale.KOREA))) {
                return true;
            }
        }
        return false;
    }

    private String createReply(String userText, boolean danger) {
        if (danger) {
            if ("친구".equals(mode)) {
                return "괜찮아. 지금은 가까운 편의점이나 사람이 많은 곳으로 들어가자. 바로 112나 보호자 버튼을 눌러도 돼.";
            }
            if ("남자친구".equals(mode)) {
                return "괜찮아, 내가 계속 같이 있을게. 지금은 밝고 사람 많은 곳으로 이동하고 위험하면 바로 112를 눌러.";
            }
            if ("여자친구".equals(mode)) {
                return "괜찮아, 나랑 계속 통화하는 것처럼 천천히 걸어가자. 밝은 곳으로 이동하고 위험하면 바로 112를 눌러.";
            }
            if ("안내".equals(mode)) {
                return "위험 상황일 수 있습니다. 밝고 사람이 많은 장소로 이동하고, 필요하면 즉시 112 또는 보호자에게 연락하세요.";
            }
            return "침착하게 밝은 큰길 쪽으로 이동하세요. 불안이 계속되면 바로 보호자 전화나 112 버튼을 누르세요.";
        }
        if (userText.contains("후문") || userText.contains("골목")) {
            return "골목길보다는 큰길이나 밝은 길로 이동하는 게 좋아요. 주변에 사람이 있는 곳을 우선으로 걸어가세요.";
        }
        if ("친구".equals(mode)) {
            return "좋아. 내가 같이 있어줄게. 주변을 한 번 확인하고 밝은 쪽으로 천천히 걸어가자.";
        }
        if ("남자친구".equals(mode)) {
            return "응, 나 여기 있어. 주변 밝은 쪽으로 천천히 가고, 이상하면 바로 사람 많은 곳으로 들어가자.";
        }
        if ("여자친구".equals(mode)) {
            return "응, 같이 걷는다고 생각하고 천천히 가자. 주변에 편의점이나 사람이 많은 곳이 있으면 그쪽으로 가.";
        }
        if ("안내".equals(mode)) {
            return "현재 위치 주변을 확인하세요. 밝은 길, 편의점, 사람이 있는 정류장 방향으로 이동하는 것이 좋습니다.";
        }
        return "지금처럼 천천히 이동하세요. 불안하면 주변이 밝은 곳으로 가고 보호자에게 바로 연결할 수 있어요.";
    }

    private void applyAiResult(String userText, AiClient.AiResult result, boolean localDanger) {
        boolean danger = localDanger || result.danger || isHighRiskAction(result.safetyAction);
        riskPanel.setVisibility(danger ? View.VISIBLE : View.GONE);
        if (danger) {
            sendAiDangerAlertIfNeeded(userText);
        }
        String reply = result.reply.trim().isEmpty() ? createReply(userText, danger) : result.reply.trim();
        lastSummary = result.summary.trim().isEmpty() ? createSummary(danger) : result.summary.trim();
        saveAiSummary();
        transcript.add(new AiClient.ChatMessage("assistant", reply));
        addMessage("AI", reply, false);
        speak(reply);
    }

    private boolean isHighRiskAction(String safetyAction) {
        return "call_112".equals(safetyAction)
                || "call_guardian".equals(safetyAction)
                || "move_bright".equals(safetyAction);
    }

    private void handleExplicitCallCommand(String userText) {
        String normalized = userText.replace(" ", "");
        if (normalized.contains("112") && (normalized.contains("전화") || normalized.contains("신고"))) {
            riskPanel.setVisibility(View.VISIBLE);
            PhoneUtils.dial(this, "112");
            return;
        }
        if ((normalized.contains("보호자") || normalized.contains("엄마") || normalized.contains("아빠"))
                && normalized.contains("전화")) {
            riskPanel.setVisibility(View.VISIBLE);
            dialGuardian();
        }
    }

    private boolean handleDangerMemoCommand(String userText) {
        String normalized = userText == null ? "" : userText.replace(" ", "");
        boolean mentionsDangerMemo = normalized.contains("위험지역")
                || normalized.contains("위험한지역")
                || normalized.contains("위험장소")
                || normalized.contains("위험구역")
                || normalized.contains("위험길")
                || normalized.contains("위험메모");
        boolean asksToSave = normalized.contains("추가")
                || normalized.contains("저장")
                || normalized.contains("등록")
                || normalized.contains("기록")
                || normalized.contains("메모");
        boolean shouldSaveCurrentRoad = isCurrentRoadDangerMemoIntent(normalized);
        if ((!mentionsDangerMemo || !asksToSave) && !shouldSaveCurrentRoad) {
            return false;
        }

        riskPanel.setVisibility(View.VISIBLE);
        saveDangerMemoAtCurrentLocation(userText);
        return true;
    }

    private boolean isCurrentRoadDangerMemoIntent(String normalized) {
        if (normalized == null || normalized.trim().isEmpty()) {
            return false;
        }
        boolean mentionsCurrentPlace = normalized.contains("이길")
                || normalized.contains("이곳")
                || normalized.contains("여기")
                || normalized.contains("현재위치")
                || normalized.contains("지금위치")
                || normalized.contains("지금길")
                || normalized.contains("골목")
                || normalized.contains("주변");
        boolean feelsUnsafe = normalized.contains("무서")
                || normalized.contains("위험")
                || normalized.contains("불안")
                || normalized.contains("피하고싶")
                || normalized.contains("피해야")
                || normalized.contains("피해가")
                || normalized.contains("가기싫")
                || normalized.contains("어두")
                || normalized.contains("수상")
                || normalized.contains("이상");
        return mentionsCurrentPlace && feelsUnsafe;
    }

    private void saveDangerMemoAtCurrentLocation(String userText) {
        if (!hasLocationPermission()) {
            pendingDangerMemoText = userText == null ? "" : userText;
            addAiLocalReply("현재 위치로 위험 지역을 저장하려면 위치 권한이 필요해. 권한을 허용해줘.");
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_DANGER_MEMO_LOCATION
            );
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            addAiLocalReply("현재 위치를 확인할 수 없어. 위치 서비스가 켜져 있는지 확인해줘.");
            return;
        }

        Location lastLocation = getBestLastKnownLocation(locationManager);
        if (lastLocation != null) {
            insertDangerMemoFromLocation(lastLocation, userText);
            return;
        }

        String provider = getAvailableLocationProvider(locationManager);
        if (provider == null) {
            addAiLocalReply("위치 서비스가 꺼져 있어서 현재 위치를 저장하지 못했어.");
            return;
        }

        pendingDangerMemoText = userText == null ? "" : userText;
        Toast.makeText(this, "현재 위치를 확인하는 중입니다.", Toast.LENGTH_SHORT).show();
        try {
            locationManager.requestSingleUpdate(provider, new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    insertDangerMemoFromLocation(location, pendingDangerMemoText);
                    pendingDangerMemoText = "";
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }
            }, Looper.getMainLooper());
        } catch (SecurityException ignored) {
            addAiLocalReply("위치 권한이 없어 현재 위치를 저장하지 못했어.");
        }
    }

    private void insertDangerMemoFromLocation(Location location, String userText) {
        if (location == null) {
            addAiLocalReply("현재 위치를 아직 확인하지 못했어. 잠시 후 다시 말해줘.");
            return;
        }

        double latitudeValue = location.getLatitude();
        double longitudeValue = location.getLongitude();
        String memo = userText == null || userText.trim().isEmpty()
                ? "AI 대화에서 현재 위치를 위험 지역으로 저장함"
                : "AI 대화: " + userText.trim();
        memoExecutor.execute(() -> {
            String address = resolveAddressForDangerMemo(latitudeValue, longitudeValue);
            String place = address.isEmpty() ? "AI 대화 중 현재 위치" : address;
            String reason = "AI 대화 중 위험 지역으로 표시";
            String latitude = String.format(Locale.US, "%.7f", latitudeValue);
            String longitude = String.format(Locale.US, "%.7f", longitudeValue);
            db.insertDangerMemo(
                    place,
                    reason,
                    memo,
                    memoDateFormat.format(new Date()),
                    latitude,
                    longitude,
                    address.isEmpty() ? "현재 위치" : address
            );
            runOnUiThread(() -> {
                lastSummary = "AI 대화 중 현재 위치를 위험 지역 메모로 저장함";
                saveAiSummary();
                addAiLocalReply("현재 위치를 위험 지역 메모에 저장했어. 다음 경로 계산 때 이 근처를 지나면 경고할게.");
            });
        });
    }

    private String resolveAddressForDangerMemo(double latitude, double longitude) {
        String restApiKey = BuildConfig.KAKAO_REST_API_KEY.trim();
        if (restApiKey.isEmpty()) {
            return "";
        }
        try {
            String address = KakaoLocalSearch.reverseGeocode(restApiKey, LatLng.from(latitude, longitude));
            return address == null ? "" : address.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private Location getBestLastKnownLocation(LocationManager locationManager) {
        Location bestLocation = null;
        String[] providers = new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        };
        for (String provider : providers) {
            try {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location == null) {
                    continue;
                }
                if (bestLocation == null || location.getTime() > bestLocation.getTime()) {
                    bestLocation = location;
                }
            } catch (SecurityException ignored) {
                return null;
            } catch (IllegalArgumentException ignored) {
                // Provider may not exist on some devices.
            }
        }
        return bestLocation;
    }

    private String getAvailableLocationProvider(LocationManager locationManager) {
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private void addAiLocalReply(String reply) {
        transcript.add(new AiClient.ChatMessage("assistant", reply));
        addMessage("AI", reply, false);
        speak(reply);
    }

    private void applyFallbackReply(String userText, boolean danger) {
        if (danger) {
            sendAiDangerAlertIfNeeded(userText);
        }
        String reply = createReply(userText, danger);
        lastSummary = createSummary(danger);
        saveAiSummary();
        transcript.add(new AiClient.ChatMessage("assistant", reply));
        addMessage("AI", reply, false);
        speak(reply);
    }

    private String createSummary(boolean danger) {
        return danger
                ? "귀가 중 불안을 표현해 밝은 장소 이동과 긴급 연락을 안내함"
                : "귀가 중 AI 안심 동행 통화로 상황 확인 대화를 진행함";
    }

    private void sendAiDangerAlertIfNeeded(String userText) {
        if (aiDangerAlertSent || aiDangerAlertInFlight) {
            return;
        }
        aiDangerAlertInFlight = true;
        Location location = getBestCurrentLocationForAlert();
        PushAlertClient.sendAiDangerAlert(this, userText, location, (sent, message) -> {
            aiDangerAlertInFlight = false;
            if (sent) {
                aiDangerAlertSent = true;
                Toast.makeText(this, "위험 신호를 감지해 보호자에게 알림을 보냈습니다.", Toast.LENGTH_SHORT).show();
            } else if (message != null && !message.trim().isEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private Location getBestCurrentLocationForAlert() {
        if (!hasLocationPermission()) {
            return null;
        }
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            return null;
        }
        return getBestLastKnownLocation(locationManager);
    }

    private void saveAiSummary() {
        String summary = buildSummaryForStorage();
        String transcriptText = buildTranscriptForStorage();
        prefs.edit()
                .putBoolean(SafeWayPrefs.USED_AI_CALL, true)
                .putString(SafeWayPrefs.AI_SUMMARY, summary)
                .putString(SafeWayPrefs.AI_TRANSCRIPT, transcriptText)
                .apply();
    }

    private String buildSummaryForStorage() {
        if (lastSummary != null
                && !lastSummary.trim().isEmpty()
                && !"AI 안심 동행 통화를 사용함".equals(lastSummary.trim())) {
            return lastSummary.trim();
        }
        return createTranscriptSummary();
    }

    private String createTranscriptSummary() {
        String lastUserMessage = "";
        boolean danger = false;
        for (AiClient.ChatMessage message : transcript) {
            if (message == null || !"user".equals(message.role)) {
                continue;
            }
            lastUserMessage = message.content == null ? "" : message.content.trim();
            if (containsDangerKeyword(lastUserMessage)) {
                danger = true;
            }
        }
        if (!lastUserMessage.isEmpty()) {
            String shortMessage = truncateForSummary(lastUserMessage);
            return danger
                    ? "사용자가 \"" + shortMessage + "\"라고 말해 AI가 밝은 장소 이동과 긴급 연락을 안내함"
                    : "사용자가 \"" + shortMessage + "\"라고 말하며 AI와 귀가 상황을 확인함";
        }
        return "귀가 중 AI 안심 동행 통화로 상황 확인 대화를 진행함";
    }

    private String buildTranscriptForStorage() {
        StringBuilder builder = new StringBuilder();
        for (AiClient.ChatMessage message : transcript) {
            if (message == null || message.content == null || message.content.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            String speaker = "user".equals(message.role) ? "나" : "AI";
            builder.append(speaker).append(": ").append(message.content.trim());
        }
        return builder.toString();
    }

    private String truncateForSummary(String value) {
        String normalized = value == null ? "" : value.replace("\n", " ").trim();
        if (normalized.length() <= 36) {
            return normalized;
        }
        return normalized.substring(0, 36) + "...";
    }

    private void finishCall() {
        if (finishingWithSummary) {
            return;
        }
        finishingWithSummary = true;
        autoConversationEnabled = false;
        listening = false;
        waitingForAi = false;
        stopSpeechPlayback();
        updateAutoConversationButton();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        speakButton.setEnabled(false);
        findViewById(R.id.endCallButton).setEnabled(false);
        Toast.makeText(this, "AI 통화 요약을 생성하는 중입니다.", Toast.LENGTH_SHORT).show();
        AiClient.summarize(getAiServerUrl(), transcript, (ok, summary, message) -> {
            if (ok && summary != null && !summary.trim().isEmpty()) {
                lastSummary = summary.trim();
                saveAiSummary();
            } else {
                Toast.makeText(this, "요약 실패: 기본 요약을 저장합니다.", Toast.LENGTH_SHORT).show();
                saveAiSummary();
            }
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        finishCall();
    }

    private String getAiServerUrl() {
        String savedUrl = prefs.getString(SafeWayPrefs.PUSH_SERVER_URL, "");
        if (savedUrl == null || savedUrl.trim().isEmpty()) {
            return isProbablyEmulator() ? "http://10.0.2.2:8080" : "";
        }
        return savedUrl.trim();
    }

    private boolean isProbablyEmulator() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT.toLowerCase(Locale.US);
        String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase(Locale.US);
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT.toLowerCase(Locale.US);
        return fingerprint.contains("generic")
                || fingerprint.contains("emulator")
                || model.contains("sdk")
                || model.contains("emulator")
                || product.contains("sdk");
    }

    private void toggleAutoConversation() {
        autoConversationEnabled = !autoConversationEnabled;
        updateAutoConversationButton();
        if (autoConversationEnabled) {
            Toast.makeText(this, "AI가 말한 뒤 자동으로 다시 듣습니다.", Toast.LENGTH_SHORT).show();
            if (!speaking && !waitingForAi && !listening) {
                startListening();
            }
        } else {
            if (listening && speechRecognizer != null) {
                speechRecognizer.destroy();
                speechRecognizer = null;
                listening = false;
                speakButton.setEnabled(true);
                speakButton.setText("말하기");
            }
            Toast.makeText(this, "자동 대화를 껐습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateAutoConversationButton() {
        if (autoConversationEnabled) {
            autoConversationButton.setText("자동 대화 켜짐");
            autoConversationButton.setBackgroundResource(R.drawable.bg_teal);
            autoConversationButton.setTextColor(getColor(R.color.white));
        } else {
            autoConversationButton.setText("자동 대화 켜기");
            autoConversationButton.setBackgroundResource(R.drawable.bg_outline);
            autoConversationButton.setTextColor(getColor(R.color.safeway_primary));
        }
    }

    private void scheduleAutoListen() {
        if (!autoConversationEnabled || finishingWithSummary || waitingForAi || listening || speaking) {
            return;
        }
        handler.postDelayed(() -> {
            if (autoConversationEnabled && !finishingWithSummary && !waitingForAi && !listening && !speaking) {
                startListening();
            }
        }, 700);
    }

    private void addMessage(String sender, String message, boolean user) {
        TextView bubble = new TextView(this);
        bubble.setText(sender + "\n" + message);
        bubble.setTextColor(getColor(user ? R.color.safeway_ink : R.color.safeway_muted));
        bubble.setTextSize(13);
        bubble.setTypeface(null, Typeface.BOLD);
        bubble.setBackgroundResource(user ? R.drawable.bg_primary_soft : R.drawable.bg_card);
        bubble.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.76f),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(12));
        params.gravity = user ? Gravity.END : Gravity.START;
        bubble.setLayoutParams(params);
        chatList.addView(bubble);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void speak(String text) {
        String speechText = text == null ? "" : text.trim();
        if (speechText.isEmpty()) {
            scheduleAutoListen();
            return;
        }

        stopSpeechPlayback();
        speaking = true;
        speakButton.setText("AI 말하는 중");

        AiClient.synthesizeSpeech(getAiServerUrl(), speechText, mode, (ok, audioBytes, message) -> {
            if (!speaking || finishingWithSummary) {
                return;
            }
            if (ok && audioBytes != null && audioBytes.length > 0 && playOpenAiSpeech(audioBytes, speechText)) {
                return;
            }
            speakWithAndroidTts(speechText);
        });
    }

    private boolean playOpenAiSpeech(byte[] audioBytes, String fallbackText) {
        try {
            File audioFile = new File(getCacheDir(), "safeway_ai_reply.mp3");
            try (FileOutputStream output = new FileOutputStream(audioFile)) {
                output.write(audioBytes);
            }

            releaseSpeechPlayer();
            speechPlayer = new MediaPlayer();
            speechPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            speechPlayer.setDataSource(audioFile.getAbsolutePath());
            speechPlayer.setOnPreparedListener(MediaPlayer::start);
            speechPlayer.setOnCompletionListener(player -> finishSpeechPlayback());
            speechPlayer.setOnErrorListener((player, what, extra) -> {
                releaseSpeechPlayer();
                speakWithAndroidTts(fallbackText);
                return true;
            });
            speechPlayer.prepareAsync();
            return true;
        } catch (Exception ignored) {
            releaseSpeechPlayer();
            return false;
        }
    }

    private void speakWithAndroidTts(String text) {
        if (tts == null || !ttsReady) {
            finishSpeechPlayback();
            return;
        }

        String speechText = prepareSpeechText(text);
        if (speechText.isEmpty()) {
            finishSpeechPlayback();
            return;
        }

        speaking = true;
        speakButton.setText("AI 말하는 중");
        int result = tts.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_REPLY);
        if (result == TextToSpeech.ERROR) {
            finishSpeechPlayback();
        }
    }

    private void finishSpeechPlayback() {
        speaking = false;
        speakButton.setText(autoConversationEnabled ? "자동 듣기 대기" : "말하기");
        releaseSpeechPlayer();
        scheduleAutoListen();
    }

    private void stopSpeechPlayback() {
        if (tts != null) {
            tts.stop();
        }
        releaseSpeechPlayer();
        speaking = false;
        speakButton.setText(autoConversationEnabled ? "자동 듣기 대기" : "말하기");
    }

    private void releaseSpeechPlayer() {
        if (speechPlayer == null) {
            return;
        }
        try {
            speechPlayer.stop();
        } catch (IllegalStateException ignored) {
            // Player may still be preparing or already stopped.
        }
        speechPlayer.release();
        speechPlayer = null;
    }

    private String prepareSpeechText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim()
                .replace("AI", "에이아이")
                .replace("112", "일 일이")
                .replace("/", " 또는 ")
                .replace("·", ", ")
                .replace("...", ".")
                .replaceAll("\\s+", " ");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }
        if (requestCode == REQUEST_DANGER_MEMO_LOCATION) {
            if (granted) {
                String pendingText = pendingDangerMemoText;
                pendingDangerMemoText = "";
                saveDangerMemoAtCurrentLocation(pendingText);
            } else {
                pendingDangerMemoText = "";
                addAiLocalReply("위치 권한이 없어 현재 위치를 위험 지역으로 저장하지 못했어.");
            }
            return;
        }
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        if (granted) {
            startListening();
        } else {
            autoConversationEnabled = false;
            updateAutoConversationButton();
            Toast.makeText(this, "AI 대화에는 마이크 권한이 필요합니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void dialGuardian() {
        String phone = prefs.getString(SafeWayPrefs.GUARDIAN_PHONE, "");
        if (phone == null || phone.trim().isEmpty()) {
            Toast.makeText(this, "보호자 연락처를 먼저 등록해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        PhoneUtils.dial(this, phone);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
