package com.safeway.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class SafeWayMessagingService extends FirebaseMessagingService {
    private static final int GUARDIAN_ALERT_NOTIFICATION_ID = 2001;

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        FcmTokenManager.saveDeviceToken(this, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        String title = valueOrDefault(message.getData().get("title"), "SafeWay 보호자 알림");
        String body = valueOrDefault(message.getData().get("body"), "안심귀가 알림이 도착했습니다.");
        String mapsLink = message.getData().get("mapsLink");
        String routeLink = message.getData().get("routeLink");
        String routePoints = message.getData().get("routePoints");
        String destination = message.getData().get("destination");
        String status = message.getData().get("status");
        String latitude = message.getData().get("latitude");
        String longitude = message.getData().get("longitude");
        String expectedMinutes = message.getData().get("expectedMinutes");
        saveLatestGuardianAlert(title, body, mapsLink, routeLink, routePoints, destination, status, latitude, longitude, expectedMinutes);
        showGuardianAlert(title, body);
    }

    private void showGuardianAlert(String title, String body) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        SafeWayNotificationChannels.ensureGuardianAlertChannel(this);

        Intent intent = new Intent(this, GuardianMonitorActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 1, intent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, SafeWayNotificationChannels.GUARDIAN_ALERTS)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_MESSAGE);

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(GUARDIAN_ALERT_NOTIFICATION_ID, builder.build());
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private void saveLatestGuardianAlert(
            String title,
            String body,
            String mapsLink,
            String routeLink,
            String routePoints,
            String destination,
            String status,
            String latitude,
            String longitude,
            String expectedMinutes
    ) {
        SharedPreferences prefs = SafeWayPrefs.get(this);
        String statusValue = status == null ? "" : status.trim();
        boolean resetLocationState = "linked".equals(statusValue) || "notice".equals(statusValue);
        prefs.edit()
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_TITLE, title)
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_BODY, body)
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_MAPS_LINK, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_MAPS_LINK, mapsLink))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_LINK, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_LINK, routeLink))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_POINTS, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_ROUTE_POINTS, routePoints))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_DESTINATION, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_DESTINATION, destination))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_STATUS, statusValue)
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_LATITUDE, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_LATITUDE, latitude))
                .putString(SafeWayPrefs.LATEST_GUARDIAN_ALERT_LONGITUDE, resetLocationState ? "" : keepExistingIfEmpty(prefs, SafeWayPrefs.LATEST_GUARDIAN_ALERT_LONGITUDE, longitude))
                .putInt(SafeWayPrefs.LATEST_GUARDIAN_ALERT_EXPECTED_MINUTES, resetLocationState ? 0 : keepExistingIntIfEmpty(prefs, expectedMinutes))
                .putLong(SafeWayPrefs.LATEST_GUARDIAN_ALERT_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    private String keepExistingIfEmpty(SharedPreferences prefs, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            return prefs.getString(key, "");
        }
        return value;
    }

    private int keepExistingIntIfEmpty(SharedPreferences prefs, String value) {
        if (value == null || value.trim().isEmpty()) {
            return prefs.getInt(SafeWayPrefs.LATEST_GUARDIAN_ALERT_EXPECTED_MINUTES, 0);
        }
        return parsePositiveInt(value);
    }

    private int parsePositiveInt(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
