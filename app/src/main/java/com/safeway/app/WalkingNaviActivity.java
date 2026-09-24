package com.safeway.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.View;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WalkingNaviActivity extends AppCompatActivity {
    private static final int REQUEST_LOCATION = 81;
    private static final int WALKING_NAV_ZOOM_LEVEL = 17;

    private final SharedPreferences.OnSharedPreferenceChangeListener navigationListener = (p, key) -> {
        if (SafeWayPrefs.NAV_STATE.equals(key)) render();
    };
    private SharedPreferences prefs;
    private AppDatabase db;
    private MapView mapView;
    private KakaoMap kakaoMap;
    private TextView emptyText;
    private TextView titleText;
    private TextView instructionText;
    private TextView metaText;
    private TextView distanceText;
    private TextView etaText;
    private LabelStyles currentMarkerStyles;
    private LabelStyles destinationMarkerStyles;
    private LabelStyles dangerMarkerStyles;
    private LatLng currentLatLng;
    private LatLng destinationLatLng;
    private List<LatLng> routePoints = new ArrayList<>();
    private List<WalkingGuide> guides = new ArrayList<>();
    private boolean mapStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SafeWayTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_walking_navi);

        prefs = SafeWayPrefs.get(this);
        db = new AppDatabase(this);
        mapView = findViewById(R.id.walkingFullMap);
        emptyText = findViewById(R.id.walkingFullEmptyText);
        titleText = findViewById(R.id.walkingFullTitleText);
        instructionText = findViewById(R.id.walkingFullInstructionText);
        metaText = findViewById(R.id.walkingFullMetaText);
        distanceText = findViewById(R.id.walkingFullDistanceText);
        etaText = findViewById(R.id.walkingFullEtaText);
        findViewById(R.id.closeWalkingNaviButton).setOnClickListener(v -> finish());
        findViewById(R.id.walkingVoiceButton).setOnClickListener(v -> {
            prefs.edit().putBoolean(SafeWayPrefs.NAV_VOICE, !prefs.getBoolean(SafeWayPrefs.NAV_VOICE, true)).apply();
            updateNavigationText();
        });
        findViewById(R.id.walkingRecalculateButton).setOnClickListener(v -> {
            if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false) || !hasLocationPermission()) return;
            ContextCompat.startForegroundService(this, new android.content.Intent(this, ReturnLocationService.class)
                    .setAction(ReturnLocationService.ACTION_RECALCULATE));
            Toast.makeText(this, "현재 위치를 확인해 경로를 다시 찾습니다.", Toast.LENGTH_SHORT).show();
        });

        String destinationName = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, "");
        titleText.setText(destinationName == null || destinationName.trim().isEmpty()
                ? "SafeWay 도보 안내"
                : "SafeWay 도보 안내 · " + destinationName.trim());

        if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false)) {
            showEmpty("안심귀가 시작 후 실시간 도보 안내를 사용할 수 있습니다.");
            return;
        }
        if (!hasLocationPermission()) {
            showEmpty("위치 권한이 있어야 실시간 도보 안내를 사용할 수 있습니다.");
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }

        startMap();
        startLocationUpdatesIfNeeded();
    }

    private void startMap() {
        loadStoredRoute();
        currentLatLng = getCurrentLocationLatLng();
        if (currentLatLng == null && routePoints.size() >= 1) {
            currentLatLng = routePoints.get(0);
        }
        if (destinationLatLng == null && routePoints.size() >= 2) {
            destinationLatLng = routePoints.get(routePoints.size() - 1);
        }

        if (!KakaoMapConfig.ensureInitialized(this)) {
            showEmpty("카카오 지도 키가 설정되면 실시간 도보 안내를 사용할 수 있습니다.");
            return;
        }
        mapStarted = true;
        mapView.start(new MapLifeCycleCallback() {
            @Override
            public void onMapDestroy() {
            }

            @Override
            public void onMapError(Exception error) {
                showEmpty("도보 안내 지도를 불러오지 못했습니다.");
            }
        }, new KakaoMapReadyCallback() {
            @Override
            public LatLng getPosition() {
                return currentLatLng != null ? currentLatLng : LatLng.from(37.5665, 126.9780);
            }

            @Override
            public int getZoomLevel() {
                return currentLatLng == null ? 12 : WALKING_NAV_ZOOM_LEVEL;
            }

            @Override
            public void onMapReady(@NonNull KakaoMap map) {
                kakaoMap = map;
                render();
            }
        });
    }

    private void loadStoredRoute() {
        destinationLatLng = getStoredDestinationLatLng();
        routePoints = getStoredRoutePoints();
        guides = getStoredWalkingGuides();
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
            }
        }
        return points;
    }

    private List<WalkingGuide> getStoredWalkingGuides() {
        String storedGuides = prefs.getString(SafeWayPrefs.ROUTE_LAST_GUIDES, "");
        List<WalkingGuide> stored = new ArrayList<>();
        if (storedGuides == null || storedGuides.trim().isEmpty()) {
            return stored;
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
                stored.add(new WalkingGuide(LatLng.from(latitude, longitude), text));
            }
        } catch (Exception ignored) {
        }
        return stored;
    }

    private void render() {
        loadStoredRoute();
        JSONObject navState = WalkingNavigationState.read(prefs);
        LatLng latestLocation = navState.has("latitude")
                ? LatLng.from(navState.optDouble("latitude"), navState.optDouble("longitude")) : null;
        if (latestLocation != null) {
            currentLatLng = latestLocation;
        }
        if (destinationLatLng == null && routePoints.size() >= 2) {
            destinationLatLng = routePoints.get(routePoints.size() - 1);
        }
        if (routePoints.size() < 2 && currentLatLng != null && destinationLatLng != null) {
            routePoints = new ArrayList<>();
            routePoints.add(currentLatLng);
            routePoints.add(destinationLatLng);
        }

        updateNavigationText();
        renderMap();
    }

    private void updateNavigationText() {
        JSONObject state = WalkingNavigationState.read(prefs);
        instructionText.setText(state.optString("instruction", "현재 위치를 확인하는 중입니다."));
        metaText.setText(state.optString("detail", "정확한 GPS 신호를 기다립니다."));
        distanceText.setText(WalkingNavigationState.distance(state.optDouble("remaining", -1)));
        etaText.setText(WalkingNavigationState.minutes(state.optDouble("seconds", -1)));
        TextView voiceButton = findViewById(R.id.walkingVoiceButton);
        voiceButton.setText(prefs.getBoolean(SafeWayPrefs.NAV_VOICE, true) ? "음성 켜짐" : "음성 꺼짐");
        if (prefs.getBoolean(SafeWayPrefs.NAV_VOICE, true) && !state.optBoolean("voiceReady", false))
            metaText.append("\n한국어 음성 준비 중 · 음성이 없으면 기기의 음성 설정을 확인하세요.");
    }

    private void renderMap() {
        if (kakaoMap == null) {
            return;
        }
        kakaoMap.getLabelManager().getLayer().removeAll();
        kakaoMap.getRouteLineManager().getLayer().removeAll();

        List<LatLng> routeToDraw = getRemainingRoutePoints();
        Label currentLabel = null;
        if (currentLatLng != null) {
            currentLabel = addMarker(currentLatLng, "내 위치", true);
        }
        if (destinationLatLng != null) {
            addMarker(destinationLatLng, "도착지", false);
        }
        if (routeToDraw != null && routeToDraw.size() >= 2) {
            emptyText.setVisibility(View.GONE);
            addRouteLine(routePoints, ContextCompat.getColor(this, R.color.safeway_muted));
            if (WalkingNavigationState.read(prefs).optBoolean("guidance"))
                addRouteLine(routeToDraw, ContextCompat.getColor(this, R.color.safeway_teal));
        } else {
            showEmpty("도보 경로를 찾지 못했습니다. 경로 다시 설정에서 경로를 계산해주세요.");
        }
        addDangerMemoMarkers();

        if (currentLatLng != null && WalkingNavigationState.read(prefs).optBoolean("guidance")) {
            moveCameraForNavigation(routeToDraw, currentLabel);
        } else {
            kakaoMap.getTrackingManager().stopTracking();
        }
    }

    private List<LatLng> getRemainingRoutePoints() {
        JSONObject state = WalkingNavigationState.read(prefs);
        if (!state.optBoolean("guidance") || currentLatLng == null || routePoints.size() < 2) return routePoints;
        List<LatLng> remaining = new ArrayList<>();
        int next = Math.min(routePoints.size() - 1, Math.max(1, state.optInt("segment", 0) + 1));
        remaining.add(currentLatLng);
        remaining.addAll(routePoints.subList(next, routePoints.size()));
        return remaining;
    }

    private Label addMarker(LatLng position, String title, boolean currentLocation) {
        LabelStyles styles = currentLocation ? currentMarkerStyles : destinationMarkerStyles;
        if (styles == null) {
            int icon = currentLocation ? R.drawable.ic_nav_arrow_teal : R.drawable.ic_map_marker_primary;
            styles = KakaoMarkerStyles.addMarkerStyles(this, kakaoMap, icon, 24, Color.parseColor("#172126"));
            if (currentLocation) {
                currentMarkerStyles = styles;
            } else {
                destinationMarkerStyles = styles;
            }
        }
        return kakaoMap.getLabelManager().getLayer().addLabel(
                LabelOptions.from(position)
                        .setStyles(styles)
                        .setTexts(new LabelTextBuilder().setTexts(title))
        );
    }

    private void addDangerMemoMarkers() {
        if (db == null) {
            return;
        }
        for (AppDatabase.DangerMemo memo : db.getDangerMemos()) {
            LatLng position = parseDangerMemoLatLng(memo);
            if (position == null) {
                continue;
            }
            addDangerMemoMarker(position, memo.placeName);
        }
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

    private void addRouteLine(List<LatLng> points, int color) {
        RouteLineLayer layer = kakaoMap.getRouteLineManager().getLayer();
        RouteLineStylesSet stylesSet = RouteLineStylesSet.from(
                RouteLineStyles.from(RouteLineStyle.from(dp(7), color))
        );
        RouteLineSegment segment = RouteLineSegment.from(points).setStyles(stylesSet.getStyles(0));
        RouteLineOptions options = RouteLineOptions.from(segment).setStylesSet(stylesSet);
        layer.addRouteLine(options);
    }

    private void moveCameraForNavigation(List<LatLng> routeToDraw, Label currentLabel) {
        float rotation = 0f;
        if (routeToDraw != null && routeToDraw.size() >= 2 && routeToDraw.get(1) != null) {
            rotation = bearingDegrees(currentLatLng, routeToDraw.get(1));
        }
        if (currentLabel != null) {
            currentLabel.rotateTo(rotation);
            kakaoMap.getTrackingManager().startTracking(currentLabel);
            kakaoMap.getTrackingManager().setTrackingRotation(true);
        }
        CameraPosition cameraPosition = CameraPosition.from(
                new CameraPosition.Builder()
                        .setPosition(currentLatLng)
                        .setZoomLevel(WALKING_NAV_ZOOM_LEVEL)
                        .setTiltAngle(45)
                        .setRotationAngle(rotation)
        );
        mapView.post(() -> kakaoMap.moveCamera(
                CameraUpdateFactory.newCameraPosition(cameraPosition),
                CameraAnimation.from(500)
        ));
    }

    private float distanceMeters(LatLng from, LatLng to) {
        if (from == null || to == null) {
            return 0f;
        }
        float[] results = new float[1];
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, results);
        return Math.max(0f, results[0]);
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
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        return bestLocation;
    }

    private void startLocationUpdatesIfNeeded() {
        if (prefs.getBoolean(SafeWayPrefs.RETURNING, false) && hasLocationPermission()) {
            ContextCompat.startForegroundService(this, new android.content.Intent(this, ReturnLocationService.class));
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void showEmpty(String message) {
        emptyText.setText(message);
        emptyText.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(navigationListener);
        if (mapStarted && mapView != null) {
            mapView.resume();
        }
        if (prefs.getBoolean(SafeWayPrefs.RETURNING, false)) render();
        startLocationUpdatesIfNeeded();
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(navigationListener);
        if (mapStarted && mapView != null) {
            mapView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (db != null) db.close();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) {
            return;
        }
        if (hasLocationPermission()) {
            startMap();
            startLocationUpdatesIfNeeded();
        } else {
            showEmpty("위치 권한이 없어 실시간 도보 안내를 사용할 수 없습니다.");
        }
    }

    private static final class WalkingGuide {
        final LatLng position;
        final String text;

        WalkingGuide(LatLng position, String text) {
            this.position = position;
            this.text = text;
        }
    }
}
