package com.safeway.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.widget.TextView;

final class BottomNav {
    private BottomNav() {
    }

    static void bind(Activity activity, Class<?> activeScreen) {
        bindItem(activity, R.id.bottomNavHome, MainActivity.class, activeScreen);
        bindItem(activity, R.id.bottomNavReturn, RouteActivity.class, activeScreen);
        bindItem(activity, R.id.bottomNavMemo, DangerMemoActivity.class, activeScreen);
        bindItem(activity, R.id.bottomNavRecords, ReturnRecordActivity.class, activeScreen);
        bindItem(activity, R.id.bottomNavGuardian, GuardianActivity.class, activeScreen);
    }

    private static void bindItem(Activity activity, int id, Class<?> targetScreen, Class<?> activeScreen) {
        TextView item = activity.findViewById(id);
        if (item == null) {
            return;
        }

        boolean currentScreen = targetScreen.equals(activeScreen);
        boolean sectionSelected = currentScreen
                || (HomeAddressActivity.class.equals(activeScreen) && RouteActivity.class.equals(targetScreen))
                || (GuardianMonitorActivity.class.equals(activeScreen) && GuardianActivity.class.equals(targetScreen));
        item.setTextColor(activity.getColor(sectionSelected ? R.color.safeway_primary : R.color.safeway_muted));
        item.setTypeface(null, sectionSelected ? Typeface.BOLD : Typeface.NORMAL);
        item.setBackgroundResource(sectionSelected ? R.drawable.bg_nav_selected : android.R.color.transparent);
        item.setOnClickListener(v -> {
            if (currentScreen) {
                return;
            }
            Intent intent = new Intent(activity, targetScreen);
            if (targetScreen.equals(MainActivity.class)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }
            activity.startActivity(intent);
        });
    }
}
