package com.safeway.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class GuardianActivity extends AppCompatActivity {
    private static final int REQUEST_PUSH_NOTIFICATION = 60;

    private SharedPreferences prefs;
    private TextView connectionStatusBadge;
    private TextView connectionStatusTitle;
    private TextView connectionStatusDetail;
    private TextView myProfileInitialText;
    private TextView myProfileNameText;
    private TextView myProfilePhoneText;
    private TextView currentGuardianText;
    private TextView currentGuardianPhoneText;
    private TextView guardianProfileInitialText;
    private TextView guardianProfileRelationText;
    private TextView guardianProfileTokenText;
    private TextView devicePushTokenText;
    private TextView devicePushTokenStatusText;
    private TextView pushConnectionStatusText;
    private TextView generatedPairingCodeText;
    private TextView pairingStatusText;
    private TextView themePinkButton;
    private TextView themeBlueButton;
    private EditText myNameInput;
    private EditText myPhoneInput;
    private EditText nameInput;
    private EditText phoneInput;
    private EditText guardianPushTokenInput;
    private EditText pairingCodeInput;
    private EditText pushServerUrlInput;
    private RadioGroup relationGroup;
    private RadioButton relationParent;
    private RadioButton relationFriend;
    private RadioButton relationGuardian;
    private String latestPairingCode = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SafeWayTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guardian);
        BottomNav.bind(this, GuardianActivity.class);
        prefs = SafeWayPrefs.get(this);
        SafeWayNotificationChannels.ensureGuardianAlertChannel(this);
        FcmTokenManager.refreshDeviceToken(this);

        connectionStatusBadge = findViewById(R.id.connectionStatusBadge);
        connectionStatusTitle = findViewById(R.id.connectionStatusTitle);
        connectionStatusDetail = findViewById(R.id.connectionStatusDetail);
        myProfileInitialText = findViewById(R.id.myProfileInitialText);
        myProfileNameText = findViewById(R.id.myProfileNameText);
        myProfilePhoneText = findViewById(R.id.myProfilePhoneText);
        currentGuardianText = findViewById(R.id.currentGuardianText);
        currentGuardianPhoneText = findViewById(R.id.currentGuardianPhoneText);
        guardianProfileInitialText = findViewById(R.id.guardianProfileInitialText);
        guardianProfileRelationText = findViewById(R.id.guardianProfileRelationText);
        guardianProfileTokenText = findViewById(R.id.guardianProfileTokenText);
        devicePushTokenText = findViewById(R.id.devicePushTokenText);
        devicePushTokenStatusText = findViewById(R.id.devicePushTokenStatusText);
        pushConnectionStatusText = findViewById(R.id.pushConnectionStatusText);
        generatedPairingCodeText = findViewById(R.id.generatedPairingCodeText);
        pairingStatusText = findViewById(R.id.pairingStatusText);
        themePinkButton = findViewById(R.id.themePinkButton);
        themeBlueButton = findViewById(R.id.themeBlueButton);
        myNameInput = findViewById(R.id.myNameInput);
        myPhoneInput = findViewById(R.id.myPhoneInput);
        nameInput = findViewById(R.id.guardianNameInput);
        phoneInput = findViewById(R.id.guardianPhoneInput);
        guardianPushTokenInput = findViewById(R.id.guardianPushTokenInput);
        pairingCodeInput = findViewById(R.id.pairingCodeInput);
        pushServerUrlInput = findViewById(R.id.pushServerUrlInput);
        relationGroup = findViewById(R.id.relationGroup);
        relationParent = findViewById(R.id.relationParent);
        relationFriend = findViewById(R.id.relationFriend);
        relationGuardian = findViewById(R.id.relationGuardian);

        loadGuardian();
        updateRelationChips();
        relationGroup.setOnCheckedChangeListener((group, checkedId) -> updateRelationChips());
        themePinkButton.setOnClickListener(v -> selectTheme(SafeWayTheme.THEME_PINK));
        themeBlueButton.setOnClickListener(v -> selectTheme(SafeWayTheme.THEME_BLUE));
        findViewById(R.id.saveMyProfileButton).setOnClickListener(v -> saveMyProfile());
        findViewById(R.id.saveGuardianButton).setOnClickListener(v -> saveGuardian());
        findViewById(R.id.openGuardianMonitorButton).setOnClickListener(v -> startActivity(new Intent(this, GuardianMonitorActivity.class)));
        findViewById(R.id.createPairingCodeButton).setOnClickListener(v -> createPairingCode());
        findViewById(R.id.copyPairingCodeButton).setOnClickListener(v -> copyPairingCode());
        findViewById(R.id.claimPairingCodeButton).setOnClickListener(v -> claimPairingCode());
        findViewById(R.id.copyDevicePushTokenButton).setOnClickListener(v -> copyDevicePushToken());
        findViewById(R.id.checkPushServerButton).setOnClickListener(v -> checkPushServer());
        findViewById(R.id.sendTestPushButton).setOnClickListener(v -> sendTestPush());
        ensurePushNotificationPermission();
        devicePushTokenText.postDelayed(this::updateDevicePushTokenText, 1200);
        updateThemeButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderMyProfile();
        renderGuardianProfile();
        updatePairingStatus();
        updateDevicePushTokenText();
    }

    private void loadGuardian() {
        String myName = prefs.getString(SafeWayPrefs.MY_NAME, "");
        String myPhone = prefs.getString(SafeWayPrefs.MY_PHONE, "");
        String name = prefs.getString(SafeWayPrefs.GUARDIAN_NAME, "");
        String phone = prefs.getString(SafeWayPrefs.GUARDIAN_PHONE, "");
        String relation = prefs.getString(SafeWayPrefs.GUARDIAN_RELATION, "부모님");
        String guardianPushToken = prefs.getString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, "");
        String pushServerUrl = prefs.getString(SafeWayPrefs.PUSH_SERVER_URL, "");

        myNameInput.setText(myName);
        myPhoneInput.setText(myPhone);
        if (!name.isEmpty()) {
            nameInput.setText(name);
            phoneInput.setText(phone);
        }
        guardianPushTokenInput.setText(guardianPushToken);
        pushServerUrlInput.setText(pushServerUrl);
        renderMyProfile();
        renderGuardianProfile();
        updatePushStatus("서버 연결과 테스트 알림을 확인해주세요.", false);
        updatePairingStatus();

        if ("친구".equals(relation)) relationFriend.setChecked(true);
        else if ("보호자".equals(relation)) relationGuardian.setChecked(true);
        else relationParent.setChecked(true);
    }

    private void saveMyProfile() {
        String myName = myNameInput.getText().toString().trim();
        String myPhone = myPhoneInput.getText().toString().trim();
        if (myName.isEmpty()) {
            Toast.makeText(this, "내 이름을 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit()
                .putString(SafeWayPrefs.MY_NAME, myName)
                .putString(SafeWayPrefs.MY_PHONE, myPhone)
                .apply();
        renderMyProfile();
        Toast.makeText(this, "내 프로필이 저장되었습니다.", Toast.LENGTH_SHORT).show();
    }

    private void saveGuardian() {
        String name = nameInput.getText().toString().trim();
        String phone = phoneInput.getText().toString().trim();
        if (name.isEmpty() || phone.isEmpty()) {
            Toast.makeText(this, "이름과 전화번호를 모두 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        String relation = getSelectedRelation();
        String guardianPushToken = guardianPushTokenInput.getText().toString().trim();
        String pushServerUrl = pushServerUrlInput.getText().toString().trim();
        prefs.edit()
                .putString(SafeWayPrefs.GUARDIAN_NAME, name)
                .putString(SafeWayPrefs.GUARDIAN_PHONE, phone)
                .putString(SafeWayPrefs.GUARDIAN_RELATION, relation)
                .putString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, guardianPushToken)
                .putString(SafeWayPrefs.PUSH_SERVER_URL, pushServerUrl)
                .apply();
        renderGuardianProfile();
        updatePairingStatus();
        Toast.makeText(this, "보호자 프로필이 저장되었습니다.", Toast.LENGTH_SHORT).show();
    }

    private void checkPushServer() {
        String pushServerUrl = pushServerUrlInput.getText().toString().trim();
        prefs.edit().putString(SafeWayPrefs.PUSH_SERVER_URL, pushServerUrl).apply();
        updatePushStatus("서버 연결을 확인하는 중입니다.", false);
        PushAlertClient.checkServerHealth(pushServerUrl, (ok, message) -> {
            updatePushStatus(message, ok);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }

    private void sendTestPush() {
        String guardianPushToken = guardianPushTokenInput.getText().toString().trim();
        String pushServerUrl = pushServerUrlInput.getText().toString().trim();
        prefs.edit()
                .putString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, guardianPushToken)
                .putString(SafeWayPrefs.PUSH_SERVER_URL, pushServerUrl)
                .apply();
        updatePushStatus("테스트 알림을 보내는 중입니다.", false);
        PushAlertClient.sendTestAlert(pushServerUrl, guardianPushToken, (ok, message) -> {
            updatePushStatus(message, ok);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void createPairingCode() {
        String pushServerUrl = pushServerUrlInput.getText().toString().trim();
        prefs.edit().putString(SafeWayPrefs.PUSH_SERVER_URL, pushServerUrl).apply();

        String devicePushToken = prefs.getString(SafeWayPrefs.DEVICE_PUSH_TOKEN, "");
        if (devicePushToken == null || devicePushToken.trim().isEmpty()) {
            FcmTokenManager.refreshDeviceToken(this);
            updateDevicePushTokenText();
            updatePushStatus("이 기기의 푸시 토큰을 준비하는 중입니다. 잠시 후 다시 시도해주세요.", false);
            Toast.makeText(this, "푸시 토큰 준비 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        String name = nameInput.getText().toString().trim();
        String phone = phoneInput.getText().toString().trim();
        String relation = getSelectedRelation();
        if (name.isEmpty()) {
            name = "보호자";
        }

        updatePushStatus("연동 코드를 만드는 중입니다.", false);
        PushAlertClient.createGuardianPairingCode(
                pushServerUrl,
                name,
                phone,
                relation,
                devicePushToken,
                (ok, result, message) -> {
                    if (ok && result != null && !result.code.isEmpty()) {
                        latestPairingCode = result.code;
                        int minutes = Math.max(1, result.expiresInSeconds / 60);
                        generatedPairingCodeText.setText(result.code + "\n" + minutes + "분 안에 입력");
                        pairingStatusText.setText("코드 생성 완료. 자녀 기기에서 " + result.code + "을 입력하면 연결됩니다.");
                        pairingStatusText.setTextColor(getColor(R.color.safeway_primary));
                        pairingStatusText.setBackgroundResource(R.drawable.bg_primary_soft);
                        updateConnectionHeader(false, "연동 코드 대기 중", "자녀 기기에서 " + result.code + "을 입력하면 이 보호자 프로필과 연결됩니다.");
                    }
                    updatePushStatus(message, ok);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
        );
    }

    private void copyPairingCode() {
        if (latestPairingCode == null || latestPairingCode.trim().isEmpty()) {
            Toast.makeText(this, "먼저 연동 코드를 만들어주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("SafeWay Pairing Code", latestPairingCode));
            Toast.makeText(this, "연동 코드를 복사했습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void claimPairingCode() {
        String pushServerUrl = pushServerUrlInput.getText().toString().trim();
        String code = pairingCodeInput.getText().toString().trim();
        prefs.edit().putString(SafeWayPrefs.PUSH_SERVER_URL, pushServerUrl).apply();

        updatePushStatus("보호자 연동을 확인하는 중입니다.", false);
        PushAlertClient.claimGuardianPairingCode(pushServerUrl, code, (ok, result, message) -> {
            if (ok && result != null) {
                String name = result.guardianName.isEmpty() ? "보호자" : result.guardianName;
                String relation = result.guardianRelation.isEmpty() ? "보호자" : result.guardianRelation;
                prefs.edit()
                        .putString(SafeWayPrefs.GUARDIAN_NAME, name)
                        .putString(SafeWayPrefs.GUARDIAN_PHONE, result.guardianPhone)
                        .putString(SafeWayPrefs.GUARDIAN_RELATION, relation)
                        .putString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, result.guardianToken)
                        .putString(SafeWayPrefs.PUSH_SERVER_URL, pushServerUrl)
                        .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_TITLE, "SafeWay 보호자 연동 완료")
                        .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_BODY, "자녀 기기에서 보호자 연결을 완료했습니다.")
                        .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_MAPS_LINK, "")
                        .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_LINK, "")
                        .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_POINTS, "")
                        .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_DESTINATION, "")
                        .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_STATUS, "linked")
                        .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_LATITUDE, "")
                        .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_LONGITUDE, "")
                        .putInt(SafeWayPrefs.LATEST_GUARDIAN_ALERT_EXPECTED_MINUTES, 0)
                        .putLong(SafeWayPrefs.LATEST_GUARDIAN_ALERT_UPDATED_AT, System.currentTimeMillis())
                        .apply();
                nameInput.setText(name);
                phoneInput.setText(result.guardianPhone);
                guardianPushTokenInput.setText(result.guardianToken);
                selectRelation(relation);
                renderGuardianProfile();
                updatePairingStatus();
                pairingCodeInput.setText("");
            }
            updatePushStatus(message, ok);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void updatePushStatus(String message, boolean ok) {
        pushConnectionStatusText.setText(message);
        pushConnectionStatusText.setTextColor(getColor(ok ? R.color.safeway_teal : R.color.safeway_warning));
        pushConnectionStatusText.setBackgroundResource(ok ? R.drawable.bg_teal_soft : R.drawable.bg_warning_soft);
    }

    private void updatePairingStatus() {
        String token = prefs.getString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, "");
        String name = prefs.getString(SafeWayPrefs.GUARDIAN_NAME, "");
        String relation = prefs.getString(SafeWayPrefs.GUARDIAN_RELATION, "보호자");
        if (token != null && !token.trim().isEmpty()) {
            String label = name == null || name.trim().isEmpty() ? "보호자" : name.trim();
            updateConnectionHeader(true, "보호자 알림 연결됨", label + "에게 안심귀가 시작, 완료, 경로 이탈 알림이 전송됩니다.");
            pairingStatusText.setText("연동 완료\n" + label + " · " + relation + " 프로필로 보호자 모니터가 연결되어 있습니다.");
            pairingStatusText.setTextColor(getColor(R.color.safeway_teal));
            pairingStatusText.setBackgroundResource(R.drawable.bg_teal_soft);
            renderGuardianProfile();
            return;
        }
        updateConnectionHeader(false, "보호자 알림 연결이 필요합니다", "코드를 만들거나 입력하면 안심귀가 시작, 완료, 경로 이탈 알림이 보호자에게 전송됩니다.");
        pairingStatusText.setText("연동 전입니다.\n보호자 기기에서 코드를 만들거나 자녀 기기에서 받은 코드를 입력해주세요.");
        pairingStatusText.setTextColor(getColor(R.color.safeway_warning));
        pairingStatusText.setBackgroundResource(R.drawable.bg_warning_soft);
        renderGuardianProfile();
    }

    private void updateConnectionHeader(boolean linked, String title, String detail) {
        connectionStatusBadge.setText(linked ? "연동 완료" : "연동 전");
        connectionStatusBadge.setTextColor(getColor(linked ? R.color.safeway_teal : R.color.safeway_warning));
        connectionStatusBadge.setBackgroundResource(linked ? R.drawable.bg_teal_soft : R.drawable.bg_warning_soft);
        connectionStatusTitle.setText(title);
        connectionStatusDetail.setText(detail);
    }

    private void renderMyProfile() {
        String myName = prefs.getString(SafeWayPrefs.MY_NAME, "");
        String myPhone = prefs.getString(SafeWayPrefs.MY_PHONE, "");
        String label = myName == null || myName.trim().isEmpty() ? "내 프로필 없음" : myName.trim();
        myProfileNameText.setText(label);
        myProfilePhoneText.setText(myPhone == null || myPhone.trim().isEmpty() ? "전화번호 미등록" : formatPhone(myPhone));
        myProfileInitialText.setText(profileInitial(myName, "나"));
    }

    private void renderGuardianProfile() {
        String token = prefs.getString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, "");
        String name = prefs.getString(SafeWayPrefs.GUARDIAN_NAME, "");
        String phone = prefs.getString(SafeWayPrefs.GUARDIAN_PHONE, "");
        String relation = prefs.getString(SafeWayPrefs.GUARDIAN_RELATION, "보호자");
        boolean linked = token != null && !token.trim().isEmpty();
        String label = name == null || name.trim().isEmpty() ? "보호자 프로필 없음" : name.trim();
        String relationLabel = relation == null || relation.trim().isEmpty() ? "보호자" : relation.trim();

        currentGuardianText.setText(label);
        currentGuardianPhoneText.setText(phone == null || phone.trim().isEmpty() ? "전화번호 미등록" : formatPhone(phone));
        guardianProfileInitialText.setText(profileInitial(name, relationLabel));
        guardianProfileInitialText.setBackgroundResource(linked ? R.drawable.bg_teal : R.drawable.bg_primary);
        guardianProfileRelationText.setText(linked ? relationLabel + " · 연동 완료" : relationLabel + " · 연동 전");
        guardianProfileRelationText.setTextColor(getColor(linked ? R.color.safeway_teal : R.color.safeway_primary));
        guardianProfileTokenText.setText(linked
                ? "알림 수신 준비 완료. 안심귀가 상태가 이 보호자 기기로 전달됩니다."
                : "연동 코드를 만들거나 입력하면 알림 수신 상태가 표시됩니다.");
        guardianProfileTokenText.setTextColor(getColor(linked ? R.color.safeway_teal : R.color.safeway_primary));
        guardianProfileTokenText.setBackgroundResource(linked ? R.drawable.bg_teal_soft : R.drawable.bg_primary_soft);
    }

    private String profileInitial(String name, String relation) {
        String source = name == null || name.trim().isEmpty() ? relation : name.trim();
        if (source == null || source.trim().isEmpty()) {
            return "?";
        }
        return source.trim().substring(0, 1);
    }

    private void selectTheme(String theme) {
        SafeWayTheme.select(this, theme);
        updateThemeButtons();
    }

    private void updateThemeButtons() {
        SafeWayTheme.styleChoice(this, themePinkButton, themeBlueButton);
    }

    private String getSelectedRelation() {
        int checked = relationGroup.getCheckedRadioButtonId();
        if (checked == R.id.relationFriend) return "친구";
        if (checked == R.id.relationGuardian) return "보호자";
        return "부모님";
    }

    private void selectRelation(String relation) {
        if ("친구".equals(relation)) {
            relationFriend.setChecked(true);
        } else if ("보호자".equals(relation)) {
            relationGuardian.setChecked(true);
        } else {
            relationParent.setChecked(true);
        }
        updateRelationChips();
    }

    private void updateRelationChips() {
        updateChip(relationParent, relationParent.isChecked());
        updateChip(relationFriend, relationFriend.isChecked());
        updateChip(relationGuardian, relationGuardian.isChecked());
    }

    private void updateChip(RadioButton button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_primary : R.drawable.bg_outline);
        button.setTextColor(getColor(selected ? R.color.white : R.color.safeway_muted));
        button.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private String formatPhone(String phone) {
        if (phone == null || phone.length() < 10) return phone == null ? "" : phone;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() == 11) {
            return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
        }
        return phone;
    }

    private void updateDevicePushTokenText() {
        String token = prefs.getString(SafeWayPrefs.DEVICE_PUSH_TOKEN, "");
        String status = prefs.getString(SafeWayPrefs.DEVICE_PUSH_TOKEN_STATUS, "토큰을 준비하는 중입니다.");
        devicePushTokenStatusText.setText(status);
        if (token == null || token.trim().isEmpty()) {
            devicePushTokenText.setText("아직 토큰이 없습니다. Firebase 설정 후 다시 열어주세요.");
            return;
        }
        devicePushTokenText.setText(token);
    }

    private void copyDevicePushToken() {
        String token = prefs.getString(SafeWayPrefs.DEVICE_PUSH_TOKEN, "");
        if (token == null || token.trim().isEmpty()) {
            Toast.makeText(this, "복사할 푸시 토큰이 아직 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("SafeWay FCM Token", token));
            Toast.makeText(this, "이 기기의 푸시 토큰을 복사했습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void ensurePushNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PUSH_NOTIFICATION);
        }
    }
}
