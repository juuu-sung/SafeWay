package com.safeway.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import java.util.Locale;

final class ReturnTrackRecorder {
    private static final float MIN_DISTANCE_METERS = 5f;
    private static final int MAX_POINTS = 900;

    private ReturnTrackRecorder() {
    }

    static void reset(Context context) {
        SafeWayPrefs.get(context).edit()
                .remove(SafeWayPrefs.ACTUAL_ROUTE_POINTS)
                .apply();
    }

    static String get(Context context) {
        return SafeWayPrefs.get(context).getString(SafeWayPrefs.ACTUAL_ROUTE_POINTS, "");
    }

    static void record(Context context, Location location) {
        if (location == null) {
            return;
        }
        record(context, location.getLatitude(), location.getLongitude());
    }

    static synchronized void record(Context context, double latitude, double longitude) {
        if (!isValidCoordinate(latitude, longitude)) {
            return;
        }

        SharedPreferences prefs = SafeWayPrefs.get(context);
        String existing = prefs.getString(SafeWayPrefs.ACTUAL_ROUTE_POINTS, "");
        if (isNearLastPoint(existing, latitude, longitude)) {
            return;
        }

        String point = String.format(Locale.US, "%.6f,%.6f", latitude, longitude);
        prefs.edit()
                .putString(SafeWayPrefs.ACTUAL_ROUTE_POINTS, appendAndTrim(existing, point))
                .apply();
    }

    private static boolean isValidCoordinate(double latitude, double longitude) {
        return latitude >= -90d && latitude <= 90d && longitude >= -180d && longitude <= 180d;
    }

    private static boolean isNearLastPoint(String existing, double latitude, double longitude) {
        if (existing == null || existing.trim().isEmpty()) {
            return false;
        }
        int separator = existing.lastIndexOf(';');
        String lastPair = separator >= 0 ? existing.substring(separator + 1) : existing;
        String[] coordinates = lastPair.split(",");
        if (coordinates.length != 2) {
            return false;
        }
        try {
            double lastLatitude = Double.parseDouble(coordinates[0]);
            double lastLongitude = Double.parseDouble(coordinates[1]);
            float[] results = new float[1];
            Location.distanceBetween(lastLatitude, lastLongitude, latitude, longitude, results);
            return results[0] < MIN_DISTANCE_METERS;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String appendAndTrim(String existing, String point) {
        if (existing == null || existing.trim().isEmpty()) {
            return point;
        }

        String[] points = existing.split(";");
        if (points.length < MAX_POINTS) {
            return existing + ";" + point;
        }

        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, points.length - MAX_POINTS + 1);
        for (int i = start; i < points.length; i++) {
            if (points[i] == null || points[i].trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(points[i]);
        }
        if (builder.length() > 0) {
            builder.append(';');
        }
        builder.append(point);
        return builder.toString();
    }
}
