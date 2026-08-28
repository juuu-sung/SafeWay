package com.safeway.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.messaging.FirebaseMessaging;

final class FcmTokenManager {
    private FcmTokenManager() {
    }

    static void refreshDeviceToken(Context context) {
        SharedPreferences prefs = SafeWayPrefs.get(context);
        try {
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful() || task.getResult() == null) {
                            prefs.edit()
                                    .putString(SafeWayPrefs.DEVICE_PUSH_TOKEN_STATUS, "Firebase 설정을 확인해주세요.")
                                    .apply();
                            return;
                        }
                        prefs.edit()
                                .putString(SafeWayPrefs.DEVICE_PUSH_TOKEN, task.getResult())
                                .putString(SafeWayPrefs.DEVICE_PUSH_TOKEN_STATUS, "토큰 준비 완료")
                                .apply();
                    });
        } catch (RuntimeException ignored) {
            prefs.edit()
                    .putString(SafeWayPrefs.DEVICE_PUSH_TOKEN_STATUS, "app/google-services.json 설정이 필요합니다.")
                    .apply();
        }
    }

    static void saveDeviceToken(Context context, String token) {
        SafeWayPrefs.get(context).edit()
                .putString(SafeWayPrefs.DEVICE_PUSH_TOKEN, token == null ? "" : token)
                .putString(SafeWayPrefs.DEVICE_PUSH_TOKEN_STATUS, "토큰 준비 완료")
                .apply();
    }
}
