package com.safeway.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;

final class SafeWayTheme {
    static final String THEME_PINK = "pink";
    static final String THEME_BLUE = "blue";

    private SafeWayTheme() {
    }

    static void apply(Context context) {
        AppCompatDelegate.setDefaultNightMode(isBlue(context)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }

    static String current(Context context) {
        return SafeWayPrefs.get(context).getString(SafeWayPrefs.APP_THEME, THEME_PINK);
    }

    static boolean isBlue(Context context) {
        return THEME_BLUE.equals(current(context));
    }

    static void select(Activity activity, String theme) {
        String normalizedTheme = THEME_BLUE.equals(theme) ? THEME_BLUE : THEME_PINK;
        SharedPreferences prefs = SafeWayPrefs.get(activity);
        String previous = prefs.getString(SafeWayPrefs.APP_THEME, THEME_PINK);
        if (normalizedTheme.equals(previous)) {
            return;
        }
        prefs.edit().putString(SafeWayPrefs.APP_THEME, normalizedTheme).apply();
        apply(activity);
        activity.recreate();
    }

    static void styleChoice(Activity activity, TextView pinkButton, TextView blueButton) {
        boolean blue = isBlue(activity);
        styleButton(activity, pinkButton, !blue);
        styleButton(activity, blueButton, blue);
    }

    private static void styleButton(Activity activity, TextView button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_segment_selected : android.R.color.transparent);
        button.setTextColor(activity.getColor(selected ? R.color.safeway_primary : R.color.safeway_muted));
    }
}
