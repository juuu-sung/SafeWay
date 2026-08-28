package com.safeway.app;

import android.graphics.Color;
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
import com.kakao.vectormap.camera.CameraUpdateFactory;
import com.kakao.vectormap.label.LabelOptions;
import com.kakao.vectormap.label.LabelStyles;
import com.kakao.vectormap.label.LabelTextBuilder;
import com.kakao.vectormap.route.RouteLineLayer;
import com.kakao.vectormap.route.RouteLineOptions;
import com.kakao.vectormap.route.RouteLineSegment;
import com.kakao.vectormap.route.RouteLineStyle;
import com.kakao.vectormap.route.RouteLineStyles;
import com.kakao.vectormap.route.RouteLineStylesSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ReturnRouteActivity extends AppCompatActivity {
    static final String EXTRA_RECORD_ID = "com.safeway.app.EXTRA_RECORD_ID";

    private static final LatLng DEFAULT_CAMERA = LatLng.from(37.5665, 126.9780);

    private AppDatabase db;
    private MapView mapView;
    private KakaoMap kakaoMap;
    private TextView emptyText;
    private TextView titleText;
    private TextView metaText;
    private TextView destinationText;
    private TextView detailText;
    private LabelStyles startMarkerStyles;
    private LabelStyles endMarkerStyles;
    private AppDatabase.ReturnRecord record;
    private List<LatLng> actualRoutePoints = new ArrayList<>();
    private boolean mapStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SafeWayTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_return_route);

        db = new AppDatabase(this);
        mapView = findViewById(R.id.returnRouteMap);
        emptyText = findViewById(R.id.returnRouteEmptyText);
        titleText = findViewById(R.id.returnRouteTitleText);
        metaText = findViewById(R.id.returnRouteMetaText);
        destinationText = findViewById(R.id.returnRouteDestinationText);
        detailText = findViewById(R.id.returnRouteDetailText);
        findViewById(R.id.closeReturnRouteButton).setOnClickListener(v -> finish());

        int recordId = getIntent().getIntExtra(EXTRA_RECORD_ID, -1);
        record = db.getReturnRecord(recordId);
        if (record == null) {
            showEmpty("귀가 기록을 찾지 못했습니다.");
            return;
        }

        actualRoutePoints = parseRoutePoints(record.actualRoutePoints);
        titleText.setText("실제 이동 경로");
        metaText.setText(String.format(Locale.KOREA, "%s → %s · 소요 %d분",
                record.startTime, record.endTime, record.durationMinutes));
        destinationText.setText("도착지: " + getDestinationLabel(record));
        detailText.setText(String.format(Locale.KOREA,
                "실제 위치 기록 %d개를 연결했습니다.", actualRoutePoints.size()));

        if (actualRoutePoints.size() < 2) {
            showEmpty("실제 이동 경로가 충분히 저장되지 않았습니다.");
            return;
        }

        startMap();
    }

    private void startMap() {
        if (!KakaoMapConfig.ensureInitialized(this)) {
            showEmpty("local.properties에 KAKAO_NATIVE_APP_KEY를 먼저 설정해야 합니다.");
            return;
        }
        mapStarted = true;
        mapView.start(new MapLifeCycleCallback() {
            @Override
            public void onMapDestroy() {
            }

            @Override
            public void onMapError(Exception error) {
                showEmpty("카카오 지도 오류: " + error.getMessage());
            }
        }, new KakaoMapReadyCallback() {
            @Override
            public LatLng getPosition() {
                if (!actualRoutePoints.isEmpty()) {
                    return actualRoutePoints.get(0);
                }
                return DEFAULT_CAMERA;
            }

            @Override
            public int getZoomLevel() {
                return actualRoutePoints.size() >= 2 ? 15 : 12;
            }

            @Override
            public void onMapReady(@NonNull KakaoMap map) {
                kakaoMap = map;
                emptyText.setVisibility(View.GONE);
                renderRoute();
            }
        });
    }

    private void renderRoute() {
        if (kakaoMap == null || actualRoutePoints.size() < 2) {
            return;
        }
        kakaoMap.getLabelManager().getLayer().removeAll();
        kakaoMap.getRouteLineManager().getLayer().removeAll();

        addRouteLine(actualRoutePoints);
        addMarker(actualRoutePoints.get(0), "출발", true);
        addMarker(actualRoutePoints.get(actualRoutePoints.size() - 1), "마지막 위치", false);

        mapView.post(() -> {
            try {
                kakaoMap.moveCamera(
                        CameraUpdateFactory.fitMapPoints(actualRoutePoints.toArray(new LatLng[0]), dp(72), 16),
                        CameraAnimation.from(500)
                );
            } catch (Exception ignored) {
                kakaoMap.moveCamera(CameraUpdateFactory.newCenterPosition(actualRoutePoints.get(0), 15));
            }
        });
    }

    private void addRouteLine(List<LatLng> points) {
        RouteLineLayer layer = kakaoMap.getRouteLineManager().getLayer();
        int color = ContextCompat.getColor(this, R.color.safeway_teal);
        RouteLineStylesSet stylesSet = RouteLineStylesSet.from(
                RouteLineStyles.from(RouteLineStyle.from(dp(7), color))
        );
        RouteLineSegment segment = RouteLineSegment.from(points).setStyles(stylesSet.getStyles(0));
        layer.addRouteLine(RouteLineOptions.from(segment).setStylesSet(stylesSet));
    }

    private void addMarker(LatLng position, String label, boolean start) {
        LabelStyles styles = start ? startMarkerStyles : endMarkerStyles;
        if (styles == null) {
            int icon = start ? R.drawable.ic_map_marker_teal : R.drawable.ic_map_marker_primary;
            styles = KakaoMarkerStyles.addMarkerStyles(this, kakaoMap, icon, 22, Color.parseColor("#172126"));
            if (start) {
                startMarkerStyles = styles;
            } else {
                endMarkerStyles = styles;
            }
        }
        kakaoMap.getLabelManager().getLayer().addLabel(
                LabelOptions.from(position)
                        .setStyles(styles)
                        .setTexts(new LabelTextBuilder().setTexts(label))
        );
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
            try {
                points.add(LatLng.from(
                        Double.parseDouble(coordinates[0]),
                        Double.parseDouble(coordinates[1])
                ));
            } catch (NumberFormatException ignored) {
                // Skip malformed points from older or partial records.
            }
        }
        return points;
    }

    private String getDestinationLabel(AppDatabase.ReturnRecord record) {
        if (record.routeDestination == null || record.routeDestination.trim().isEmpty()) {
            return "저장된 도착지 없음";
        }
        return record.routeDestination.trim();
    }

    private void showEmpty(String message) {
        emptyText.setText(message);
        emptyText.setVisibility(View.VISIBLE);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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
    }

    @Override
    protected void onPause() {
        if (mapStarted && mapView != null) {
            mapView.pause();
        }
        super.onPause();
    }
}
