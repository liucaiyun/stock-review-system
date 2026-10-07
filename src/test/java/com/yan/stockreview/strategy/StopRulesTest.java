package com.yan.stockreview.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StopRulesTest {

    @Test
    void manualPctOverridesAtr() {
        StopRules.Result r = StopRules.of(100.0, 8.0, false, 2.0);
        assertEquals("MANUAL", r.source());
        assertEquals(92.0, r.stopLine(), 1e-6);
        assertEquals(8.0, r.stopPct(), 1e-6);
    }

    @Test
    void atrLineIsCostMinusTwoAtr() {
        StopRules.Result r = StopRules.of(100.0, null, false, 2.0);
        assertEquals("ATR", r.source());
        assertEquals(96.0, r.stopLine(), 1e-6);
        assertEquals(4.0, r.stopPct(), 1e-6);
        assertEquals("2×ATR", StopRules.sourceLabel(r.source()));
    }

    @Test
    void atrFloorIsOnePercentOfCost() {
        StopRules.Result r = StopRules.of(100.0, null, false, 80.0);
        assertEquals("ATR", r.source());
        assertEquals(1.0, r.stopLine(), 1e-6);
    }

    @Test
    void defaultPctWhenNoAtr() {
        StopRules.Result stock = StopRules.of(100.0, null, false, null);
        assertEquals("DEFAULT_PCT", stock.source());
        assertEquals(92.0, stock.stopLine(), 1e-6);
        StopRules.Result etf = StopRules.of(100.0, null, true, null);
        assertEquals(88.0, etf.stopLine(), 1e-6);
    }

    @Test
    void nullCostReturnsNull() {
        assertNull(StopRules.of(null, 8.0, false, 2.0));
        assertNull(StopRules.of(0.0, 8.0, false, 2.0));
    }
}
