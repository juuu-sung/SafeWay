package com.safeway.app;

interface InlineNaviController {
    void startGuidance(double originLat, double originLng, double destinationLat, double destinationLng, String destinationName);

    void onHostResume();

    void onHostPause();

    void onHostDestroy();
}
