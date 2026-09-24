package com.safeway.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A single navigation owner for foreground UI, speech and background tracking. */
final class WalkingNavigator {
    interface Listener { void changed(String instruction, String detail); }
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final WalkingVoice voice;
    private final Listener listener;
    private WalkingNavigationEngine engine;
    private final List<WalkingNavigationEngine.Guide> guides = new ArrayList<>();
    private String key = "", warning = "", retryMessage = "";
    private final java.util.Set<String> spoken = new java.util.HashSet<>();
    private long lastVoice, lastAccepted, retryAt;
    private int failures;
    private long generation;
    private Future<?> request;
    private boolean inFlight, closed, alertInFlight;
    private Location latest;
    private WalkingNavigationEngine.Progress progress;
    private JSONObject state = new JSONObject();
    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (p, name) -> {
        if (SafeWayPrefs.NAV_VOICE.equals(name) && !p.getBoolean(name, true)) voiceStop();
    };
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (closed) return;
            reload();
            if (engine == null) publish("경로 계산이 필요합니다.", "실제 도보 경로를 계산한 뒤 안내를 시작하세요.", false);
            else if (latest == null || SystemClock.elapsedRealtime() - lastAccepted > 15000)
                publish("현재 위치를 확인하는 중입니다.", "GPS 신호를 기다립니다. 방향 안내와 재탐색을 잠시 멈춥니다.", false);
            main.postDelayed(this, 5000);
        }
    };

    WalkingNavigator(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        prefs = SafeWayPrefs.get(context);
        voice = new WalkingVoice(context);
        prefs.registerOnSharedPreferenceChangeListener(preferences);
        main.post(heartbeat);
    }

    private void voiceStop() { voice.stop(); }

    private void reload() {
        String current = WalkingNavigationState.routeKey(prefs);
        if (current.equals(key)) return;
        key = current;
        generation++;
        if (request != null) request.cancel(true);
        inFlight = false;
        engine = null; progress = null; latest = null; lastAccepted = 0;
        guides.clear(); warning = ""; retryMessage = ""; spoken.clear(); retryAt = 0; failures = 0;
        voice.stop();
        if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false)
                || !prefs.getBoolean(SafeWayPrefs.ROUTE_NAVIGABLE, false)) return;
        try {
            List<WalkingNavigationEngine.Point> points = new ArrayList<>();
            for (String pair : prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, "").split(";")) {
                String[] xy = pair.split(",");
                points.add(WalkingRouteClient.point(new JSONObject()
                        .put("latitude", Double.parseDouble(xy[0]))
                        .put("longitude", Double.parseDouble(xy[1]))));
            }
            JSONArray saved = new JSONArray(prefs.getString(SafeWayPrefs.ROUTE_LAST_GUIDES, "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject guide = saved.getJSONObject(i);
                guides.add(new WalkingNavigationEngine.Guide(WalkingRouteClient.point(guide), guide.getString("text")));
            }
            engine = new WalkingNavigationEngine(points, guides);
        } catch (Exception ignored) { engine = null; }
    }

    /** Returns true only for fresh, accurate, plausible fixes. */
    boolean location(Location location) {
        reload();
        long now = SystemClock.elapsedRealtime();
        WalkingNavigationEngine.Fix fix = new WalkingNavigationEngine.Fix(location.getLatitude(),
                location.getLongitude(), location.hasAccuracy() ? location.getAccuracy() : Double.NaN,
                location.getElapsedRealtimeNanos() / 1_000_000);
        if (engine == null) {
            publish("경로 계산이 필요합니다.", "직선 참고선으로는 도보 안내를 시작할 수 없습니다.", false);
            return WalkingNavigationEngine.valid(fix, now);
        }
        progress = engine.update(fix, now);
        if (!progress.accepted) {
            publish("위치 정확도를 확인하는 중입니다.", "GPS 오차가 줄어들면 안내를 이어갑니다.", false);
            return false;
        }
        latest = new Location(location);
        lastAccepted = fix.time;
        if (progress.offRoute) {
            if (inFlight) publish("새로운 도보 경로를 찾는 중입니다.", "현재 위치에서 도착지까지 다시 계산합니다.", false);
            else {
                publish("경로를 벗어났습니다.", retryMessage.isEmpty()
                        ? "현재 위치에서 경로를 다시 찾습니다." : retryMessage, false);
                reroute();
            }
            alertDeviation(location);
            say("offroute", "경로를 벗어났습니다. 새로운 도보 경로를 찾습니다.");
        } else if (progress.deviation > Math.max(30, location.getAccuracy() * 1.5)) {
            publish("경로 위치를 확인하고 있습니다.", "주변 도로와 지도를 확인하세요.", false);
        } else if (progress.arrived) {
            publish("도착지 근처입니다.", "주변을 확인한 뒤 귀가 완료 버튼을 눌러주세요.", true);
            say("arrived", "도착지 근처입니다. 주변을 확인한 뒤 귀가 완료를 눌러 주세요.");
        } else {
            String instruction = progress.guide >= 0 ? guides.get(progress.guide).text : "표시된 보행 경로를 따라 이동하세요.";
            int next = progress.guide + 1;
            String detail = next < guides.size()
                    ? "다음 안내까지 " + WalkingNavigationState.distance(progress.nextDistance)
                    : "도착지까지 " + WalkingNavigationState.distance(progress.remaining);
            if (!warning.isEmpty()) detail += " · " + warning;
            if (inFlight) detail += " · 경로 다시 계산 중";
            publish(instruction, detail, true);
            if (next < guides.size() && progress.nextDistance > 12 && progress.nextDistance <= 45) {
                say("soon-" + next, "약 " + Math.round(progress.nextDistance) + "미터 후 " + guides.get(next).text);
            } else say("step-" + progress.guide, instruction);
        }
        return true;
    }

    void reroute() {
        reload();
        long now = SystemClock.elapsedRealtime();
        if (closed || engine == null || latest == null || inFlight || now < retryAt
                || now - lastAccepted > 15000 || progress == null || !progress.accepted) return;
        String server = prefs.getString(SafeWayPrefs.PUSH_SERVER_URL, "").trim();
        if (server.isEmpty()) {
            retryAt = now + 60000;
            retryMessage = "서버 연결 설정을 확인하세요. 기존 경로는 지도에 남아 있습니다.";
            publish("경로를 다시 찾을 수 없습니다.", retryMessage, false);
            return;
        }
        final String expectedKey = key;
        final long expectedGeneration = generation;
        final Location origin = new Location(latest);
        final WalkingNavigationEngine.Point destination;
        try {
            destination = WalkingRouteClient.point(new JSONObject()
                    .put("latitude", Double.parseDouble(prefs.getString(SafeWayPrefs.ROUTE_DESTINATION_LAT, "")))
                    .put("longitude", Double.parseDouble(prefs.getString(SafeWayPrefs.ROUTE_DESTINATION_LNG, ""))));
        } catch (Exception error) { return; }
        String mode = prefs.getString(SafeWayPrefs.ROUTE_MODE, "BROAD_FIRST");
        List<WalkingNavigationEngine.Point> dangers = new ArrayList<>();
        AppDatabase db = new AppDatabase(context);
        try {
            for (AppDatabase.DangerMemo memo : db.getDangerMemos()) {
                try {
                    dangers.add(WalkingRouteClient.point(new JSONObject()
                            .put("latitude", Double.parseDouble(memo.latitude))
                            .put("longitude", Double.parseDouble(memo.longitude))));
                } catch (Exception ignored) { }
            }
        } finally { db.close(); }
        inFlight = true;
        retryAt = now + 30000;
        publish("새로운 도보 경로를 찾는 중입니다.", "현재 위치에서 도착지까지 다시 계산합니다.", false);
        request = network.submit(() -> {
            try {
                WalkingRouteClient.Route route = WalkingRouteClient.fetch(server,
                        new WalkingNavigationEngine.Point(origin.getLatitude(), origin.getLongitude()),
                        destination, mode, dangers);
                main.post(() -> {
                    if (!current(expectedKey, expectedGeneration)) return;
                    inFlight = false;
                    if (latest == null || SystemClock.elapsedRealtime() - lastAccepted > 15000
                            || origin.distanceTo(latest) > 60) {
                        retryMessage = "이동한 위치를 확인한 뒤 경로를 다시 찾습니다.";
                        return;
                    }
                    Location currentLocation = new Location(latest);
                    String link = PushAlertClient.buildDirectionsLink(origin,
                            destination.lat + "," + destination.lng);
                    prefs.edit()
                            .putString(SafeWayPrefs.ROUTE_LAST_POINTS, route.serialize())
                            .putString(SafeWayPrefs.ROUTE_LAST_GUIDES, route.guideJson.toString())
                            .putString(SafeWayPrefs.ROUTE_LAST_LINK, link)
                            .putInt(SafeWayPrefs.ROUTE_LAST_DISTANCE_METERS, (int) Math.round(route.distance))
                            .putInt(SafeWayPrefs.ROUTE_EXPECTED_MINUTES, Math.max(1, (int) Math.ceil(route.seconds / 60)))
                            .putBoolean(SafeWayPrefs.ROUTE_NAVIGABLE, true).apply();
                    reload();
                    warning = route.warning;
                    retryAt = SystemClock.elapsedRealtime() + 30000;
                    location(currentLocation);
                    say("rerouted", "새로운 도보 경로로 안내합니다.");
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!current(expectedKey, expectedGeneration)) return;
                    inFlight = false;
                    failures = Math.min(4, failures + 1);
                    retryAt = SystemClock.elapsedRealtime() + Math.min(300000, 15000L << failures);
                    retryMessage = "재탐색에 실패했습니다. 기존 경로를 유지하고 잠시 후 다시 시도합니다.";
                    publish("경로를 다시 찾지 못했습니다.", retryMessage, false);
                });
            }
        });
    }

    private boolean current(String expectedKey, long expectedGeneration) {
        return !closed && generation == expectedGeneration
                && expectedKey.equals(WalkingNavigationState.routeKey(prefs));
    }

    private void alertDeviation(Location location) {
        long now = System.currentTimeMillis();
        if (alertInFlight || now - prefs.getLong(SafeWayPrefs.NAV_ALERT_TIME, 0) < 300000) return;
        alertInFlight = true;
        // Throttle attempts too, so an unavailable push server cannot cause a request storm.
        prefs.edit().putLong(SafeWayPrefs.NAV_ALERT_TIME, now).apply();
        PushAlertClient.sendRouteDeviation(context, location, (int) Math.round(progress.deviation),
                (sent, message) -> main.post(() -> alertInFlight = false));
    }

    private void say(String id, String text) {
        long now = SystemClock.elapsedRealtime();
        if (!prefs.getBoolean(SafeWayPrefs.NAV_VOICE, true) || spoken.contains(id)
                || now - lastVoice < 8000) return;
        if (voice.speak(text)) { spoken.add(id); lastVoice = now; }
    }

    private void publish(String instruction, String detail, boolean guidance) {
        if (closed) return;
        if (!guidance && state.optBoolean("guidance")) voice.stop();
        try {
            JSONObject next = new JSONObject()
                    .put("session", prefs.getLong(SafeWayPrefs.START_TIME, 0))
                    .put("routeKey", key).put("updated", SystemClock.elapsedRealtime())
                    .put("instruction", instruction).put("detail", detail)
                    .put("guidance", guidance).put("voiceReady", voice.available())
                    .put("remaining", guidance && progress != null ? progress.remaining : -1)
                    .put("seconds", guidance && progress != null ? progress.remaining / 1.2 : -1)
                    .put("segment", progress == null ? 0 : progress.segment);
            if (latest != null) next.put("latitude", latest.getLatitude()).put("longitude", latest.getLongitude());
            state = next;
            prefs.edit().putString(SafeWayPrefs.NAV_STATE, state.toString()).apply();
            listener.changed(instruction, detail);
        } catch (Exception ignored) { }
    }

    void close() {
        closed = true;
        generation++;
        main.removeCallbacks(heartbeat);
        prefs.unregisterOnSharedPreferenceChangeListener(preferences);
        if (request != null) request.cancel(true);
        network.shutdownNow();
        voice.close();
        prefs.edit().remove(SafeWayPrefs.NAV_STATE).apply();
    }
}
