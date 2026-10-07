package com.yan.stockreview.strategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FIFO 持仓账本：用成交流水还原「还剩多少股 / 还剩多少成本 / 已实现盈亏」。
 * 已实现只计已卖出的那部分成本，未平仓买入不能算进已实现。
 */
public final class LotLedger {

    private LotLedger() {}

    public record Fill(String code, String direction, double shares, double price, Double amount) {}

    public record CodeState(String code, double remainingShares, double remainingCost, double realized) {
        public Double remainingCostPrice() {
            return remainingShares > 1e-6 ? remainingCost / remainingShares : null;
        }
    }

    public static Map<String, CodeState> replay(List<Fill> fills) {
        Map<String, List<double[]>> lots = new LinkedHashMap<>();
        Map<String, Double> realized = new LinkedHashMap<>();
        if (fills == null) {
            return Map.of();
        }
        for (Fill f : fills) {
            if (f == null || f.code() == null || f.code().isBlank() || f.shares() <= 0 || f.price() <= 0) {
                continue;
            }
            String code = f.code().trim();
            String dir = f.direction() == null ? "" : f.direction().trim().toLowerCase();
            double amt = f.amount() != null && f.amount() > 0 ? f.amount() : f.shares() * f.price();
            lots.computeIfAbsent(code, k -> new ArrayList<>());
            realized.putIfAbsent(code, 0.0);
            if ("buy".equals(dir)) {
                lots.get(code).add(new double[] {f.shares(), amt});
            } else if ("sell".equals(dir)) {
                double remain = f.shares();
                double proceeds = amt;
                double costTaken = 0;
                List<double[]> queue = lots.get(code);
                while (remain > 1e-8 && !queue.isEmpty()) {
                    double[] lot = queue.get(0);
                    double take = Math.min(lot[0], remain);
                    double lotCost = lot[0] <= 0 ? 0 : lot[1] * (take / lot[0]);
                    costTaken += lotCost;
                    lot[0] -= take;
                    lot[1] -= lotCost;
                    remain -= take;
                    if (lot[0] <= 1e-8) {
                        queue.remove(0);
                    }
                }
                double soldAmt = proceeds * (f.shares() - remain) / f.shares();
                if (remain > 1e-6) {
                    // 卖超了，没有对应买入：把多卖的部分按 0 成本计，避免把账弄「好看」
                    soldAmt = proceeds;
                }
                realized.merge(code, soldAmt - costTaken, Double::sum);
            }
        }
        Map<String, CodeState> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<double[]>> e : lots.entrySet()) {
            double shares = 0;
            double cost = 0;
            for (double[] lot : e.getValue()) {
                if (lot[0] > 1e-8) {
                    shares += lot[0];
                    cost += lot[1];
                }
            }
            out.put(e.getKey(), new CodeState(e.getKey(), round4(shares), round2(cost),
                    round2(realized.getOrDefault(e.getKey(), 0.0))));
        }
        for (Map.Entry<String, Double> e : realized.entrySet()) {
            out.putIfAbsent(e.getKey(), new CodeState(e.getKey(), 0, 0, round2(e.getValue())));
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
