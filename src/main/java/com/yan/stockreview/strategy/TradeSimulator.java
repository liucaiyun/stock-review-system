package com.yan.stockreview.strategy;

import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.SignalPoint;
import com.yan.stockreview.dto.SimTrade;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 逐笔交易模拟：把「信号」变成「一笔笔真实的交易」，这样才能算出 R 倍数和期望值。
 *
 * <p>为什么必须做这件事：原来的「信号后 N 日涨跌」有两个致命问题——
 * ① 同一只票连续 5 天出信号，会被当成 5 个独立样本（窗口重叠，等于自己骗自己）；
 * ② 完全没有出场规则，而真实收益 90% 由「怎么止损、怎么拿住」决定，不是由入场决定的。
 *
 * <p>模拟规则（全部写死在这里，可对照、可质疑）：
 * <ol>
 *   <li><b>入场</b>：买点次日开盘价；开盘就涨停（缺口 ≥ 9.5%）视为买不到，跳过；</li>
 *   <li><b>一次只持一笔</b>：持仓期间出现的新买点全部忽略，直到这笔平仓（消除重叠样本）；</li>
 *   <li><b>初始止损</b>：入场价 − {@value #STOP_ATR}×ATR(14)，每股风险 = 入场价 − 止损价，这就是 1R；</li>
 *   <li><b>保本</b>：盘中收盘涨到 {@value #BREAKEVEN_AT_R}R 以上，把止损抬到成本；</li>
 *   <li><b>跟踪</b>：收盘涨到 {@value #TRAIL_AT_R}R 以上，止损改为「收盘 − {@value #STOP_ATR}×ATR」并只升不降；</li>
 *   <li><b>时间止损</b>：最长持有 {@value #MAX_HOLD_DAYS} 个交易日，到时按收盘价出；</li>
 *   <li><b>策略卖点</b>：出现卖点信号 → 次日开盘出；</li>
 *   <li><b>数据末尾</b>：还没出场就按最后一根收盘平掉，出场原因记「末端」。</li>
 * </ol>
 *
 * <p>成交价的保守处理：止损日若开盘已低于止损价（跳空），按<b>开盘价</b>成交而不是止损价；
 * 买入/卖出都加 0.05% 滑点；佣金/过户费/印花税按 {@link TradeCost} 扣。
 * 也就是说，这套模拟在每一个可以「偏向好看」的地方都选了偏保守的一边。
 */
public final class TradeSimulator {

    /** 初始止损距离 = 2×ATR(14)；同时也是 1R 的定义（等于 {@link TradeParams#DEFAULT}） */
    public static final double STOP_ATR = TradeParams.DEFAULT.stopAtr();
    /** 盈利达到 1R 把止损抬到成本 */
    public static final double BREAKEVEN_AT_R = TradeParams.DEFAULT.breakevenAtR();
    /** 盈利达到 2R 之后改用 ATR 跟踪止损 */
    public static final double TRAIL_AT_R = TradeParams.DEFAULT.trailAtR();
    /** 最长持有交易日数（时间止损） */
    public static final int MAX_HOLD_DAYS = TradeParams.DEFAULT.maxHoldDays();
    /** ATR 周期 */
    public static final int ATR_PERIOD = 14;
    /** 少于这个交易数只能当参考 */
    public static final int MIN_TRADES = 30;

    private TradeSimulator() {}

    public static List<SimTrade> simulate(List<KlineBar> bars, List<SignalPoint> signals, String strategyId) {
        return simulate(bars, signals, strategyId, null, TradeParams.DEFAULT);
    }

    public static List<SimTrade> simulate(List<KlineBar> bars, List<SignalPoint> signals, String strategyId,
                                          Map<String, Double> benchCloses) {
        return simulate(bars, signals, strategyId, benchCloses, TradeParams.DEFAULT);
    }

    /**
     * @param benchCloses 沪深300 收盘价（日期 → 收盘），可为 null（此时每笔的 benchPct 为 null）
     * @param params      止损/保本/跟踪/持有期限参数；换参数就能做样本外验证与敏感性检验
     * @return 按时间顺序的成交记录；无信号或数据不足时返回空列表
     */
    public static List<SimTrade> simulate(List<KlineBar> bars, List<SignalPoint> signals, String strategyId,
                                          Map<String, Double> benchCloses, TradeParams params) {
        List<SimTrade> out = new ArrayList<>();
        if (bars == null || bars.size() < 30 || signals == null || signals.isEmpty()) {
            return out;
        }
        TradeParams p = params == null ? TradeParams.DEFAULT : params;
        int n = bars.size();
        double[] atr = IndicatorEngine.atr(bars, ATR_PERIOD);
        Map<String, Integer> at = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            at.put(bars.get(i).date(), i);
        }
        Set<Integer> buyDays = new HashSet<>();
        Set<Integer> sellDays = new HashSet<>();
        for (SignalPoint s : signals) {
            if (!strategyId.equals(s.strategy())) {
                continue;
            }
            Integer idx = at.get(s.date());
            if (idx == null) {
                continue;
            }
            if ("BUY".equals(s.action())) {
                buyDays.add(idx);
            } else if ("SELL".equals(s.action())) {
                sellDays.add(idx);
            }
        }
        if (buyDays.isEmpty()) {
            return out;
        }
        TreeMap<String, Double> bench = benchCloses == null || benchCloses.isEmpty()
                ? null : new TreeMap<>(benchCloses);

        int i = 0;
        while (i < n) {
            if (!buyDays.contains(i)) {
                i++;
                continue;
            }
            int entryIdx = i + 1;
            if (entryIdx >= n) {
                break;
            }
            double signalClose = bars.get(i).close();
            double openPx = bars.get(entryIdx).open();
            double atrAtEntry = atr[entryIdx];
            if (!(openPx > 0) || !(signalClose > 0) || !(atrAtEntry > 0)) {
                i++;
                continue;
            }
            if (openPx / signalClose - 1 >= StrategyEngine.LIMIT_UP_GAP) {
                i++; // 开盘涨停，买不到
                continue;
            }
            double entry = TradeCost.buyPrice(openPx);
            double risk = p.stopAtr() * atrAtEntry;
            if (!(risk > 0)) {
                i++;
                continue;
            }

            double stop = entry - risk;
            boolean stopMoved = false;
            double mae = 0;
            double mfe = 0;
            int exitIdx = -1;
            double exitRaw = 0;
            String reason = null;

            for (int d = entryIdx; d < n; d++) {
                KlineBar bar = bars.get(d);
                // 1) 昨天出了卖点 → 今天开盘就走（开盘先于盘中任何价格）
                if (d > entryIdx && sellDays.contains(d - 1)) {
                    exitRaw = bar.open();
                    exitIdx = d;
                    reason = "卖点";
                    break;
                }
                mae = Math.min(mae, (bar.low() - entry) / risk);
                mfe = Math.max(mfe, (bar.high() - entry) / risk);
                // 2) 盘中止损（跳空低开按开盘价成交，比按止损价成交更保守）
                if (bar.low() <= stop) {
                    exitRaw = bar.open() < stop ? bar.open() : stop;
                    exitIdx = d;
                    reason = stopMoved ? "移动止损" : "止损";
                    break;
                }
                // 3) 用收盘价更新移动止损，只对「次日」生效，避免用当天收盘价当天成交
                double rNow = (bar.close() - entry) / risk;
                if (rNow >= p.breakevenAtR() && stop < entry) {
                    stop = entry;
                    stopMoved = true;
                }
                if (rNow >= p.trailAtR() && atr[d] > 0) {
                    double trail = bar.close() - p.stopAtr() * atr[d];
                    if (trail > stop) {
                        stop = trail;
                        stopMoved = true;
                    }
                }
                // 4) 时间止损
                if (d - entryIdx + 1 >= p.maxHoldDays()) {
                    exitRaw = bar.close();
                    exitIdx = d;
                    reason = "时间";
                    break;
                }
            }
            if (exitIdx < 0) {
                exitIdx = n - 1;
                exitRaw = bars.get(exitIdx).close();
                reason = "末端";
            }
            if (!(exitRaw > 0)) {
                i = exitIdx + 1;
                continue;
            }
            double exit = TradeCost.sellPrice(exitRaw);
            double pnlPerShare = TradeCost.netPnlPerShare(entry, exit);
            Double benchPct = benchReturn(bench, bars.get(entryIdx).date(), bars.get(exitIdx).date());
            out.add(new SimTrade(
                    strategyId,
                    bars.get(entryIdx).date(),
                    round(entry, 3),
                    bars.get(exitIdx).date(),
                    round(exit, 3),
                    reason,
                    round(pnlPerShare / risk, 3),
                    exitIdx - entryIdx + 1,
                    round(mae, 3),
                    round(mfe, 3),
                    round(pnlPerShare / entry * 100, 2),
                    benchPct == null ? null : round(benchPct * 100, 2)
            ));
            i = exitIdx + 1; // 一次只持一笔：平仓之后才接受下一个买点
        }
        return out;
    }

    private static Double benchReturn(TreeMap<String, Double> bench, String fromDate, String toDate) {
        if (bench == null) {
            return null;
        }
        Map.Entry<String, Double> from = bench.floorEntry(fromDate);
        Map.Entry<String, Double> to = bench.floorEntry(toDate);
        if (from == null || to == null || !(from.getValue() > 0)) {
            return null;
        }
        return to.getValue() / from.getValue() - 1;
    }

    private static double round(double v, int scale) {
        double p = Math.pow(10, scale);
        return Math.round(v * p) / p;
    }
}
