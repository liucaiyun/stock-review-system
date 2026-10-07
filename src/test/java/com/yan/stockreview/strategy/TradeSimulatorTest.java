package com.yan.stockreview.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.SignalPoint;
import com.yan.stockreview.dto.SimTrade;
import com.yan.stockreview.dto.TradeStats;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradeSimulatorTest {

    @Test
    void timeStopAfterTwentyDays() {
        List<SimTrade> t1 = TradeSimulator.simulate(flat(60, 100, 1.0), List.of(sig(20, "T", "BUY")), "T", null);
        assertEquals(1, t1.size());
        assertEquals("时间", t1.get(0).exitReason());
        assertEquals(20, t1.get(0).holdDays());
        assertTrue(Math.abs(t1.get(0).r()) < 0.12);
    }

    @Test
    void initialStopHitsNearMinusOneR() {
        List<KlineBar> b2 = with(flat(60, 100, 1.0), 22, bar(22, 99, 99, 95, 96));
        List<SimTrade> t2 = TradeSimulator.simulate(b2, List.of(sig(20, "T", "BUY")), "T", null);
        assertEquals(1, t2.size());
        assertEquals("止损", t2.get(0).exitReason());
        assertEquals(2, t2.get(0).holdDays());
        assertTrue(Math.abs(t2.get(0).r() + 1.04) < 0.08);
    }

    @Test
    void gapDownFillsAtOpenWorseThanStop() {
        List<KlineBar> b2b = with(flat(60, 100, 1.0), 22, bar(22, 95, 95, 94, 94.5));
        List<SimTrade> t2b = TradeSimulator.simulate(b2b, List.of(sig(20, "T", "BUY")), "T", null);
        assertTrue(!t2b.isEmpty() && t2b.get(0).r() < -1.15);
    }

    @Test
    void breakevenTrailNearZeroR() {
        List<KlineBar> b3 = with(flat(40, 100, 1.0), 22, bar(22, 100, 105.5, 100.5, 105));
        b3 = with(b3, 23, bar(23, 101, 101, 99.5, 100));
        List<SimTrade> t3 = TradeSimulator.simulate(b3, List.of(sig(20, "T", "BUY")), "T", null);
        assertEquals(1, t3.size());
        assertEquals("移动止损", t3.get(0).exitReason());
        assertTrue(t3.get(0).r() > -0.15 && t3.get(0).r() < 0.05);
    }

    @Test
    void atrTrailLocksMostOfTwoR() {
        List<KlineBar> b4 = with(flat(40, 100, 1.0), 22, bar(22, 100, 110.5, 100, 110));
        b4 = with(b4, 23, bar(23, 104, 104.5, 103, 103.5));
        List<SimTrade> t4 = TradeSimulator.simulate(b4, List.of(sig(20, "T", "BUY")), "T", null);
        assertEquals(1, t4.size());
        assertEquals("移动止损", t4.get(0).exitReason());
        assertTrue(t4.get(0).r() > 0.8 && t4.get(0).r() < 1.1);
    }

    @Test
    void sellSignalExitsNextOpen() {
        List<KlineBar> b5 = with(flat(40, 100, 1.0), 24, bar(24, 103, 103.5, 102, 103));
        List<SimTrade> t5 = TradeSimulator.simulate(b5,
                List.of(sig(20, "T", "BUY"), sig(23, "T", "SELL")), "T", null);
        assertEquals(1, t5.size());
        assertEquals("卖点", t5.get(0).exitReason());
        assertEquals(4, t5.get(0).holdDays());
    }

    @Test
    void overlappingBuysAreIgnored() {
        List<SimTrade> t6 = TradeSimulator.simulate(flat(60, 100, 1.0),
                List.of(sig(20, "T", "BUY"), sig(22, "T", "BUY")), "T", null);
        assertEquals(1, t6.size());
    }

    @Test
    void limitUpOpenCannotFill() {
        List<KlineBar> b7 = with(flat(40, 100, 1.0), 21, bar(21, 110, 110.5, 109, 110));
        List<SimTrade> t7 = TradeSimulator.simulate(b7, List.of(sig(20, "T", "BUY")), "T", null);
        assertTrue(t7.isEmpty());
    }

    @Test
    void tradeStatsFromFourTrades() {
        List<SimTrade> fake = List.of(
                fakeTrade("2025-01-01", 2.0), fakeTrade("2025-01-02", -1.0),
                fakeTrade("2025-01-03", -1.0), fakeTrade("2025-01-04", 3.0));
        TradeStats ts = TradeStats.from(fake, 30);
        assertEquals(0.75, ts.expectancyR(), 0.006);
        assertEquals(50.0, ts.winRate(), 0.006);
        assertEquals(2.5, ts.avgWinR(), 0.006);
        assertEquals(1.0, ts.avgLossR(), 0.006);
        assertEquals(2.5, ts.profitFactor(), 0.006);
        assertEquals(2, ts.maxConsecLoss());
        assertEquals(-2.0, ts.maxDrawdownR(), 0.006);
        assertTrue(ts.insufficient());
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

    static List<KlineBar> with(List<KlineBar> bars, int idx, KlineBar b) {
        List<KlineBar> out = new ArrayList<>(bars);
        out.set(idx, b);
        return out;
    }

    static String date(int i) {
        return String.format("D%03d", i);
    }

    static SignalPoint sig(int day, String strategy, String action) {
        return new SignalPoint(date(day), strategy, "test", action, "", 0);
    }

    static SimTrade fakeTrade(String exitDate, double r) {
        return new SimTrade("T", "2024-12-01", 100, exitDate, 100, "时间", r, 5, -0.5, 1.0, r, 0.0);
    }
}
