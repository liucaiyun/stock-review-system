package com.yan.stockreview.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yan.stockreview.dto.KlineBar;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketRegimeTest {

    @Test
    void tooShortReturnsNull() {
        assertNull(MarketRegime.of(flat(10, 100, 1)));
    }

    @Test
    void risingSeriesIsBullWithPositiveHorizons() {
        List<KlineBar> bars = rising(80, 80, 1);
        MarketRegime.Snapshot s = MarketRegime.of(bars);
        assertNotNull(s);
        assertEquals("BULL", s.trend());
        assertEquals("多头", s.trendLabel());
        assertTrue(s.pct5() > 0);
        assertTrue(s.pct20() > 0);
        assertTrue(s.pct60() > 0);
        assertTrue(s.distMa20Pct() > 0);
        assertEquals(5.0, MarketRegime.ret(new double[] {100, 101, 102, 103, 104, 105}, 5), 0.01);
    }

    @Test
    void fallingSeriesIsBear() {
        List<KlineBar> bars = falling(80, 180, 1);
        MarketRegime.Snapshot s = MarketRegime.of(bars);
        assertNotNull(s);
        assertEquals("BEAR", s.trend());
        assertTrue(s.pct20() < 0);
        assertTrue(s.distMa20Pct() < 0);
    }

    @Test
    void pullbackAfterUptrend() {
        List<KlineBar> bars = rising(70, 80, 1);
        // 最近 8 根跌破短均，但 MA20 仍高于 MA60
        for (int i = bars.size() - 8; i < bars.size(); i++) {
            bars.set(i, bar(i, 100));
        }
        MarketRegime.Snapshot s = MarketRegime.of(bars);
        assertNotNull(s);
        assertEquals("PULLBACK", s.trend());
    }

    @Test
    void styleComparesChiNextVsHs300() {
        assertEquals("成长占优", MarketRegime.styleLabel(1.0, 4.0));
        assertEquals("大盘占优", MarketRegime.styleLabel(3.0, 0.5));
        assertEquals("风格接近", MarketRegime.styleLabel(1.0, 1.5));
        assertNull(MarketRegime.styleLabel(null, 2.0));
    }

    @Test
    void highRangeMarksHighVol() {
        List<KlineBar> quiet = flat(70, 100, 0.3);
        MarketRegime.Snapshot q = MarketRegime.of(quiet);
        List<KlineBar> wild = flat(70, 100, 0.3);
        wild.set(69, new KlineBar("D069", 100, 100, 110, 90, 1000, 0, 0, 0));
        MarketRegime.Snapshot w = MarketRegime.of(wild);
        assertNotNull(q);
        assertNotNull(w);
        assertEquals("MID", q.vol());
        assertEquals("HIGH", w.vol());
    }

    static List<KlineBar> flat(int n, double price, double range) {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            bars.add(new KlineBar(date(i), price, price, price + range, price - range, 1000, 0, 0, 0));
        }
        return bars;
    }

    static List<KlineBar> rising(int n, double start, double step) {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            bars.add(bar(i, start + i * step));
        }
        return bars;
    }

    static List<KlineBar> falling(int n, double start, double step) {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            bars.add(bar(i, start - i * step));
        }
        return bars;
    }

    static KlineBar bar(int i, double px) {
        return new KlineBar(date(i), px, px, px + 0.4, px - 0.4, 1000, 0, 0, 0);
    }

    static String date(int i) {
        return String.format("D%03d", i);
    }
}
