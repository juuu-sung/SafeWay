package com.safeway.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AiClient {
    interface ChatCallback {
        void onResult(boolean ok, AiResult result, String message);
    }

    interface SummaryCallback {
        void onResult(boolean ok, String summary, String message);
    }

    interface SpeechCallback {
        void onResult(boolean ok, byte[] audioBytes, String message);
    }

    static final class ChatMessage {
        final String role;
        final String content;

        ChatMessage(String role, String content) {
            this.role = role == null ? "" : role;
            this.content = content == null ? "" : content;
        }
    }

    static final class AiResult {
        final String reply;
        final boolean danger;
        final String safetyAction;
        final String summary;

        AiResult(String reply, boolean danger, String safetyAction, String summary) {
            this.reply = reply == null ? "" : reply;
            this.danger = danger;
            this.safetyAction = safetyAction == null ? "" : safetyAction;
            this.summary = summary == null ? "" : summary;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AiClient() {
    }

    static void sendChat(String serverUrl, String userText, String mode,
                         List<ChatMessage> messages, ChatCallback callback) {
        String normalizedUrl = normalizeBaseUrl(serverUrl);
        if (normalizedUrl.isEmpty()) {
            postChat(callback, false, null, "AI 서버 주소가 없습니다.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("userText", userText == null ? "" : userText);
                payload.put("mode", mode == null ? "보호자" : mode);
                payload.put("messages", toJsonMessages(messages));

                connection = openJsonPost(normalizedUrl + "/ai/chat", payload);
                int status = connection.getResponseCode();
                JSONObject response = new JSONObject(readResponse(connection));
                if (status >= 200 && status < 300 && response.optBoolean("ok")) {
                    AiResult result = new AiResult(
                            response.optString("reply", ""),
                            response.optBoolean("danger", false),
                            response.optString("safetyAction", ""),
                            response.optString("summary", "")
                    );
                    postChat(callback, true, result, "AI 응답을 받았습니다.");
                } else {
                    postChat(callback, false, null, response.optString("detail", "AI 응답 실패: " + status));
                }
            } catch (Exception e) {
                postChat(callback, false, null, "AI 응답 실패: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    static void summarize(String serverUrl, List<ChatMessage> messages, SummaryCallback callback) {
        String normalizedUrl = normalizeBaseUrl(serverUrl);
        if (normalizedUrl.isEmpty()) {
            postSummary(callback, false, "", "AI 서버 주소가 없습니다.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("messages", toJsonMessages(messages));

                connection = openJsonPost(normalizedUrl + "/ai/summary", payload);
                int status = connection.getResponseCode();
                JSONObject response = new JSONObject(readResponse(connection));
                if (status >= 200 && status < 300 && response.optBoolean("ok")) {
                    postSummary(callback, true, response.optString("summary", ""), "AI 요약을 저장했습니다.");
                } else {
                    postSummary(callback, false, "", response.optString("detail", "AI 요약 실패: " + status));
                }
            } catch (Exception e) {
                postSummary(callback, false, "", "AI 요약 실패: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    static void synthesizeSpeech(String serverUrl, String input, String mode, SpeechCallback callback) {
        String normalizedUrl = normalizeBaseUrl(serverUrl);
        if (normalizedUrl.isEmpty()) {
            postSpeech(callback, false, null, "AI 서버 주소가 없습니다.");
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("input", input == null ? "" : input);
                payload.put("mode", mode == null ? "보호자" : mode);

                connection = openJsonPost(normalizedUrl + "/ai/speech", payload);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    postSpeech(callback, true, readBinaryResponse(connection), "AI 음성을 받았습니다.");
                } else {
                    JSONObject response = new JSONObject(readResponse(connection));
                    postSpeech(callback, false, null, response.optString("detail", "AI 음성 생성 실패: " + status));
                }
            } catch (Exception e) {
                postSpeech(callback, false, null, "AI 음성 생성 실패: " + e.getMessage());
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
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        return connection;
    }

    private static JSONArray toJsonMessages(List<ChatMessage> messages) throws Exception {
        JSONArray array = new JSONArray();
        if (messages == null) {
            return array;
        }
        int start = Math.max(0, messages.size() - 10);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message == null || message.role.trim().isEmpty() || message.content.trim().isEmpty()) {
                continue;
            }
            JSONObject object = new JSONObject();
            object.put("role", message.role);
            object.put("content", message.content);
            array.put(object);
        }
        return array;
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
                return "{}";
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
            return "{}";
        }
    }

    private static byte[] readBinaryResponse(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = stream.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
        stream.close();
        return output.toByteArray();
    }

    private static void postChat(ChatCallback callback, boolean ok, AiResult result, String message) {
        if (callback == null) {
            return;
        }
        MAIN.post(() -> callback.onResult(ok, result, message));
    }

    private static void postSummary(SummaryCallback callback, boolean ok, String summary, String message) {
        if (callback == null) {
            return;
        }
        MAIN.post(() -> callback.onResult(ok, summary, message));
    }

    private static void postSpeech(SpeechCallback callback, boolean ok, byte[] audioBytes, String message) {
        if (callback == null) {
            return;
        }
        MAIN.post(() -> callback.onResult(ok, audioBytes, message));
    }
}
