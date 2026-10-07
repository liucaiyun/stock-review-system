package com.yan.stockreview.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yan.stockreview.dto.KlineBar;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndicatorEngineTest {

    @Test
    void atrOfFlatRangeOneIsExactlyTwo() {
        double[] atr = IndicatorEngine.atr(flat(40, 100, 1.0), 14);
        assertEquals(2.0, atr[39], 1e-9);
        assertTrue(Double.isNaN(atr[12]));
        assertEquals(2.0, atr[13], 1e-9);
    }

    @Test
    void atrWilderRecursionOnZeroTrBar() {
        List<KlineBar> gapBars = flat(20, 100, 1.0);
        gapBars.set(19, bar(19, 100, 100, 100, 100));
        double[] atrGap = IndicatorEngine.atr(gapBars, 14);
        double expectedLast = (2.0 * 13 + 0) / 14;
        assertEquals(expectedLast, atrGap[19], 1e-9);
    }

    static List<KlineBar> flat(int n, double price, double range) {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            bars.add(new KlineBar(date(i), price, price, price + range, price - range, 1000, 0, 0, 0));
        }
        return bars;
    }

    static KlineBar bar(int i, double o, double h, double l, double c) {
        return new KlineBar(date(i), o, c, h, l, 1000, 0, 0, 0);
    }

    static String date(int i) {
        return String.format("D%03d", i);
    }
}
