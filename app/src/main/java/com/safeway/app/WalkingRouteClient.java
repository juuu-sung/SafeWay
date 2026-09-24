package com.safeway.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Only genuine walking responses can replace the active navigation route. */
final class WalkingRouteClient {
    static final class Route {
        final List<WalkingNavigationEngine.Point> points = new ArrayList<>();
        final List<WalkingNavigationEngine.Guide> guides = new ArrayList<>();
        JSONArray guideJson;
        double distance, seconds;
        String warning = "";
        String serialize() {
            StringBuilder text = new StringBuilder();
            for (WalkingNavigationEngine.Point p : points) {
                if (text.length() > 0) text.append(';');
                text.append(p.lat).append(',').append(p.lng);
            }
            return text.toString();
        }
    }

    static Route fetch(String server, WalkingNavigationEngine.Point origin,
                       WalkingNavigationEngine.Point destination, String mode,
                       List<WalkingNavigationEngine.Point> dangers) throws Exception {
        Route best = request(server, origin, destination, mode, null);
        int bestDanger = dangerCount(best, dangers);
        if (bestDanger == 0) return best;
        // Try both sides of the first affected memo; never assume the result avoids every memo.
        WalkingNavigationEngine.Point danger = null;
        for (WalkingNavigationEngine.Point point : dangers) {
            if (nearRoute(best, point)) { danger = point; break; }
        }
        if (danger == null) return best;
        double scale = Math.max(1, 111320 * Math.cos(Math.toRadians(danger.lat)));
        double dx = (destination.lng - origin.lng) * scale;
        double dy = (destination.lat - origin.lat) * 111320;
        double length = Math.max(1, Math.hypot(dx, dy));
        for (int sign : new int[]{1, -1}) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            WalkingNavigationEngine.Point via = new WalkingNavigationEngine.Point(
                    danger.lat + sign * dx / length * 260 / 111320,
                    danger.lng - sign * dy / length * 260 / scale);
            try {
                Route candidate = request(server, origin, destination, mode, via);
                int count = dangerCount(candidate, dangers);
                if (count < bestDanger || (count == bestDanger && candidate.distance < best.distance)) {
                    best = candidate;
                    bestDanger = count;
                }
                if (count == 0) break;
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()) throw error;
            }
        }
        if (bestDanger > 0) best.warning = "위험 메모 근처를 지납니다. 주변을 확인하세요.";
        return best;
    }

    private static Route request(String server, WalkingNavigationEngine.Point origin,
                                 WalkingNavigationEngine.Point destination, String mode,
                                 WalkingNavigationEngine.Point via) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        JSONObject body = new JSONObject().put("origin", json(origin))
                .put("destination", json(destination)).put("routeMode", mode);
        if (via != null) body.put("waypoints", new JSONArray().put(json(via)));
        HttpURLConnection connection = (HttpURLConnection) new URL(
                server.replaceAll("/+$", "") + "/routes/compute").openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(data); }
            if (connection.getResponseCode() != 200) throw new java.io.IOException("경로 서버 연결 실패");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = in.read(chunk)) != -1) {
                    if (bytes.size() + count > 4_000_000) throw new java.io.IOException("경로 응답이 너무 큽니다");
                    bytes.write(chunk, 0, count);
                }
            }
            JSONObject response = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if (!response.optBoolean("ok") || response.optBoolean("fallback", true)
                    || !"kakao_map_walking".equals(response.optString("source"))) {
                throw new java.io.IOException("실제 도보 경로를 찾지 못했습니다");
            }
            Route result = new Route();
            JSONArray points = response.getJSONArray("points");
            for (int i = 0; i < points.length(); i++) result.points.add(point(points.getJSONObject(i)));
            if (result.points.size() < 2) throw new java.io.IOException("경로 좌표가 없습니다");
            result.guideJson = response.optJSONArray("guides");
            if (result.guideJson == null) result.guideJson = new JSONArray();
            for (int i = 0; i < result.guideJson.length(); i++) {
                JSONObject guide = result.guideJson.getJSONObject(i);
                result.guides.add(new WalkingNavigationEngine.Guide(point(guide), guide.optString("text")));
            }
            result.distance = response.getDouble("distanceMeters");
            result.seconds = Double.parseDouble(response.getString("duration").replace("s", ""));
            if (!Double.isFinite(result.distance) || !Double.isFinite(result.seconds)
                    || result.distance <= 0 || result.seconds <= 0) throw new java.io.IOException("잘못된 경로 거리");
            return result;
        } finally { connection.disconnect(); }
    }

    static WalkingNavigationEngine.Point point(JSONObject json) throws Exception {
        double lat = json.getDouble("latitude"), lng = json.getDouble("longitude");
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180)
            throw new IllegalArgumentException("Invalid coordinate");
        return new WalkingNavigationEngine.Point(lat, lng);
    }

    private static JSONObject json(WalkingNavigationEngine.Point p) throws Exception {
        return new JSONObject().put("latitude", p.lat).put("longitude", p.lng);
    }

    private static boolean nearRoute(Route route, WalkingNavigationEngine.Point p) {
        // An initial projection is global and returns perpendicular distance to the path.
        WalkingNavigationEngine engine = new WalkingNavigationEngine(route.points, route.guides);
        return engine.update(new WalkingNavigationEngine.Fix(p.lat, p.lng, 5, 1000), 1000).deviation <= 120;
    }

    private static int dangerCount(Route route, List<WalkingNavigationEngine.Point> dangers) {
        int count = 0;
        for (WalkingNavigationEngine.Point p : dangers) if (nearRoute(route, p)) count++;
        return count;
    }
}
