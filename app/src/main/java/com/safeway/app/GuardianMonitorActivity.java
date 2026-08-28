package com.safeway.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ActivityNotFoundException;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import com.kakao.vectormap.camera.CameraUpdateFactory;
import com.kakao.vectormap.label.LabelOptions;
import com.kakao.vectormap.label.LabelStyle;
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
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GuardianMonitorActivity extends AppCompatActivity {
    private static final LatLng DEFAULT_CAMERA = LatLng.from(37.5665, 126.9780);
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_DEVIATED = "deviated";
    private static final String STATUS_DANGER = "danger";
    private static final String STATUS_LINKED = "linked";
    private static final String STATUS_NOTICE = "notice";
    private static final String LOCATION_ADDRESS_LOADING = "주소 확인 중...";
    private static final String LOCATION_ADDRESS_UNAVAILABLE = "주소를 확인할 수 없습니다.";
    private static final long GUARDIAN_STATUS_REFRESH_INTERVAL_MS = 5000L;

    private final SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA);
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA);
    private final SimpleDateFormat monthTitleFormat = new SimpleDateFormat("yyyy년 M월", Locale.KOREA);
    private final SimpleDateFormat monthDayFormat = new SimpleDateFormat("M/d", Locale.KOREA);
    private final SimpleDateFormat weekdayFormat = new SimpleDateFormat("E", Locale.KOREA);
    private final SimpleDateFormat shortTimeFormat = new SimpleDateFormat("HH:mm", Locale.KOREA);
    private final ExecutorService addressExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private AppDatabase db;

    private TextView statusBadge;
    private TextView updatedText;
    private TextView titleText;
    private TextView descriptionText;
    private TextView mapEmptyText;
    private TextView locationMetaText;
    private View progressPanel;
    private TextView progressTitleText;
    private TextView progressDetailText;
    private View progressFill;
    private TextView calendarMonthText;
    private LinearLayout calendarGrid;
    private TextView selectedDateSummaryText;
    private LinearLayout timelineList;
    private LinearLayout recentEventsList;
    private MapView mapView;
    private KakaoMap kakaoMap;
    private LabelStyles currentMarkerStyles;
    private LabelStyles destinationMarkerStyles;
    private LatLng currentLatLng;
    private LatLng destinationLatLng;
    private List<LatLng> guardianRoutePoints = new ArrayList<>();
    private boolean mapStarted;
    private String lastAddressLookupKey = "";
    private String lastLocationAddressText = "";
    private boolean addressLookupRunning;
    private String selectedHistoryDate = "";
    private boolean guardianStatusAutoRefreshRunning;
    private boolean guardianStatusRefreshInFlight;
    private final Calendar visibleHistoryMonth = Calendar.getInstance(Locale.KOREA);
    private final Runnable guardianStatusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!guardianStatusAutoRefreshRunning) {
                return;
            }
            refreshLatestAlertFromServer(false);
            mainHandler.postDelayed(this, GUARDIAN_STATUS_REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SafeWayTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guardian_monitor);
        BottomNav.bind(this, GuardianMonitorActivity.class);

        prefs = SafeWayPrefs.get(this);
        db = new AppDatabase(this);
        FcmTokenManager.refreshDeviceToken(this);
        statusBadge = findViewById(R.id.monitorStatusBadge);
        updatedText = findViewById(R.id.monitorUpdatedText);
        titleText = findViewById(R.id.monitorTitleText);
        descriptionText = findViewById(R.id.monitorDescriptionText);
        mapEmptyText = findViewById(R.id.mapEmptyText);
        locationMetaText = findViewById(R.id.locationMetaText);
        progressPanel = findViewById(R.id.progressPanel);
        progressTitleText = findViewById(R.id.progressTitleText);
        progressDetailText = findViewById(R.id.progressDetailText);
        progressFill = findViewById(R.id.guardianProgressFill);
        calendarMonthText = findViewById(R.id.guardianCalendarMonthText);
        calendarGrid = findViewById(R.id.guardianCalendarGrid);
        selectedDateSummaryText = findViewById(R.id.guardianSelectedDateSummaryText);
        timelineList = findViewById(R.id.timelineList);
        recentEventsList = findViewById(R.id.recentGuardianEventsList);
        mapView = findViewById(R.id.guardianMap);

        findViewById(R.id.openGuardianRouteButton).setOnClickListener(v ->
                openLink(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_LINK, "경로 링크가 아직 없습니다."));
        findViewById(R.id.openGuardianLocationButton).setOnClickListener(v ->
                openLink(SafeWayPrefs.LATEST_GUARDIAN_ALERT_MAPS_LINK, "현재 위치 링크가 아직 없습니다."));
        findViewById(R.id.refreshGuardianStatusButton).setOnClickListener(v -> refreshLatestAlertFromServer());
        findViewById(R.id.guardianEmergencyButton).setOnClickListener(v -> PhoneUtils.dial(this, "112"));
        findViewById(R.id.guardianCalendarPrevButton).setOnClickListener(v -> moveVisibleHistoryMonth(-1));
        findViewById(R.id.guardianCalendarNextButton).setOnClickListener(v -> moveVisibleHistoryMonth(1));

        startMap();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FcmTokenManager.refreshDeviceToken(this);
        if (mapStarted && mapView != null) {
            mapView.resume();
        }
        renderLatestAlert();
        startGuardianStatusAutoRefresh();
    }

    @Override
    protected void onPause() {
        stopGuardianStatusAutoRefresh();
        if (mapStarted && mapView != null) {
            mapView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopGuardianStatusAutoRefresh();
        addressExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refreshLatestAlertFromServer() {
        refreshLatestAlertFromServer(true);
    }

    private void refreshLatestAlertFromServer(boolean showToast) {
        if (guardianStatusRefreshInFlight) {
            return;
        }
        if (showToast) {
            updatedText.setText("서버 상태 확인 중...");
        }
        guardianStatusRefreshInFlight = true;
        PushAlertClient.fetchLatestGuardianStatus(this, (ok, message) -> {
            guardianStatusRefreshInFlight = false;
            renderLatestAlert();
            if (showToast) {
                Toast.makeText(this, message, ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            }
        });
    }

    private void startGuardianStatusAutoRefresh() {
        guardianStatusAutoRefreshRunning = true;
        mainHandler.removeCallbacks(guardianStatusRefreshRunnable);
        refreshLatestAlertFromServer(false);
        mainHandler.postDelayed(guardianStatusRefreshRunnable, GUARDIAN_STATUS_REFRESH_INTERVAL_MS);
    }

    private void stopGuardianStatusAutoRefresh() {
        guardianStatusAutoRefreshRunning = false;
        mainHandler.removeCallbacks(guardianStatusRefreshRunnable);
    }

    private void startMap() {
        if (!KakaoMapConfig.ensureInitialized(this)) {
            mapEmptyText.setText("카카오 지도 키가 설정되면 지도에서 위치를 확인할 수 있습니다.");
            return;
        }
        mapStarted = true;
        mapView.start(new MapLifeCycleCallback() {
            @Override
            public void onMapDestroy() {
            }

            @Override
            public void onMapError(Exception error) {
                mapEmptyText.setVisibility(View.VISIBLE);
                mapEmptyText.setText("지도를 불러오지 못했습니다. 지도 열기 버튼을 사용하세요.");
            }
        }, new KakaoMapReadyCallback() {
            @Override
            public LatLng getPosition() {
                return currentLatLng != null ? currentLatLng : DEFAULT_CAMERA;
            }

            @Override
            public int getZoomLevel() {
                return currentLatLng == null ? 12 : 15;
            }

            @Override
            public void onMapReady(@NonNull KakaoMap map) {
                kakaoMap = map;
                renderMap();
            }
        });
    }

    private void renderLatestAlert() {
        String title = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_TITLE, "");
        String body = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_BODY, "");
        String status = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_STATUS, "");
        String destination = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_DESTINATION, "");
        String latitude = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_LATITUDE, "");
        String longitude = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_LONGITUDE, "");
        String routeLink = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_LINK, "");
        String routePoints = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_POINTS, "");
        int expectedMinutes = prefs.getInt(SafeWayPrefs.LATEST_GUARDIAN_ALERT_EXPECTED_MINUTES, 0);
        long updatedAt = prefs.getLong(SafeWayPrefs.LATEST_GUARDIAN_ALERT_UPDATED_AT, 0L);

        currentLatLng = parseLatLng(latitude, longitude);
        guardianRoutePoints = parseRoutePoints(routePoints);
        destinationLatLng = parseDestinationFromRouteLink(routeLink);
        if (destinationLatLng == null && guardianRoutePoints.size() >= 2) {
            destinationLatLng = guardianRoutePoints.get(guardianRoutePoints.size() - 1);
        }

        if (title == null || title.trim().isEmpty()) {
            renderEmptyState();
            renderMap();
            return;
        }

        boolean linked = STATUS_LINKED.equals(status);
        boolean notice = STATUS_NOTICE.equals(status);
        boolean completed = STATUS_COMPLETED.equals(status);
        boolean deviated = STATUS_DEVIATED.equals(status);
        boolean danger = STATUS_DANGER.equals(status);
        boolean active = STATUS_ACTIVE.equals(status) || deviated || (!completed && !linked && !notice && hasAnyLink());
        boolean late = active && expectedMinutes > 0 && updatedAt > 0L
                && System.currentTimeMillis() - updatedAt > expectedMinutes * 60000L;

        if (linked) {
            applyStatus("연동됨", R.drawable.bg_teal_soft, R.color.safeway_teal);
            titleText.setText("보호자 연결 완료");
            descriptionText.setText(nonEmpty(body, "자녀 기기와 보호자 모니터가 연결되었습니다."));
        } else if (completed) {
            applyStatus("완료", R.drawable.bg_success, R.color.white);
            titleText.setText("무사히 귀가 완료");
            descriptionText.setText(nonEmpty(body, "안심귀가가 완료되었습니다. 무사히 도착했는지 확인하세요."));
        } else if (deviated) {
            applyStatus("경로 이탈", R.drawable.bg_danger, R.color.white);
            titleText.setText("경로 이탈이 감지되었습니다.");
            descriptionText.setText(nonEmpty(body, "마지막 위치와 경로를 확인하고, 필요하면 바로 연락하세요."));
        } else if (danger) {
            applyStatus("위험 감지", R.drawable.bg_danger, R.color.white);
            titleText.setText("AI 통화 중 위험 신호가 감지되었습니다.");
            descriptionText.setText(nonEmpty(body, "마지막 위치를 확인하고 바로 연락하세요."));
        } else if (late) {
            applyStatus("시간 초과", R.drawable.bg_warning_soft, R.color.safeway_warning);
            titleText.setText("예상 시간이 지났습니다.");
            descriptionText.setText("마지막 위치와 경로를 확인하고, 필요하면 바로 연락하세요.");
        } else if (active) {
            applyStatus("귀가 중", R.drawable.bg_primary_soft, R.color.safeway_primary);
            titleText.setText("자녀가 귀가 중입니다.");
            descriptionText.setText(nonEmpty(body, "안심귀가가 시작되었습니다. 위치와 경로를 확인하세요."));
        } else {
            applyStatus("수신", R.drawable.bg_teal_soft, R.color.safeway_teal);
            titleText.setText(title);
            descriptionText.setText(nonEmpty(body, "보호자 알림이 도착했습니다."));
        }

        updatedText.setText(updatedAt > 0L
                ? "마지막 업데이트 " + formatRelativeTime(updatedAt) + " · " + dateTimeFormat.format(new Date(updatedAt))
                : "마지막 업데이트 정보 없음");
        renderLocationMeta(linked, destination, latitude, longitude);
        renderProgress(completed, active, late, updatedAt, expectedMinutes);
        renderTimeline(linked, completed, active, late, updatedAt, expectedMinutes, destination);
        renderHistoryCalendar();
        renderRecentEvents(linked, completed, active, late, updatedAt, destination);
        renderMap();
    }

    private void renderEmptyState() {
        applyStatus("대기", R.drawable.bg_primary_soft, R.color.safeway_primary);
        updatedText.setText("최근 알림 없음");
        titleText.setText("아직 진행 중인 귀가가 없습니다.");
        descriptionText.setText("자녀가 안심귀가를 시작하면 위치와 경로 상태가 표시됩니다.");
        locationMetaText.setText("현재 위치 정보 없음");
        progressPanel.setVisibility(View.GONE);
        mapEmptyText.setVisibility(View.VISIBLE);
        timelineList.removeAllViews();
        timelineList.addView(createInfoRow("연동 대기", "자녀 앱에서 안심귀가를 시작하면 보호자 알림이 이 화면에 표시됩니다."));
        recentEventsList.removeAllViews();
        recentEventsList.addView(createInfoRow("최근 기록 없음", "수신한 귀가 알림이 아직 없습니다."));
        renderHistoryCalendar();
    }

    private void renderLocationMeta(boolean linked, String destination, String latitude, String longitude) {
        if (linked) {
            locationMetaText.setText("연동 완료. 자녀가 안심귀가를 시작하면 위치와 경로가 이 화면에 표시됩니다.");
            mapEmptyText.setVisibility(View.VISIBLE);
            return;
        }
        String locationLine = lastLocationAddressLine(latitude, longitude);
        locationMetaText.setText(buildLocationMeta(destination, locationLine));
        mapEmptyText.setVisibility(currentLatLng == null ? View.VISIBLE : View.GONE);
    }

    private String buildLocationMeta(String destination, String locationLine) {
        StringBuilder meta = new StringBuilder();
        if (destination != null && !destination.trim().isEmpty()) {
            meta.append("도착지: ").append(destination.trim());
        }
        if (locationLine != null && !locationLine.trim().isEmpty()) {
            if (meta.length() > 0) {
                meta.append("\n");
            }
            meta.append("마지막 위치: ").append(locationLine.trim());
        }
        if (meta.length() == 0) {
            meta.append("위치 링크 또는 경로 링크를 받으면 지도와 함께 표시됩니다.");
        }
        return meta.toString();
    }

    private String lastLocationAddressLine(String latitude, String longitude) {
        LatLng point = parseLatLng(latitude, longitude);
        if (point == null) {
            return "";
        }
        String key = latitude.trim() + "," + longitude.trim();
        if (!key.equals(lastAddressLookupKey)) {
            lastAddressLookupKey = key;
            lastLocationAddressText = LOCATION_ADDRESS_LOADING;
            addressLookupRunning = false;
        }
        if (LOCATION_ADDRESS_LOADING.equals(lastLocationAddressText) && !addressLookupRunning) {
            resolveLastLocationAddress(key, point);
        }
        return lastLocationAddressText;
    }

    private void resolveLastLocationAddress(String lookupKey, LatLng point) {
        String restApiKey = KakaoMapConfig.restApiKey();
        if (restApiKey.isEmpty()) {
            lastLocationAddressText = LOCATION_ADDRESS_UNAVAILABLE + " (Kakao REST 키 필요)";
            return;
        }
        addressLookupRunning = true;
        addressExecutor.execute(() -> {
            String address = "";
            try {
                address = KakaoLocalSearch.reverseGeocode(restApiKey, point);
            } catch (Exception ignored) {
            }
            String resolved = address == null || address.trim().isEmpty()
                    ? LOCATION_ADDRESS_UNAVAILABLE
                    : address.trim();
            mainHandler.post(() -> {
                if (!lookupKey.equals(lastAddressLookupKey)) {
                    return;
                }
                lastLocationAddressText = resolved;
                addressLookupRunning = false;
                renderLatestAlert();
            });
        });
    }

    private void renderProgress(boolean completed, boolean active, boolean late, long updatedAt, int expectedMinutes) {
        if (!completed && !active) {
            progressPanel.setVisibility(View.GONE);
            return;
        }
        progressPanel.setVisibility(View.VISIBLE);
        if (completed) {
            progressTitleText.setText("귀가 완료");
            progressDetailText.setText(expectedMinutes > 0
                    ? "예상 " + expectedMinutes + "분 기준으로 완료 알림을 받았습니다."
                    : "완료 알림을 받았습니다.");
            updateProgressWidth(100);
            return;
        }
        if (expectedMinutes <= 0 || updatedAt <= 0L) {
            progressTitleText.setText(late ? "확인 필요" : "귀가 진행");
            progressDetailText.setText("예상 시간이 없어 진행률 대신 마지막 위치를 기준으로 확인합니다.");
            updateProgressWidth(12);
            return;
        }

        long elapsedMinutes = Math.max(0, (System.currentTimeMillis() - updatedAt) / 60000L);
        int percent = (int) Math.min(100, elapsedMinutes * 100 / expectedMinutes);
        long remaining = expectedMinutes - elapsedMinutes;
        progressTitleText.setText(late ? "예상 시간 초과" : "귀가 진행");
        progressDetailText.setText(late
                ? "예상 " + expectedMinutes + "분을 지났습니다. 마지막 위치를 확인하세요."
                : elapsedMinutes + "분 경과 · 약 " + Math.max(1, remaining) + "분 남음 · 예상 " + expectedMinutes + "분");
        updateProgressWidth(Math.max(8, percent));
    }

    private void renderTimeline(boolean linked, boolean completed, boolean active, boolean late, long updatedAt,
                                int expectedMinutes, String destination) {
        timelineList.removeAllViews();
        String timeText = updatedAt > 0L ? dateTimeFormat.format(new Date(updatedAt)) : "시간 정보 없음";
        if (linked) {
            timelineList.addView(createInfoRow("보호자 연동 완료", timeText + " 자녀 기기와 연결되었습니다."));
            timelineList.addView(createInfoRow("다음 단계", "자녀가 안심귀가를 시작하면 위치와 경로 알림을 받습니다."));
            return;
        }
        if (completed) {
            timelineList.addView(createInfoRow("귀가 완료", timeText + " 완료 알림을 받았습니다."));
            if (destination != null && !destination.trim().isEmpty()) {
                timelineList.addView(createInfoRow("도착지 확인", destination.trim()));
            }
            return;
        }
        if (active) {
            timelineList.addView(createInfoRow("안심귀가 시작", timeText + " 시작 알림을 받았습니다."));
            timelineList.addView(createInfoRow(currentLatLng == null ? "위치 확인 대기" : "현재 위치 수신",
                    currentLatLng == null ? "위치가 수신되면 지도에 표시됩니다." : "지도에서 마지막 위치를 확인할 수 있습니다."));
            if (expectedMinutes > 0) {
                timelineList.addView(createInfoRow(late ? "예상 시간 초과" : "예상 시간 설정", "예상 소요 시간 " + expectedMinutes + "분"));
            }
            return;
        }
        timelineList.addView(createInfoRow("알림 수신", timeText + " 보호자 알림을 받았습니다."));
    }

    private void renderRecentEvents(boolean linked, boolean completed, boolean active, boolean late, long updatedAt, String destination) {
        recentEventsList.removeAllViews();
        String state = linked ? "연동됨" : completed ? "완료" : late ? "시간 초과" : active ? "귀가 중" : "알림";
        String when = updatedAt > 0L ? dateTimeFormat.format(new Date(updatedAt)) : "시간 정보 없음";
        String desc = destination == null || destination.trim().isEmpty()
                ? when
                : when + "\n도착지: " + destination.trim();
        recentEventsList.addView(createInfoRow(state, desc));
    }

    private void renderHistoryCalendar() {
        List<GuardianHistoryEntry> entries = loadHistoryEntries();
        if (selectedHistoryDate == null || selectedHistoryDate.trim().isEmpty()) {
            selectedHistoryDate = defaultSelectedHistoryDate(entries);
            setVisibleMonthFromDateKey(selectedHistoryDate);
        }
        renderCalendarMonth(entries);
        renderSelectedDateHistory(entries);
    }

    private void moveVisibleHistoryMonth(int monthOffset) {
        visibleHistoryMonth.add(Calendar.MONTH, monthOffset);
        visibleHistoryMonth.set(Calendar.DAY_OF_MONTH, 1);
        renderHistoryCalendar();
    }

    private String defaultSelectedHistoryDate(List<GuardianHistoryEntry> entries) {
        if (entries != null && !entries.isEmpty()) {
            GuardianHistoryEntry first = entries.get(0);
            if (first.dateKey != null && !first.dateKey.trim().isEmpty()) {
                return first.dateKey.trim();
            }
        }
        return dayFormat.format(new Date());
    }

    private void setVisibleMonthFromDateKey(String dateKey) {
        Date date = parseDateKey(dateKey);
        visibleHistoryMonth.setTime(date == null ? new Date() : date);
        visibleHistoryMonth.set(Calendar.DAY_OF_MONTH, 1);
    }

    private List<GuardianHistoryEntry> loadHistoryEntries() {
        List<GuardianHistoryEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        loadServerHistoryEntries(entries, seen);
        addLatestGuardianAlertEntry(entries, seen);
        addLocalReturnRecordEntries(entries, seen);
        entries.sort((first, second) -> {
            long firstSort = first.sortMillis();
            long secondSort = second.sortMillis();
            return Long.compare(secondSort, firstSort);
        });
        return entries;
    }

    private void loadServerHistoryEntries(List<GuardianHistoryEntry> entries, Set<String> seen) {
        String raw = prefs.getString(SafeWayPrefs.GUARDIAN_ALERT_HISTORY_JSON, "[]");
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        try {
            JSONArray history = new JSONArray(raw);
            for (int index = 0; index < history.length(); index++) {
                JSONObject item = history.optJSONObject(index);
                GuardianHistoryEntry entry = GuardianHistoryEntry.fromGuardianJson(item, dayFormat);
                addHistoryEntry(entries, seen, entry);
            }
        } catch (Exception ignored) {
        }
    }

    private void addLatestGuardianAlertEntry(List<GuardianHistoryEntry> entries, Set<String> seen) {
        String status = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_STATUS, "");
        if (!isReturnHistoryStatus(status)) {
            return;
        }
        GuardianHistoryEntry entry = new GuardianHistoryEntry();
        entry.status = status;
        entry.title = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_TITLE, "");
        entry.body = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_BODY, "");
        entry.destination = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_DESTINATION, "");
        entry.routeLink = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_LINK, "");
        entry.routePoints = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_POINTS, "");
        entry.latitude = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_LATITUDE, "");
        entry.longitude = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_LONGITUDE, "");
        entry.expectedMinutes = prefs.getInt(SafeWayPrefs.LATEST_GUARDIAN_ALERT_EXPECTED_MINUTES, 0);
        entry.updatedAt = prefs.getLong(SafeWayPrefs.LATEST_GUARDIAN_ALERT_UPDATED_AT, 0L);
        entry.dateKey = entry.updatedAt > 0L ? dayFormat.format(new Date(entry.updatedAt)) : dayFormat.format(new Date());
        addHistoryEntry(entries, seen, entry);
    }

    private void addLocalReturnRecordEntries(List<GuardianHistoryEntry> entries, Set<String> seen) {
        if (db == null) {
            return;
        }
        for (AppDatabase.ReturnRecord record : db.getReturnRecords()) {
            GuardianHistoryEntry entry = new GuardianHistoryEntry();
            entry.sourceKey = "local-" + record.id;
            entry.status = STATUS_COMPLETED;
            entry.title = "귀가 완료";
            entry.body = record.startTime + " → " + record.endTime + " · 소요 " + record.durationMinutes + "분";
            entry.destination = record.routeDestination;
            entry.routeLink = record.routeLink;
            entry.routePoints = record.actualRoutePoints;
            entry.expectedMinutes = record.expectedMinutes;
            entry.durationMinutes = record.durationMinutes;
            entry.dateKey = record.createdDate == null || record.createdDate.trim().isEmpty()
                    ? dayFormat.format(new Date())
                    : record.createdDate.trim();
            addHistoryEntry(entries, seen, entry);
        }
    }

    private void addHistoryEntry(List<GuardianHistoryEntry> entries, Set<String> seen, GuardianHistoryEntry entry) {
        if (entry == null || !isReturnHistoryStatus(entry.status)) {
            return;
        }
        if (entry.dateKey == null || entry.dateKey.trim().isEmpty()) {
            entry.dateKey = entry.updatedAt > 0L ? dayFormat.format(new Date(entry.updatedAt)) : dayFormat.format(new Date());
        }
        String key = entry.uniqueKey();
        if (seen.contains(key)) {
            return;
        }
        seen.add(key);
        entries.add(entry);
    }

    private void renderCalendarMonth(List<GuardianHistoryEntry> entries) {
        calendarMonthText.setText(monthTitleFormat.format(visibleHistoryMonth.getTime()));
        calendarGrid.removeAllViews();
        addWeekdayRow();

        Calendar cursor = (Calendar) visibleHistoryMonth.clone();
        cursor.set(Calendar.DAY_OF_MONTH, 1);
        int firstDayOffset = cursor.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        cursor.add(Calendar.DATE, -firstDayOffset);
        int visibleMonth = visibleHistoryMonth.get(Calendar.MONTH);

        for (int week = 0; week < 6; week++) {
            LinearLayout weekRow = new LinearLayout(this);
            weekRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rowParams.setMargins(0, week == 0 ? dp(6) : dp(5), 0, 0);
            weekRow.setLayoutParams(rowParams);

            for (int day = 0; day < 7; day++) {
                String dateKey = dayFormat.format(cursor.getTime());
                int count = countEntriesForDate(entries, dateKey);
                boolean inMonth = cursor.get(Calendar.MONTH) == visibleMonth;
                weekRow.addView(createCalendarDayCell(cursor, dateKey, count, inMonth));
                cursor.add(Calendar.DATE, 1);
            }
            calendarGrid.addView(weekRow);
        }
    }

    private void addWeekdayRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"일", "월", "화", "수", "목", "금", "토"};
        for (int index = 0; index < labels.length; index++) {
            TextView day = new TextView(this);
            day.setText(labels[index]);
            day.setGravity(Gravity.CENTER);
            day.setTextSize(11);
            day.setTypeface(null, Typeface.BOLD);
            day.setTextColor(getColor(index == 0 ? R.color.safeway_danger : R.color.safeway_muted));
            row.addView(day, new LinearLayout.LayoutParams(0, dp(24), 1f));
        }
        calendarGrid.addView(row);
    }

    private View createCalendarDayCell(Calendar source, String dateKey, int count, boolean inMonth) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        boolean selected = dateKey.equals(selectedHistoryDate);
        cell.setBackgroundResource(selected ? R.drawable.bg_primary : count > 0 ? R.drawable.bg_teal_soft : R.drawable.bg_card);
        cell.setPadding(dp(2), dp(5), dp(2), dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(58), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        cell.setLayoutParams(params);

        TextView dayNumber = new TextView(this);
        dayNumber.setText(String.valueOf(source.get(Calendar.DAY_OF_MONTH)));
        dayNumber.setGravity(Gravity.CENTER);
        dayNumber.setTextSize(14);
        dayNumber.setTypeface(null, Typeface.BOLD);
        int dayColor = selected
                ? R.color.white
                : !inMonth
                ? R.color.safeway_border
                : count > 0 ? R.color.safeway_teal : R.color.safeway_ink;
        dayNumber.setTextColor(getColor(dayColor));
        cell.addView(dayNumber, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView countView = new TextView(this);
        countView.setText(count > 0 ? count + "건" : "");
        countView.setGravity(Gravity.CENTER);
        countView.setTextSize(9);
        countView.setTypeface(null, Typeface.BOLD);
        countView.setTextColor(getColor(selected ? R.color.white : R.color.safeway_teal));
        cell.addView(countView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));

        cell.setOnClickListener(v -> {
            selectedHistoryDate = dateKey;
            if (!inMonth) {
                setVisibleMonthFromDateKey(dateKey);
            }
            renderHistoryCalendar();
        });
        return cell;
    }

    private void renderSelectedDateHistory(List<GuardianHistoryEntry> entries) {
        List<GuardianHistoryEntry> selected = new ArrayList<>();
        for (GuardianHistoryEntry entry : entries) {
            if (selectedHistoryDate.equals(entry.dateKey)) {
                selected.add(entry);
            }
        }
        selected.sort((first, second) -> Long.compare(second.sortMillis(), first.sortMillis()));

        int completed = 0;
        int active = 0;
        int issue = 0;
        for (GuardianHistoryEntry entry : selected) {
            if (STATUS_COMPLETED.equals(entry.status)) {
                completed++;
            } else if (STATUS_ACTIVE.equals(entry.status)) {
                active++;
            } else {
                issue++;
            }
        }
        selectedDateSummaryText.setText(buildSelectedDateSummary(selectedHistoryDate, selected.size(), completed, active, issue));

        timelineList.removeAllViews();
        if (selected.isEmpty()) {
            timelineList.addView(createInfoRow("귀가 기록 없음", formatDateHeading(selectedHistoryDate) + "에는 저장된 귀가 상태가 없습니다."));
            return;
        }
        for (GuardianHistoryEntry entry : selected) {
            timelineList.addView(createHistoryRow(entry));
        }
    }

    private View createHistoryRow(GuardianHistoryEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_record_row);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(params);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(header);

        TextView title = new TextView(this);
        title.setText(historyTitle(entry));
        title.setTextColor(getColor(R.color.safeway_ink));
        title.setTextSize(13);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText(historyBadge(entry));
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(10);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setTextColor(getColor(historyTextColor(entry)));
        badge.setBackgroundResource(historyBackground(entry));
        badge.setPadding(dp(10), 0, dp(10), 0);
        header.addView(badge, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)));

        TextView desc = new TextView(this);
        desc.setText(historyDescription(entry));
        desc.setTextColor(getColor(R.color.safeway_muted));
        desc.setTextSize(12);
        desc.setLineSpacing(dp(2), 1f);
        desc.setPadding(0, dp(8), 0, 0);
        row.addView(desc);

        if (entry.routeLink != null && !entry.routeLink.trim().isEmpty()) {
            TextView route = new TextView(this);
            route.setText("경로 열기");
            route.setTextColor(getColor(R.color.safeway_primary));
            route.setTextSize(11);
            route.setTypeface(null, Typeface.BOLD);
            route.setPadding(0, dp(9), 0, 0);
            route.setOnClickListener(v -> openHistoryRoute(entry));
            row.addView(route);
        }
        return row;
    }

    private String buildSelectedDateSummary(String dateKey, int total, int completed, int active, int issue) {
        String label = formatDateHeading(dateKey);
        if (total == 0) {
            return label + " · 귀가 기록 없음";
        }
        return label + " · 총 " + total + "건 · 완료 " + completed + " · 진행 " + active + " · 확인 필요 " + issue;
    }

    private String formatCalendarChip(String dateKey, int count) {
        Date date = parseDateKey(dateKey);
        String day = date == null ? dateKey : monthDayFormat.format(date);
        String week = date == null ? "" : weekdayFormat.format(date);
        return day + "\n" + week + "\n" + count + "건";
    }

    private String formatDateHeading(String dateKey) {
        Date date = parseDateKey(dateKey);
        if (date == null) {
            return dateKey;
        }
        return monthDayFormat.format(date) + " " + weekdayFormat.format(date);
    }

    private int countEntriesForDate(List<GuardianHistoryEntry> entries, String dateKey) {
        int count = 0;
        for (GuardianHistoryEntry entry : entries) {
            if (dateKey.equals(entry.dateKey)) {
                count++;
            }
        }
        return count;
    }

    private boolean isReturnHistoryStatus(String status) {
        return STATUS_ACTIVE.equals(status)
                || STATUS_COMPLETED.equals(status)
                || STATUS_DEVIATED.equals(status)
                || STATUS_DANGER.equals(status);
    }

    private String historyTitle(GuardianHistoryEntry entry) {
        if (STATUS_COMPLETED.equals(entry.status)) {
            if (entry.expectedMinutes > 0 && entry.durationMinutes > entry.expectedMinutes) {
                return "귀가 완료 · 예상보다 " + (entry.durationMinutes - entry.expectedMinutes) + "분 늦음";
            }
            return "귀가 완료";
        }
        if (STATUS_DEVIATED.equals(entry.status)) {
            return "경로 이탈 감지";
        }
        if (STATUS_DANGER.equals(entry.status)) {
            return "AI 위험 신호 감지";
        }
        return "안심귀가 진행";
    }

    private String historyBadge(GuardianHistoryEntry entry) {
        if (STATUS_COMPLETED.equals(entry.status)) {
            return "완료";
        }
        if (STATUS_DEVIATED.equals(entry.status)) {
            return "이탈";
        }
        if (STATUS_DANGER.equals(entry.status)) {
            return "위험";
        }
        return "진행";
    }

    private int historyBackground(GuardianHistoryEntry entry) {
        if (STATUS_COMPLETED.equals(entry.status)) {
            return R.drawable.bg_teal_soft;
        }
        if (STATUS_ACTIVE.equals(entry.status)) {
            return R.drawable.bg_primary_soft;
        }
        return R.drawable.bg_danger_soft;
    }

    private int historyTextColor(GuardianHistoryEntry entry) {
        if (STATUS_COMPLETED.equals(entry.status)) {
            return R.color.safeway_teal;
        }
        if (STATUS_ACTIVE.equals(entry.status)) {
            return R.color.safeway_primary;
        }
        return R.color.safeway_danger;
    }

    private String historyDescription(GuardianHistoryEntry entry) {
        StringBuilder builder = new StringBuilder();
        String time = entry.updatedAt > 0L ? shortTimeFormat.format(new Date(entry.updatedAt)) : "";
        if (!time.isEmpty()) {
            builder.append(time);
        }
        if (entry.body != null && !entry.body.trim().isEmpty()) {
            appendLine(builder, entry.body.trim());
        }
        if (entry.destination != null && !entry.destination.trim().isEmpty()) {
            appendLine(builder, "도착지: " + entry.destination.trim());
        }
        if (entry.expectedMinutes > 0) {
            String detail = "예상 " + entry.expectedMinutes + "분";
            if (entry.durationMinutes > 0) {
                detail += " · 실제 " + entry.durationMinutes + "분";
            }
            appendLine(builder, detail);
        } else if (entry.durationMinutes > 0) {
            appendLine(builder, "실제 소요 " + entry.durationMinutes + "분");
        }
        if (entry.offRouteMeters > 0) {
            appendLine(builder, "경로에서 약 " + entry.offRouteMeters + "m 벗어남");
        }
        return builder.length() == 0 ? "상세 정보 없음" : builder.toString();
    }

    private void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) {
            builder.append("\n");
        }
        builder.append(line);
    }

    private void openHistoryRoute(GuardianHistoryEntry entry) {
        if (entry.routeLink == null || entry.routeLink.trim().isEmpty()) {
            Toast.makeText(this, "저장된 경로 링크가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = Uri.parse(entry.routeLink.trim());
        if (openKakaoMapAppUri(uri)) {
            return;
        }
        openExternalUri(toHttpsFallbackUri(uri), "카카오맵 또는 브라우저 앱을 열 수 없습니다.");
    }

    private Date parseDateKey(String dateKey) {
        try {
            return dayFormat.parse(dateKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    private long dateKeySortMillis(String dateKey) {
        Date date = parseDateKey(dateKey);
        return date == null ? 0L : date.getTime();
    }

    private void renderMap() {
        if (kakaoMap == null) {
            return;
        }
        kakaoMap.getLabelManager().getLayer().removeAll();
        kakaoMap.getRouteLineManager().getLayer().removeAll();

        List<LatLng> boundsPoints = new ArrayList<>();
        if (currentLatLng != null) {
            addMarker(currentLatLng, "마지막 위치", true);
            boundsPoints.add(currentLatLng);
        }
        if (destinationLatLng != null) {
            addMarker(destinationLatLng, "도착지", false);
            boundsPoints.add(destinationLatLng);
        }
        if (guardianRoutePoints != null && guardianRoutePoints.size() >= 2) {
            addRouteLine(guardianRoutePoints);
            boundsPoints.addAll(guardianRoutePoints);
        } else if (currentLatLng != null && destinationLatLng != null) {
            List<LatLng> fallbackPoints = new ArrayList<>();
            fallbackPoints.add(currentLatLng);
            fallbackPoints.add(destinationLatLng);
            addRouteLine(fallbackPoints);
        }

        if (boundsPoints.size() >= 2) {
            mapView.post(() -> kakaoMap.moveCamera(
                    CameraUpdateFactory.fitMapPoints(boundsPoints.toArray(new LatLng[0]), dp(64), 16),
                    CameraAnimation.from(500)
            ));
        } else if (currentLatLng != null) {
            kakaoMap.moveCamera(CameraUpdateFactory.newCenterPosition(currentLatLng, 15), CameraAnimation.from(500));
        } else {
            kakaoMap.moveCamera(CameraUpdateFactory.newCenterPosition(DEFAULT_CAMERA, 12));
        }
    }

    private void addMarker(LatLng position, String label, boolean currentLocation) {
        LabelStyles styles = currentLocation ? currentMarkerStyles : destinationMarkerStyles;
        if (styles == null) {
            int icon = currentLocation ? R.drawable.ic_map_marker_teal : R.drawable.ic_map_marker_primary;
            styles = KakaoMarkerStyles.addMarkerStyles(this, kakaoMap, icon, 22, Color.parseColor("#172126"));
            if (currentLocation) {
                currentMarkerStyles = styles;
            } else {
                destinationMarkerStyles = styles;
            }
        }
        kakaoMap.getLabelManager().getLayer().addLabel(
                LabelOptions.from(position)
                        .setStyles(styles)
                        .setTexts(new LabelTextBuilder().setTexts(label))
        );
    }

    private void addRouteLine(List<LatLng> points) {
        if (points == null || points.size() < 2) {
            return;
        }
        RouteLineLayer layer = kakaoMap.getRouteLineManager().getLayer();
        int color = ContextCompat.getColor(this, R.color.safeway_primary);
        RouteLineStylesSet stylesSet = RouteLineStylesSet.from(
                RouteLineStyles.from(RouteLineStyle.from(dp(5), color))
        );
        RouteLineSegment segment = RouteLineSegment.from(points).setStyles(stylesSet.getStyles(0));
        layer.addRouteLine(RouteLineOptions.from(segment).setStylesSet(stylesSet));
    }

    private void applyStatus(String text, int background, int textColor) {
        statusBadge.setText(text);
        statusBadge.setBackgroundResource(background);
        statusBadge.setTextColor(ContextCompat.getColor(this, textColor));
    }

    private View createInfoRow(String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(params);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(this, R.color.safeway_ink));
        titleView.setTextSize(13);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(titleView);

        TextView descView = new TextView(this);
        descView.setText(desc);
        descView.setTextColor(ContextCompat.getColor(this, R.color.safeway_muted));
        descView.setTextSize(11);
        descView.setPadding(0, dp(5), 0, 0);
        row.addView(descView);
        return row;
    }

    private void updateProgressWidth(int percent) {
        ViewGroup parent = (ViewGroup) progressFill.getParent();
        parent.post(() -> {
            int parentWidth = parent.getWidth();
            if (parentWidth <= 0) {
                return;
            }
            ViewGroup.LayoutParams params = progressFill.getLayoutParams();
            params.width = Math.max(dp(8), parentWidth * Math.max(0, Math.min(100, percent)) / 100);
            progressFill.setLayoutParams(params);
        });
    }

    private void openLink(String prefKey, String emptyMessage) {
        String link = prefs.getString(prefKey, "");
        if (link == null || link.trim().isEmpty()) {
            Toast.makeText(this, emptyMessage, Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = Uri.parse(link);
        if (openKakaoMapAppUri(uri)) {
            return;
        }
        openExternalUri(toHttpsFallbackUri(uri), "카카오맵 또는 브라우저 앱을 열 수 없습니다.");
    }

    private boolean openKakaoMapAppUri(Uri sourceUri) {
        Uri kakaoMapUri = toKakaoMapAppUri(sourceUri);
        if (kakaoMapUri == null) {
            return false;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, kakaoMapUri);
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    private Uri toKakaoMapAppUri(Uri sourceUri) {
        if (sourceUri == null) {
            return null;
        }
        String scheme = sourceUri.getScheme();
        if ("kakaomap".equalsIgnoreCase(scheme)) {
            return sourceUri;
        }

        String path = sourceUri.getPath();
        if (path == null) {
            return null;
        }
        if (path.contains("/scheme/route")) {
            String sp = sourceUri.getQueryParameter("sp");
            String ep = sourceUri.getQueryParameter("ep");
            if (isEmpty(sp) || isEmpty(ep)) {
                return null;
            }
            return Uri.parse("kakaomap://route").buildUpon()
                    .appendQueryParameter("sp", sp)
                    .appendQueryParameter("ep", ep)
                    .appendQueryParameter("by", normalizeRouteType(sourceUri.getQueryParameter("by")))
                    .build();
        }
        if (path.contains("/scheme/look")) {
            String point = sourceUri.getQueryParameter("p");
            if (isEmpty(point)) {
                return null;
            }
            return Uri.parse("kakaomap://look").buildUpon()
                    .appendQueryParameter("p", point)
                    .build();
        }
        if (path.contains("/scheme/search")) {
            String query = sourceUri.getQueryParameter("q");
            if (isEmpty(query)) {
                return null;
            }
            return Uri.parse("kakaomap://search").buildUpon()
                    .appendQueryParameter("q", query)
                    .build();
        }
        return null;
    }

    private Uri toHttpsFallbackUri(Uri uri) {
        if (uri == null) {
            return Uri.parse("https://map.kakao.com");
        }
        String value = uri.toString();
        if (value.startsWith("http://m.map.kakao.com")) {
            return Uri.parse("https://m.map.kakao.com" + value.substring("http://m.map.kakao.com".length()));
        }
        return uri;
    }

    private String normalizeRouteType(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "FOOT";
        }
        String normalized = value.trim().toUpperCase(Locale.US);
        if ("FOOT".equals(normalized) || "CAR".equals(normalized) || "PUBLICTRANSIT".equals(normalized)) {
            return normalized;
        }
        if ("WALK".equals(normalized) || "WALKING".equals(normalized)) {
            return "FOOT";
        }
        return "FOOT";
    }

    private void openExternalUri(Uri uri, String errorMessage) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean hasAnyLink() {
        String routeLink = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_LINK, "");
        String mapsLink = prefs.getString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_MAPS_LINK, "");
        return (routeLink != null && !routeLink.trim().isEmpty())
                || (mapsLink != null && !mapsLink.trim().isEmpty());
    }

    private LatLng parseDestinationFromRouteLink(String routeLink) {
        if (routeLink == null || routeLink.trim().isEmpty()) {
            return null;
        }
        try {
            Uri uri = Uri.parse(routeLink);
            String ep = uri.getQueryParameter("ep");
            if (ep == null || ep.trim().isEmpty()) {
                return null;
            }
            String[] parts = ep.split(",");
            if (parts.length != 2) {
                return null;
            }
            return LatLng.from(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private LatLng parseLatLng(String latitude, String longitude) {
        if (latitude == null || longitude == null || latitude.trim().isEmpty() || longitude.trim().isEmpty()) {
            return null;
        }
        try {
            return LatLng.from(Double.parseDouble(latitude.trim()), Double.parseDouble(longitude.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<LatLng> parseRoutePoints(String routePoints) {
        List<LatLng> points = new ArrayList<>();
        if (routePoints == null || routePoints.trim().isEmpty()) {
            return points;
        }
        String[] pairs = routePoints.split(";");
        for (String pair : pairs) {
            if (pair == null || pair.trim().isEmpty()) {
                continue;
            }
            String[] coordinates = pair.split(",");
            if (coordinates.length != 2) {
                continue;
            }
            LatLng point = parseLatLng(coordinates[0], coordinates[1]);
            if (point != null) {
                points.add(point);
            }
        }
        return points;
    }

    private String formatRelativeTime(long millis) {
        long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000L);
        if (seconds < 60) {
            return "방금 전";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "분 전";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "시간 전";
        }
        return (hours / 24) + "일 전";
    }

    private String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static class GuardianHistoryEntry {
        String sourceKey = "";
        String status = "";
        String title = "";
        String body = "";
        String destination = "";
        String routeLink = "";
        String routePoints = "";
        String latitude = "";
        String longitude = "";
        int expectedMinutes;
        int durationMinutes;
        int offRouteMeters;
        long updatedAt;
        String dateKey = "";

        static GuardianHistoryEntry fromGuardianJson(JSONObject item, SimpleDateFormat dayFormat) {
            if (item == null) {
                return null;
            }
            GuardianHistoryEntry entry = new GuardianHistoryEntry();
            entry.status = item.optString("status", "");
            entry.title = item.optString("title", "");
            entry.body = item.optString("body", "");
            entry.destination = item.optString("destination", "");
            entry.routeLink = item.optString("routeLink", "");
            entry.routePoints = item.optString("routePoints", "");
            entry.latitude = item.optString("latitude", "");
            entry.longitude = item.optString("longitude", "");
            entry.expectedMinutes = parsePositiveInt(item.optString("expectedMinutes", ""));
            entry.durationMinutes = parsePositiveInt(item.optString("durationMinutes", ""));
            entry.offRouteMeters = parsePositiveInt(item.optString("offRouteMeters", ""));
            entry.updatedAt = item.optLong("updatedAt", 0L);
            entry.dateKey = entry.updatedAt > 0L ? dayFormat.format(new Date(entry.updatedAt)) : "";
            entry.sourceKey = "server-" + entry.status + "-" + entry.updatedAt + "-" + entry.destination;
            return entry;
        }

        String uniqueKey() {
            if (sourceKey != null && !sourceKey.trim().isEmpty()) {
                return sourceKey;
            }
            return status + "|" + updatedAt + "|" + dateKey + "|" + destination + "|" + title;
        }

        long sortMillis() {
            return updatedAt;
        }

        private static int parsePositiveInt(String value) {
            if (value == null || value.trim().isEmpty()) {
                return 0;
            }
            try {
                return Math.max(0, Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
