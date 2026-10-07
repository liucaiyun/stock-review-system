package com.yan.stockreview.service;

import com.yan.stockreview.entity.TradeRecord;
import com.yan.stockreview.entity.WatchStock;
import com.yan.stockreview.strategy.LotLedger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 用成交流水 FIFO 还原仓位，对照手动持仓。对不上的标红，避免「账上还有、券商已经平了」。
 */
@Service
public class InventoryService {

    private static final double SHARE_EPS = 0.51;
    private static final double COST_PCT_EPS = 1.0;

    private final TradeService tradeService;
    private final WatchlistService watchlistService;

    public InventoryService(TradeService tradeService, WatchlistService watchlistService) {
        this.tradeService = tradeService;
        this.watchlistService = watchlistService;
    }

    public Map<String, Object> reconcile() {
        List<TradeRecord> chrono = new ArrayList<>(tradeService.list());
        chrono.sort(Comparator
                .comparing(TradeRecord::getTradeDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(t -> t.getId() == null ? 0L : t.getId()));
        List<LotLedger.Fill> fills = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> traded = new LinkedHashSet<>();
        for (TradeRecord t : chrono) {
            if (t.getCode() == null || t.getShares() == null || t.getShares() <= 0
                    || t.getPrice() == null || t.getPrice() <= 0) {
                continue;
            }
            fills.add(new LotLedger.Fill(t.getCode(), t.getDirection(), t.getShares(), t.getPrice(), t.getAmount()));
            names.putIfAbsent(t.getCode(), t.getName());
            traded.add(t.getCode());
        }
        Map<String, LotLedger.CodeState> ledger = LotLedger.replay(fills);
        Map<String, WatchStock> book = new LinkedHashMap<>();
        for (WatchStock s : watchlistService.list()) {
            if (s.getShares() != null && s.getShares() > 0) {
                book.put(s.getCode(), s);
                names.putIfAbsent(s.getCode(), s.getName());
            }
        }
        Set<String> codes = new LinkedHashSet<>();
        codes.addAll(ledger.keySet());
        codes.addAll(book.keySet());
        List<Map<String, Object>> mismatches = new ArrayList<>();
        List<Map<String, Object>> ledgerOnly = new ArrayList<>();
        List<Map<String, Object>> bookOnly = new ArrayList<>();
        double realized = 0;
        for (LotLedger.CodeState st : ledger.values()) {
            realized += st.realized();
        }
        for (String code : codes) {
            LotLedger.CodeState st = ledger.get(code);
            WatchStock ws = book.get(code);
            double ledShares = st == null ? 0 : st.remainingShares();
            double bookShares = ws == null || ws.getShares() == null ? 0 : ws.getShares();
            Double ledCostPx = st == null ? null : st.remainingCostPrice();
            Double bookCostPx = ws == null ? null : ws.getCostPrice();
            String name = names.get(code);
            if (ledShares > SHARE_EPS && bookShares <= SHARE_EPS) {
                ledgerOnly.add(row(code, name, st, ws, "流水还有仓，持仓没有"));
                mismatches.add(row(code, name, st, ws, "流水还有仓，持仓没有"));
                continue;
            }
            if (bookShares > SHARE_EPS && ledShares <= SHARE_EPS) {
                if (traded.contains(code)) {
                    bookOnly.add(row(code, name, st, ws, "账本已平仓，持仓仍有"));
                    mismatches.add(row(code, name, st, ws, "账本已平仓，持仓仍有"));
                } else {
                    bookOnly.add(row(code, name, st, ws, "成交里没有这只，没法对账"));
                }
                continue;
            }
            if (Math.abs(ledShares - bookShares) > SHARE_EPS) {
                mismatches.add(row(code, name, st, ws, "股数对不上（流水 "
                        + round4(ledShares) + " / 持仓 " + round4(bookShares) + "）"));
                continue;
            }
            if (ledShares > SHARE_EPS && ledCostPx != null && bookCostPx != null && bookCostPx > 0) {
                double diffPct = Math.abs(ledCostPx - bookCostPx) / bookCostPx * 100;
                if (diffPct > COST_PCT_EPS) {
                    mismatches.add(row(code, name, st, ws, "成本价差 "
                            + round2(diffPct) + "%（流水 " + round4(ledCostPx) + " / 持仓 " + round4(bookCostPx) + "）"));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", mismatches.isEmpty());
        out.put("tradeCount", fills.size());
        out.put("realized", round2(realized));
        out.put("mismatchCount", mismatches.size());
        out.put("mismatches", mismatches);
        out.put("ledgerOnly", ledgerOnly);
        out.put("bookOnly", bookOnly);
        return out;
    }

    private static Map<String, Object> row(String code, String name, LotLedger.CodeState st, WatchStock ws, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("ledgerShares", st == null ? 0 : st.remainingShares());
        m.put("ledgerCost", st == null ? 0 : st.remainingCost());
        m.put("ledgerCostPrice", st == null ? null : st.remainingCostPrice());
        m.put("ledgerRealized", st == null ? 0 : st.realized());
        m.put("bookShares", ws == null || ws.getShares() == null ? 0 : ws.getShares());
        m.put("bookCost", ws == null ? null : ws.getCostAmount());
        m.put("bookCostPrice", ws == null ? null : ws.getCostPrice());
        m.put("reason", reason);
        return m;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
