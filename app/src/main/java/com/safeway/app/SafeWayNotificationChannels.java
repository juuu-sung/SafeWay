package com.safeway.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

final class SafeWayNotificationChannels {
    static final String GUARDIAN_ALERTS = "safeway_guardian_alerts";

    private SafeWayNotificationChannels() {
    }

    static void ensureGuardianAlertChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(GUARDIAN_ALERTS) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                GUARDIAN_ALERTS,
                "보호자 알림",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("보호자에게 전달되는 안심귀가 시작 및 위치 알림입니다.");
        manager.createNotificationChannel(channel);
    }
}
