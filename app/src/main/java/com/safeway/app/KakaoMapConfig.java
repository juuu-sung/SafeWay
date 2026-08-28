package com.safeway.app;

import android.content.Context;

import com.kakao.vectormap.KakaoMapSdk;

final class KakaoMapConfig {
    private static boolean initialized;

    private KakaoMapConfig() {
    }

    static boolean ensureInitialized(Context context) {
        String appKey = nativeAppKey();
        if (appKey.isEmpty()) {
            return false;
        }
        if (!initialized) {
            KakaoMapSdk.init(context.getApplicationContext(), appKey);
            initialized = true;
        }
        return true;
    }

    static String nativeAppKey() {
        return BuildConfig.KAKAO_NATIVE_APP_KEY == null ? "" : BuildConfig.KAKAO_NATIVE_APP_KEY.trim();
    }

    static String restApiKey() {
        return BuildConfig.KAKAO_REST_API_KEY == null ? "" : BuildConfig.KAKAO_REST_API_KEY.trim();
    }
}
