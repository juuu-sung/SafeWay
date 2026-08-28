package com.safeway.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeAddressActivity extends AppCompatActivity {
    private static final int REQUEST_HOME_ROUTE_START = 82;
    private static final LatLng DEFAULT_CAMERA = LatLng.from(37.5665, 126.9780);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private EditText homeAddressInput;
    private TextView homeStatusText;
    private TextView pickHomeMapCenterButton;
    private android.view.View homeMapCenterTarget;
    private MapView kakaoMapView;
    private KakaoMap kakaoMap;
    private LabelStyles homeMarkerStyles;
    private LatLng homeLatLng;
    private boolean mapStarted;
    private boolean mapSelectionMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SafeWayTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home_address);
        BottomNav.bind(this, HomeAddressActivity.class);

        prefs = SafeWayPrefs.get(this);
        homeAddressInput = findViewById(R.id.homeAddressInput);
        homeStatusText = findViewById(R.id.homeStatusText);
        pickHomeMapCenterButton = findViewById(R.id.pickHomeMapCenterButton);
        homeMapCenterTarget = findViewById(R.id.homeMapCenterTarget);
        kakaoMapView = findViewById(R.id.homeMap);
        homeMapCenterTarget.setVisibility(android.view.View.GONE);

        loadSavedHome();
        startKakaoMap();

        findViewById(R.id.searchHomePlaceButton).setOnClickListener(v -> searchTypedAddress());
        findViewById(R.id.searchHomeAddressButton).setOnClickListener(v -> searchTypedAddress());
        pickHomeMapCenterButton.setOnClickListener(v -> toggleMapSelectionMode());
        findViewById(R.id.saveHomeAddressButton).setOnClickListener(v -> saveHomeAddress());
        findViewById(R.id.startHomeReturnButton).setOnClickListener(v -> startHomeReturn());
        findViewById(R.id.clearHomeAddressButton).setOnClickListener(v -> clearHomeAddress());
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
                Toast.makeText(HomeAddressActivity.this, "카카오 지도 오류: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, new KakaoMapReadyCallback() {
            @Override
            public LatLng getPosition() {
                return homeLatLng == null ? DEFAULT_CAMERA : homeLatLng;
            }

            @Override
            public int getZoomLevel() {
                return homeLatLng == null ? 12 : 16;
            }

            @Override
            public void onMapReady(@NonNull KakaoMap map) {
                kakaoMap = map;
                kakaoMap.setOnMapClickListener((clickedMap, position, screenPoint, poi) ->
                        selectHomeFromMapTap(position));
                kakaoMap.setOnTerrainLongClickListener((clickedMap, position, screenPoint) ->
                        selectHomeFromMapTap(position));
                renderMap();
            }
        });
    }

    private void loadSavedHome() {
        String home = prefs.getString(SafeWayPrefs.HOME_DESTINATION, "");
        homeLatLng = getStoredHomeLatLng();
        if (home != null && !home.trim().isEmpty()) {
            homeAddressInput.setText(home);
        }
        updateHomeStatus();
    }

    private void searchTypedAddress() {
        String query = homeAddressInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "검색할 집 주소나 장소명을 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        String restApiKey = KakaoMapConfig.restApiKey();
        if (restApiKey.isEmpty()) {
            Toast.makeText(this, "local.properties에 KAKAO_REST_API_KEY를 먼저 설정해야 합니다.", Toast.LENGTH_LONG).show();
            return;
        }

        homeStatusText.setText("카카오 Local API로 주소를 검색하는 중입니다.");
        LatLng center = homeLatLng == null ? DEFAULT_CAMERA : homeLatLng;
        executor.execute(() -> {
            try {
                List<KakaoLocalSearch.Result> results = KakaoLocalSearch.search(restApiKey, query, center);
                mainHandler.post(() -> showHomeSearchResults(results));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    updateHomeStatus();
                    Toast.makeText(this, "주소 검색 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showHomeSearchResults(List<KakaoLocalSearch.Result> results) {
        if (results == null || results.isEmpty()) {
            updateHomeStatus();
            Toast.makeText(this, "주소나 장소를 찾지 못했습니다. 검색어를 더 구체적으로 입력해주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (results.size() == 1) {
            KakaoLocalSearch.Result result = results.get(0);
            setHomeCandidate(result.latLng, result.label, true);
            return;
        }

        String[] labels = new String[results.size()];
        for (int i = 0; i < results.size(); i++) {
            labels[i] = results.get(i).display;
        }
        new AlertDialog.Builder(this)
                .setTitle("집 위치 선택")
                .setItems(labels, (dialog, which) -> {
                    KakaoLocalSearch.Result result = results.get(which);
                    setHomeCandidate(result.latLng, result.label, true);
                })
                .show();
        updateHomeStatus();
    }

    private void toggleMapSelectionMode() {
        if (kakaoMap == null) {
            Toast.makeText(this, "지도가 준비되는 중입니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        setMapSelectionMode(!mapSelectionMode, true);
    }

    private void selectHomeFromMapTap(LatLng position) {
        if (!mapSelectionMode || position == null) {
            return;
        }
        setMapSelectionMode(false, false);
        setHomeCandidate(position, mapPickedLabel(position), true);
    }

    private void setMapSelectionMode(boolean enabled, boolean refreshStatus) {
        mapSelectionMode = enabled;
        homeMapCenterTarget.setVisibility(android.view.View.GONE);
        pickHomeMapCenterButton.setText(enabled ? "선택 취소" : "지도에서 선택");
        if (enabled) {
            homeStatusText.setText("지도에서 집으로 저장할 위치를 한 번 탭하세요.");
            Toast.makeText(this, "지도에서 집 위치를 탭해주세요.", Toast.LENGTH_SHORT).show();
        } else if (refreshStatus) {
            updateHomeStatus();
        }
    }

    private void saveHomeAddress() {
        String home = homeAddressInput.getText().toString().trim();
        if (home.isEmpty()) {
            Toast.makeText(this, "저장할 집 주소를 먼저 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (homeLatLng == null) {
            Toast.makeText(this, "주소 검색이나 지도 선택으로 집 위치를 먼저 정해주세요.", Toast.LENGTH_LONG).show();
            return;
        }

        prefs.edit()
                .putString(SafeWayPrefs.HOME_DESTINATION, home)
                .putString(SafeWayPrefs.HOME_DESTINATION_LAT, String.valueOf(homeLatLng.latitude))
                .putString(SafeWayPrefs.HOME_DESTINATION_LNG, String.valueOf(homeLatLng.longitude))
                .apply();
        updateHomeStatus();
        Toast.makeText(this, "집 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void startHomeReturn() {
        if (!hasSavedHome()) {
            Toast.makeText(this, "집 주소를 먼저 저장해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, RouteActivity.class);
        intent.putExtra(RouteActivity.EXTRA_START_FLOW, true);
        intent.putExtra(RouteActivity.EXTRA_USE_HOME_DESTINATION, true);
        startActivityForResult(intent, REQUEST_HOME_ROUTE_START);
    }

    private void clearHomeAddress() {
        prefs.edit()
                .remove(SafeWayPrefs.HOME_DESTINATION)
                .remove(SafeWayPrefs.HOME_DESTINATION_LAT)
                .remove(SafeWayPrefs.HOME_DESTINATION_LNG)
                .apply();
        homeLatLng = null;
        homeAddressInput.setText("");
        renderMap();
        updateHomeStatus();
        Toast.makeText(this, "저장된 집 주소를 삭제했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void setHomeCandidate(LatLng latLng, String label, boolean moveCamera) {
        String homeLabel = label == null ? "" : label.trim();
        if (homeLabel.isEmpty()) {
            homeLabel = formatLatLng(latLng);
        }
        homeLatLng = latLng;
        homeAddressInput.setText(homeLabel);
        renderMap();
        if (moveCamera) {
            moveCameraTo(latLng, 16, true);
        }
        updateHomeStatus();
        Toast.makeText(this, "집 위치를 선택했습니다. 저장하기를 눌러 확정하세요.", Toast.LENGTH_SHORT).show();
    }

    private void renderMap() {
        if (kakaoMap == null) {
            return;
        }
        kakaoMap.getLabelManager().getLayer().removeAll();
        LatLng target = homeLatLng == null ? DEFAULT_CAMERA : homeLatLng;
        if (homeLatLng != null) {
            addMarker(homeLatLng, "집 주소");
        }
        moveCameraTo(target, homeLatLng == null ? 12 : 16, false);
    }

    private void addMarker(LatLng position, String title) {
        if (homeMarkerStyles == null) {
            homeMarkerStyles = KakaoMarkerStyles.addMarkerStyles(
                    this,
                    kakaoMap,
                    R.drawable.ic_map_marker_primary,
                    22,
                    Color.parseColor("#172126")
            );
        }
        kakaoMap.getLabelManager().getLayer().addLabel(
                LabelOptions.from(position)
                        .setStyles(homeMarkerStyles)
                        .setTexts(new LabelTextBuilder().setTexts(title))
        );
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

    private void updateHomeStatus() {
        String savedHome = prefs.getString(SafeWayPrefs.HOME_DESTINATION, "");
        LatLng savedLatLng = getStoredHomeLatLng();
        if (savedHome == null || savedHome.trim().isEmpty() || savedLatLng == null) {
            if (homeLatLng == null) {
                homeStatusText.setText("저장된 집 주소가 없습니다. 주소를 검색하거나 지도에서 집 위치를 선택해주세요.");
                return;
            }
            homeStatusText.setText("선택한 집 위치: " + homeAddressInput.getText().toString().trim()
                    + "\n저장하기를 눌러 집 주소로 확정하세요.");
            return;
        }
        homeStatusText.setText("저장된 집 주소: " + savedHome
                + "\n집으로 귀가 시작 시 이 위치가 도착지로 자동 설정됩니다.");
    }

    private boolean hasSavedHome() {
        String home = prefs.getString(SafeWayPrefs.HOME_DESTINATION, "");
        return home != null && !home.trim().isEmpty() && getStoredHomeLatLng() != null;
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

    private String formatLatLng(LatLng latLng) {
        return String.format(Locale.US, "%.7f,%.7f", latLng.latitude, latLng.longitude);
    }

    private String mapPickedLabel(LatLng latLng) {
        return "지도 선택 위치 " + formatLatLng(latLng);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_HOME_ROUTE_START && resultCode == RESULT_OK) {
            openMainAndStartReturn();
        }
    }

    private void openMainAndStartReturn() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_AUTO_START_RETURN, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapStarted && kakaoMapView != null) {
            kakaoMapView.resume();
        }
    }

    @Override
    protected void onPause() {
        if (mapStarted && kakaoMapView != null) {
            kakaoMapView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
