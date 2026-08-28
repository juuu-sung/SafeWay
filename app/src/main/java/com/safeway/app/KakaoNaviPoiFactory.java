package com.safeway.app;

import com.kakaomobility.knsdk.common.objects.KNPOI;
import com.kakaomobility.knsdk.common.util.IntPoint;

final class KakaoNaviPoiFactory {
    private KakaoNaviPoiFactory() {
    }

    static KNPOI fromKatec(String name, int x, int y) {
        return new KNPOI(name, new IntPoint(x, y));
    }
}
