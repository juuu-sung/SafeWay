package com.safeway.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public class ReturnLocationService extends Service {
    static final String ACTION_RECALCULATE = "com.safeway.app.RECALCULATE_WALK";
    static final String ACTION_MUTE = "com.safeway.app.MUTE_WALK";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "safeway_return";

    private LocationManager locationManager;
    private LocationListener locationListener;
    private WalkingNavigator navigator;
    private String navigationText = "현재 위치를 확인하고 있습니다.";

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!SafeWayPrefs.get(this).getBoolean(SafeWayPrefs.RETURNING, false) || !hasLocationPermission()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        if (navigator == null) navigator = new WalkingNavigator(this, (instruction, detail) -> {
            navigationText = instruction + " · " + detail;
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
        });
        if (intent != null && ACTION_MUTE.equals(intent.getAction())) {
            SharedPreferences prefs = SafeWayPrefs.get(this);
            prefs.edit().putBoolean(SafeWayPrefs.NAV_VOICE, !prefs.getBoolean(SafeWayPrefs.NAV_VOICE, true)).apply();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        }
        if (locationListener == null) startLocationUpdates();
        if (intent != null && ACTION_RECALCULATE.equals(intent.getAction())) navigator.reroute();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startLocationUpdates() {
        SharedPreferences prefs = SafeWayPrefs.get(this);
        if (!prefs.getBoolean(SafeWayPrefs.RETURNING, false) || !hasLocationPermission() || locationManager == null) {
            stopSelf();
            return;
        }

        stopLocationUpdates();
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (!SafeWayPrefs.get(ReturnLocationService.this).getBoolean(SafeWayPrefs.RETURNING, false)) {
                    stopSelf();
                    return;
                }
                if (navigator != null && navigator.location(location)) {
                    ReturnTrackRecorder.record(ReturnLocationService.this, location);
                    PushAlertClient.sendReturnLocationUpdate(ReturnLocationService.this, location, null);
                }
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }
        };

        try {
            Location lastLocation = getBestLastKnownLocation();
            if (lastLocation != null) locationListener.onLocationChanged(lastLocation);
            // Register both providers, including disabled GPS, so enabling GPS resumes the session.
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (locationManager.getAllProviders().contains(provider))
                    locationManager.requestLocationUpdates(provider, 3000L, 0f, locationListener, Looper.getMainLooper());
            }
        } catch (SecurityException ignored) {
            stopSelf();
        }
    }

    private void stopLocationUpdates() {
        if (locationManager == null || locationListener == null) {
            locationListener = null;
            return;
        }
        try {
            locationManager.removeUpdates(locationListener);
        } catch (SecurityException ignored) {
            // Permission may have been revoked while tracking was active.
        }
        locationListener = null;
    }

    private Notification buildNotification() {
        ensureNotificationChannel();
        Intent intent = new Intent(this, WalkingNaviActivity.class);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags);
        String body = navigationText;
        PendingIntent mute = PendingIntent.getService(this, 1,
                new Intent(this, ReturnLocationService.class).setAction(ACTION_MUTE), pendingIntentFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("SafeWay 안심귀가 진행 중")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null,
                        SafeWayPrefs.get(this).getBoolean(SafeWayPrefs.NAV_VOICE, true) ? "음성 끄기" : "음성 켜기",
                        mute).build())
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "안심귀가 진행",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("안심귀가 진행 중 실제 이동 경로를 기록합니다.");
        manager.createNotificationChannel(channel);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String getLocationProvider() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Location getBestLastKnownLocation() {
        if (locationManager == null) {
            return null;
        }
        Location bestLocation = null;
        String[] providers = new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        };
        for (String provider : providers) {
            try {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location == null) {
                    continue;
                }
                if (bestLocation == null || location.getTime() > bestLocation.getTime()) {
                    bestLocation = location;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                return null;
            }
        }
        return bestLocation;
    }

    @Override
    public void onDestroy() {
        stopLocationUpdates();
        if (navigator != null) navigator.close();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
