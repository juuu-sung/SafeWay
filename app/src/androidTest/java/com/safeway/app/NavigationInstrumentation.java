package com.safeway.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Device integration tests using synthetic public coordinates and a loopback route server.
 * No Kakao request or guardian message is sent; preferences are restored in finally.
 */
public final class NavigationInstrumentation extends Instrumentation {
    private SharedPreferences prefs;
    private WalkingNavigator navigator;
    private int assertions;
    private Activity activity;
    private LocationManager locations;
    private boolean mockInstalled;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        prefs = SafeWayPrefs.get(getTargetContext());
        Map<String, ?> backup = prefs.getAll();
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_OK;
        try {
            getTargetContext().stopService(new Intent(getTargetContext(), ReturnLocationService.class));
            setup();
            navigationStateAndFailures();
            delayedResponseCannotOverwriteDestination();
            automaticRerouteCannotOverwriteCompletedReturn();
            successfulReroute();
            backgroundService();
            result.putString("stream", "PASS: " + assertions + " Android navigation assertions\n");
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
            resultCode = Activity.RESULT_CANCELED;
        } finally {
            runOnMainSync(() -> {
                if (navigator != null) navigator.close();
                if (activity != null) activity.finish();
                getTargetContext().stopService(new Intent(getTargetContext(), ReturnLocationService.class));
                if (mockInstalled) locations.removeTestProvider(LocationManager.GPS_PROVIDER);
            });
            waitForIdleSync();
            SharedPreferences.Editor edit = prefs.edit().clear();
            for (Map.Entry<String, ?> e : backup.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) edit.putString(e.getKey(), (String)v);
                else if (v instanceof Boolean) edit.putBoolean(e.getKey(), (Boolean)v);
                else if (v instanceof Long) edit.putLong(e.getKey(), (Long)v);
                else if (v instanceof Integer) edit.putInt(e.getKey(), (Integer)v);
                else if (v instanceof Float) edit.putFloat(e.getKey(), (Float)v);
            }
            edit.commit();
        }
        finish(resultCode, result);
    }

    private void setup() {
        prefs.edit().clear()
                .putBoolean(SafeWayPrefs.RETURNING, true)
                .putLong(SafeWayPrefs.START_TIME, System.currentTimeMillis())
                .putBoolean(SafeWayPrefs.ROUTE_NAVIGABLE, true)
                .putBoolean(SafeWayPrefs.NAV_VOICE, false)
                .putString(SafeWayPrefs.ROUTE_DESTINATION, "공개 좌표 안내 테스트")
                .putString(SafeWayPrefs.ROUTE_DESTINATION_LAT, "37.5692")
                .putString(SafeWayPrefs.ROUTE_DESTINATION_LNG, "126.978")
                .putString(SafeWayPrefs.ROUTE_MODE, "BROAD_FIRST")
                .putString(SafeWayPrefs.ROUTE_LAST_POINTS, "37.5665,126.978;37.5674,126.978;37.5692,126.978")
                .putString(SafeWayPrefs.ROUTE_LAST_GUIDES,
                        "[{\"latitude\":37.5665,\"longitude\":126.978,\"text\":\"100미터 직진\"},"
                                + "{\"latitude\":37.5674,\"longitude\":126.978,\"text\":\"계속 직진\"}]")
                .commit();
    }

    private Location fix(double latitude) {
        Location fix = new Location(LocationManager.GPS_PROVIDER);
        fix.setLatitude(latitude); fix.setLongitude(126.978);
        fix.setAccuracy(5); fix.setTime(System.currentTimeMillis());
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return fix;
    }

    private void createNavigator() {
        runOnMainSync(() -> navigator = new WalkingNavigator(getTargetContext(), (a, b) -> {}));
    }

    private void closeNavigator() {
        runOnMainSync(() -> { navigator.close(); navigator = null; });
    }

    private void navigationStateAndFailures() throws Exception {
        createNavigator();
        runOnMainSync(() -> navigator.location(fix(37.5668)));
        check(WalkingNavigationState.read(prefs).optBoolean("guidance"), "fresh fix starts guidance");
        check(WalkingNavigationState.read(prefs).optDouble("remaining") > 200, "remaining distance");
        Location inaccurate = fix(37.5669); inaccurate.setAccuracy(150);
        runOnMainSync(() -> navigator.location(inaccurate));
        check(!WalkingNavigationState.read(prefs).optBoolean("guidance"), "bad GPS pauses guidance");
        SystemClock.sleep(10);
        runOnMainSync(() -> navigator.location(fix(37.56681)));
        try (RouteServer server = new RouteServer(true, false)) {
            prefs.edit().putString(SafeWayPrefs.PUSH_SERVER_URL, server.url()).commit();
            String old = prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, "");
            runOnMainSync(() -> navigator.reroute());
            check(server.requested.await(5, TimeUnit.SECONDS), "reroute was requested");
            awaitInstruction("다시 찾지 못했습니다");
            check(old.equals(prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, "")), "fallback cannot replace active route");
            runOnMainSync(() -> navigator.reroute());
            check(server.requests == 1, "failure backoff prevents repeated request");
        }
        prefs.edit().putBoolean(SafeWayPrefs.ROUTE_NAVIGABLE, false).commit();
        runOnMainSync(() -> navigator.location(fix(37.5668)));
        check(!WalkingNavigationState.read(prefs).optBoolean("guidance"), "straight-line route never navigates");
        closeNavigator();
    }

    private void delayedResponseCannotOverwriteDestination() throws Exception {
        setup(); createNavigator();
        runOnMainSync(() -> navigator.location(fix(37.5668)));
        try (RouteServer server = new RouteServer(false, true)) {
            prefs.edit().putString(SafeWayPrefs.PUSH_SERVER_URL, server.url()).commit();
            String old = prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, "");
            runOnMainSync(() -> navigator.reroute());
            check(server.requested.await(5, TimeUnit.SECONDS), "delayed reroute began");
            prefs.edit().putString(SafeWayPrefs.ROUTE_DESTINATION_LAT, "37.5700").commit();
            server.release.countDown();
            check(server.responded.await(5, TimeUnit.SECONDS), "delayed response delivered");
            SystemClock.sleep(300);
            waitForIdleSync();
            check(old.equals(prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, "")), "new destination rejects old result");
        }
        closeNavigator();
    }

    private void successfulReroute() throws Exception {
        setup(); createNavigator();
        runOnMainSync(() -> navigator.location(fix(37.5668)));
        try (RouteServer server = new RouteServer(false, false)) {
            prefs.edit().putString(SafeWayPrefs.PUSH_SERVER_URL, server.url()).commit();
            runOnMainSync(() -> navigator.reroute());
            check(server.responded.await(5, TimeUnit.SECONDS), "walking response delivered");
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (prefs.getInt(SafeWayPrefs.ROUTE_LAST_DISTANCE_METERS, 0) != 280
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            check(prefs.getInt(SafeWayPrefs.ROUTE_LAST_DISTANCE_METERS, 0) == 280, "new route stored atomically");
            // The preference write happens before the main-thread callback publishes
            // its new snapshot. Wait for that callback, not just its first write.
            waitForIdleSync();
            check(WalkingNavigationState.read(prefs).optBoolean("guidance"), "guidance resumes on new route");
        }
        closeNavigator();
    }

    private void automaticRerouteCannotOverwriteCompletedReturn() throws Exception {
        setup(); createNavigator();
        try (RouteServer server = new RouteServer(false, true)) {
            prefs.edit().putString(SafeWayPrefs.PUSH_SERVER_URL, server.url()).commit();
            String old = prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, "");
            long now = SystemClock.elapsedRealtimeNanos();
            for (int i = 0; i < 3; i++) {
                Location outside = fix(37.5668);
                outside.setLongitude(126.979);
                outside.setElapsedRealtimeNanos(now - (2 - i) * 5_000_000_000L);
                runOnMainSync(() -> navigator.location(outside));
            }
            check(server.requested.await(5, TimeUnit.SECONDS), "confirmed deviation automatically reroutes");
            prefs.edit().putBoolean(SafeWayPrefs.RETURNING, false).commit();
            server.release.countDown();
            check(server.responded.await(5, TimeUnit.SECONDS), "response arrives after return completion");
            SystemClock.sleep(300);
            waitForIdleSync();
            check(old.equals(prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, "")), "completed return rejects old result");
            check(WalkingNavigationState.read(prefs).length() == 0, "completed return never resumes guidance");
        }
        closeNavigator();
    }

    private void backgroundService() throws Exception {
        setup();
        prefs.edit().putBoolean(SafeWayPrefs.RETURNING, false).commit();
        activity = startActivitySync(new Intent(getTargetContext(), WalkingNaviActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        prefs.edit().putBoolean(SafeWayPrefs.RETURNING, true).commit();
        locations = (LocationManager) getTargetContext().getSystemService(Context.LOCATION_SERVICE);
        runOnMainSync(() -> {
            locations.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false,
                    false, true, true, true, 1, 1);
            mockInstalled = true;
            locations.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            getTargetContext().startForegroundService(new Intent(getTargetContext(), ReturnLocationService.class));
        });
        waitForIdleSync();
        runOnMainSync(() -> activity.moveTaskToBack(true));
        for (int i = 0; i < 3; i++) {
            final double lat = 37.5666 + i * .00002;
            runOnMainSync(() -> locations.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix(lat)));
            SystemClock.sleep(3200);
        }
        check(WalkingNavigationState.read(prefs).optBoolean("guidance"), "background service continues guidance");
        NotificationManager manager = getTargetContext().getSystemService(NotificationManager.class);
        check(manager.getActiveNotifications().length > 0, "persistent navigation notification");
        getTargetContext().startService(new Intent(getTargetContext(), ReturnLocationService.class)
                .setAction(ReturnLocationService.ACTION_MUTE));
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!prefs.getBoolean(SafeWayPrefs.NAV_VOICE, false)
                && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        check(prefs.getBoolean(SafeWayPrefs.NAV_VOICE, false), "notification voice toggle");
        runOnMainSync(() -> {
            prefs.edit().putBoolean(SafeWayPrefs.RETURNING, false).apply();
            getTargetContext().stopService(new Intent(getTargetContext(), ReturnLocationService.class));
        });
        waitForIdleSync();
        check(WalkingNavigationState.read(prefs).length() == 0, "completed return clears navigation");
    }

    private void awaitInstruction(String part) throws Exception {
        long end = SystemClock.elapsedRealtime() + 5000;
        while (!WalkingNavigationState.read(prefs).optString("instruction").contains(part)
                && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50);
        check(WalkingNavigationState.read(prefs).optString("instruction").contains(part), "state: " + part);
    }

    private void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static final class RouteServer implements AutoCloseable {
        final ServerSocket server;
        final CountDownLatch requested = new CountDownLatch(1), responded = new CountDownLatch(1),
                release = new CountDownLatch(1);
        volatile int requests;
        RouteServer(boolean fallback, boolean delayed) throws Exception {
            server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    int length = 0;
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                            length = Integer.parseInt(line.substring(15).trim());
                    }
                    for (int i = 0; i < length; i++) if (reader.read() == -1) break;
                    requests++; requested.countDown();
                    if (delayed) release.await(8, TimeUnit.SECONDS);
                    String json = "{\"ok\":true,\"fallback\":" + fallback + ",\"source\":\"kakao_map_walking\","
                            + "\"distanceMeters\":280,\"duration\":\"250s\","
                            + "\"points\":[{\"latitude\":37.5668,\"longitude\":126.978},"
                            + "{\"latitude\":37.5692,\"longitude\":126.978}],\"guides\":[]}";
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(bytes);
                    socket.getOutputStream().flush();
                    responded.countDown();
                } catch (Exception ignored) { }
            }, "navigation-test-server");
            thread.setDaemon(true);
            thread.start();
        }
        String url() { return "http://127.0.0.1:" + server.getLocalPort(); }
        @Override public void close() throws Exception { release.countDown(); server.close(); }
    }
}
