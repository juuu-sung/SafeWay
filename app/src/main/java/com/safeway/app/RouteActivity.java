package com.safeway.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.kakao.vectormap.KakaoMap;
import com.kakao.vectormap.KakaoMapReadyCallback;
import com.kakao.vectormap.LatLng;
import com.kakao.vectormap.MapLifeCycleCallback;
import com.kakao.vectormap.MapView;
import com.kakao.vectormap.camera.CameraAnimation;
import com.kakao.vectormap.camera.CameraUpdate;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RouteActivity extends AppCompatActivity {
    public static final String EXTRA_START_FLOW = "com.safeway.app.EXTRA_START_FLOW";
    public static final String EXTRA_USE_HOME_DESTINATION = "com.safeway.app.EXTRA_USE_HOME_DESTINATION";

    private static final int REQUEST_ROUTE_LOCATION = 70;
    private static final LatLng DEFAULT_CAMERA = LatLng.from(37.5665, 126.9780);
    private static final float DANGER_MEMO_ROUTE_RADIUS_METERS = 120f;
    private static final float DANGER_MEMO_AVOID_OFFSET_METERS = 260f;
    private static final float WALKING_SPEED_METERS_PER_SECOND = 1.2f;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private AppDatabase db;
    private EditText destinationInput;
    private TextView routeStatusText;
    private TextView currentRouteText;
    private TextView pickMapCenterButton;
    private TextView startReturnFromRouteButton;
    private View mapCenterTarget;
    private MapView kakaoMapView;
    private KakaoMap kakaoMap;
    private LabelStyles currentMarkerStyles;
    private LabelStyles destinationMarkerStyles;
    private LabelStyles dangerMarkerStyles;
    private LatLng currentLatLng;
    private LatLng destinationLatLng;
    private List<LatLng> routePoints;
    private boolean mapStarted;
    private boolean pendingCalculateRoute;
    private boolean pendingFocusCurrentLocation;
    private boolean pendingOpenWalkingGuidance;
    private boolean mapSelectionMode;
    private boolean startFlow;
    private boolean useHomeDestination;
    private boolean requestedHomeMissing;
    private boolean liveLocationUpdatesActive;
    private LocationListener liveLocationListener;

    private static class RouteResponse {
        final List<LatLng> points;
        final int distanceMeters;
        final String duration;
        final boolean fallback;
        final String routeMode;
        final JSONArray guides;

        RouteResponse(List<LatLng> points, int distanceMeters, String duration,
                      boolean fallback, String routeMode, JSONArray guides) {
            this.points = points;
            this.distanceMeters = distanceMeters;
            this.duration = duration;
            this.fallback = fallback;
            this.routeMode = routeMode;
            this.guides = guides;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SafeWayTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route);
        BottomNav.bind(this, RouteActivity.class);

        prefs = SafeWayPrefs.get(this);
        db = new AppDatabase(this);
        db.seedDefaultMemosIfEmpty();
        destinationInput = findViewById(R.id.destinationInput);
        routeStatusText = findViewById(R.id.routeStatusText);
        currentRouteText = findViewById(R.id.currentRouteText);
        pickMapCenterButton = findViewById(R.id.pickMapCenterButton);
        startReturnFromRouteButton = findViewById(R.id.startReturnFromRouteButton);
        mapCenterTarget = findViewById(R.id.mapCenterTarget);
        kakaoMapView = findViewById(R.id.routeMap);
        mapCenterTarget.setVisibility(View.GONE);
        startFlow = getIntent().getBooleanExtra(EXTRA_START_FLOW, false);
        useHomeDestination = getIntent().getBooleanExtra(EXTRA_USE_HOME_DESTINATION, false);

        destinationInput.setText(prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, ""));
        destinationLatLng = getStoredDestinationLatLng();
        routePoints = getStoredRoutePoints();
        if (useHomeDestination && !applyHomeDestination(false)) {
            requestedHomeMissing = true;
            destinationInput.setText("");
            destinationLatLng = null;
            routePoints = null;
        }
        updateRouteText();
        startKakaoMap();

        findViewById(R.id.searchPlaceButton).setOnClickListener(v -> searchDestination());
        findViewById(R.id.openRouteButton).setOnClickListener(v -> calculateRouteInApp());
        findViewById(R.id.openKakaoNaviButton).setOnClickListener(v -> openKakaoWalkingGuidance());
        findViewById(R.id.openCurrentLocationButton).setOnClickListener(v -> focusCurrentLocation());
        findViewById(R.id.openExternalMapButton).setOnClickListener(v -> openExternalRouteMap());
        pickMapCenterButton.setOnClickListener(v -> toggleMapSelectionMode());
        findViewById(R.id.loadHomeButton).setOnClickListener(v -> loadHomeDestination());
        startReturnFromRouteButton.setOnClickListener(v -> finishRouteSetupAndStart());
    }

    private void startKakaoMap() {
        if (!KakaoMapConfig.ensureInitialized(this)) {
            Toast.makeText(this, "local.properties에 KAKAO_NATIVE_APP_KEY를 먼저 설정해야 합니다.", Toast.LENGTH_LONG).show();
            return;
        }
        mapStarted = true;
        kakaoMapView.start(new MapLifeCycleCallback() {
            @Override
            public void onMapDestroy() {
            }

            @Override
            public void onMapError(Exception error) {
                Toast.makeText(RouteActivity.this, "카카오 지도 오류: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, new KakaoMapReadyCallback() {
            @Override
            public LatLng getPosition() {
                if (destinationLatLng != null) {
                    return destinationLatLng;
                }
                if (currentLatLng != null) {
                    return currentLatLng;
                }
                return DEFAULT_CAMERA;
            }

            @Override
            public int getZoomLevel() {
                return destinationLatLng == null && currentLatLng == null ? 12 : 15;
            }

            @Override
            public void onMapReady(@NonNull KakaoMap map) {
                kakaoMap = map;
                kakaoMap.setOnMapClickListener((clickedMap, position, screenPoint, poi) ->
                        selectDestinationFromMapTap(position));
                kakaoMap.setOnTerrainLongClickListener((clickedMap, position, screenPoint) ->
                        selectDestinationFromMapTap(position));
                refreshCurrentLocation();
                renderMap();
                startLiveLocationUpdatesIfNeeded();
            }
        });
    }

    private void searchDestination() {
        String query = destinationInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "검색할 도착지 주소나 장소명을 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        searchTypedAddress(query);
    }

    private void searchTypedAddress(String query) {
        String restApiKey = KakaoMapConfig.restApiKey();
        if (restApiKey.isEmpty()) {
            Toast.makeText(this, "local.properties에 KAKAO_REST_API_KEY를 먼저 설정해야 합니다.", Toast.LENGTH_LONG).show();
            return;
        }

        routeStatusText.setText("카카오 Local API로 도착지를 검색하는 중입니다.");
        LatLng center = currentLatLng != null ? currentLatLng : (destinationLatLng != null ? destinationLatLng : DEFAULT_CAMERA);
        executor.execute(() -> {
            try {
                List<KakaoLocalSearch.Result> results = KakaoLocalSearch.search(restApiKey, query, center);
                mainHandler.post(() -> showDestinationSearchResults(results));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    updateRouteText();
                    Toast.makeText(this, "주소 검색 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showDestinationSearchResults(List<KakaoLocalSearch.Result> results) {
        if (results == null || results.isEmpty()) {
            updateRouteText();
            Toast.makeText(this, "주소나 장소를 찾지 못했습니다. 검색어를 더 구체적으로 입력해주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (results.size() == 1) {
            KakaoLocalSearch.Result result = results.get(0);
            selectDestination(result.latLng, result.label, true);
            return;
        }

        String[] labels = new String[results.size()];
        for (int i = 0; i < results.size(); i++) {
            labels[i] = results.get(i).display;
        }
        new AlertDialog.Builder(this)
                .setTitle("도착지 선택")
                .setItems(labels, (dialog, which) -> {
                    KakaoLocalSearch.Result result = results.get(which);
                    selectDestination(result.latLng, result.label, true);
                })
                .show();
        updateRouteText();
    }

    private void loadHomeDestination() {
        if (applyHomeDestination(true)) {
            updateRouteText();
        }
    }

    private boolean applyHomeDestination(boolean showToast) {
        LatLng homeLatLng = getStoredHomeLatLng();
        String home = prefs.getString(SafeWayPrefs.HOME_DESTINATION, "");
        if (home == null || home.trim().isEmpty() || homeLatLng == null) {
            if (showToast) {
                Toast.makeText(this, "저장된 집 주소가 없습니다.", Toast.LENGTH_SHORT).show();
            }
            return false;
        }

        requestedHomeMissing = false;
        applyDestination(homeLatLng, home, true, showToast);
        return true;
    }

    private void calculateRouteInApp() {
        String destination = destinationInput.getText().toString().trim();
        if (destination.isEmpty()) {
            Toast.makeText(this, "도착지를 먼저 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(SafeWayPrefs.ROUTE_DESTINATION, destination).apply();

        if (!hasLocationPermission()) {
            pendingCalculateRoute = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_ROUTE_LOCATION);
            return;
        }

        Location location = getBestLastKnownLocation();
        if (location == null) {
            Toast.makeText(this, "현재 위치를 아직 확인할 수 없습니다. 위치 권한과 GPS를 확인해주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        currentLatLng = LatLng.from(location.getLatitude(), location.getLongitude());

        if (destinationLatLng == null) {
            destinationLatLng = getStoredDestinationLatLng();
        }
        if (destinationLatLng == null) {
            Toast.makeText(this, "앱 안 경로선은 주소 검색이나 지도 선택으로 도착지 좌표를 정해야 합니다.", Toast.LENGTH_LONG).show();
            renderMap();
            return;
        }

        String serverUrl = normalizeBaseUrl(prefs.getString(SafeWayPrefs.PUSH_SERVER_URL, ""));
        if (serverUrl.isEmpty()) {
            showLocalFallbackRoute(
                    currentLatLng,
                    destinationLatLng,
                    location,
                    destination,
                    "서버 주소가 없어 직선 참고선을 표시했습니다."
            );
            return;
        }

        routeStatusText.setText("현재 위치에서 도착지까지 카카오 경로를 계산하는 중입니다.");
        renderMap();
        requestRouteFromServer(serverUrl, currentLatLng, destinationLatLng, location, destination);
    }

    private void requestRouteFromServer(String serverUrl, LatLng origin, LatLng destination, Location location, String destinationText) {
        executor.execute(() -> {
            try {
                RouteResponse selectedRoute = fetchRouteFromServer(serverUrl, origin, destination, null);
                if (selectedRoute.points.size() < 2) {
                    mainHandler.post(() -> showLocalFallbackRoute(
                            origin,
                            destination,
                            location,
                            destinationText,
                            "경로선 응답이 없어 직선 참고선을 표시했습니다."
                    ));
                    return;
                }

                boolean avoidanceApplied = false;
                List<AppDatabase.DangerMemo> dangerMemos = findDangerMemosNearRoute(selectedRoute.points);
                if (!selectedRoute.fallback && !dangerMemos.isEmpty()) {
                    mainHandler.post(() -> routeStatusText.setText("위험 메모를 피해 경로를 다시 계산하는 중입니다."));
                    RouteResponse avoidedRoute = findAvoidanceRoute(serverUrl, origin, destination, selectedRoute, dangerMemos);
                    if (avoidedRoute != null) {
                        selectedRoute = avoidedRoute;
                        avoidanceApplied = true;
                    }
                }

                final RouteResponse route = selectedRoute;
                final boolean avoided = avoidanceApplied;
                final List<AppDatabase.DangerMemo> remainingDangerMemos = findDangerMemosNearRoute(route.points);
                mainHandler.post(() -> {
                    routePoints = route.points;
                    String routeLink = PushAlertClient.buildDirectionsLink(location, formatLatLng(destination));
                    int expectedMinutes = parseDurationMinutes(route.duration);
                    prefs.edit()
                            .putString(SafeWayPrefs.ROUTE_LAST_LINK, routeLink)
                            .putString(SafeWayPrefs.ROUTE_LAST_POINTS, serializeRoutePoints(route.points))
                            .putString(SafeWayPrefs.ROUTE_LAST_GUIDES, route.guides == null ? "" : route.guides.toString())
                            .putInt(SafeWayPrefs.ROUTE_EXPECTED_MINUTES, expectedMinutes)
                            .apply();
                    renderMap();
                    updateRouteResultText(destinationText, route.distanceMeters, route.duration, route.fallback, route.routeMode);
                    if (avoided) {
                        routeStatusText.setText(remainingDangerMemos.isEmpty()
                                ? "위험 메모를 피해 경로를 계산했습니다."
                                : "위험 메모를 최대한 피해 경로를 계산했습니다.");
                        currentRouteText.append("\n위험 메모를 피해 우회 경유지를 반영했습니다.");
                    }
                    appendDangerMemoWarning(remainingDangerMemos);
                    if (prefs.getBoolean(SafeWayPrefs.RETURNING, false)) {
                        PushAlertClient.sendReturnStarted(this, location, null);
                    }
                    Toast.makeText(
                            this,
                            route.fallback ? "실제 길찾기 경로를 찾지 못해 직선 참고선을 표시했습니다." : "앱 안 지도에 귀가 경로를 표시했습니다.",
                            Toast.LENGTH_SHORT
                    ).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> showLocalFallbackRoute(
                        origin,
                        destination,
                        location,
                        destinationText,
                        "서버 연결이 안 되어 직선 참고선을 표시했습니다."
                ));
            }
        });
    }

    private RouteResponse fetchRouteFromServer(String serverUrl, LatLng origin, LatLng destination, List<LatLng> waypoints) throws Exception {
        HttpURLConnection connection = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("origin", toLatLngJson(origin));
            payload.put("destination", toLatLngJson(destination));
            if (waypoints != null && !waypoints.isEmpty()) {
                JSONArray waypointArray = new JSONArray();
                for (LatLng waypoint : waypoints) {
                    waypointArray.put(toLatLngJson(waypoint));
                }
                payload.put("waypoints", waypointArray);
            }

            URL url = new URL(serverUrl + "/routes/compute");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(16000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            JSONObject json = new JSONObject(response);
            if (status < 200 || status >= 300 || !json.optBoolean("ok", false)) {
                throw new IllegalStateException(json.optString("error", "Kakao Mobility API 응답 오류") + " (" + status + ")");
            }

            List<LatLng> decodedPoints = decodePolyline(json.optString("encodedPolyline", ""));
            int distanceMeters = json.optInt("distanceMeters", 0);
            String duration = json.optString("duration", "");
            boolean fallback = json.optBoolean("fallback", false);
            String routeMode = json.optString("routeMode", "");
            JSONArray guides = json.optJSONArray("guides");
            if ("driving".equals(routeMode)) {
                duration = estimateWalkingDuration(distanceMeters);
                routeMode = "walking_estimate";
                guides = null;
            }
            return new RouteResponse(decodedPoints, distanceMeters, duration, fallback, routeMode, guides);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private RouteResponse findAvoidanceRoute(
            String serverUrl,
            LatLng origin,
            LatLng destination,
            RouteResponse originalRoute,
            List<AppDatabase.DangerMemo> dangerMemos
    ) {
        List<List<LatLng>> candidates = buildAvoidanceWaypointCandidates(origin, destination, dangerMemos);
        if (candidates.isEmpty()) {
            return null;
        }

        RouteResponse bestRoute = null;
        int originalDangerCount = dangerMemos.size();
        int bestDangerCount = originalDangerCount;
        int bestDistance = originalRoute.distanceMeters;
        for (List<LatLng> candidate : candidates) {
            try {
                RouteResponse candidateRoute = fetchRouteFromServer(serverUrl, origin, destination, candidate);
                if (candidateRoute == null || candidateRoute.points.size() < 2 || candidateRoute.fallback) {
                    continue;
                }
                int dangerCount = findDangerMemosNearRoute(candidateRoute.points).size();
                boolean safer = dangerCount < bestDangerCount;
                boolean sameSafetyButShorter = bestRoute != null
                        && dangerCount == bestDangerCount
                        && candidateRoute.distanceMeters < bestDistance;
                if (safer || sameSafetyButShorter) {
                    bestRoute = candidateRoute;
                    bestDangerCount = dangerCount;
                    bestDistance = candidateRoute.distanceMeters;
                }
            } catch (Exception ignored) {
                // Try the next candidate; the original route is still usable.
            }
        }
        return bestRoute;
    }

    private List<List<LatLng>> buildAvoidanceWaypointCandidates(
            LatLng origin,
            LatLng destination,
            List<AppDatabase.DangerMemo> dangerMemos
    ) {
        List<List<LatLng>> candidates = new ArrayList<>();
        if (origin == null || destination == null || dangerMemos == null || dangerMemos.isEmpty()) {
            return candidates;
        }
        LatLng dangerPoint = parseMemoLatLng(dangerMemos.get(0));
        if (dangerPoint == null) {
            return candidates;
        }

        double referenceLat = Math.toRadians(dangerPoint.latitude);
        double metersPerDegreeLat = 111320.0;
        double metersPerDegreeLng = Math.max(1.0, 111320.0 * Math.cos(referenceLat));
        double originX = origin.longitude * metersPerDegreeLng;
        double originY = origin.latitude * metersPerDegreeLat;
        double destinationX = destination.longitude * metersPerDegreeLng;
        double destinationY = destination.latitude * metersPerDegreeLat;
        double deltaX = destinationX - originX;
        double deltaY = destinationY - originY;
        double length = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        if (length <= 0.0) {
            return candidates;
        }

        double perpendicularX = -deltaY / length;
        double perpendicularY = deltaX / length;
        candidates.add(singleWaypoint(
                dangerPoint.latitude + (perpendicularY * DANGER_MEMO_AVOID_OFFSET_METERS) / metersPerDegreeLat,
                dangerPoint.longitude + (perpendicularX * DANGER_MEMO_AVOID_OFFSET_METERS) / metersPerDegreeLng
        ));
        candidates.add(singleWaypoint(
                dangerPoint.latitude - (perpendicularY * DANGER_MEMO_AVOID_OFFSET_METERS) / metersPerDegreeLat,
                dangerPoint.longitude - (perpendicularX * DANGER_MEMO_AVOID_OFFSET_METERS) / metersPerDegreeLng
        ));
        return candidates;
    }

    private List<LatLng> singleWaypoint(double latitude, double longitude) {
        List<LatLng> waypoints = new ArrayList<>();
        waypoints.add(LatLng.from(latitude, longitude));
        return waypoints;
    }

    private void showLocalFallbackRoute(
            LatLng origin,
            LatLng destination,
            Location location,
            String destinationText,
            String toastMessage
    ) {
        List<LatLng> fallbackPoints = new ArrayList<>();
        fallbackPoints.add(origin);
        fallbackPoints.add(destination);
        routePoints = fallbackPoints;

        int distanceMeters = estimateDistanceMeters(origin, destination);
        String duration = estimateWalkingDuration(distanceMeters);
        String routeLink = PushAlertClient.buildDirectionsLink(location, formatLatLng(destination));
        int expectedMinutes = parseDurationMinutes(duration);
        prefs.edit()
                .putString(SafeWayPrefs.ROUTE_LAST_LINK, routeLink)
                .putString(SafeWayPrefs.ROUTE_LAST_POINTS, serializeRoutePoints(fallbackPoints))
                .remove(SafeWayPrefs.ROUTE_LAST_GUIDES)
                .putInt(SafeWayPrefs.ROUTE_EXPECTED_MINUTES, expectedMinutes)
                .apply();

        renderMap();
        updateRouteResultText(destinationText, distanceMeters, duration, true, "line");
        applyDangerMemoWarningForRoute(fallbackPoints);
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show();
    }

    private int estimateDistanceMeters(LatLng origin, LatLng destination) {
        float[] results = new float[1];
        Location.distanceBetween(
                origin.latitude,
                origin.longitude,
                destination.latitude,
                destination.longitude,
                results
        );
        return Math.max(0, Math.round(results[0]));
    }

    private void focusCurrentLocation() {
        if (!hasLocationPermission()) {
            pendingFocusCurrentLocation = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_ROUTE_LOCATION);
            return;
        }
        refreshCurrentLocation();
        if (currentLatLng == null) {
            Toast.makeText(this, "현재 위치를 아직 확인할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        renderMap();
        moveCameraTo(currentLatLng, 16, true);
    }

    private void openExternalRouteMap() {
        String destination = destinationInput.getText().toString().trim();
        if (destination.isEmpty()) {
            Toast.makeText(this, "도착지를 먼저 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(SafeWayPrefs.ROUTE_DESTINATION, destination).apply();

        Location location = hasLocationPermission() ? getBestLastKnownLocation() : null;
        String destinationForMap = destinationLatLng == null ? destination : formatLatLng(destinationLatLng);
        String routeLink = PushAlertClient.buildDirectionsLink(location, destinationForMap);
        prefs.edit().putString(SafeWayPrefs.ROUTE_LAST_LINK, routeLink).apply();
        updateRouteText();
        if (prefs.getBoolean(SafeWayPrefs.RETURNING, false)) {
            PushAlertClient.sendReturnStarted(this, location, null);
        }
        openKakaoRouteUriWithFallback(location, destinationLatLng, routeLink);
    }

    private void openKakaoWalkingGuidance() {
        String destination = destinationInput.getText().toString().trim();
        if (destination.isEmpty()) {
            Toast.makeText(this, "도착지를 먼저 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(SafeWayPrefs.ROUTE_DESTINATION, destination).apply();

        if (!hasLocationPermission()) {
            pendingOpenWalkingGuidance = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_ROUTE_LOCATION);
            return;
        }

        Location location = getBestLastKnownLocation();
        if (location == null) {
            Toast.makeText(this, "현재 위치를 아직 확인할 수 없습니다. 위치 권한과 GPS를 확인해주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        currentLatLng = LatLng.from(location.getLatitude(), location.getLongitude());

        if (destinationLatLng == null) {
            destinationLatLng = getStoredDestinationLatLng();
        }
        if (destinationLatLng == null) {
            Toast.makeText(this, "카카오 장소 검색이나 지도 선택으로 도착지 좌표를 먼저 정해주세요.", Toast.LENGTH_LONG).show();
            renderMap();
            return;
        }

        String routeLink = PushAlertClient.buildDirectionsLink(location, formatLatLng(destinationLatLng));
        prefs.edit().putString(SafeWayPrefs.ROUTE_LAST_LINK, routeLink).apply();
        updateRouteText();
        if (prefs.getBoolean(SafeWayPrefs.RETURNING, false)) {
            PushAlertClient.sendReturnStarted(this, location, null);
        }
        openKakaoRouteUriWithFallback(location, destinationLatLng, routeLink);
    }

    private void toggleMapSelectionMode() {
        if (kakaoMap == null) {
            Toast.makeText(this, "지도가 준비되는 중입니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        setMapSelectionMode(!mapSelectionMode, true);
    }

    private void selectDestinationFromMapTap(LatLng position) {
        if (!mapSelectionMode || position == null) {
            return;
        }
        setMapSelectionMode(false, false);
        selectDestination(position, mapPickedLabel("지도 선택 위치", position), true);
    }

    private void setMapSelectionMode(boolean enabled, boolean refreshStatus) {
        mapSelectionMode = enabled;
        mapCenterTarget.setVisibility(View.GONE);
        pickMapCenterButton.setText(enabled ? "선택 취소" : "지도에서 선택");
        if (enabled) {
            routeStatusText.setText("지도에서 원하는 위치를 한 번 탭하면 도착지로 설정됩니다.");
            Toast.makeText(this, "지도에서 원하는 위치를 탭해주세요.", Toast.LENGTH_SHORT).show();
        } else if (refreshStatus) {
            updateRouteText();
        }
    }

    private void finishRouteSetupAndStart() {
        if (prefs.getBoolean(SafeWayPrefs.RETURNING, false)) {
            Toast.makeText(this, "이미 귀가가 진행 중입니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String destination = destinationInput.getText().toString().trim();
        if (destination.isEmpty()) {
            Toast.makeText(this, "안심귀가를 시작하기 전에 도착지를 선택해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (destinationLatLng == null) {
            destinationLatLng = getStoredDestinationLatLng();
        }
        if (destinationLatLng == null) {
            Toast.makeText(this, "카카오 장소 검색이나 지도 선택으로 도착지를 선택한 뒤 시작해주세요.", Toast.LENGTH_LONG).show();
            return;
        }

        Location location = hasLocationPermission() ? getBestLastKnownLocation() : null;
        String routeLink = prefs.getString(SafeWayPrefs.ROUTE_LAST_LINK, "");
        if (routeLink == null || routeLink.trim().isEmpty()) {
            Toast.makeText(this, "경로 계산을 먼저 눌러 예상 시간을 확인해주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        int expectedMinutes = prefs.getInt(SafeWayPrefs.ROUTE_EXPECTED_MINUTES, 0);
        if (expectedMinutes <= 0) {
            Toast.makeText(this, "경로 계산을 먼저 눌러 예상 시간을 확인해주세요.", Toast.LENGTH_LONG).show();
            return;
        }

        prefs.edit()
                .putString(SafeWayPrefs.ROUTE_DESTINATION, destination)
                .putString(SafeWayPrefs.ROUTE_DESTINATION_LAT, String.valueOf(destinationLatLng.latitude))
                .putString(SafeWayPrefs.ROUTE_DESTINATION_LNG, String.valueOf(destinationLatLng.longitude))
                .putString(SafeWayPrefs.ROUTE_LAST_LINK, routeLink)
                .putInt(SafeWayPrefs.EXPECTED_MINUTES, expectedMinutes)
                .apply();

        openMainAndStartReturn();
    }

    private void openMainAndStartReturn() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_AUTO_START_RETURN, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void refreshCurrentLocation() {
        if (!hasLocationPermission()) {
            currentLatLng = null;
            return;
        }
        Location location = getBestLastKnownLocation();
        if (location != null) {
            currentLatLng = LatLng.from(location.getLatitude(), location.getLongitude());
        }
    }

    private void startLiveLocationUpdatesIfNeeded() {
        if (liveLocationUpdatesActive || !prefs.getBoolean(SafeWayPrefs.RETURNING, false) || !hasLocationPermission()) {
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }

        String provider = getLiveLocationProvider(locationManager);
        if (provider == null) {
            return;
        }

        liveLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                currentLatLng = LatLng.from(location.getLatitude(), location.getLongitude());
                ReturnTrackRecorder.record(RouteActivity.this, location);
                renderMap();
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }
        };

        try {
            locationManager.requestLocationUpdates(provider, 3000L, 5f, liveLocationListener, Looper.getMainLooper());
            liveLocationUpdatesActive = true;
        } catch (SecurityException ignored) {
            liveLocationListener = null;
        } catch (IllegalArgumentException ignored) {
            liveLocationListener = null;
        }
    }

    private void stopLiveLocationUpdates() {
        if (!liveLocationUpdatesActive || liveLocationListener == null) {
            liveLocationUpdatesActive = false;
            liveLocationListener = null;
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager != null) {
            locationManager.removeUpdates(liveLocationListener);
        }
        liveLocationUpdatesActive = false;
        liveLocationListener = null;
    }

    private String getLiveLocationProvider(LocationManager locationManager) {
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

    private void renderMap() {
        if (kakaoMap == null) {
            return;
        }
        kakaoMap.getLabelManager().getLayer().removeAll();
        kakaoMap.getRouteLineManager().getLayer().removeAll();

        List<LatLng> boundsPoints = new ArrayList<>();
        if (currentLatLng != null) {
            addMarker(currentLatLng, "현재 위치", true);
            boundsPoints.add(currentLatLng);
        }
        if (destinationLatLng != null) {
            addMarker(destinationLatLng, "도착지", false);
            boundsPoints.add(destinationLatLng);
        }
        if (routePoints != null && routePoints.size() >= 2) {
            addRouteLine(routePoints);
            boundsPoints.addAll(routePoints);
        }
        List<LatLng> dangerPoints = addDangerMemoMarkers();
        if (boundsPoints.isEmpty()) {
            boundsPoints.addAll(dangerPoints);
        }

        if (boundsPoints.size() >= 2) {
            moveCameraToBounds(boundsPoints);
            return;
        }
        LatLng target = currentLatLng != null ? currentLatLng : (destinationLatLng != null ? destinationLatLng : DEFAULT_CAMERA);
        moveCameraTo(target, currentLatLng == null && destinationLatLng == null ? 12 : 15, false);
    }

    private void addMarker(LatLng position, String title, boolean currentLocation) {
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
                        .setTexts(new LabelTextBuilder().setTexts(title))
        );
    }

    private List<LatLng> addDangerMemoMarkers() {
        List<LatLng> dangerPoints = new ArrayList<>();
        if (db == null) {
            return dangerPoints;
        }
        for (AppDatabase.DangerMemo memo : db.getDangerMemos()) {
            LatLng position = parseMemoLatLng(memo);
            if (position == null) {
                continue;
            }
            dangerPoints.add(position);
            addDangerMemoMarker(position, memo.placeName);
        }
        return dangerPoints;
    }

    private void addDangerMemoMarker(LatLng position, String title) {
        if (dangerMarkerStyles == null) {
            dangerMarkerStyles = KakaoMarkerStyles.addMarkerStyles(this, kakaoMap,
                    R.drawable.ic_map_marker_danger, 20, Color.parseColor("#172126"));
        }
        String label = title == null || title.trim().isEmpty() ? "위험 지역" : title.trim();
        kakaoMap.getLabelManager().getLayer().addLabel(
                LabelOptions.from(position)
                        .setStyles(dangerMarkerStyles)
                        .setTexts(new LabelTextBuilder().setTexts(label))
        );
    }

    private void addRouteLine(List<LatLng> points) {
        RouteLineLayer layer = kakaoMap.getRouteLineManager().getLayer();
        int color = ContextCompat.getColor(this, R.color.safeway_primary);
        RouteLineStylesSet stylesSet = RouteLineStylesSet.from(
                RouteLineStyles.from(RouteLineStyle.from(dpToPx(6), color))
        );
        RouteLineSegment segment = RouteLineSegment.from(points).setStyles(stylesSet.getStyles(0));
        RouteLineOptions options = RouteLineOptions.from(segment).setStylesSet(stylesSet);
        layer.addRouteLine(options);
    }

    private void moveCameraTo(LatLng target, int zoomLevel, boolean animated) {
        if (kakaoMap == null || target == null) {
            return;
        }
        CameraUpdate update = CameraUpdateFactory.newCenterPosition(target, zoomLevel);
        if (animated) {
            kakaoMap.moveCamera(update, CameraAnimation.from(500));
        } else {
            kakaoMap.moveCamera(update);
        }
    }

    private void moveCameraToBounds(List<LatLng> points) {
        if (kakaoMapView == null || kakaoMap == null || points.isEmpty()) {
            return;
        }
        kakaoMapView.post(() -> {
            try {
                LatLng[] pointArray = points.toArray(new LatLng[0]);
                kakaoMap.moveCamera(CameraUpdateFactory.fitMapPoints(pointArray, dpToPx(72), 16), CameraAnimation.from(500));
            } catch (Exception ignored) {
                LatLng fallback = points.get(0);
                moveCameraTo(fallback, 14, false);
            }
        });
    }

    private int dpToPx(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateRouteText() {
        boolean returning = prefs.getBoolean(SafeWayPrefs.RETURNING, false);
        String destination = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, "");
        String routeLink = prefs.getString(SafeWayPrefs.ROUTE_LAST_LINK, "");

        routeStatusText.setText(returning
                ? "귀가 진행 중입니다. 이 화면에서 현재 위치를 실시간으로 갱신합니다."
                : (startFlow
                ? "안심귀가를 시작하기 전에 도착지와 경로를 먼저 설정하세요."
                : "현재 위치를 기준으로 집까지 경로를 설정합니다."));

        startReturnFromRouteButton.setVisibility(returning ? View.GONE : View.VISIBLE);

        if (requestedHomeMissing) {
            currentRouteText.setText("저장된 집 주소가 없습니다. 주소 검색, 지도 선택으로 집을 정한 뒤 집 주소로 저장해주세요.");
            return;
        }
        if (destination.isEmpty()) {
            currentRouteText.setText("저장된 도착지가 없습니다. 주소 검색, 지도 선택으로 도착지를 정해주세요.");
            return;
        }
        if (destinationLatLng == null) {
            currentRouteText.setText("도착지: " + destination + "\n앱 안 경로선을 보려면 카카오 장소 검색이나 지도 선택으로 좌표를 정해야 합니다.");
            return;
        }
        if (routeLink.isEmpty()) {
            currentRouteText.setText("도착지: " + destination + "\n앱 안에서 경로 계산을 누르면 지도에 경로가 표시됩니다.");
            return;
        }
        currentRouteText.setText("도착지: " + destination + "\n보호자에게 공유할 경로 링크와 앱 안 경로가 준비되었습니다.");
    }

    private void updateRouteResultText(String destination, int distanceMeters, String duration, boolean fallback, String routeMode) {
        String distanceText = distanceMeters > 0
                ? String.format(Locale.KOREA, "%.1fkm", distanceMeters / 1000f)
                : "거리 정보 없음";
        String durationText = formatDuration(duration);
        boolean walkingEstimate = "walking_estimate".equals(routeMode);
        routeStatusText.setText(fallback
                ? "직선 참고선으로 경로를 표시했습니다."
                : walkingEstimate
                ? "도보 기준 예상 시간을 계산했습니다."
                : "귀가 경로 계산이 완료되었습니다.");
        String routeTypeText = fallback
                ? "\n실제 길찾기 경로를 찾지 못해 현재 위치와 도착지를 직선 참고선으로 연결했습니다."
                : walkingEstimate
                ? "\n예상 시간은 도보 기준으로 계산했습니다."
                : "\n보호자에게 공유할 경로 링크와 앱 안 경로가 준비되었습니다.";
        currentRouteText.setText("도착지: " + destination
                + "\n예상 거리: " + distanceText
                + "\n예상 시간: " + durationText + " · 도보 기준"
                + routeTypeText);
    }

    private void applyDangerMemoWarningForRoute(List<LatLng> points) {
        appendDangerMemoWarning(findDangerMemosNearRoute(points));
    }

    private void appendDangerMemoWarning(List<AppDatabase.DangerMemo> memos) {
        if (memos == null || memos.isEmpty()) {
            return;
        }
        List<String> dangerNames = new ArrayList<>();
        for (AppDatabase.DangerMemo memo : memos) {
            dangerNames.add(memo.placeName);
        }
        String names = joinDangerNames(dangerNames);
        routeStatusText.setText("위험 메모 근처를 지나는 경로입니다.");
        currentRouteText.append("\n주의: " + names + " 근처를 지납니다. 가능하면 밝은 큰길로 우회하세요.");
    }

    private List<AppDatabase.DangerMemo> findDangerMemosNearRoute(List<LatLng> points) {
        List<AppDatabase.DangerMemo> dangerMemos = new ArrayList<>();
        if (db == null || points == null || points.size() < 2) {
            return dangerMemos;
        }
        List<AppDatabase.DangerMemo> memos = db.getDangerMemos();
        for (AppDatabase.DangerMemo memo : memos) {
            LatLng memoPoint = parseMemoLatLng(memo);
            if (memoPoint == null) {
                continue;
            }
            if (distanceToRouteMeters(memoPoint, points) <= DANGER_MEMO_ROUTE_RADIUS_METERS) {
                dangerMemos.add(memo);
            }
        }
        return dangerMemos;
    }

    private List<String> findDangerMemoNamesNearRoute(List<LatLng> points) {
        List<String> names = new ArrayList<>();
        for (AppDatabase.DangerMemo memo : findDangerMemosNearRoute(points)) {
            names.add(memo.placeName);
        }
        return names;
    }

    private LatLng parseMemoLatLng(AppDatabase.DangerMemo memo) {
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

    private float distanceToRouteMeters(LatLng point, List<LatLng> points) {
        float minDistance = Float.MAX_VALUE;
        for (int index = 1; index < points.size(); index++) {
            float distance = distancePointToSegmentMeters(point, points.get(index - 1), points.get(index));
            if (distance < minDistance) {
                minDistance = distance;
            }
        }
        return minDistance == Float.MAX_VALUE ? Float.MAX_VALUE : minDistance;
    }

    private float distancePointToSegmentMeters(LatLng point, LatLng start, LatLng end) {
        if (start == null || end == null) {
            return Float.MAX_VALUE;
        }
        double referenceLat = Math.toRadians((point.latitude + start.latitude + end.latitude) / 3.0);
        double metersPerDegreeLat = 111320.0;
        double metersPerDegreeLng = 111320.0 * Math.cos(referenceLat);

        double pointX = point.longitude * metersPerDegreeLng;
        double pointY = point.latitude * metersPerDegreeLat;
        double startX = start.longitude * metersPerDegreeLng;
        double startY = start.latitude * metersPerDegreeLat;
        double endX = end.longitude * metersPerDegreeLng;
        double endY = end.latitude * metersPerDegreeLat;

        double deltaX = endX - startX;
        double deltaY = endY - startY;
        double lengthSquared = deltaX * deltaX + deltaY * deltaY;
        if (lengthSquared <= 0.0) {
            return distanceMeters(point, start);
        }

        double t = ((pointX - startX) * deltaX + (pointY - startY) * deltaY) / lengthSquared;
        double clamped = Math.max(0.0, Math.min(1.0, t));
        double projectionX = startX + clamped * deltaX;
        double projectionY = startY + clamped * deltaY;
        double diffX = pointX - projectionX;
        double diffY = pointY - projectionY;
        return (float) Math.sqrt(diffX * diffX + diffY * diffY);
    }

    private float distanceMeters(LatLng first, LatLng second) {
        float[] results = new float[1];
        Location.distanceBetween(first.latitude, first.longitude, second.latitude, second.longitude, results);
        return results[0];
    }

    private String joinDangerNames(List<String> names) {
        if (names.size() == 1) {
            return names.get(0);
        }
        if (names.size() == 2) {
            return names.get(0) + ", " + names.get(1);
        }
        return names.get(0) + ", " + names.get(1) + " 외 " + (names.size() - 2) + "곳";
    }

    private String formatDuration(String duration) {
        if (duration == null || duration.trim().isEmpty()) {
            return "시간 정보 없음";
        }
        String value = duration.trim();
        if (!value.endsWith("s")) {
            return value;
        }
        try {
            long seconds = Long.parseLong(value.substring(0, value.length() - 1));
            long minutes = Math.max(1, Math.round(seconds / 60.0));
            return minutes + "분";
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private int parseDurationMinutes(String duration) {
        if (duration == null || duration.trim().isEmpty()) {
            return 20;
        }
        String value = duration.trim();
        if (!value.endsWith("s")) {
            return 20;
        }
        try {
            long seconds = Long.parseLong(value.substring(0, value.length() - 1));
            return Math.max(1, (int) Math.round(seconds / 60.0));
        } catch (NumberFormatException ignored) {
            return 20;
        }
    }

    private String estimateWalkingDuration(int distanceMeters) {
        int durationSeconds = Math.max(60, Math.round(Math.max(0, distanceMeters) / WALKING_SPEED_METERS_PER_SECOND));
        return durationSeconds + "s";
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private Location getBestLastKnownLocation() {
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            return null;
        }
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
                // Some devices do not expose every provider.
            }
        }
        return bestLocation;
    }

    private LatLng getStoredDestinationLatLng() {
        String lat = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION_LAT, "");
        String lng = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION_LNG, "");
        if (lat.isEmpty() || lng.isEmpty()) {
            return null;
        }
        try {
            return LatLng.from(Double.parseDouble(lat), Double.parseDouble(lng));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private LatLng getStoredHomeLatLng() {
        String lat = prefs.getString(SafeWayPrefs.HOME_DESTINATION_LAT, "");
        String lng = prefs.getString(SafeWayPrefs.HOME_DESTINATION_LNG, "");
        if (lat.isEmpty() || lng.isEmpty()) {
            return null;
        }
        try {
            return LatLng.from(Double.parseDouble(lat), Double.parseDouble(lng));
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
                        Double.parseDouble(coordinates[0]),
                        Double.parseDouble(coordinates[1])
                ));
            } catch (NumberFormatException ignored) {
                // Ignore malformed saved points and keep any valid points.
            }
        }
        return points.size() >= 2 ? points : null;
    }

    private String serializeRoutePoints(List<LatLng> points) {
        if (points == null || points.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (LatLng point : points) {
            if (point == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(formatLatLng(point));
        }
        return builder.toString();
    }

    private void selectDestination(LatLng latLng, String label, boolean moveCamera) {
        applyDestination(latLng, label, moveCamera, true);
    }

    private void applyDestination(LatLng latLng, String label, boolean moveCamera, boolean showToast) {
        String destinationLabel = label == null ? "" : label.trim();
        if (destinationLabel.isEmpty()) {
            destinationLabel = formatLatLng(latLng);
        }

        requestedHomeMissing = false;
        destinationLatLng = latLng;
        routePoints = null;
        destinationInput.setText(destinationLabel);
        prefs.edit()
                .putString(SafeWayPrefs.ROUTE_DESTINATION, destinationLabel)
                .putString(SafeWayPrefs.ROUTE_DESTINATION_LAT, String.valueOf(latLng.latitude))
                .putString(SafeWayPrefs.ROUTE_DESTINATION_LNG, String.valueOf(latLng.longitude))
                .remove(SafeWayPrefs.ROUTE_LAST_LINK)
                .remove(SafeWayPrefs.ROUTE_LAST_POINTS)
                .remove(SafeWayPrefs.ROUTE_LAST_GUIDES)
                .remove(SafeWayPrefs.ROUTE_EXPECTED_MINUTES)
                .apply();
        renderMap();
        if (moveCamera) {
            moveCameraTo(latLng, 16, true);
        }
        updateRouteText();
        if (showToast) {
            Toast.makeText(this, "도착지를 설정했습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private JSONObject toLatLngJson(LatLng latLng) throws Exception {
        JSONObject json = new JSONObject();
        json.put("latitude", latLng.latitude);
        json.put("longitude", latLng.longitude);
        return json;
    }

    private String readResponse(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private List<LatLng> decodePolyline(String encoded) {
        List<LatLng> points = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return points;
        }

        int index = 0;
        int lat = 0;
        int lng = 0;
        while (index < encoded.length()) {
            int result = 0;
            int shift = 0;
            int b;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && index < encoded.length());
            int deltaLat = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            lat += deltaLat;

            result = 0;
            shift = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && index < encoded.length());
            int deltaLng = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            lng += deltaLng;

            points.add(LatLng.from(lat / 1E5, lng / 1E5));
        }
        return points;
    }

    private String formatLatLng(LatLng latLng) {
        return String.format(Locale.US, "%.7f,%.7f", latLng.latitude, latLng.longitude);
    }

    private String mapPickedLabel(String prefix, LatLng latLng) {
        return prefix + " " + formatLatLng(latLng);
    }

    private String normalizeBaseUrl(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void openKakaoRouteUriWithFallback(Location location, LatLng destination, String fallbackLink) {
        Uri appUri = buildKakaoMapWalkingRouteUri(location, destination);
        if (appUri != null && openUri(appUri, true)) {
            return;
        }
        openUri(toHttpsFallbackUri(fallbackLink), false);
    }

    private Uri buildKakaoMapWalkingRouteUri(Location location, LatLng destination) {
        if (location == null || destination == null) {
            return null;
        }
        return Uri.parse("kakaomap://route").buildUpon()
                .appendQueryParameter("sp", String.format(Locale.US, "%.7f,%.7f", location.getLatitude(), location.getLongitude()))
                .appendQueryParameter("ep", formatLatLng(destination))
                .appendQueryParameter("by", "FOOT")
                .build();
    }

    private Uri toHttpsFallbackUri(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return Uri.parse("https://map.kakao.com");
        }
        String value = uri.trim();
        if (value.startsWith("http://m.map.kakao.com")) {
            return Uri.parse("https://m.map.kakao.com" + value.substring("http://m.map.kakao.com".length()));
        }
        return Uri.parse(value);
    }

    private boolean openUri(Uri uri, boolean preferKakaoMapApp) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (preferKakaoMapApp) {
            intent.setPackage("net.daum.android.map");
        }
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException firstError) {
            if (preferKakaoMapApp) {
                try {
                    Intent fallbackIntent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(fallbackIntent);
                    return true;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    return false;
                }
            }
            Toast.makeText(this, "카카오맵 또는 브라우저 앱을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void openUri(String uri) {
        openUri(Uri.parse(uri), false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapStarted && kakaoMapView != null) {
            kakaoMapView.resume();
        }
        startLiveLocationUpdatesIfNeeded();
    }

    @Override
    protected void onPause() {
        stopLiveLocationUpdates();
        if (mapStarted && kakaoMapView != null) {
            kakaoMapView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopLiveLocationUpdates();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_ROUTE_LOCATION) {
            return;
        }
        if (!hasLocationPermission()) {
            Toast.makeText(this, "위치 권한이 없어 지도를 사용할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshCurrentLocation();
        renderMap();
        if (pendingCalculateRoute) {
            pendingCalculateRoute = false;
            calculateRouteInApp();
            return;
        }
        if (pendingOpenWalkingGuidance) {
            pendingOpenWalkingGuidance = false;
            openKakaoWalkingGuidance();
            return;
        }
        if (pendingFocusCurrentLocation) {
            pendingFocusCurrentLocation = false;
            focusCurrentLocation();
        }
    }
}
