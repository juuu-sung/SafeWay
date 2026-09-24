package com.safeway.app;

import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.Locale;

final class WalkingNavigationState {
    static String routeKey(SharedPreferences prefs) {
        return prefs.getLong(SafeWayPrefs.START_TIME, 0) + "|"
                + prefs.getBoolean(SafeWayPrefs.RETURNING, false) + "|"
                + prefs.getString(SafeWayPrefs.ROUTE_DESTINATION_LAT, "") + "|"
                + prefs.getString(SafeWayPrefs.ROUTE_DESTINATION_LNG, "") + "|"
                + prefs.getString(SafeWayPrefs.ROUTE_MODE, "BROAD_FIRST") + "|"
                + prefs.getBoolean(SafeWayPrefs.ROUTE_NAVIGABLE, false) + "|"
                + prefs.getString(SafeWayPrefs.ROUTE_LAST_POINTS, "") + "|"
                + prefs.getString(SafeWayPrefs.ROUTE_LAST_GUIDES, "");
    }

    static JSONObject read(SharedPreferences prefs) {
        try {
            JSONObject state = new JSONObject(prefs.getString(SafeWayPrefs.NAV_STATE, "{}"));
            long age = SystemClock.elapsedRealtime() - state.optLong("updated", -1);
            if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false)
                    || state.optLong("session", -1) != prefs.getLong(SafeWayPrefs.START_TIME, 0)
                    || !routeKey(prefs).equals(state.optString("routeKey"))
                    || age < 0 || age > 20000) return new JSONObject();
            return state;
        } catch (Exception ignored) { return new JSONObject(); }
    }

    static String distance(double meters) {
        if (meters < 0 || !Double.isFinite(meters)) return "-";
        return meters >= 1000 ? String.format(Locale.KOREA, "%.1fkm", meters / 1000)
                : Math.round(meters) + "m";
    }

    static String minutes(double seconds) {
        return seconds < 0 ? "-" : Math.max(1, (int) Math.ceil(seconds / 60)) + "분";
    }
}
