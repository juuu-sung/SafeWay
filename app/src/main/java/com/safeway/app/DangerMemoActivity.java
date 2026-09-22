package com.safeway.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
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
    private ScrollView memoScroll;
    private LinearLayout memoList;
    private View quickFormCard;
    private TextView quickFormTitle;
    private EditText placeNameInput;
    private EditText reasonInput;
    private EditText memoInput;
    private TextView memoLocationStatusText;
    private TextView duplicateCleanupButton;
    private TextView saveMemoFormButton;
    private TextView cancelMemoEditButton;
    private TextView clearMemoLocationButton;
    private String selectedLatitude = "";
    private String selectedLongitude = "";
    private String selectedLocationAddress = "";
    private String editingMemoCreatedAt = "";
    private int editingMemoId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SafeWayTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_danger_memo);
        BottomNav.bind(this, DangerMemoActivity.class);
        db = new AppDatabase(this);
        db.seedDefaultMemosIfEmpty();

        memoScroll = findViewById(R.id.memoScroll);
        memoList = findViewById(R.id.memoList);
        quickFormCard = findViewById(R.id.quickFormCard);
        quickFormTitle = findViewById(R.id.quickFormTitle);
        placeNameInput = findViewById(R.id.placeNameInput);
        reasonInput = findViewById(R.id.reasonInput);
        memoInput = findViewById(R.id.memoInput);
        memoLocationStatusText = findViewById(R.id.memoLocationStatusText);
        duplicateCleanupButton = findViewById(R.id.duplicateCleanupButton);
        saveMemoFormButton = findViewById(R.id.saveMemoFormButton);
        cancelMemoEditButton = findViewById(R.id.cancelMemoEditButton);
        clearMemoLocationButton = findViewById(R.id.clearMemoLocationButton);

        findViewById(R.id.saveMemoButton).setOnClickListener(v -> startCreateMemo());
        duplicateCleanupButton.setOnClickListener(v -> confirmMergeDuplicates());
        saveMemoFormButton.setOnClickListener(v -> saveMemo());
        cancelMemoEditButton.setOnClickListener(v -> resetMemoForm());
        clearMemoLocationButton.setOnClickListener(v -> clearSelectedLocation());
        findViewById(R.id.useCurrentLocationButton).setOnClickListener(v -> attachCurrentLocation());
        resetMemoForm();
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
        AppDatabase.DangerMemoWriteResult result = db.saveDangerMemo(
                editingMemoId,
                place,
                reason,
                memo,
                editingMemoCreatedAt.isEmpty() ? dateFormat.format(new Date()) : editingMemoCreatedAt,
                selectedLatitude,
                selectedLongitude,
                getSelectedAddressForStorage()
        );
        if (!result.isSuccessful()) {
            Toast.makeText(this, "위험 지역 메모를 저장하지 못했습니다.", Toast.LENGTH_LONG).show();
            return;
        }

        String message;
        if (result.type == AppDatabase.DangerMemoWriteType.MERGED) {
            message = "같은 위험 지역 메모를 하나로 합쳤습니다.";
        } else if (result.type == AppDatabase.DangerMemoWriteType.UPDATED) {
            message = "위험 지역 메모를 수정했습니다.";
        } else {
            message = "위험 지역 메모가 저장되었습니다.";
        }
        resetMemoForm();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        renderMemos();
    }

    private void startCreateMemo() {
        resetMemoForm();
        scrollToMemoForm();
        placeNameInput.requestFocus();
    }

    private void startEditMemo(AppDatabase.DangerMemo memo) {
        editingMemoId = memo.id;
        placeNameInput.setText(memo.placeName);
        reasonInput.setText(memo.reason);
        memoInput.setText(memo.memo);
        selectedLatitude = memo.latitude;
        selectedLongitude = memo.longitude;
        selectedLocationAddress = memo.locationAddress;
        editingMemoCreatedAt = memo.createdAt;
        quickFormTitle.setText("메모 수정");
        saveMemoFormButton.setText("수정 저장");
        cancelMemoEditButton.setVisibility(View.VISIBLE);
        updateLocationStatus();
        scrollToMemoForm();
        placeNameInput.requestFocus();
        placeNameInput.setSelection(placeNameInput.getText().length());
    }

    private void resetMemoForm() {
        editingMemoId = -1;
        placeNameInput.setText("");
        reasonInput.setText("");
        memoInput.setText("");
        selectedLatitude = "";
        selectedLongitude = "";
        selectedLocationAddress = "";
        editingMemoCreatedAt = "";
        quickFormTitle.setText("빠른 등록");
        saveMemoFormButton.setText("메모 저장");
        cancelMemoEditButton.setVisibility(View.GONE);
        updateLocationStatus();
    }

    private void scrollToMemoForm() {
        memoScroll.post(() -> memoScroll.smoothScrollTo(0, quickFormCard.getTop()));
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
        int duplicateCount = db.countDuplicateDangerMemos();
        duplicateCleanupButton.setText(duplicateCount > 0
                ? "중복 메모 " + duplicateCount + "개 정리"
                : "중복 메모 없음");
        duplicateCleanupButton.setAlpha(duplicateCount > 0 ? 1f : 0.6f);

        if (memos.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("저장된 위험 지역 메모가 없습니다.\n불안했던 장소를 추가하면 다음 경로 계산에 반영됩니다.");
            empty.setTextColor(getColor(R.color.safeway_muted));
            empty.setTextSize(14);
            empty.setLineSpacing(dp(3), 1f);
            empty.setBackgroundResource(R.drawable.bg_card);
            empty.setPadding(dp(20), dp(20), dp(20), dp(20));
            memoList.addView(empty);
            return;
        }
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

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
        );
        actionRowParams.setMargins(0, dp(14), 0, 0);
        actions.setLayoutParams(actionRowParams);

        TextView edit = createCardAction("수정", R.color.safeway_primary, R.drawable.bg_primary_soft);
        edit.setContentDescription(memo.placeName + " 메모 수정");
        edit.setOnClickListener(v -> startEditMemo(memo));
        actions.addView(edit);

        TextView delete = createCardAction("삭제", R.color.safeway_danger, R.drawable.bg_danger_soft);
        LinearLayout.LayoutParams deleteParams = (LinearLayout.LayoutParams) delete.getLayoutParams();
        deleteParams.setMargins(dp(10), 0, 0, 0);
        delete.setLayoutParams(deleteParams);
        delete.setContentDescription(memo.placeName + " 메모 삭제");
        delete.setOnClickListener(v -> confirmDeleteMemo(memo));
        actions.addView(delete);

        card.addView(actions);
        return card;
    }

    private TextView createCardAction(String text, int colorResource, int backgroundResource) {
        TextView action = new TextView(this);
        action.setText(text);
        action.setTextColor(getColor(colorResource));
        action.setTextSize(13);
        action.setTypeface(null, Typeface.BOLD);
        action.setGravity(android.view.Gravity.CENTER);
        action.setBackgroundResource(backgroundResource);
        action.setClickable(true);
        action.setFocusable(true);
        action.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return action;
    }

    private void confirmDeleteMemo(AppDatabase.DangerMemo memo) {
        new AlertDialog.Builder(this)
                .setTitle("메모 삭제")
                .setMessage("‘" + memo.placeName + "’ 메모를 삭제할까요?\n삭제하면 다음 경로 계산에서도 제외됩니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    if (db.deleteDangerMemo(memo.id)) {
                        if (editingMemoId == memo.id) {
                            resetMemoForm();
                        }
                        renderMemos();
                        Toast.makeText(this, "위험 지역 메모를 삭제했습니다.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "메모를 삭제하지 못했습니다.", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void confirmMergeDuplicates() {
        int duplicateCount = db.countDuplicateDangerMemos();
        if (duplicateCount <= 0) {
            Toast.makeText(this, "정리할 중복 메모가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("중복 메모 정리")
                .setMessage("중복으로 판단된 " + duplicateCount + "개 메모를 합칠까요?\n"
                        + "장소와 위험 이유가 같거나, 30m 이내에서 위험 이유가 같은 메모를 묶습니다. 상세 메모는 모두 보존됩니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("합치기", (dialog, which) -> {
                    int mergedCount = db.mergeDuplicateDangerMemos();
                    resetMemoForm();
                    renderMemos();
                    Toast.makeText(
                            this,
                            mergedCount + "개 중복 메모를 정리했습니다.",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .show();
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
            clearMemoLocationButton.setVisibility(View.GONE);
            return;
        }
        String label = selectedLocationAddress == null || selectedLocationAddress.trim().isEmpty()
                ? "현재 위치"
                : selectedLocationAddress.trim();
        memoLocationStatusText.setText("첨부 위치: " + label);
        clearMemoLocationButton.setVisibility(View.VISIBLE);
    }

    private void clearSelectedLocation() {
        selectedLatitude = "";
        selectedLongitude = "";
        selectedLocationAddress = "";
        updateLocationStatus();
        Toast.makeText(this, "첨부 위치를 제거했습니다.", Toast.LENGTH_SHORT).show();
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
