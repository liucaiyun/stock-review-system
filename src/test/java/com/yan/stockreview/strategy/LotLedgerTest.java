package com.yan.stockreview.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LotLedgerTest {

    @Test
    void remainingInventoryIsNotRealized() {
        Map<String, LotLedger.CodeState> out = LotLedger.replay(List.of(
                new LotLedger.Fill("600519", "buy", 100, 10, 1000.0)));
        LotLedger.CodeState st = out.get("600519");
        assertEquals(100, st.remainingShares(), 1e-6);
        assertEquals(1000, st.remainingCost(), 0.01);
        assertEquals(0, st.realized(), 0.01);
    }

    @Test
    void fifoRealizedOnlyCountsSoldLots() {
        Map<String, LotLedger.CodeState> out = LotLedger.replay(List.of(
                new LotLedger.Fill("600519", "buy", 100, 10, 1000.0),
                new LotLedger.Fill("600519", "sell", 40, 12, 480.0)));
        LotLedger.CodeState st = out.get("600519");
        assertEquals(60, st.remainingShares(), 1e-6);
        assertEquals(600, st.remainingCost(), 0.01);
        assertEquals(80, st.realized(), 0.01);
        assertEquals(10.0, st.remainingCostPrice(), 1e-6);
    }

    @Test
    void laterBuyDoesNotChangeEarlierRealized() {
        Map<String, LotLedger.CodeState> out = LotLedger.replay(List.of(
                new LotLedger.Fill("1", "buy", 100, 10, null),
                new LotLedger.Fill("1", "sell", 100, 12, null),
                new LotLedger.Fill("1", "buy", 50, 20, null)));
        LotLedger.CodeState st = out.get("1");
        assertEquals(50, st.remainingShares(), 1e-6);
        assertEquals(1000, st.remainingCost(), 0.01);
        assertEquals(200, st.realized(), 0.01);
    }

    @Test
    void sellWithoutBuyIsZeroCostNotPretty() {
        Map<String, LotLedger.CodeState> out = LotLedger.replay(List.of(
                new LotLedger.Fill("1", "sell", 100, 10, 1000.0)));
        LotLedger.CodeState st = out.get("1");
        assertEquals(0, st.remainingShares(), 1e-6);
        assertTrue(st.realized() >= 999);
        assertNull(st.remainingCostPrice());
    }
}
