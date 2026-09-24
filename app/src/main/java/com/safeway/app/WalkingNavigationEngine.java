package com.safeway.app;

import java.util.ArrayList;
import java.util.List;

/** Geometry and fix filtering independent of Android, so recorded walks can be replayed. */
final class WalkingNavigationEngine {
    static final class Point {
        final double lat, lng;
        Point(double lat, double lng) { this.lat = lat; this.lng = lng; }
    }

    static final class Guide {
        final Point point;
        final String text;
        Guide(Point point, String text) { this.point = point; this.text = text; }
    }

    static final class Fix {
        final Point point;
        final double accuracy;
        final long time;
        Fix(double lat, double lng, double accuracy, long elapsedMillis) {
            point = new Point(lat, lng);
            this.accuracy = accuracy;
            time = elapsedMillis;
        }
    }

    static final class Progress {
        final boolean accepted, offRoute, arrived;
        final double along, remaining, deviation, nextDistance;
        final int segment, guide;
        Progress(boolean accepted, boolean offRoute, boolean arrived, double along,
                 double remaining, double deviation, double nextDistance, int segment, int guide) {
            this.accepted = accepted; this.offRoute = offRoute; this.arrived = arrived;
            this.along = along; this.remaining = remaining; this.deviation = deviation;
            this.nextDistance = nextDistance; this.segment = segment; this.guide = guide;
        }
    }

    private final List<Point> route;
    private final double[] cumulative, guideOffsets;
    private Fix last;
    private double along;
    private long deviationSince = -1, arrivalSince = -1;
    private int deviationHits, arrivalHits;

    WalkingNavigationEngine(List<Point> points, List<Guide> guides) {
        if (points.size() < 2) throw new IllegalArgumentException("A walking route needs two points");
        route = new ArrayList<>(points);
        cumulative = new double[route.size()];
        for (int i = 1; i < route.size(); i++) {
            cumulative[i] = cumulative[i - 1] + distance(route.get(i - 1), route.get(i));
        }
        guideOffsets = new double[guides.size()];
        double floor = 0;
        for (int i = 0; i < guides.size(); i++) {
            Projection p = project(guides.get(i).point, floor, Double.MAX_VALUE);
            guideOffsets[i] = Math.max(floor, p.along);
            floor = guideOffsets[i];
        }
    }

    Progress update(Fix fix, long now) {
        if (!valid(fix, now) || (last != null && (fix.time <= last.time
                || distance(last.point, fix.point) > Math.max(50,
                (fix.time - last.time) / 1000.0 * 5 + last.accuracy + fix.accuracy)))) {
            deviationHits = arrivalHits = 0;
            deviationSince = arrivalSince = -1;
            return new Progress(false, false, false, along, total() - along, 0, 0, 0, -1);
        }
        // Restrict progress to the nearby part of the route; crossings must not skip whole loops.
        double travelWindow = last == null ? Double.MAX_VALUE
                : Math.max(60, distance(last.point, fix.point) + fix.accuracy * 2 + 20);
        Projection p = project(fix.point, last == null ? 0 : Math.max(0, along - travelWindow),
                last == null ? Double.MAX_VALUE : along + travelWindow);
        double offThreshold = Math.max(45, fix.accuracy * 2);
        boolean distant = p.distance > offThreshold;
        if (distant) {
            if (deviationSince < 0) deviationSince = fix.time;
            deviationHits++;
        } else {
            deviationSince = -1;
            deviationHits = 0;
            along = p.along;
        }
        boolean offRoute = distant && deviationHits >= 3 && fix.time - deviationSince >= 9000;
        boolean nearEnd = !distant && total() - along <= 35
                && distance(fix.point, route.get(route.size() - 1)) <= 25 && fix.accuracy <= 20;
        if (nearEnd) {
            if (arrivalSince < 0) arrivalSince = fix.time;
            arrivalHits++;
        } else {
            arrivalSince = -1;
            arrivalHits = 0;
        }
        boolean arrived = nearEnd && arrivalHits >= 3 && fix.time - arrivalSince >= 6000;
        last = fix;
        int active = -1;
        for (int i = 0; i < guideOffsets.length; i++) {
            if (guideOffsets[i] <= along + 5) active = i;
            else break;
        }
        int next = active + 1;
        double nextDistance = next < guideOffsets.length ? Math.max(0, guideOffsets[next] - along)
                : Math.max(0, total() - along);
        return new Progress(true, offRoute, arrived, along, Math.max(0, total() - along),
                p.distance, nextDistance, p.segment, active);
    }

    double total() { return cumulative[cumulative.length - 1]; }

    static boolean valid(Fix fix, long now) {
        return Double.isFinite(fix.point.lat) && Double.isFinite(fix.point.lng)
                && Math.abs(fix.point.lat) <= 90 && Math.abs(fix.point.lng) <= 180
                && Double.isFinite(fix.accuracy) && fix.accuracy > 0 && fix.accuracy <= 35
                && now >= fix.time && now - fix.time <= 15000;
    }

    private static final class Projection {
        double distance = Double.MAX_VALUE, along;
        int segment;
    }

    private Projection project(Point point, double min, double max) {
        Projection best = new Projection();
        for (int i = 0; i < route.size() - 1; i++) {
            if (cumulative[i + 1] < min || cumulative[i] > max) continue;
            Point a = route.get(i), b = route.get(i + 1);
            double scale = 111320 * Math.cos(Math.toRadians(point.lat));
            double ax = (a.lng - point.lng) * scale, ay = (a.lat - point.lat) * 111320;
            double dx = (b.lng - a.lng) * scale, dy = (b.lat - a.lat) * 111320;
            double squared = dx * dx + dy * dy;
            double t = squared == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / squared));
            // Project onto the allowed portion, not the entire segment. Even when
            // its unconstrained projection is outside the window, its boundary
            // is still a valid candidate (and must not yield infinite deviation).
            double length = cumulative[i + 1] - cumulative[i];
            if (length > 0) t = Math.max(Math.max(0, (min - cumulative[i]) / length),
                    Math.min(Math.min(1, (max - cumulative[i]) / length), t));
            double offset = cumulative[i] + t * (cumulative[i + 1] - cumulative[i]);
            double distance = Math.hypot(ax + t * dx, ay + t * dy);
            if (distance < best.distance) {
                best.distance = distance; best.along = offset; best.segment = i;
            }
        }
        return best;
    }

    static double distance(Point a, Point b) {
        double x = Math.toRadians(b.lng - a.lng) * Math.cos(Math.toRadians((a.lat + b.lat) / 2));
        double y = Math.toRadians(b.lat - a.lat);
        return Math.hypot(x, y) * 6371000;
    }
}
