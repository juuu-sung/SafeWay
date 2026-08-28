package com.safeway.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kakao.vectormap.LatLng;

public class DangerMemoActivity extends AppCompatActivity {
    private static final int REQUEST_MEMO_LOCATION = 91;
    private static final String LOCATION_LOOKUP_LOADING = "주소 확인 중...";

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA);
    private final ExecutorService addressExecutor = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private LinearLayout memoList;
    private EditText placeNameInput;
    private EditText reasonInput;
    private EditText memoInput;
    private TextView memoLocationStatusText;
    private String selectedLatitude = "";
    private String selectedLongitude = "";
    private String selectedLocationAddress = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SafeWayTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_danger_memo);
        BottomNav.bind(this, DangerMemoActivity.class);
        db = new AppDatabase(this);
        db.seedDefaultMemosIfEmpty();

        memoList = findViewById(R.id.memoList);
        placeNameInput = findViewById(R.id.placeNameInput);
        reasonInput = findViewById(R.id.reasonInput);
        memoInput = findViewById(R.id.memoInput);
        memoLocationStatusText = findViewById(R.id.memoLocationStatusText);
        findViewById(R.id.saveMemoButton).setOnClickListener(v -> saveMemo());
        findViewById(R.id.useCurrentLocationButton).setOnClickListener(v -> attachCurrentLocation());
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderMemos();
    }

    private void saveMemo() {
        String place = placeNameInput.getText().toString().trim();
        String reason = reasonInput.getText().toString().trim();
        String memo = memoInput.getText().toString().trim();
        if (place.isEmpty() || reason.isEmpty()) {
            Toast.makeText(this, "장소명과 위험 이유를 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        db.insertDangerMemo(
                place,
                reason,
                memo,
                dateFormat.format(new Date()),
                selectedLatitude,
                selectedLongitude,
                getSelectedAddressForStorage()
        );
        placeNameInput.setText("");
        reasonInput.setText("");
        memoInput.setText("");
        selectedLatitude = "";
        selectedLongitude = "";
        selectedLocationAddress = "";
        updateLocationStatus();
        Toast.makeText(this, "위험 지역 메모가 저장되었습니다.", Toast.LENGTH_SHORT).show();
        renderMemos();
    }

    private void attachCurrentLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_MEMO_LOCATION
            );
            return;
        }

        Location location = getBestLastKnownLocation();
        if (location == null) {
            Toast.makeText(this, "현재 위치를 아직 확인할 수 없습니다. 위치 설정을 확인해주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        selectedLatitude = String.format(Locale.US, "%.7f", location.getLatitude());
        selectedLongitude = String.format(Locale.US, "%.7f", location.getLongitude());
        selectedLocationAddress = LOCATION_LOOKUP_LOADING;
        updateLocationStatus();
        resolveSelectedLocationAddress(location);
        Toast.makeText(this, "현재 위치를 메모에 첨부했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void renderMemos() {
        memoList.removeAllViews();
        List<AppDatabase.DangerMemo> memos = db.getDangerMemos();
        for (AppDatabase.DangerMemo memo : memos) {
            memoList.addView(createMemoCard(memo));
        }
    }

    private LinearLayout createMemoCard(AppDatabase.DangerMemo memo) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(params);

        TextView place = new TextView(this);
        place.setText(memo.placeName);
        place.setTextColor(getColor(R.color.safeway_ink));
        place.setTextSize(16);
        place.setTypeface(null, Typeface.BOLD);
        card.addView(place);

        TextView reason = new TextView(this);
        reason.setText(memo.reason);
        reason.setTextColor(getColor(R.color.safeway_muted));
        reason.setTextSize(13);
        reason.setPadding(0, dp(8), 0, 0);
        card.addView(reason);

        TextView meta = new TextView(this);
        String detail = memo.memo.trim().isEmpty() ? memo.createdAt : memo.createdAt + " · " + memo.memo;
        meta.setText(detail);
        meta.setTextColor(getColor(R.color.safeway_muted));
        meta.setTextSize(10);
        meta.setTypeface(null, Typeface.BOLD);
        meta.setPadding(0, dp(8), 0, 0);
        card.addView(meta);

        if (hasMemoLocation(memo)) {
            TextView location = new TextView(this);
            location.setText("위치   " + getMemoLocationLabel(memo));
            location.setTextColor(getColor(R.color.safeway_teal));
            location.setTextSize(11);
            location.setTypeface(null, Typeface.BOLD);
            location.setBackgroundResource(R.drawable.bg_teal_soft);
            location.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams locationParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            locationParams.setMargins(0, dp(12), 0, 0);
            card.addView(location, locationParams);

            TextView map = new TextView(this);
            map.setText("위치 보기");
            map.setTextColor(getColor(R.color.safeway_primary));
            map.setTextSize(12);
            map.setTypeface(null, Typeface.BOLD);
            map.setPadding(0, dp(12), 0, 0);
            map.setOnClickListener(v -> openMemoLocation(memo));
            card.addView(map);
        }
        return card;
    }

    private void resolveSelectedLocationAddress(Location location) {
        String restApiKey = BuildConfig.KAKAO_REST_API_KEY.trim();
        String lookupLatitude = selectedLatitude;
        String lookupLongitude = selectedLongitude;
        if (restApiKey.isEmpty()) {
            selectedLocationAddress = "현재 위치";
            updateLocationStatus();
            return;
        }
        addressExecutor.execute(() -> {
            String address = "";
            try {
                address = KakaoLocalSearch.reverseGeocode(
                        restApiKey,
                        LatLng.from(location.getLatitude(), location.getLongitude())
                );
            } catch (Exception ignored) {
                address = "";
            }
            String resolvedAddress = address == null || address.trim().isEmpty() ? "현재 위치" : address.trim();
            runOnUiThread(() -> {
                if (!lookupLatitude.equals(selectedLatitude) || !lookupLongitude.equals(selectedLongitude)) {
                    return;
                }
                selectedLocationAddress = resolvedAddress;
                if (placeNameInput.getText().toString().trim().isEmpty()) {
                    placeNameInput.setText(resolvedAddress);
                }
                updateLocationStatus();
            });
        });
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

    private void updateLocationStatus() {
        if (selectedLatitude.isEmpty() || selectedLongitude.isEmpty()) {
            memoLocationStatusText.setText("위치를 첨부하지 않았습니다.");
            return;
        }
        String label = selectedLocationAddress == null || selectedLocationAddress.trim().isEmpty()
                ? "현재 위치"
                : selectedLocationAddress.trim();
        memoLocationStatusText.setText("첨부 위치: " + label);
    }

    private String getSelectedAddressForStorage() {
        if (selectedLatitude.isEmpty() || selectedLongitude.isEmpty()) {
            return "";
        }
        if (selectedLocationAddress == null || selectedLocationAddress.trim().isEmpty()
                || LOCATION_LOOKUP_LOADING.equals(selectedLocationAddress.trim())) {
            return "현재 위치";
        }
        return selectedLocationAddress.trim();
    }

    private String getMemoLocationLabel(AppDatabase.DangerMemo memo) {
        if (memo.locationAddress != null && !memo.locationAddress.trim().isEmpty()) {
            return memo.locationAddress.trim();
        }
        return "위치 첨부됨";
    }

    private boolean hasMemoLocation(AppDatabase.DangerMemo memo) {
        return memo.latitude != null && memo.longitude != null
                && !memo.latitude.trim().isEmpty()
                && !memo.longitude.trim().isEmpty();
    }

    private void openMemoLocation(AppDatabase.DangerMemo memo) {
        if (!hasMemoLocation(memo)) {
            Toast.makeText(this, "저장된 위치가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String link = PushAlertClient.buildKakaoLookLink(
                    Double.parseDouble(memo.latitude.trim()),
                    Double.parseDouble(memo.longitude.trim())
            );
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
        } catch (NumberFormatException e) {
            Toast.makeText(this, "저장된 위치 형식이 올바르지 않습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEMO_LOCATION) {
            boolean granted = false;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
            if (granted) {
                attachCurrentLocation();
            } else {
                Toast.makeText(this, "위치 권한이 필요하면 앱 설정에서 허용해주세요.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        addressExecutor.shutdownNow();
        super.onDestroy();
    }
}
