package com.safeway.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.kakao.vectormap.KakaoMap;
import com.kakao.vectormap.KakaoMapReadyCallback;
import com.kakao.vectormap.LatLng;
import com.kakao.vectormap.MapLifeCycleCallback;
import com.kakao.vectormap.MapView;
import com.kakao.vectormap.camera.CameraAnimation;
import com.kakao.vectormap.camera.CameraPosition;
import com.kakao.vectormap.camera.CameraUpdateFactory;
import com.kakao.vectormap.label.Label;
import com.kakao.vectormap.label.LabelOptions;
import com.kakao.vectormap.label.LabelStyles;
import com.kakao.vectormap.label.LabelTextBuilder;
import com.kakao.vectormap.route.RouteLineLayer;
import com.kakao.vectormap.route.RouteLineOptions;
import com.kakao.vectormap.route.RouteLineSegment;
import com.kakao.vectormap.route.RouteLineStyle;
import com.kakao.vectormap.route.RouteLineStyles;
import com.kakao.vectormap.route.RouteLineStylesSet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_AUTO_START_RETURN = "com.safeway.app.EXTRA_AUTO_START_RETURN";

    private static final int REQUEST_LOCATION_FOR_START = 30;
    private static final int REQUEST_NOTIFICATION_FOR_START = 31;
    private static final int REQUEST_ROUTE_FOR_START = 32;
    private static final int RETURN_NOTIFICATION_ID = 1001;
    private static final String RETURN_NOTIFICATION_CHANNEL_ID = "safeway_return";
    private static final float WALKING_SPEED_METERS_PER_SECOND = 1.2f;
    private static final int WALKING_NAV_ZOOM_LEVEL = 17;
    private static final float ROUTE_DEVIATION_THRESHOLD_METERS = 80f;
    private static final int ROUTE_DEVIATION_CONFIRMATION_COUNT = 2;
    private static final long ROUTE_DEVIATION_ALERT_COOLDOWN_MS = 5 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.KOREA);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA);

    private SharedPreferences prefs;
    private AppDatabase db;

    private TextView headerSubtitle;
    private TextView statusBadge;
    private TextView stateTitle;
    private TextView stateDescription;
    private TextView themePinkButton;
    private TextView themeBlueButton;
    private View idleStateCard;
    private View activePanel;
    private TextView elapsedText;
    private TextView startDetailText;
    private View progressFill;
    private View timerWarning;
    private TextView warningTitle;
    private TextView startButton;
    private TextView completeButton;
    private View walkingRoutePanel;
    private MapView walkingRouteMap;
    private TextView walkingRouteEmptyText;
    private TextView walkingNavInstructionText;
    private TextView walkingNavMetaText;
    private TextView walkingNavDistanceText;
    private TextView walkingNavEtaText;
    private TextView refreshWalkingRouteButton;
    private TextView openWalkingRouteButton;
    private TextView nearbyMemoTitle;
    private LinearLayout nearbyMemoList;
    private EmergencySoundPlayer emergencySoundPlayer;
    private boolean pendingStartAfterLocationPermission;
    private boolean pendingStartAfterNotificationPermission;
    private boolean pendingRefreshWalkingRouteAfterLocationPermission;
    private boolean pendingOpenWalkingNaviAfterLocationPermission;
    private KakaoMap walkingKakaoMap;
    private LabelStyles walkingCurrentMarkerStyles;
    private LabelStyles walkingDestinationMarkerStyles;
    private LabelStyles walkingDangerMarkerStyles;
    private LatLng walkingCurrentLatLng;
    private LatLng walkingDestinationLatLng;
    private List<LatLng> walkingRoutePoints = new ArrayList<>();
    private List<WalkingGuide> walkingGuides = new ArrayList<>();
    private boolean walkingMapStarted;
    private boolean walkingLocationUpdatesActive;
    private LocationListener walkingLocationListener;
    private int routeDeviationHitCount;
    private long lastRouteDeviationAlertMillis;
    private boolean routeDeviationAlertInFlight;
    private boolean routeDeviationActive;
    private float routeDeviationDistanceMeters;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            updateStateUi();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SafeWayTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = SafeWayPrefs.get(this);
        db = new AppDatabase(this);
        db.seedDefaultMemosIfEmpty();
        SafeWayNotificationChannels.ensureGuardianAlertChannel(this);
        FcmTokenManager.refreshDeviceToken(this);
        emergencySoundPlayer = new EmergencySoundPlayer(this);

        bindViews();
        bindEvents();
        BottomNav.bind(this, MainActivity.class);
        updateThemeButtons();
        startWalkingRouteMap();
        handleAutoStartIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutoStartIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (walkingMapStarted && walkingRouteMap != null) {
            walkingRouteMap.resume();
        }
        updateStateUi();
        renderWalkingRoutePanel();
        startWalkingLocationUpdatesIfNeeded();
        handler.post(timerRunnable);
    }

    @Override
    protected void onPause() {
        stopWalkingLocationUpdates();
        if (walkingMapStarted && walkingRouteMap != null) {
            walkingRouteMap.pause();
        }
        super.onPause();
        handler.removeCallbacks(timerRunnable);
    }

    private void bindViews() {
        headerSubtitle = findViewById(R.id.headerSubtitle);
        statusBadge = findViewById(R.id.statusBadge);
        stateTitle = findViewById(R.id.stateTitle);
        stateDescription = findViewById(R.id.stateDescription);
        themePinkButton = findViewById(R.id.themePinkButton);
        themeBlueButton = findViewById(R.id.themeBlueButton);
        idleStateCard = findViewById(R.id.idleStateCard);
        activePanel = findViewById(R.id.activePanel);
        elapsedText = findViewById(R.id.elapsedText);
        startDetailText = findViewById(R.id.startDetailText);
        progressFill = findViewById(R.id.progressFill);
        timerWarning = findViewById(R.id.timerWarning);
        warningTitle = findViewById(R.id.warningTitle);
        startButton = findViewById(R.id.startButton);
        completeButton = findViewById(R.id.completeButton);
        walkingRoutePanel = findViewById(R.id.walkingRoutePanel);
        walkingRouteMap = findViewById(R.id.walkingRouteMap);
        walkingRouteEmptyText = findViewById(R.id.walkingRouteEmptyText);
        walkingNavInstructionText = findViewById(R.id.walkingNavInstructionText);
        walkingNavMetaText = findViewById(R.id.walkingNavMetaText);
        walkingNavDistanceText = findViewById(R.id.walkingNavDistanceText);
        walkingNavEtaText = findViewById(R.id.walkingNavEtaText);
        refreshWalkingRouteButton = findViewById(R.id.refreshWalkingRouteButton);
        openWalkingRouteButton = findViewById(R.id.openWalkingRouteButton);
        nearbyMemoTitle = findViewById(R.id.nearbyMemoTitle);
        nearbyMemoList = findViewById(R.id.nearbyMemoList);
    }

    private void bindEvents() {
        startButton.setOnClickListener(v -> openRouteBeforeStart());
        completeButton.setOnClickListener(v -> completeReturn());
        themePinkButton.setOnClickListener(v -> selectTheme(SafeWayTheme.THEME_PINK));
        themeBlueButton.setOnClickListener(v -> selectTheme(SafeWayTheme.THEME_BLUE));
        findViewById(R.id.call112Button).setOnClickListener(v -> PhoneUtils.dial(this, "112"));
        findViewById(R.id.guardianCallButton).setOnClickListener(v -> dialGuardian());
        findViewById(R.id.whistleButton).setOnClickListener(v -> emergencySoundPlayer.playWhistle());
        findViewById(R.id.helpVoiceButton).setOnClickListener(v -> emergencySoundPlayer.playHelpVoice());
        findViewById(R.id.aiCallButton).setOnClickListener(v -> startActivity(new Intent(this, AiCallActivity.class)));
        refreshWalkingRouteButton.setOnClickListener(v -> refreshWalkingRoutePanel());
        openWalkingRouteButton.setOnClickListener(v -> startActivity(new Intent(this, RouteActivity.class)));
        View walkingRouteMapContainer = findViewById(R.id.walkingRouteMapContainer);
        walkingRouteMapContainer.setOnClickListener(v -> openFullWalkingNaviFromWalkingPanel());
        walkingRouteMap.setOnClickListener(v -> openFullWalkingNaviFromWalkingPanel());
        findViewById(R.id.walkingNavStatusBox).setOnClickListener(v -> openFullWalkingNaviFromWalkingPanel());
        findViewById(R.id.walkingNavTrackingText).setOnClickListener(v -> openFullWalkingNaviFromWalkingPanel());
        walkingNavInstructionText.setOnClickListener(v -> openFullWalkingNaviFromWalkingPanel());
        walkingNavMetaText.setOnClickListener(v -> openFullWalkingNaviFromWalkingPanel());
    }

    private void selectTheme(String theme) {
        SafeWayTheme.select(this, theme);
        updateThemeButtons();
    }

    private void updateThemeButtons() {
        SafeWayTheme.styleChoice(this, themePinkButton, themeBlueButton);
    }

    private void openRouteBeforeStart() {
        Intent intent = new Intent(this, RouteActivity.class);
        intent.putExtra(RouteActivity.EXTRA_START_FLOW, true);
        startActivityForResult(intent, REQUEST_ROUTE_FOR_START);
    }

    private void handleAutoStartIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_AUTO_START_RETURN, false)) {
            return;
        }
        intent.removeExtra(EXTRA_AUTO_START_RETURN);
        if (prefs.getBoolean(SafeWayPrefs.RETURNING, false)) {
            Toast.makeText(this, "이미 귀가가 진행 중입니다.", Toast.LENGTH_SHORT).show();
            updateStateUi();
            return;
        }
        startReturn();
    }

    private void startReturn() {
        if (!hasNotificationPermission()) {
            pendingStartAfterNotificationPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_FOR_START);
            return;
        }
        continueStartReturnAfterNotificationPermission();
    }

    private void continueStartReturnAfterNotificationPermission() {
        if (!hasLocationPermission()) {
            pendingStartAfterLocationPermission = true;
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_LOCATION_FOR_START
            );
            return;
        }
        startReturnAndShowLocationNotification();
    }

    private void startReturnAndShowLocationNotification() {
        int expectedMinutes = getRouteExpectedMinutes();
        prefs.edit()
                .putBoolean(SafeWayPrefs.RETURNING, true)
                .putLong(SafeWayPrefs.START_TIME, System.currentTimeMillis())
                .putInt(SafeWayPrefs.EXPECTED_MINUTES, expectedMinutes)
                .putBoolean(SafeWayPrefs.USED_AI_CALL, false)
                .putString(SafeWayPrefs.AI_SUMMARY, "")
                .putString(SafeWayPrefs.AI_TRANSCRIPT, "")
                .apply();
        ReturnTrackRecorder.reset(this);
        recordBestKnownReturnLocation();
        startReturnLocationService();
        resetRouteDeviationState();
        Toast.makeText(this, "안심귀가를 시작했습니다.", Toast.LENGTH_SHORT).show();
        updateStateUi();
        renderWalkingRoutePanel();
        startWalkingLocationUpdatesIfNeeded();
        showReturnStartNotificationWithLocation();
    }

    private void completeReturn() {
        long startMillis = prefs.getLong(SafeWayPrefs.START_TIME, 0L);
        if (startMillis == 0L) {
            Toast.makeText(this, "진행 중인 귀가가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        long endMillis = System.currentTimeMillis();
        int durationMinutes = Math.max(1, (int) ((endMillis - startMillis) / 60000L));
        int expectedMinutes = prefs.getInt(SafeWayPrefs.EXPECTED_MINUTES, getRouteExpectedMinutes());
        boolean usedAiCall = prefs.getBoolean(SafeWayPrefs.USED_AI_CALL, false);
        String aiSummary = prefs.getString(SafeWayPrefs.AI_SUMMARY, "");
        String aiTranscript = prefs.getString(SafeWayPrefs.AI_TRANSCRIPT, "");
        String routeDestination = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, "");
        String routeLink = prefs.getString(SafeWayPrefs.ROUTE_LAST_LINK, "");
        String actualRoutePoints = ReturnTrackRecorder.get(this);

        db.insertReturnRecord(
                timeFormat.format(new Date(startMillis)),
                timeFormat.format(new Date(endMillis)),
                durationMinutes,
                expectedMinutes,
                usedAiCall,
                aiSummary,
                aiTranscript,
                routeDestination,
                routeLink,
                actualRoutePoints,
                dateFormat.format(new Date(endMillis))
        );

        PushAlertClient.sendReturnCompleted(this, durationMinutes, expectedMinutes, null);

        stopReturnLocationService();
        prefs.edit()
                .putBoolean(SafeWayPrefs.RETURNING, false)
                .remove(SafeWayPrefs.START_TIME)
                .remove(SafeWayPrefs.AI_SUMMARY)
                .remove(SafeWayPrefs.AI_TRANSCRIPT)
                .remove(SafeWayPrefs.ACTUAL_ROUTE_POINTS)
                .putBoolean(SafeWayPrefs.USED_AI_CALL, false)
                .apply();

        Toast.makeText(this, "귀가 기록이 저장되었습니다.", Toast.LENGTH_SHORT).show();
        cancelReturnNotification();
        resetRouteDeviationState();
        stopWalkingLocationUpdates();
        updateStateUi();
    }

    private int getRouteExpectedMinutes() {
        return Math.max(1, prefs.getInt(SafeWayPrefs.ROUTE_EXPECTED_MINUTES, 20));
    }

    private void recordBestKnownReturnLocation() {
        if (!hasLocationPermission()) {
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }
        ReturnTrackRecorder.record(this, getBestLastKnownLocation(locationManager));
    }

    private void startReturnLocationService() {
        Intent intent = new Intent(this, ReturnLocationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
    }

    private void stopReturnLocationService() {
        stopService(new Intent(this, ReturnLocationService.class));
    }

    private void updateStateUi() {
        boolean returning = prefs.getBoolean(SafeWayPrefs.RETURNING, false);
        long startMillis = prefs.getLong(SafeWayPrefs.START_TIME, 0L);
        int expectedMinutes = prefs.getInt(SafeWayPrefs.EXPECTED_MINUTES, 20);

        if (!returning || startMillis == 0L) {
            headerSubtitle.setText("오늘도 조용히, 안전하게 귀가해요.");
            statusBadge.setText("귀가 전");
            statusBadge.setTextColor(ContextCompat.getColor(this, R.color.safeway_primary));
            statusBadge.setBackgroundResource(R.drawable.bg_primary_soft);
            idleStateCard.setVisibility(View.VISIBLE);
            activePanel.setVisibility(View.GONE);
            timerWarning.setVisibility(View.GONE);
            startButton.setVisibility(View.VISIBLE);
            completeButton.setVisibility(View.GONE);
            walkingRoutePanel.setVisibility(View.GONE);
            nearbyMemoTitle.setVisibility(View.GONE);
            nearbyMemoList.setVisibility(View.GONE);
            stateTitle.setText("귀가 전");
            stateDescription.setText("시작하면 보호자에게 푸시 알림이 전송됩니다.");
            return;
        }

        long elapsedMillis = System.currentTimeMillis() - startMillis;
        long elapsedSeconds = Math.max(0, elapsedMillis / 1000L);
        int expectedSeconds = expectedMinutes * 60;
        int progressPercent = expectedSeconds == 0 ? 0 : (int) Math.min(100, elapsedSeconds * 100 / expectedSeconds);
        long remainingSeconds = expectedSeconds - elapsedSeconds;

        headerSubtitle.setText("보호자에게 시작 알림을 보냈어요.");
        statusBadge.setText("귀가 중");
        statusBadge.setTextColor(ContextCompat.getColor(this, R.color.safeway_primary));
        statusBadge.setBackgroundResource(R.drawable.bg_primary_soft);
        idleStateCard.setVisibility(View.GONE);
        activePanel.setVisibility(View.VISIBLE);
        startButton.setVisibility(View.GONE);
        completeButton.setVisibility(View.VISIBLE);
        walkingRoutePanel.setVisibility(View.VISIBLE);
        nearbyMemoTitle.setVisibility(View.VISIBLE);
        nearbyMemoList.setVisibility(View.VISIBLE);

        elapsedText.setText(formatDuration(elapsedSeconds));
        String destination = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, "");
        String destinationPrefix = destination == null || destination.trim().isEmpty()
                ? ""
                : "도착지 " + destination.trim() + " · ";
        startDetailText.setText(destinationPrefix + "시작 " + timeFormat.format(new Date(startMillis)) + " · 예상 " + expectedMinutes + "분");
        updateProgressWidth(progressPercent);

        timerWarning.setVisibility(View.VISIBLE);
        if (remainingSeconds >= 0) {
            warningTitle.setText("예상 시간까지 " + Math.max(1, remainingSeconds / 60) + "분 남음");
        } else {
            warningTitle.setText("예상 귀가 시간이 지났습니다.");
        }

        populateNearbyMemos();
        updateWalkingNavigationStatus();
    }

    private void updateProgressWidth(int percent) {
        ViewGroup parent = (ViewGroup) progressFill.getParent();
        parent.post(() -> {
            int parentWidth = parent.getWidth();
            if (parentWidth > 0) {
                ViewGroup.LayoutParams params = progressFill.getLayoutParams();
                params.width = Math.max(8, parentWidth * percent / 100);
                progressFill.setLayoutParams(params);
            }
        });
    }

    private String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.KOREA, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private void populateNearbyMemos() {
        nearbyMemoList.removeAllViews();
        List<AppDatabase.DangerMemo> memos = db.getDangerMemos();
        int count = Math.min(2, memos.size());
        for (int i = 0; i < count; i++) {
            AppDatabase.DangerMemo memo = memos.get(i);
            nearbyMemoList.addView(createSmallMemoCard(memo.placeName, memo.reason));
        }
    }

    private View createSmallMemoCard(String title, String desc) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(18), dp(12), dp(18), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(this, R.color.safeway_ink));
        titleView.setTextSize(13);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(titleView);

        TextView descView = new TextView(this);
        descView.setText(desc);
        descView.setTextColor(ContextCompat.getColor(this, R.color.safeway_muted));
        descView.setTextSize(10);
        descView.setPadding(0, dp(4), 0, 0);
        card.addView(descView);
        return card;
    }

    private void dialGuardian() {
        String phone = prefs.getString(SafeWayPrefs.GUARDIAN_PHONE, "");
        if (phone == null || phone.trim().isEmpty()) {
            Toast.makeText(this, "보호자 연락처를 먼저 등록해주세요.", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, GuardianActivity.class));
            return;
        }
        PhoneUtils.dial(this, phone);
    }

    private void refreshWalkingRoutePanel() {
        if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false)) {
            Toast.makeText(this, "안심귀가 시작 후 도보 경로를 확인할 수 있습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasLocationPermission()) {
            pendingRefreshWalkingRouteAfterLocationPermission = true;
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_LOCATION_FOR_START
            );
            return;
        }
        renderWalkingRoutePanel();
        Toast.makeText(this, "도보 경로를 새로고침했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void openFullWalkingNaviFromWalkingPanel() {
        if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false)) {
            Toast.makeText(this, "안심귀가 시작 후 전체화면 도보 내비를 열 수 있습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasLocationPermission()) {
            pendingOpenWalkingNaviAfterLocationPermission = true;
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_LOCATION_FOR_START
            );
            return;
        }

        LatLng current = getCurrentLocationLatLng();
        if (current != null) {
            walkingCurrentLatLng = current;
        } else {
            current = walkingCurrentLatLng;
        }

        LatLng destination = getStoredDestinationLatLng();
        if (destination == null) {
            destination = walkingDestinationLatLng;
        }

        if (current == null || destination == null) {
            Toast.makeText(this, "현재 위치와 도착지를 확인한 뒤 다시 눌러주세요.", Toast.LENGTH_LONG).show();
            renderWalkingRoutePanel();
            return;
        }

        startActivity(new Intent(this, WalkingNaviActivity.class));
    }

    private LatLng getStoredDestinationLatLng() {
        String lat = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION_LAT, "");
        String lng = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION_LNG, "");
        if (lat == null || lng == null || lat.trim().isEmpty() || lng.trim().isEmpty()) {
            return null;
        }
        try {
            return LatLng.from(Double.parseDouble(lat.trim()), Double.parseDouble(lng.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<LatLng> getStoredRoutePoints() {
        String storedPoints = prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, "");
        List<LatLng> points = new ArrayList<>();
        if (storedPoints == null || storedPoints.trim().isEmpty()) {
            return points;
        }
        String[] pairs = storedPoints.split(";");
        for (String pair : pairs) {
            String[] coordinates = pair.split(",");
            if (coordinates.length != 2) {
                continue;
            }
            try {
                points.add(LatLng.from(
                        Double.parseDouble(coordinates[0].trim()),
                        Double.parseDouble(coordinates[1].trim())
                ));
            } catch (NumberFormatException ignored) {
                // Keep any valid points and skip malformed saved points.
            }
        }
        return points;
    }

    private List<WalkingGuide> getStoredWalkingGuides() {
        String storedGuides = prefs.getString(SafeWayPrefs.ROUTE_LAST_GUIDES, "");
        List<WalkingGuide> guides = new ArrayList<>();
        if (storedGuides == null || storedGuides.trim().isEmpty()) {
            return guides;
        }
        try {
            JSONArray array = new JSONArray(storedGuides);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                double latitude = item.optDouble("latitude", Double.NaN);
                double longitude = item.optDouble("longitude", Double.NaN);
                String text = item.optString("text", "").trim();
                if (Double.isNaN(latitude) || Double.isNaN(longitude) || text.isEmpty()) {
                    continue;
                }
                guides.add(new WalkingGuide(LatLng.from(latitude, longitude), text));
            }
        } catch (Exception ignored) {
            // Older saved routes did not include guide data.
        }
        return guides;
    }

    private void startWalkingRouteMap() {
        if (!KakaoMapConfig.ensureInitialized(this)) {
            walkingRouteEmptyText.setText("카카오 지도 키가 설정되면 도보 경로를 확인할 수 있습니다.");
            return;
        }
        walkingMapStarted = true;
        walkingRouteMap.start(new MapLifeCycleCallback() {
            @Override
            public void onMapDestroy() {
            }

            @Override
            public void onMapError(Exception error) {
                walkingRouteEmptyText.setVisibility(View.VISIBLE);
                walkingRouteEmptyText.setText("도보 경로 지도를 불러오지 못했습니다. 경로 다시 설정을 사용하세요.");
            }
        }, new KakaoMapReadyCallback() {
            @Override
            public LatLng getPosition() {
                return walkingCurrentLatLng != null ? walkingCurrentLatLng : LatLng.from(37.5665, 126.9780);
            }

            @Override
            public int getZoomLevel() {
                return walkingCurrentLatLng == null ? 12 : 15;
            }

            @Override
            public void onMapReady(@NonNull KakaoMap map) {
                walkingKakaoMap = map;
                renderWalkingRoutePanel();
            }
        });
    }

    private void renderWalkingRoutePanel() {
        walkingDestinationLatLng = getStoredDestinationLatLng();
        walkingRoutePoints = getStoredRoutePoints();
        walkingGuides = getStoredWalkingGuides();
        LatLng latestLocation = getCurrentLocationLatLng();
        if (latestLocation != null) {
            walkingCurrentLatLng = latestLocation;
        }
        if (walkingCurrentLatLng == null && walkingRoutePoints.size() >= 1) {
            walkingCurrentLatLng = walkingRoutePoints.get(0);
        }
        if (walkingDestinationLatLng == null && walkingRoutePoints.size() >= 2) {
            walkingDestinationLatLng = walkingRoutePoints.get(walkingRoutePoints.size() - 1);
        }

        if (walkingDestinationLatLng == null) {
            walkingRouteEmptyText.setVisibility(View.VISIBLE);
            walkingRouteEmptyText.setText("도착지 좌표가 없습니다. 경로 다시 설정에서 도착지를 선택해주세요.");
            updateWalkingNavigationStatus();
            renderWalkingMap();
            return;
        }
        if (walkingRoutePoints.size() < 2 && walkingCurrentLatLng != null) {
            walkingRoutePoints = new ArrayList<>();
            walkingRoutePoints.add(walkingCurrentLatLng);
            walkingRoutePoints.add(walkingDestinationLatLng);
        }
        walkingRouteEmptyText.setVisibility(walkingRoutePoints.size() >= 2 ? View.GONE : View.VISIBLE);
        if (walkingRoutePoints.size() < 2) {
            walkingRouteEmptyText.setText("현재 위치를 확인하면 도보 경로가 표시됩니다.");
        }
        updateWalkingNavigationStatus();
        renderWalkingMap();
    }

    private LatLng getCurrentLocationLatLng() {
        if (!hasLocationPermission()) {
            return null;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location location = locationManager == null ? null : getBestLastKnownLocation(locationManager);
        if (location == null) {
            return null;
        }
        return LatLng.from(location.getLatitude(), location.getLongitude());
    }

    private void updateWalkingNavigationStatus() {
        if (walkingNavInstructionText == null || walkingNavMetaText == null) {
            return;
        }
        if (walkingDestinationLatLng == null) {
            walkingNavInstructionText.setText("도착지를 먼저 설정하세요.");
            walkingNavMetaText.setText("경로 다시 설정에서 도착지를 선택하면 도보 네비가 시작됩니다.");
            walkingNavDistanceText.setText("-");
            walkingNavEtaText.setText("-");
            return;
        }
        if (walkingCurrentLatLng == null) {
            walkingNavInstructionText.setText("현재 위치를 확인하는 중입니다.");
            walkingNavMetaText.setText("위치 권한과 GPS가 켜져 있어야 도보 네비가 갱신됩니다.");
            walkingNavDistanceText.setText("-");
            walkingNavEtaText.setText("-");
            return;
        }

        List<LatLng> route = walkingRoutePoints == null ? new ArrayList<>() : walkingRoutePoints;
        if (route.size() < 2) {
            route = new ArrayList<>();
            route.add(walkingCurrentLatLng);
            route.add(walkingDestinationLatLng);
        }

        if (routeDeviationActive) {
            walkingNavInstructionText.setText("경로를 벗어난 것 같아요.");
            walkingNavMetaText.setText(formatDistance(routeDeviationDistanceMeters) + " 이탈 · 보호자에게 알림을 보냅니다.");
            walkingNavDistanceText.setText(formatDistance(routeDeviationDistanceMeters));
            walkingNavEtaText.setText("확인 필요");
            return;
        }

        int nearestIndex = findNearestRoutePointIndex(walkingCurrentLatLng, route);
        int nextIndex = findNextRoutePointIndex(walkingCurrentLatLng, route, nearestIndex);
        LatLng nextPoint = route.get(nextIndex);
        float nextDistanceMeters = distanceMeters(walkingCurrentLatLng, nextPoint);
        float remainingMeters = estimateRemainingWalkingDistance(walkingCurrentLatLng, route, nextIndex);
        WalkingGuide nextGuide = findNextWalkingGuide(walkingCurrentLatLng, route, nearestIndex);

        if (remainingMeters <= 30f || distanceMeters(walkingCurrentLatLng, walkingDestinationLatLng) <= 30f) {
            walkingNavInstructionText.setText("도착지 근처입니다.");
            walkingNavMetaText.setText("주변을 확인한 뒤 귀가 완료 버튼을 눌러주세요.");
            walkingNavDistanceText.setText("30m 이내");
            walkingNavEtaText.setText("곧 도착");
            return;
        }

        if (nextGuide != null) {
            float guideDistanceMeters = distanceMeters(walkingCurrentLatLng, nextGuide.position);
            walkingNavInstructionText.setText(nextGuide.text);
            walkingNavMetaText.setText(formatDistance(guideDistanceMeters) + " 후 안내 · 도보 네비");
        } else {
            walkingNavInstructionText.setText("경로를 따라 " + formatDistance(nextDistanceMeters) + " 이동");
            walkingNavMetaText.setText(describeBearing(walkingCurrentLatLng, nextPoint) + " 방향 · 도보 네비");
        }
        walkingNavDistanceText.setText(formatDistance(remainingMeters));
        walkingNavEtaText.setText(formatWalkingMinutes(remainingMeters));
    }

    private WalkingGuide findNextWalkingGuide(LatLng current, List<LatLng> route, int nearestIndex) {
        if (walkingGuides == null || walkingGuides.isEmpty() || route == null || route.size() < 2) {
            return null;
        }
        WalkingGuide bestGuide = null;
        int bestRouteIndex = Integer.MAX_VALUE;
        float bestDistance = Float.MAX_VALUE;
        for (WalkingGuide guide : walkingGuides) {
            if (guide == null || guide.position == null) {
                continue;
            }
            float distance = distanceMeters(current, guide.position);
            if (distance < 12f) {
                continue;
            }
            int routeIndex = findNearestRoutePointIndex(guide.position, route);
            if (routeIndex < nearestIndex) {
                continue;
            }
            if (routeIndex < bestRouteIndex || (routeIndex == bestRouteIndex && distance < bestDistance)) {
                bestGuide = guide;
                bestRouteIndex = routeIndex;
                bestDistance = distance;
            }
        }
        return bestGuide;
    }

    private int findNearestRoutePointIndex(LatLng current, List<LatLng> route) {
        int nearestIndex = 0;
        float nearestDistance = Float.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            LatLng point = route.get(i);
            if (point == null) {
                continue;
            }
            float distance = distanceMeters(current, point);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    private int findNextRoutePointIndex(LatLng current, List<LatLng> route, int nearestIndex) {
        int nextIndex = Math.min(Math.max(nearestIndex + 1, 1), route.size() - 1);
        while (nextIndex < route.size() - 1 && distanceMeters(current, route.get(nextIndex)) < 15f) {
            nextIndex++;
        }
        return nextIndex;
    }

    private float estimateRemainingWalkingDistance(LatLng current, List<LatLng> route, int nextIndex) {
        if (route == null || route.isEmpty()) {
            return walkingDestinationLatLng == null ? 0f : distanceMeters(current, walkingDestinationLatLng);
        }
        int safeNextIndex = Math.min(Math.max(nextIndex, 0), route.size() - 1);
        float total = distanceMeters(current, route.get(safeNextIndex));
        for (int i = safeNextIndex; i + 1 < route.size(); i++) {
            total += distanceMeters(route.get(i), route.get(i + 1));
        }
        return total;
    }

    private float distanceMeters(LatLng from, LatLng to) {
        if (from == null || to == null) {
            return 0f;
        }
        float[] results = new float[1];
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, results);
        return Math.max(0f, results[0]);
    }

    private void checkRouteDeviation(Location location) {
        if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false) || location == null) {
            resetRouteDeviationState();
            return;
        }
        List<LatLng> route = walkingRoutePoints;
        if (route == null || route.size() < 2) {
            route = getStoredRoutePoints();
        }
        if (route == null || route.size() < 2) {
            resetRouteDeviationState();
            return;
        }

        LatLng current = LatLng.from(location.getLatitude(), location.getLongitude());
        LatLng destination = walkingDestinationLatLng != null ? walkingDestinationLatLng : getStoredDestinationLatLng();
        if (destination != null && distanceMeters(current, destination) <= 40f) {
            resetRouteDeviationState();
            return;
        }

        float distanceFromRoute = distanceToRouteMeters(current, route);
        routeDeviationActive = distanceFromRoute > ROUTE_DEVIATION_THRESHOLD_METERS;
        routeDeviationDistanceMeters = distanceFromRoute;
        if (!routeDeviationActive) {
            routeDeviationHitCount = 0;
            return;
        }

        routeDeviationHitCount++;
        long now = System.currentTimeMillis();
        boolean cooldownPassed = now - lastRouteDeviationAlertMillis >= ROUTE_DEVIATION_ALERT_COOLDOWN_MS;
        if (routeDeviationHitCount >= ROUTE_DEVIATION_CONFIRMATION_COUNT
                && cooldownPassed
                && !routeDeviationAlertInFlight) {
            routeDeviationAlertInFlight = true;
            int roundedDistance = Math.max(1, Math.round(distanceFromRoute));
            PushAlertClient.sendRouteDeviation(this, location, roundedDistance, (sent, message) -> {
                routeDeviationAlertInFlight = false;
                if (sent) {
                    lastRouteDeviationAlertMillis = System.currentTimeMillis();
                    Toast.makeText(this, "경로 이탈을 감지해 보호자에게 알렸습니다.", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private float distanceToRouteMeters(LatLng point, List<LatLng> route) {
        if (point == null || route == null || route.size() < 2) {
            return 0f;
        }
        float minDistance = Float.MAX_VALUE;
        for (int i = 0; i + 1 < route.size(); i++) {
            LatLng start = route.get(i);
            LatLng end = route.get(i + 1);
            if (start == null || end == null) {
                continue;
            }
            minDistance = Math.min(minDistance, distanceToSegmentMeters(point, start, end));
        }
        return minDistance == Float.MAX_VALUE ? 0f : minDistance;
    }

    private float distanceToSegmentMeters(LatLng point, LatLng start, LatLng end) {
        double metersPerDegreeLat = 111_320.0;
        double metersPerDegreeLng = metersPerDegreeLat * Math.cos(Math.toRadians(point.latitude));
        double pointX = point.longitude * metersPerDegreeLng;
        double pointY = point.latitude * metersPerDegreeLat;
        double startX = start.longitude * metersPerDegreeLng;
        double startY = start.latitude * metersPerDegreeLat;
        double endX = end.longitude * metersPerDegreeLng;
        double endY = end.latitude * metersPerDegreeLat;

        double segmentX = endX - startX;
        double segmentY = endY - startY;
        double segmentLengthSquared = segmentX * segmentX + segmentY * segmentY;
        if (segmentLengthSquared == 0) {
            return distanceMeters(point, start);
        }
        double projection = ((pointX - startX) * segmentX + (pointY - startY) * segmentY) / segmentLengthSquared;
        projection = Math.max(0.0, Math.min(1.0, projection));
        double closestX = startX + projection * segmentX;
        double closestY = startY + projection * segmentY;
        double deltaX = pointX - closestX;
        double deltaY = pointY - closestY;
        return (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
    }

    private void resetRouteDeviationState() {
        routeDeviationHitCount = 0;
        routeDeviationAlertInFlight = false;
        routeDeviationActive = false;
        routeDeviationDistanceMeters = 0f;
    }

    private String describeBearing(LatLng from, LatLng to) {
        float bearing = bearingDegrees(from, to);
        if (bearing < 22.5f || bearing >= 337.5f) {
            return "북쪽";
        }
        if (bearing < 67.5f) {
            return "북동쪽";
        }
        if (bearing < 112.5f) {
            return "동쪽";
        }
        if (bearing < 157.5f) {
            return "남동쪽";
        }
        if (bearing < 202.5f) {
            return "남쪽";
        }
        if (bearing < 247.5f) {
            return "남서쪽";
        }
        if (bearing < 292.5f) {
            return "서쪽";
        }
        return "북서쪽";
    }

    private float bearingDegrees(LatLng from, LatLng to) {
        Location fromLocation = new Location("from");
        fromLocation.setLatitude(from.latitude);
        fromLocation.setLongitude(from.longitude);
        Location toLocation = new Location("to");
        toLocation.setLatitude(to.latitude);
        toLocation.setLongitude(to.longitude);
        return (fromLocation.bearingTo(toLocation) + 360f) % 360f;
    }

    private String formatDistance(float meters) {
        if (meters >= 1000f) {
            return String.format(Locale.KOREA, "%.1fkm", meters / 1000f);
        }
        return Math.max(1, Math.round(meters)) + "m";
    }

    private String formatWalkingMinutes(float meters) {
        int minutes = Math.max(1, Math.round((meters / WALKING_SPEED_METERS_PER_SECOND) / 60f));
        return minutes + "분";
    }

    private void renderWalkingMap() {
        if (walkingKakaoMap == null) {
            return;
        }
        walkingKakaoMap.getLabelManager().getLayer().removeAll();
        walkingKakaoMap.getRouteLineManager().getLayer().removeAll();

        boolean navigationMode = prefs.getBoolean(SafeWayPrefs.RETURNING, false) && walkingCurrentLatLng != null;
        List<LatLng> routeToDraw = navigationMode ? getRemainingWalkingRoutePoints() : walkingRoutePoints;
        List<LatLng> boundsPoints = new ArrayList<>();
        Label currentLocationLabel = null;
        if (walkingCurrentLatLng != null) {
            currentLocationLabel = addWalkingMarker(walkingCurrentLatLng, "내 위치", true);
            boundsPoints.add(walkingCurrentLatLng);
        }
        if (walkingDestinationLatLng != null) {
            addWalkingMarker(walkingDestinationLatLng, "도착지", false);
            boundsPoints.add(walkingDestinationLatLng);
        }
        if (routeToDraw != null && routeToDraw.size() >= 2) {
            addWalkingRouteLine(routeToDraw);
            boundsPoints.addAll(routeToDraw);
        }
        List<LatLng> dangerPoints = addWalkingDangerMemoMarkers();
        if (boundsPoints.isEmpty()) {
            boundsPoints.addAll(dangerPoints);
        }

        if (navigationMode) {
            moveWalkingCameraForNavigation(routeToDraw, currentLocationLabel);
            return;
        }
        walkingKakaoMap.getTrackingManager().stopTracking();

        if (boundsPoints.size() >= 2) {
            walkingRouteMap.post(() -> walkingKakaoMap.moveCamera(
                    CameraUpdateFactory.fitMapPoints(boundsPoints.toArray(new LatLng[0]), dp(64), 16),
                    CameraAnimation.from(500)
            ));
        } else if (!boundsPoints.isEmpty()) {
            walkingKakaoMap.moveCamera(CameraUpdateFactory.newCenterPosition(boundsPoints.get(0), 15), CameraAnimation.from(500));
        }
    }

    private List<LatLng> getRemainingWalkingRoutePoints() {
        List<LatLng> remaining = new ArrayList<>();
        if (walkingCurrentLatLng == null) {
            return walkingRoutePoints;
        }
        if (walkingRoutePoints == null || walkingRoutePoints.size() < 2) {
            if (walkingDestinationLatLng != null) {
                remaining.add(walkingCurrentLatLng);
                remaining.add(walkingDestinationLatLng);
            }
            return remaining;
        }
        int nearestIndex = findNearestRoutePointIndex(walkingCurrentLatLng, walkingRoutePoints);
        int nextIndex = findNextRoutePointIndex(walkingCurrentLatLng, walkingRoutePoints, nearestIndex);
        remaining.add(walkingCurrentLatLng);
        for (int i = nextIndex; i < walkingRoutePoints.size(); i++) {
            LatLng point = walkingRoutePoints.get(i);
            if (point != null) {
                remaining.add(point);
            }
        }
        if (remaining.size() < 2 && walkingDestinationLatLng != null) {
            remaining.add(walkingDestinationLatLng);
        }
        return remaining;
    }

    private void moveWalkingCameraForNavigation(List<LatLng> routeToDraw, Label currentLocationLabel) {
        float rotation = 0f;
        if (routeToDraw != null && routeToDraw.size() >= 2 && routeToDraw.get(1) != null) {
            rotation = bearingDegrees(walkingCurrentLatLng, routeToDraw.get(1));
        }
        if (currentLocationLabel != null) {
            currentLocationLabel.rotateTo(rotation);
            walkingKakaoMap.getTrackingManager().startTracking(currentLocationLabel);
            walkingKakaoMap.getTrackingManager().setTrackingRotation(true);
        }
        CameraPosition cameraPosition = CameraPosition.from(
                new CameraPosition.Builder()
                        .setPosition(walkingCurrentLatLng)
                        .setZoomLevel(WALKING_NAV_ZOOM_LEVEL)
                        .setTiltAngle(45)
                        .setRotationAngle(rotation)
        );
        walkingRouteMap.post(() -> walkingKakaoMap.moveCamera(
                CameraUpdateFactory.newCameraPosition(cameraPosition),
                CameraAnimation.from(500)
        ));
    }

    private Label addWalkingMarker(LatLng position, String title, boolean currentLocation) {
        LabelStyles styles = currentLocation ? walkingCurrentMarkerStyles : walkingDestinationMarkerStyles;
        if (styles == null) {
            int icon = currentLocation ? R.drawable.ic_nav_arrow_teal : R.drawable.ic_map_marker_primary;
            styles = KakaoMarkerStyles.addMarkerStyles(this, walkingKakaoMap, icon, 22, Color.parseColor("#172126"));
            if (currentLocation) {
                walkingCurrentMarkerStyles = styles;
            } else {
                walkingDestinationMarkerStyles = styles;
            }
        }
        return walkingKakaoMap.getLabelManager().getLayer().addLabel(
                LabelOptions.from(position)
                        .setStyles(styles)
                        .setTexts(new LabelTextBuilder().setTexts(title))
        );
    }

    private List<LatLng> addWalkingDangerMemoMarkers() {
        List<LatLng> dangerPoints = new ArrayList<>();
        if (db == null) {
            return dangerPoints;
        }
        for (AppDatabase.DangerMemo memo : db.getDangerMemos()) {
            LatLng position = parseDangerMemoLatLng(memo);
            if (position == null) {
                continue;
            }
            dangerPoints.add(position);
            addWalkingDangerMarker(position, memo.placeName);
        }
        return dangerPoints;
    }

    private void addWalkingDangerMarker(LatLng position, String title) {
        if (walkingDangerMarkerStyles == null) {
            walkingDangerMarkerStyles = KakaoMarkerStyles.addMarkerStyles(this, walkingKakaoMap,
                    R.drawable.ic_map_marker_danger, 20, Color.parseColor("#172126"));
        }
        String label = title == null || title.trim().isEmpty() ? "위험 지역" : title.trim();
        walkingKakaoMap.getLabelManager().getLayer().addLabel(
                LabelOptions.from(position)
                        .setStyles(walkingDangerMarkerStyles)
                        .setTexts(new LabelTextBuilder().setTexts(label))
        );
    }

    private LatLng parseDangerMemoLatLng(AppDatabase.DangerMemo memo) {
        if (memo == null || memo.latitude == null || memo.longitude == null
                || memo.latitude.trim().isEmpty() || memo.longitude.trim().isEmpty()) {
            return null;
        }
        try {
            return LatLng.from(Double.parseDouble(memo.latitude.trim()), Double.parseDouble(memo.longitude.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void addWalkingRouteLine(List<LatLng> points) {
        RouteLineLayer layer = walkingKakaoMap.getRouteLineManager().getLayer();
        int color = ContextCompat.getColor(this, R.color.safeway_teal);
        RouteLineStylesSet stylesSet = RouteLineStylesSet.from(
                RouteLineStyles.from(RouteLineStyle.from(dp(6), color))
        );
        RouteLineSegment segment = RouteLineSegment.from(points).setStyles(stylesSet.getStyles(0));
        RouteLineOptions options = RouteLineOptions.from(segment).setStylesSet(stylesSet);
        layer.addRouteLine(options);
    }

    private void startWalkingLocationUpdatesIfNeeded() {
        if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false) || walkingLocationUpdatesActive || !hasLocationPermission()) {
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }
        String provider = getWalkingLocationProvider(locationManager);
        if (provider == null) {
            return;
        }
        walkingLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                walkingCurrentLatLng = LatLng.from(location.getLatitude(), location.getLongitude());
                ReturnTrackRecorder.record(MainActivity.this, location);
                checkRouteDeviation(location);
                renderWalkingRoutePanel();
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
        };
        try {
            locationManager.requestLocationUpdates(provider, 3000L, 5f, walkingLocationListener, Looper.getMainLooper());
            walkingLocationUpdatesActive = true;
        } catch (SecurityException ignored) {
            walkingLocationListener = null;
            walkingLocationUpdatesActive = false;
        }
    }

    private void stopWalkingLocationUpdates() {
        if (!walkingLocationUpdatesActive || walkingLocationListener == null) {
            walkingLocationUpdatesActive = false;
            walkingLocationListener = null;
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(walkingLocationListener);
            } catch (SecurityException ignored) {
                // Location permission may have been revoked while the screen was open.
            }
        }
        walkingLocationUpdatesActive = false;
        walkingLocationListener = null;
    }

    private String getWalkingLocationProvider(LocationManager locationManager) {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ROUTE_FOR_START && resultCode == RESULT_OK) {
            startReturn();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_FOR_START && pendingStartAfterNotificationPermission) {
            pendingStartAfterNotificationPermission = false;
            continueStartReturnAfterNotificationPermission();
            if (!hasNotificationPermission()) {
                Toast.makeText(this, "알림 권한이 없어 알림 없이 안심귀가를 시작합니다.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode != REQUEST_LOCATION_FOR_START) {
            return;
        }

        if (pendingStartAfterLocationPermission) {
            pendingStartAfterLocationPermission = false;
            startReturnAndShowLocationNotification();
            if (!hasLocationPermission()) {
                Toast.makeText(this, "위치 권한이 없어 위치 링크 없이 안심귀가 알림을 표시합니다.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (pendingRefreshWalkingRouteAfterLocationPermission) {
            pendingRefreshWalkingRouteAfterLocationPermission = false;
            if (hasLocationPermission()) {
                startWalkingLocationUpdatesIfNeeded();
                refreshWalkingRoutePanel();
            } else {
                Toast.makeText(this, "위치 권한이 없어 도보 경로를 새로고침할 수 없습니다.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (pendingOpenWalkingNaviAfterLocationPermission) {
            pendingOpenWalkingNaviAfterLocationPermission = false;
            if (hasLocationPermission()) {
                startWalkingLocationUpdatesIfNeeded();
                openFullWalkingNaviFromWalkingPanel();
            } else {
                Toast.makeText(this, "위치 권한이 없어 전체화면 도보 내비를 열 수 없습니다.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void showReturnStartNotificationWithLocation() {
        if (!hasLocationPermission()) {
            showReturnNotification(null);
            sendGuardianReturnPush(null);
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            showReturnNotification(null);
            sendGuardianReturnPush(null);
            return;
        }

        Location lastLocation = getBestLastKnownLocation(locationManager);
        if (lastLocation != null) {
            ReturnTrackRecorder.record(this, lastLocation);
            showReturnNotification(lastLocation);
            sendGuardianReturnPush(lastLocation);
            return;
        }

        showReturnNotification(null);
        sendGuardianReturnPush(null);
        requestSingleLocationUpdate(locationManager);
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

    private void requestSingleLocationUpdate(LocationManager locationManager) {
        String provider = null;
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            provider = LocationManager.NETWORK_PROVIDER;
        } else if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            provider = LocationManager.GPS_PROVIDER;
        }

        if (provider == null) {
            Toast.makeText(this, "위치 서비스가 꺼져 있어 위치 링크 없이 알림을 표시합니다.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Toast.makeText(this, "현재 위치를 확인하는 중입니다.", Toast.LENGTH_SHORT).show();
            locationManager.requestSingleUpdate(provider, new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    walkingCurrentLatLng = LatLng.from(location.getLatitude(), location.getLongitude());
                    ReturnTrackRecorder.record(MainActivity.this, location);
                    renderWalkingRoutePanel();
                    showReturnNotification(location);
                    sendGuardianReturnPush(location);
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
            showReturnNotification(null);
            sendGuardianReturnPush(null);
            Toast.makeText(this, "위치 권한이 없어 위치 링크 없이 알림을 표시합니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showReturnNotification(Location location) {
        if (!hasNotificationPermission()) {
            return;
        }

        ensureReturnNotificationChannel();

        boolean hasLocation = location != null;
        String destination = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, "");
        boolean hasDestination = destination != null && !destination.trim().isEmpty();
        String title = "SafeWay 안심귀가 진행 중";
        String body = hasLocation
                ? (hasDestination ? "귀가 경로 링크가 준비되었습니다. 알림을 눌러 지도에서 확인하세요." : "현재 위치 링크가 준비되었습니다. 알림을 눌러 지도에서 확인하세요.")
                : "안심귀가가 시작되었습니다. 위치를 확인하는 중입니다.";

        Intent intent = hasLocation
                ? new Intent(Intent.ACTION_VIEW, Uri.parse(hasDestination
                ? PushAlertClient.buildDirectionsLink(location, destination)
                : PushAlertClient.buildMapsLink(location)))
                : new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, RETURN_NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_STATUS);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(RETURN_NOTIFICATION_ID, builder.build());
        }
    }

    private void ensureReturnNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(RETURN_NOTIFICATION_CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                RETURN_NOTIFICATION_CHANNEL_ID,
                "안심귀가 알림",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("안심귀가 시작과 현재 위치 상태를 알려줍니다.");
        manager.createNotificationChannel(channel);
    }

    private void cancelReturnNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(RETURN_NOTIFICATION_ID);
        }
    }

    private void sendGuardianReturnPush(Location location) {
        PushAlertClient.sendReturnStarted(this, location, null);
    }

    private static final class WalkingGuide {
        final LatLng position;
        final String text;

        WalkingGuide(LatLng position, String text) {
            this.position = position;
            this.text = text;
        }
    }

    @Override
    protected void onDestroy() {
        stopWalkingLocationUpdates();
        if (emergencySoundPlayer != null) {
            emergencySoundPlayer.release();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
