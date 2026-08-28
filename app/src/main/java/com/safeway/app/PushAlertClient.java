package com.safeway.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PushAlertClient {
    interface Callback {
        void onResult(boolean sent, String message);
    }

    interface PairingCodeCallback {
        void onResult(boolean ok, PairingCodeResult result, String message);
    }

    interface GuardianLinkCallback {
        void onResult(boolean ok, GuardianLinkResult result, String message);
    }

    static final class PairingCodeResult {
        final String code;
        final long expiresAt;
        final int expiresInSeconds;

        PairingCodeResult(String code, long expiresAt, int expiresInSeconds) {
            this.code = code == null ? "" : code;
            this.expiresAt = expiresAt;
            this.expiresInSeconds = expiresInSeconds;
        }
    }

    static final class GuardianLinkResult {
        final String guardianName;
        final String guardianPhone;
        final String guardianRelation;
        final String guardianToken;
        final boolean linkNotificationSent;

        GuardianLinkResult(String guardianName, String guardianPhone, String guardianRelation,
                           String guardianToken, boolean linkNotificationSent) {
            this.guardianName = guardianName == null ? "" : guardianName;
            this.guardianPhone = guardianPhone == null ? "" : guardianPhone;
            this.guardianRelation = guardianRelation == null ? "" : guardianRelation;
            this.guardianToken = guardianToken == null ? "" : guardianToken;
            this.linkNotificationSent = linkNotificationSent;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_GUARDIAN_ROUTE_POINTS = 80;
    private static final long LIVE_LOCATION_UPDATE_INTERVAL_MS = 5000L;
    private static final float LIVE_LOCATION_UPDATE_MIN_DISTANCE_METERS = 5f;
    private static final Object LIVE_LOCATION_UPDATE_LOCK = new Object();
    private static long lastLiveLocationUpdateSentAt;
    private static double lastLiveLocationUpdateLatitude = Double.NaN;
    private static double lastLiveLocationUpdateLongitude = Double.NaN;

    private PushAlertClient() {
    }

    static void createGuardianPairingCode(String serverUrl, String guardianName, String guardianPhone,
                                          String guardianRelation, String guardianToken,
                                          PairingCodeCallback callback) {
        String normalizedUrl = normalizeBaseUrl(serverUrl);
        String token = guardianToken == null ? "" : guardianToken.trim();
        if (normalizedUrl.isEmpty()) {
            postPairingCode(callback, false, null, "푸시 서버 주소를 입력해주세요.");
            return;
        }
        if (token.isEmpty()) {
            postPairingCode(callback, false, null, "이 기기의 푸시 토큰이 아직 없습니다.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("guardianName", guardianName == null ? "보호자" : guardianName);
                payload.put("guardianPhone", guardianPhone == null ? "" : guardianPhone);
                payload.put("guardianRelation", guardianRelation == null ? "보호자" : guardianRelation);
                payload.put("guardianToken", token);

                connection = openJsonPost(normalizedUrl + "/guardians/pairing-code", payload);
                int status = connection.getResponseCode();
                String responseBody = readResponse(connection);
                JSONObject response = parseJsonObject(responseBody);
                if (status >= 200 && status < 300 && response.optBoolean("ok")) {
                    postPairingCode(callback, true, new PairingCodeResult(
                            response.optString("code", ""),
                            response.optLong("expiresAt", 0L),
                            response.optInt("expiresInSeconds", 0)
                    ), "연동 코드가 생성되었습니다.");
                } else {
                    postPairingCode(callback, false, null,
                            response.optString("error", buildPairingEndpointError(status, responseBody)));
                }
            } catch (JSONException e) {
                postPairingCode(callback, false, null, "연동 코드 생성 실패: 서버를 최신 코드로 재시작해주세요.");
            } catch (Exception e) {
                postPairingCode(callback, false, null, buildConnectionFailureMessage("연동 코드 생성 실패", normalizedUrl, e));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    static void claimGuardianPairingCode(String serverUrl, String code, GuardianLinkCallback callback) {
        String normalizedUrl = normalizeBaseUrl(serverUrl);
        String normalizedCode = code == null ? "" : code.replaceAll("[^0-9]", "");
        if (normalizedUrl.isEmpty()) {
            postGuardianLink(callback, false, null, "푸시 서버 주소를 입력해주세요.");
            return;
        }
        if (normalizedCode.length() != 6) {
            postGuardianLink(callback, false, null, "6자리 연동 코드를 입력해주세요.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("code", normalizedCode);

                connection = openJsonPost(normalizedUrl + "/guardians/link", payload);
                int status = connection.getResponseCode();
                String responseBody = readResponse(connection);
                JSONObject response = parseJsonObject(responseBody);
                if (status >= 200 && status < 300 && response.optBoolean("ok")) {
                    GuardianLinkResult result = new GuardianLinkResult(
                            response.optString("guardianName", ""),
                            response.optString("guardianPhone", ""),
                            response.optString("guardianRelation", "보호자"),
                            response.optString("guardianToken", ""),
                            response.optBoolean("linkNotificationSent", false)
                    );
                    postGuardianLink(callback, true, result, result.linkNotificationSent
                            ? "보호자와 연동되었습니다. 보호자 폰으로 확인 알림을 보냈습니다."
                            : "보호자와 연동되었습니다. 보호자 폰 알림은 테스트 알림으로 확인해주세요.");
                } else {
                    postGuardianLink(callback, false, null,
                            response.optString("error", buildPairingEndpointError(status, responseBody)));
                }
            } catch (JSONException e) {
                postGuardianLink(callback, false, null, "연동 실패: 서버를 최신 코드로 재시작해주세요.");
            } catch (Exception e) {
                postGuardianLink(callback, false, null, buildConnectionFailureMessage("연동 실패", normalizedUrl, e));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    static void fetchLatestGuardianStatus(Context context, Callback callback) {
        SharedPreferences prefs = SafeWayPrefs.get(context);
        String serverUrl = normalizeBaseUrl(prefs.getString(SafeWayPrefs.PUSH_SERVER_URL, ""));
        String guardianToken = prefs.getString(SafeWayPrefs.DEVICE_PUSH_TOKEN, "");
        if (guardianToken == null || guardianToken.trim().isEmpty()) {
            guardianToken = prefs.getString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, "");
        }

        if (serverUrl.isEmpty()) {
            post(callback, false, "푸시 서버 주소를 입력해주세요.");
            return;
        }
        if (guardianToken == null || guardianToken.trim().isEmpty()) {
            post(callback, false, "이 기기의 푸시 토큰이 아직 없습니다.");
            return;
        }

        String token = guardianToken.trim();
        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("guardianToken", token);

                connection = openJsonPost(serverUrl + "/guardians/status", payload);
                int status = connection.getResponseCode();
                String responseBody = readResponse(connection);
                JSONObject response = parseJsonObject(responseBody);
                if (status >= 200 && status < 300 && response.optBoolean("ok")) {
                    JSONObject state = response.optJSONObject("state");
                    if (!response.optBoolean("hasStatus") || state == null) {
                        post(callback, false, "서버에 저장된 귀가 상태가 아직 없습니다.");
                        return;
                    }
                    saveGuardianStatusToPrefs(context, state);
                    post(callback, true, "서버에서 보호자 모니터 상태를 불러왔습니다.");
                } else {
                    post(callback, false, response.optString("error", "보호자 상태 조회 실패: " + status));
                }
            } catch (JSONException e) {
                post(callback, false, "보호자 상태 조회 실패: 서버를 최신 코드로 재시작해주세요.");
            } catch (Exception e) {
                post(callback, false, buildConnectionFailureMessage("보호자 상태 조회 실패", serverUrl, e));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    static void sendReturnStarted(Context context, Location location, Callback callback) {
        SharedPreferences prefs = SafeWayPrefs.get(context);
        String serverUrl = normalizeBaseUrl(prefs.getString(SafeWayPrefs.PUSH_SERVER_URL, ""));
        String guardianToken = prefs.getString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, "");
        String guardianName = prefs.getString(SafeWayPrefs.GUARDIAN_NAME, "보호자");
        String destination = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, "");
        String routeDestination = getRouteDestination(prefs, destination);
        String routePoints = compactRoutePoints(prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, ""));
        int expectedMinutes = prefs.getInt(SafeWayPrefs.ROUTE_EXPECTED_MINUTES, 0);

        if (serverUrl.isEmpty() || guardianToken == null || guardianToken.trim().isEmpty()) {
            post(callback, false, "푸시 서버 주소 또는 보호자 토큰이 없습니다.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("guardianToken", guardianToken.trim());
                payload.put("guardianName", guardianName);
                payload.put("title", "SafeWay 안심귀가 알림");
                payload.put("status", "active");
                if (expectedMinutes > 0) {
                    payload.put("expectedMinutes", String.valueOf(expectedMinutes));
                }
                boolean hasDestination = destination != null && !destination.trim().isEmpty();
                if (hasDestination) {
                    payload.put("destination", destination.trim());
                }
                if (location != null) {
                    String mapsLink = buildMapsLink(location);
                    if (hasDestination) {
                        payload.put("routeLink", buildDirectionsLink(location, routeDestination));
                    }
                    if (!routePoints.isEmpty()) {
                        payload.put("routePoints", routePoints);
                    }
                    payload.put("body", hasDestination
                            ? "안심귀가가 시작되었습니다. 알림을 눌러 귀가 경로를 확인하세요."
                            : "안심귀가가 시작되었습니다. 알림을 눌러 현재 위치를 확인하세요.");
                    payload.put("mapsLink", mapsLink);
                    payload.put("latitude", String.format(Locale.US, "%.7f", location.getLatitude()));
                    payload.put("longitude", String.format(Locale.US, "%.7f", location.getLongitude()));
                } else {
                    payload.put("body", "안심귀가가 시작되었습니다. 위치를 확인하는 중입니다.");
                }

                URL url = new URL(serverUrl + "/alerts/return-started");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }

                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    post(callback, true, "보호자 푸시 알림을 요청했습니다.");
                } else {
                    post(callback, false, "푸시 서버 응답 오류: " + status);
                }
            } catch (Exception e) {
                post(callback, false, "푸시 전송 실패: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private static HttpURLConnection openJsonPost(String urlValue, JSONObject payload) throws Exception {
        URL url = new URL(urlValue);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        return connection;
    }

    static void sendReturnCompleted(Context context, int durationMinutes, int expectedMinutes, Callback callback) {
        SharedPreferences prefs = SafeWayPrefs.get(context);
        String serverUrl = normalizeBaseUrl(prefs.getString(SafeWayPrefs.PUSH_SERVER_URL, ""));
        String guardianToken = prefs.getString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, "");
        String destination = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, "");
        String routeLink = prefs.getString(SafeWayPrefs.ROUTE_LAST_LINK, "");
        String routePoints = compactRoutePoints(prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, ""));

        if (serverUrl.isEmpty() || guardianToken == null || guardianToken.trim().isEmpty()) {
            post(callback, false, "푸시 서버 주소 또는 보호자 토큰이 없습니다.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("guardianToken", guardianToken.trim());
                payload.put("status", "completed");
                payload.put("title", "SafeWay 귀가 완료");
                payload.put("body", "안심귀가가 완료되었습니다. 무사히 도착했는지 확인하세요.");
                payload.put("durationMinutes", String.valueOf(Math.max(1, durationMinutes)));
                payload.put("expectedMinutes", String.valueOf(Math.max(0, expectedMinutes)));
                if (destination != null && !destination.trim().isEmpty()) {
                    payload.put("destination", destination.trim());
                }
                if (routeLink != null && !routeLink.trim().isEmpty()) {
                    payload.put("routeLink", routeLink.trim());
                }
                if (!routePoints.isEmpty()) {
                    payload.put("routePoints", routePoints);
                }

                URL url = new URL(serverUrl + "/alerts/return-completed");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }

                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    post(callback, true, "보호자에게 귀가 완료 알림을 요청했습니다.");
                } else {
                    post(callback, false, "귀가 완료 알림 실패: " + status);
                }
            } catch (Exception e) {
                post(callback, false, "귀가 완료 알림 실패: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    static void sendRouteDeviation(Context context, Location location, int offRouteMeters, Callback callback) {
        SharedPreferences prefs = SafeWayPrefs.get(context);
        String serverUrl = normalizeBaseUrl(prefs.getString(SafeWayPrefs.PUSH_SERVER_URL, ""));
        String guardianToken = prefs.getString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, "");
        String destination = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, "");
        String routeDestination = getRouteDestination(prefs, destination);
        String routePoints = compactRoutePoints(prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, ""));
        int expectedMinutes = prefs.getInt(SafeWayPrefs.ROUTE_EXPECTED_MINUTES, 0);

        if (serverUrl.isEmpty() || guardianToken == null || guardianToken.trim().isEmpty()) {
            post(callback, false, "푸시 서버 주소 또는 보호자 토큰이 없습니다.");
            return;
        }
        if (location == null) {
            post(callback, false, "현재 위치가 없어 경로 이탈 알림을 보낼 수 없습니다.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("guardianToken", guardianToken.trim());
                payload.put("status", "deviated");
                payload.put("title", "SafeWay 경로 이탈 감지");
                payload.put("body", "자녀가 설정한 귀가 경로에서 약 " + Math.max(1, offRouteMeters) + "m 벗어났습니다. 현재 위치를 확인하세요.");
                payload.put("mapsLink", buildMapsLink(location));
                payload.put("routeLink", buildDirectionsLink(location, routeDestination));
                if (!routePoints.isEmpty()) {
                    payload.put("routePoints", routePoints);
                }
                payload.put("latitude", String.format(Locale.US, "%.7f", location.getLatitude()));
                payload.put("longitude", String.format(Locale.US, "%.7f", location.getLongitude()));
                payload.put("offRouteMeters", String.valueOf(Math.max(1, offRouteMeters)));
                if (destination != null && !destination.trim().isEmpty()) {
                    payload.put("destination", destination.trim());
                }
                if (expectedMinutes > 0) {
                    payload.put("expectedMinutes", String.valueOf(expectedMinutes));
                }

                connection = openJsonPost(serverUrl + "/alerts/route-deviation", payload);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    post(callback, true, "보호자에게 경로 이탈 알림을 요청했습니다.");
                } else {
                    post(callback, false, "경로 이탈 알림 실패: " + status + " " + readResponse(connection));
                }
            } catch (Exception e) {
                post(callback, false, "경로 이탈 알림 실패: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    static void sendReturnLocationUpdate(Context context, Location location, Callback callback) {
        if (location == null || !shouldSendLiveLocationUpdate(location)) {
            return;
        }

        SharedPreferences prefs = SafeWayPrefs.get(context);
        if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false)) {
            return;
        }

        String serverUrl = normalizeBaseUrl(prefs.getString(SafeWayPrefs.PUSH_SERVER_URL, ""));
        String guardianToken = prefs.getString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, "");
        String destination = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, "");
        String routeDestination = getRouteDestination(prefs, destination);
        String routePoints = compactRoutePoints(prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, ""));
        int expectedMinutes = prefs.getInt(SafeWayPrefs.ROUTE_EXPECTED_MINUTES, 0);

        if (serverUrl.isEmpty() || guardianToken == null || guardianToken.trim().isEmpty()) {
            post(callback, false, "푸시 서버 주소 또는 보호자 토큰이 없습니다.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("guardianToken", guardianToken.trim());
                payload.put("status", "active");
                payload.put("title", "SafeWay 실시간 위치");
                payload.put("body", "안심귀가 중입니다. 위치가 실시간으로 업데이트되고 있습니다.");
                payload.put("mapsLink", buildMapsLink(location));
                if (destination != null && !destination.trim().isEmpty()) {
                    payload.put("destination", destination.trim());
                    payload.put("routeLink", buildDirectionsLink(location, routeDestination));
                }
                if (!routePoints.isEmpty()) {
                    payload.put("routePoints", routePoints);
                }
                if (expectedMinutes > 0) {
                    payload.put("expectedMinutes", String.valueOf(expectedMinutes));
                }
                payload.put("latitude", String.format(Locale.US, "%.7f", location.getLatitude()));
                payload.put("longitude", String.format(Locale.US, "%.7f", location.getLongitude()));

                connection = openJsonPost(serverUrl + "/alerts/return-location-update", payload);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    post(callback, true, "보호자 위치 상태를 업데이트했습니다.");
                } else {
                    post(callback, false, "보호자 위치 업데이트 실패: " + status + " " + readResponse(connection));
                }
            } catch (Exception e) {
                post(callback, false, "보호자 위치 업데이트 실패: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private static boolean shouldSendLiveLocationUpdate(Location location) {
        long now = System.currentTimeMillis();
        synchronized (LIVE_LOCATION_UPDATE_LOCK) {
            if (now - lastLiveLocationUpdateSentAt < LIVE_LOCATION_UPDATE_INTERVAL_MS) {
                return false;
            }
            if (!Double.isNaN(lastLiveLocationUpdateLatitude) && !Double.isNaN(lastLiveLocationUpdateLongitude)) {
                float[] results = new float[1];
                Location.distanceBetween(
                        lastLiveLocationUpdateLatitude,
                        lastLiveLocationUpdateLongitude,
                        location.getLatitude(),
                        location.getLongitude(),
                        results
                );
                if (results[0] < LIVE_LOCATION_UPDATE_MIN_DISTANCE_METERS) {
                    return false;
                }
            }
            lastLiveLocationUpdateSentAt = now;
            lastLiveLocationUpdateLatitude = location.getLatitude();
            lastLiveLocationUpdateLongitude = location.getLongitude();
            return true;
        }
    }

    static void checkServerHealth(String serverUrl, Callback callback) {
        String normalizedUrl = normalizeBaseUrl(serverUrl);
        if (normalizedUrl.isEmpty()) {
            post(callback, false, "푸시 서버 주소를 입력해주세요.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(normalizedUrl + "/health");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    post(callback, true, "서버 연결이 정상입니다.");
                } else {
                    post(callback, false, "서버 응답 오류: " + status);
                }
            } catch (Exception e) {
                post(callback, false, "서버 연결 실패: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    static void sendAiDangerAlert(Context context, String userText, Location location, Callback callback) {
        SharedPreferences prefs = SafeWayPrefs.get(context);
        String serverUrl = normalizeBaseUrl(prefs.getString(SafeWayPrefs.PUSH_SERVER_URL, ""));
        String guardianToken = prefs.getString(SafeWayPrefs.GUARDIAN_PUSH_TOKEN, "");
        String destination = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION, "");
        String routeDestination = getRouteDestination(prefs, destination);
        String routeLink = prefs.getString(SafeWayPrefs.ROUTE_LAST_LINK, "");
        String routePoints = compactRoutePoints(prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, ""));
        int expectedMinutes = prefs.getInt(SafeWayPrefs.ROUTE_EXPECTED_MINUTES, 0);

        if (serverUrl.isEmpty() || guardianToken == null || guardianToken.trim().isEmpty()) {
            post(callback, false, "푸시 서버 주소 또는 보호자 토큰이 없어 위험 알림을 보낼 수 없습니다.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String utterance = truncateForAlert(userText, 48);
                JSONObject payload = new JSONObject();
                payload.put("guardianToken", guardianToken.trim());
                payload.put("status", "danger");
                payload.put("title", "SafeWay AI 위험 감지");
                payload.put("body", utterance.isEmpty()
                        ? "자녀가 AI 통화 중 위험 신호를 보냈습니다. 현재 상태를 확인하세요."
                        : "자녀가 AI 통화 중 \"" + utterance + "\"라고 말했습니다. 현재 상태를 확인하세요.");
                if (location != null) {
                    payload.put("mapsLink", buildMapsLink(location));
                    payload.put("latitude", String.format(Locale.US, "%.7f", location.getLatitude()));
                    payload.put("longitude", String.format(Locale.US, "%.7f", location.getLongitude()));
                    if (routeLink == null || routeLink.trim().isEmpty()) {
                        payload.put("routeLink", buildDirectionsLink(location, routeDestination));
                    }
                }
                if (routeLink != null && !routeLink.trim().isEmpty()) {
                    payload.put("routeLink", routeLink.trim());
                }
                if (!routePoints.isEmpty()) {
                    payload.put("routePoints", routePoints);
                }
                if (destination != null && !destination.trim().isEmpty()) {
                    payload.put("destination", destination.trim());
                }
                if (expectedMinutes > 0) {
                    payload.put("expectedMinutes", String.valueOf(expectedMinutes));
                }

                connection = openJsonPost(serverUrl + "/alerts/ai-danger", payload);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    post(callback, true, "보호자에게 AI 위험 감지 알림을 요청했습니다.");
                } else {
                    post(callback, false, "AI 위험 알림 실패: " + status + " " + readResponse(connection));
                }
            } catch (Exception e) {
                post(callback, false, "AI 위험 알림 실패: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    static void sendTestAlert(String serverUrl, String guardianToken, Callback callback) {
        String normalizedUrl = normalizeBaseUrl(serverUrl);
        String token = guardianToken == null ? "" : guardianToken.trim();
        if (normalizedUrl.isEmpty()) {
            post(callback, false, "푸시 서버 주소를 입력해주세요.");
            return;
        }
        if (token.isEmpty()) {
            post(callback, false, "보호자 앱 푸시 토큰을 입력해주세요.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("guardianToken", token);

                URL url = new URL(normalizedUrl + "/alerts/test");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }

                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    post(callback, true, "테스트 알림을 보냈습니다.");
                } else {
                    post(callback, false, "테스트 알림 실패: " + status + " " + readResponse(connection));
                }
            } catch (Exception e) {
                post(callback, false, "테스트 알림 실패: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    static String buildMapsLink(Location location) {
        return buildKakaoLookLink(location.getLatitude(), location.getLongitude());
    }

    static String buildDirectionsLink(Location location, String destination) {
        double[] destinationLatLng = parseLatLng(destination);
        if (destinationLatLng != null) {
            if (location == null) {
                return buildKakaoLookLink(destinationLatLng[0], destinationLatLng[1]);
            }
            return Uri.parse("http://m.map.kakao.com/scheme/route").buildUpon()
                    .appendQueryParameter("sp", location.getLatitude() + "," + location.getLongitude())
                    .appendQueryParameter("ep", destinationLatLng[0] + "," + destinationLatLng[1])
                    .appendQueryParameter("by", "foot")
                    .build()
                    .toString();
        }
        return Uri.parse("http://m.map.kakao.com/scheme/search").buildUpon()
                .appendQueryParameter("q", destination == null ? "" : destination)
                .build()
                .toString();
    }

    private static String truncateForAlert(String value, int maxLength) {
        String normalized = value == null ? "" : value.replace("\n", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength)) + "...";
    }

    static String buildKakaoLookLink(double latitude, double longitude) {
        return Uri.parse("http://m.map.kakao.com/scheme/look").buildUpon()
                .appendQueryParameter("p", latitude + "," + longitude)
                .build()
                .toString();
    }

    private static double[] parseLatLng(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String getRouteDestination(SharedPreferences prefs, String fallback) {
        String lat = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION_LAT, "");
        String lng = prefs.getString(SafeWayPrefs.ROUTE_DESTINATION_LNG, "");
        if (lat != null && lng != null && !lat.trim().isEmpty() && !lng.trim().isEmpty()) {
            return lat.trim() + "," + lng.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String readResponse(HttpURLConnection connection) {
        try {
            InputStream stream = connection.getErrorStream();
            if (stream == null) {
                stream = connection.getInputStream();
            }
            if (stream == null) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            }
            return builder.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JSONObject parseJsonObject(String responseBody) throws JSONException {
        String trimmed = responseBody == null ? "" : responseBody.trim();
        if (!trimmed.startsWith("{")) {
            throw new JSONException("Response is not JSON");
        }
        return new JSONObject(trimmed);
    }

    static void saveGuardianStatusToPrefs(Context context, JSONObject state) {
        if (state == null) {
            return;
        }
        SharedPreferences prefs = SafeWayPrefs.get(context);
        String status = state.optString("status", "").trim();
        boolean resetLocationState = "linked".equals(status) || "notice".equals(status);
        long updatedAt = state.optLong("updatedAt", System.currentTimeMillis());
        if (updatedAt <= 0L) {
            updatedAt = System.currentTimeMillis();
        }

        prefs.edit()
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_TITLE, state.optString("title", ""))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_BODY, state.optString("body", ""))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_MAPS_LINK, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_MAPS_LINK, state.optString("mapsLink", "")))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_LINK, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_LINK, state.optString("routeLink", "")))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_POINTS, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_POINTS, state.optString("routePoints", "")))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_DESTINATION, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_DESTINATION, state.optString("destination", "")))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_STATUS, status)
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_LATITUDE, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_LATITUDE, state.optString("latitude", "")))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_LONGITUDE, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_LONGITUDE, state.optString("longitude", "")))
                .putInt(SafeWayPrefs.LATEST_GUARDIAN_ALERT_EXPECTED_MINUTES, resetLocationState ? 0 : keepExistingIntIfEmpty(prefs, state.optString("expectedMinutes", "")))
                .putLong(SafeWayPrefs.LATEST_GUARDIAN_ALERT_UPDATED_AT, updatedAt)
                .putString(SafeWayPrefs.GUARDIAN_ALERT_HISTORY_JSON, normalizeHistoryJson(state.optJSONArray("history")))
                .apply();
    }

    private static String normalizeHistoryJson(JSONArray history) {
        return history == null ? "[]" : history.toString();
    }

    private static String keepExistingIfEmpty(SharedPreferences prefs, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            return prefs.getString(key, "");
        }
        return value;
    }

    private static String compactRoutePoints(String routePoints) {
        if (routePoints == null || routePoints.trim().isEmpty()) {
            return "";
        }
        String[] pairs = routePoints.trim().split(";");
        if (pairs.length <= MAX_GUARDIAN_ROUTE_POINTS) {
            return routePoints.trim();
        }

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < MAX_GUARDIAN_ROUTE_POINTS; index++) {
            int sourceIndex = Math.round(index * (pairs.length - 1f) / (MAX_GUARDIAN_ROUTE_POINTS - 1f));
            String pair = pairs[sourceIndex] == null ? "" : pairs[sourceIndex].trim();
            if (pair.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(pair);
        }
        return builder.toString();
    }

    private static int keepExistingIntIfEmpty(SharedPreferences prefs, String value) {
        if (value == null || value.trim().isEmpty()) {
            return prefs.getInt(SafeWayPrefs.LATEST_GUARDIAN_ALERT_EXPECTED_MINUTES, 0);
        }
        return parsePositiveInt(value);
    }

    private static int parsePositiveInt(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String buildPairingEndpointError(int status, String responseBody) {
        if (status == 404 || (responseBody != null && responseBody.contains("Cannot POST"))) {
            return "연동 코드 API가 없습니다. 서버를 최신 코드로 재시작해주세요.";
        }
        return "연동 코드 요청 실패: 서버 응답 오류 " + status;
    }

    private static String buildConnectionFailureMessage(String prefix, String serverUrl, Exception e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage();
        if (serverUrl != null && serverUrl.contains("10.0.2.2")) {
            return prefix + ": 실제 휴대폰에서는 10.0.2.2 대신 PC IP 또는 HTTPS 주소를 입력해주세요.";
        }
        if (message.contains("Failed to connect") || message.contains("failed to connect")
                || message.contains("Connection refused")) {
            return prefix + ": 서버 실행 여부와 푸시 서버 주소를 확인해주세요.";
        }
        return prefix + ": " + message;
    }

    private static void post(Callback callback, boolean sent, String message) {
        if (callback == null) {
            return;
        }
        MAIN.post(() -> callback.onResult(sent, message));
    }

    private static void postPairingCode(PairingCodeCallback callback, boolean ok,
                                        PairingCodeResult result, String message) {
        if (callback == null) {
            return;
        }
        MAIN.post(() -> callback.onResult(ok, result, message));
    }

    private static void postGuardianLink(GuardianLinkCallback callback, boolean ok,
                                         GuardianLinkResult result, String message) {
        if (callback == null) {
            return;
        }
        MAIN.post(() -> callback.onResult(ok, result, message));
    }
}
