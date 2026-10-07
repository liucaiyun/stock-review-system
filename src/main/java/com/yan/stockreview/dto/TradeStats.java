package com.yan.stockreview.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逐笔交易统计：这是「能不能赚钱」的核心口径。
 *
 * <p>胜率单独看没有意义，必须和盈亏比一起看：
 * <b>期望值 expectancyR = 胜率×平均盈利R − 败率×平均亏损R</b>，也就是每笔平均赚多少个 R。
 * 胜率 40% 但盈亏比 2.5 的系统是赚钱的；胜率 70% 但盈亏比 0.2 的系统是亏钱的。
 *
 * <p>把它换算成钱：单笔风险设成账户的 1%，则「每笔期望 0.20R」≈ 每次下注平均赚账户的 0.2%，
 * 而且要扣掉你自己的滑点、误操作和情绪成本。
 */
public record TradeStats(
        int trades,
        boolean insufficient,
        Double winRate,
        Double expectancyR,
        Double avgWinR,
        Double avgLossR,
        Double bestR,
        Double worstR,
        Double profitFactor,
        Integer maxConsecLoss,
        Double avgHoldDays,
        Double avgMaeR,
        Double totalR,
        Double maxDrawdownR,
        Double avgNetPct,
        Double avgBenchPct,
        Double avgExcessPct,
        /** 每笔 R 的标准误 = 标准差 / √笔数；用来判断期望值是不是「离 0 足够远」 */
        Double rStdErr,
        /** 期望值 / 标准误。|t| < 2 基本等同于「跟 0 分不出来」 */
        Double tStat,
        Map<String, Integer> exitMix
) {

    public static TradeStats empty() {
        return new TradeStats(0, true, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, Map.of());
    }

    /** 从逐笔交易算出全部指标；{@code minTrades} 用来标记「样本太少，只能当参考」 */
    public static TradeStats from(List<SimTrade> trades, int minTrades) {
        if (trades == null || trades.isEmpty()) {
            return empty();
        }
        // 按出场日期排序：最大连亏和 R 曲线回撤都依赖时间顺序
        List<SimTrade> sorted = trades.stream()
                .sorted((a, b) -> {
                    int c = a.exitDate().compareTo(b.exitDate());
                    return c != 0 ? c : a.entryDate().compareTo(b.entryDate());
                })
                .toList();

        int n = sorted.size();
        int wins = 0;
        int losses = 0;
        double winSum = 0;
        double lossSum = 0;
        double rSqSum = 0;
        double rSum = 0;
        double best = -Double.MAX_VALUE;
        double worst = Double.MAX_VALUE;
        double holdSum = 0;
        double maeSum = 0;
        double netSum = 0;
        double benchSum = 0;
        int benchN = 0;
        int consec = 0;
        int maxConsec = 0;
        double cum = 0;
        double peak = 0;
        double maxDd = 0;
        Map<String, Integer> exits = new LinkedHashMap<>();

        for (SimTrade t : sorted) {
            double r = t.r();
            rSum += r;
            rSqSum += r * r;
            if (r > 0) {
                wins++;
                winSum += r;
            } else {
                losses++;
                lossSum += Math.abs(r);
            }
            best = Math.max(best, r);
            worst = Math.min(worst, r);
            holdSum += t.holdDays();
            maeSum += t.maeR();
            netSum += t.netPct();
            if (t.benchPct() != null) {
                benchSum += t.benchPct();
                benchN++;
            }
            exits.merge(t.exitReason() == null ? "未知" : t.exitReason(), 1, Integer::sum);
            if (r < 0) {
                consec++;
                maxConsec = Math.max(maxConsec, consec);
            } else {
                consec = 0;
            }
            // R 曲线（按出场时间累加）的最大回撤
            cum += r;
            if (cum > peak) {
                peak = cum;
            }
            maxDd = Math.min(maxDd, cum - peak);
        }

        Map<String, Integer> exitMix = new LinkedHashMap<>();
        exits.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> exitMix.put(e.getKey(), e.getValue()));

        Double avgBench = benchN == 0 ? null : round(benchSum / benchN, 2);
        Double avgNet = round(netSum / n, 2);
        Double mean = rSum / n;
        // 标准误：注意同一时间多只票同时持仓时交易之间是相关的，这个误差会被低估（真实不确定性更大）
        Double stdErr = null;
        Double tStat = null;
        if (n >= 2) {
            double var = (rSqSum - n * mean * mean) / (n - 1);
            if (var > 0) {
                stdErr = round(Math.sqrt(var / n), 3);
                if (stdErr > 0) {
                    tStat = round(mean / stdErr, 2);
                }
            }
        }
        return new TradeStats(
                n,
                n < minTrades,
                round(wins * 100.0 / n, 1),
                round(rSum / n, 3),
                wins == 0 ? null : round(winSum / wins, 3),
                losses == 0 ? null : round(lossSum / losses, 3),
                round(best, 3),
                round(worst, 3),
                losses == 0 || lossSum == 0 ? null : round(winSum / lossSum, 2),
                maxConsec,
                round(holdSum / n, 1),
                round(maeSum / n, 3),
                round(rSum, 2),
                round(maxDd, 2),
                avgNet,
                avgBench,
                avgBench == null ? null : round(avgNet - avgBench, 2),
                stdErr,
                tStat,
                exitMix
        );
    }

    private static Double round(double v, int scale) {
        double p = Math.pow(10, scale);
        return Math.round(v * p) / p;
    }
}
