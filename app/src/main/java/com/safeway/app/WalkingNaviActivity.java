package com.safeway.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
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
    private static final float WALKING_SPEED_METERS_PER_SECOND = 1.2f;
    private static final int WALKING_NAV_ZOOM_LEVEL = 17;
    private static final float ROUTE_DEVIATION_THRESHOLD_METERS = 80f;
    private static final int ROUTE_DEVIATION_CONFIRMATION_COUNT = 2;
    private static final long ROUTE_DEVIATION_ALERT_COOLDOWN_MS = 5 * 60 * 1000L;

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
    private boolean locationUpdatesActive;
    private LocationListener locationListener;
    private int routeDeviationHitCount;
    private long lastRouteDeviationAlertMillis;
    private boolean routeDeviationAlertInFlight;
    private boolean routeDeviationActive;
    private float routeDeviationDistanceMeters;

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

        String destinationName = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, "");
        titleText.setText(destinationName == null || destinationName.trim().isEmpty()
                ? "도보 내비"
                : "도보 내비 · " + destinationName.trim());

        if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false)) {
            showEmpty("안심귀가 시작 후 도보 내비를 사용할 수 있습니다.");
            return;
        }
        if (!hasLocationPermission()) {
            showEmpty("위치 권한이 있어야 도보 내비를 사용할 수 있습니다.");
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
            showEmpty("카카오 지도 키가 설정되면 도보 내비를 사용할 수 있습니다.");
            return;
        }
        mapStarted = true;
        mapView.start(new MapLifeCycleCallback() {
            @Override
            public void onMapDestroy() {
            }

            @Override
            public void onMapError(Exception error) {
                showEmpty("도보 내비 지도를 불러오지 못했습니다.");
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
        LatLng latestLocation = getCurrentLocationLatLng();
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
        if (destinationLatLng == null) {
            instructionText.setText("도착지를 먼저 설정하세요.");
            metaText.setText("경로 다시 설정에서 도착지를 선택하면 도보 내비가 시작됩니다.");
            distanceText.setText("-");
            etaText.setText("-");
            return;
        }
        if (currentLatLng == null) {
            instructionText.setText("현재 위치를 확인하는 중입니다.");
            metaText.setText("GPS가 켜져 있어야 도보 내비가 갱신됩니다.");
            distanceText.setText("-");
            etaText.setText("-");
            return;
        }

        List<LatLng> route = routePoints == null ? new ArrayList<>() : routePoints;
        if (route.size() < 2) {
            route = new ArrayList<>();
            route.add(currentLatLng);
            route.add(destinationLatLng);
        }

        if (routeDeviationActive) {
            instructionText.setText("경로를 벗어난 것 같아요.");
            metaText.setText(formatDistance(routeDeviationDistanceMeters) + " 이탈 · 보호자에게 알림을 보냅니다.");
            distanceText.setText(formatDistance(routeDeviationDistanceMeters));
            etaText.setText("확인 필요");
            return;
        }

        int nearestIndex = findNearestRoutePointIndex(currentLatLng, route);
        int nextIndex = findNextRoutePointIndex(currentLatLng, route, nearestIndex);
        LatLng nextPoint = route.get(nextIndex);
        float nextDistanceMeters = distanceMeters(currentLatLng, nextPoint);
        float remainingMeters = estimateRemainingWalkingDistance(currentLatLng, route, nextIndex);
        WalkingGuide nextGuide = findNextWalkingGuide(currentLatLng, route, nearestIndex);

        if (remainingMeters <= 30f || distanceMeters(currentLatLng, destinationLatLng) <= 30f) {
            instructionText.setText("도착지 근처입니다.");
            metaText.setText("주변을 확인한 뒤 귀가 완료 버튼을 눌러주세요.");
            distanceText.setText("30m 이내");
            etaText.setText("곧 도착");
            return;
        }

        if (nextGuide != null) {
            float guideDistanceMeters = distanceMeters(currentLatLng, nextGuide.position);
            instructionText.setText(nextGuide.text);
            metaText.setText(formatDistance(guideDistanceMeters) + " 후 안내 · 도보 내비");
        } else {
            instructionText.setText("경로를 따라 " + formatDistance(nextDistanceMeters) + " 이동");
            metaText.setText(describeBearing(currentLatLng, nextPoint) + " 방향 · 도보 내비");
        }
        distanceText.setText(formatDistance(remainingMeters));
        etaText.setText(formatWalkingMinutes(remainingMeters));
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
            addRouteLine(routeToDraw);
        } else {
            showEmpty("도보 경로를 찾지 못했습니다. 경로 다시 설정에서 경로를 계산해주세요.");
        }
        addDangerMemoMarkers();

        if (currentLatLng != null) {
            moveCameraForNavigation(routeToDraw, currentLabel);
        }
    }

    private List<LatLng> getRemainingRoutePoints() {
        List<LatLng> remaining = new ArrayList<>();
        if (currentLatLng == null) {
            return routePoints;
        }
        if (routePoints == null || routePoints.size() < 2) {
            if (destinationLatLng != null) {
                remaining.add(currentLatLng);
                remaining.add(destinationLatLng);
            }
            return remaining;
        }
        int nearestIndex = findNearestRoutePointIndex(currentLatLng, routePoints);
        int nextIndex = findNextRoutePointIndex(currentLatLng, routePoints, nearestIndex);
        remaining.add(currentLatLng);
        for (int i = nextIndex; i < routePoints.size(); i++) {
            LatLng point = routePoints.get(i);
            if (point != null) {
                remaining.add(point);
            }
        }
        if (remaining.size() < 2 && destinationLatLng != null) {
            remaining.add(destinationLatLng);
        }
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

    private void addRouteLine(List<LatLng> points) {
        RouteLineLayer layer = kakaoMap.getRouteLineManager().getLayer();
        int color = ContextCompat.getColor(this, R.color.safeway_teal);
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

    private WalkingGuide findNextWalkingGuide(LatLng current, List<LatLng> route, int nearestIndex) {
        if (guides == null || guides.isEmpty() || route == null || route.size() < 2) {
            return null;
        }
        WalkingGuide bestGuide = null;
        int bestRouteIndex = Integer.MAX_VALUE;
        float bestDistance = Float.MAX_VALUE;
        for (WalkingGuide guide : guides) {
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
            return destinationLatLng == null ? 0f : distanceMeters(current, destinationLatLng);
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
        List<LatLng> route = routePoints;
        if (route == null || route.size() < 2) {
            route = getStoredRoutePoints();
        }
        if (route == null || route.size() < 2) {
            resetRouteDeviationState();
            return;
        }

        LatLng current = LatLng.from(location.getLatitude(), location.getLongitude());
        if (destinationLatLng != null && distanceMeters(current, destinationLatLng) <= 40f) {
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
        if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false) || locationUpdatesActive || !hasLocationPermission()) {
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }
        String provider = getLocationProvider(locationManager);
        if (provider == null) {
            showEmpty("위치 서비스가 꺼져 있어 현재 위치를 추적할 수 없습니다.");
            return;
        }
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                currentLatLng = LatLng.from(location.getLatitude(), location.getLongitude());
                ReturnTrackRecorder.record(WalkingNaviActivity.this, location);
                checkRouteDeviation(location);
                render();
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
            locationManager.requestLocationUpdates(provider, 3000L, 5f, locationListener, Looper.getMainLooper());
            locationUpdatesActive = true;
        } catch (SecurityException ignored) {
            locationListener = null;
            locationUpdatesActive = false;
        }
    }

    private String getLocationProvider(LocationManager locationManager) {
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

    private void stopLocationUpdates() {
        if (!locationUpdatesActive || locationListener == null) {
            locationUpdatesActive = false;
            locationListener = null;
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
        locationUpdatesActive = false;
        locationListener = null;
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
        if (mapStarted && mapView != null) {
            mapView.resume();
        }
        startLocationUpdatesIfNeeded();
    }

    @Override
    protected void onPause() {
        stopLocationUpdates();
        if (mapStarted && mapView != null) {
            mapView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopLocationUpdates();
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
            showEmpty("위치 권한이 없어 도보 내비를 사용할 수 없습니다.");
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
