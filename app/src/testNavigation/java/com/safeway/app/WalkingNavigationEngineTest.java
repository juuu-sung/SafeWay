package com.safeway.app;

import java.util.Arrays;
import java.util.Collections;

/** Run with javac/java; no Android runtime or network required. */
public final class WalkingNavigationEngineTest {
    private static int assertions;
    private static WalkingNavigationEngine.Point p(double north, double east) {
        return new WalkingNavigationEngine.Point(37 + north / 111320, 127 + east / (111320 * Math.cos(Math.toRadians(37))));
    }
    private static WalkingNavigationEngine.Fix fix(double north, double east, double accuracy, long time) {
        WalkingNavigationEngine.Point p = p(north, east);
        return new WalkingNavigationEngine.Fix(p.lat, p.lng, accuracy, time);
    }
    private static WalkingNavigationEngine straight() {
        return new WalkingNavigationEngine(Arrays.asList(p(0,0),p(100,0),p(200,0),p(300,0)),
                Arrays.asList(new WalkingNavigationEngine.Guide(p(0,0),"출발"),
                        new WalkingNavigationEngine.Guide(p(100,0),"우회전")));
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        WalkingNavigationEngine engine = straight();
        WalkingNavigationEngine.Progress r = engine.update(fix(50, 3, 5, 10000), 10000);
        check(r.accepted && r.along > 48 && r.along < 52, "project onto a segment, not nearest vertex");
        check(r.guide == 0 && r.nextDistance > 48 && r.nextDistance < 52, "active and upcoming guide");
        check(r.remaining > 248 && r.remaining < 252, "remaining path length");
        r = engine.update(fix(55, 2, 100, 13000), 13000);
        check(!r.accepted, "reject inaccurate GPS");
        check(!engine.update(fix(55, 2, 5, 10000), 30000).accepted, "reject stale GPS");
        check(!engine.update(fix(55, 2, 5, 11000), 10000).accepted, "reject future GPS");
        check(!engine.update(fix(200, 0, 5, 13000), 13000).accepted, "reject implausible jump");
        check(engine.update(fix(55, 2, 5, 16000), 16000).accepted, "recover after bad GPS");
        check(!engine.update(fix(55, 2, 5, 16000), 17000).accepted, "reject duplicate fix");

        engine = straight();
        engine.update(fix(50, 60, 5, 10000), 10000);
        check(!engine.update(fix(51, 60, 5, 13000), 13000).offRoute, "don't react to one or two fixes");
        check(engine.update(fix(53, 60, 5, 20000), 20000).offRoute, "confirmed deviation");
        check(!engine.update(fix(54, 0, 5, 35000), 35000).offRoute, "recover on route");

        engine = straight();
        check(!engine.update(fix(280, 0, 5, 10000), 10000).arrived, "don't arrive on a single fix");
        engine.update(fix(282, 0, 5, 13000), 13000);
        check(engine.update(fix(285, 0, 5, 17000), 17000).arrived, "confirmed arrival");
        engine = straight();
        engine.update(fix(280,0,30,10000),10000);
        engine.update(fix(282,0,30,13000),13000);
        check(!engine.update(fix(285,0,30,17000),17000).arrived, "arrival requires accurate GPS");

        engine = new WalkingNavigationEngine(Arrays.asList(p(0,0),p(200,0),p(200,200),
                p(0,200),p(0,0),p(-100,0)), Collections.emptyList());
        engine.update(fix(10,0,5,10000),10000);
        r = engine.update(fix(2,0,5,15000),15000);
        check(r.along < 20 && !r.arrived, "crossing near destination must not skip route loop");
        engine = new WalkingNavigationEngine(Arrays.asList(p(0,0),p(1000,0)), Collections.emptyList());
        engine.update(fix(0,0,5,10000),10000);
        engine.update(fix(0,200,5,60000),60000);
        engine.update(fix(300,200,5,130000),130000);
        r = engine.update(fix(301,200,5,135000),135000);
        check(Double.isFinite(r.deviation) && r.deviation < 1000,
                "bounded projection must retain a finite segment boundary");
        engine = new WalkingNavigationEngine(Arrays.asList(p(0,0),p(0,0),p(100,0)), Collections.emptyList());
        check(Double.isFinite(engine.update(fix(0,0,5,10000),10000).remaining), "duplicate vertices");
        check(!WalkingNavigationEngine.valid(new WalkingNavigationEngine.Fix(Double.NaN,127,5,10000),10000),
                "invalid coordinates");
        System.out.println("PASS: " + assertions + " navigation assertions");
    }
}
