package com.safeway.app;

import android.content.Context;
import android.content.SharedPreferences;

final class SafeWayPrefs {
    static final String PREFS = "safeway_prefs";
    static final String RETURNING = "returning";
    static final String START_TIME = "start_time";
    static final String EXPECTED_MINUTES = "expected_minutes";
    static final String APP_THEME = "app_theme";
    static final String USED_AI_CALL = "used_ai_call";
    static final String AI_SUMMARY = "ai_summary";
    static final String AI_TRANSCRIPT = "ai_transcript";
    static final String MY_NAME = "my_name";
    static final String MY_PHONE = "my_phone";
    static final String GUARDIAN_NAME = "guardian_name";
    static final String GUARDIAN_PHONE = "guardian_phone";
    static final String GUARDIAN_RELATION = "guardian_relation";
    static final String GUARDIAN_PUSH_TOKEN = "guardian_push_token";
    static final String PUSH_SERVER_URL = "push_server_url";
    static final String DEVICE_PUSH_TOKEN = "device_push_token";
    static final String DEVICE_PUSH_TOKEN_STATUS = "device_push_token_status";
    static final String ROUTE_DESTINATION = "route_destination";
    static final String ROUTE_DESTINATION_LAT = "route_destination_lat";
    static final String ROUTE_DESTINATION_LNG = "route_destination_lng";
    static final String ROUTE_LAST_LINK = "route_last_link";
    static final String ROUTE_LAST_POINTS = "route_last_points";
    static final String ROUTE_LAST_GUIDES = "route_last_guides";
    static final String ROUTE_EXPECTED_MINUTES = "route_expected_minutes";
    static final String ACTUAL_ROUTE_POINTS = "actual_route_points";
    static final String HOME_DESTINATION = "home_destination";
    static final String HOME_DESTINATION_LAT = "home_destination_lat";
    static final String HOME_DESTINATION_LNG = "home_destination_lng";
    static final String LATEST_GUARDIAN_ALERT_TITLE = "latest_guardian_alert_title";
    static final String LATEST_GUARDIAN_ALERT_BODY = "latest_guardian_alert_body";
    static final String LATEST_GUARDIAN_ALERT_MAPS_LINK = "latest_guardian_alert_maps_link";
    static final String LATEST_GUARDIAN_ALERT_ROUTE_LINK = "latest_guardian_alert_route_link";
    static final String LATEST_GUARDIAN_ALERT_ROUTE_POINTS = "latest_guardian_alert_route_points";
    static final String LATEST_GUARDIAN_ALERT_DESTINATION = "latest_guardian_alert_destination";
    static final String LATEST_GUARDIAN_ALERT_STATUS = "latest_guardian_alert_status";
    static final String LATEST_GUARDIAN_ALERT_LATITUDE = "latest_guardian_alert_latitude";
    static final String LATEST_GUARDIAN_ALERT_LONGITUDE = "latest_guardian_alert_longitude";
    static final String LATEST_GUARDIAN_ALERT_EXPECTED_MINUTES = "latest_guardian_alert_expected_minutes";
    static final String LATEST_GUARDIAN_ALERT_UPDATED_AT = "latest_guardian_alert_updated_at";
    static final String GUARDIAN_ALERT_HISTORY_JSON = "guardian_alert_history_json";

    private SafeWayPrefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
