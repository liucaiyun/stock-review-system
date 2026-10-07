package com.yan.stockreview.strategy;

import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.ParamSensitivity;
import com.yan.stockreview.dto.SignalPoint;
import com.yan.stockreview.dto.SimTrade;
import com.yan.stockreview.dto.TradeStats;
import com.yan.stockreview.dto.WalkForwardFold;
import com.yan.stockreview.dto.WalkForwardStats;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 样本外验证（walk-forward）+ 参数敏感性。
 *
 * <p>要回答的问题只有一个：<b>刚才那些正期望，是真的，还是我用同一段历史挑参数挑出来的？</b>
 *
 * <p>做法：
 * <ol>
 *   <li>把全部样本股的时间轴按<b>日历日期</b>等分成 folds 段（切分只看日期，不看结果，避免泄漏）；</li>
 *   <li>锚定式推进：第 k 折用「起点 ≤ 入场日 &lt; 第 k 段起点」的交易在参数网格里挑期望值最高的参数，
 *       然后把这个参数原封不动地拿去跑第 k 段（测试段）；</li>
 *   <li>把各折测试段的交易合并，就是「如果当年就这么往前走」的真实表现；</li>
 *   <li>再拿它和「在全部历史上挑最好参数」的结果对比：两者的差就是过拟合的代价。</li>
 * </ol>
 *
 * <p>信号只需要算一次：所有指标（MA/EMA/RSI/KDJ/BOLL/ATR）都是因果的，第 i 根的信号只依赖 ≤ i 的数据，
 * 所以按日期切交易不会引入未来信息。反过来，切 K 线重算指标会让 EMA 的起点漂移，那才是错的。
 */
public final class WalkForwardAnalyzer {

    /** 默认折数：4 段 → 3 折测试（第 1 段只用来训练） */
    public static final int DEFAULT_FOLDS = 4;
    /** 训练段至少要有这么多笔交易才允许用它挑参数 */
    public static final int MIN_TRAIN_TRADES = 25;

    private WalkForwardAnalyzer() {}

    /** 一只股票的原始材料：K 线 + 已经算好的信号（含各策略的买卖点） */
    public record StockSeries(String code, List<KlineBar> bars, List<SignalPoint> signals) {}

    public static List<WalkForwardStats> analyze(List<StockSeries> series, Collection<String> strategies) {
        return analyze(series, strategies, TradeParams.grid(), DEFAULT_FOLDS, MIN_TRAIN_TRADES, null);
    }

    public static List<WalkForwardStats> analyze(List<StockSeries> series, Collection<String> strategies,
                                                 List<TradeParams> grid, int folds, int minTrainTrades,
                                                 Map<String, Double> benchCloses) {
        List<WalkForwardStats> out = new ArrayList<>();
        if (series == null || series.isEmpty() || strategies == null || strategies.isEmpty()) {
            return out;
        }
        List<TradeParams> combos = (grid == null || grid.isEmpty()) ? TradeParams.grid() : grid;
        int foldCount = Math.max(2, folds);

        // 时间范围只由 K 线决定，与参数、与结果无关
        String from = null;
        String to = null;
        int stocks = 0;
        for (StockSeries s : series) {
            if (s.bars() == null || s.bars().isEmpty()) {
                continue;
            }
            stocks++;
            String first = s.bars().get(0).date();
            String last = s.bars().get(s.bars().size() - 1).date();
            if (from == null || first.compareTo(from) < 0) {
                from = first;
            }
            if (to == null || last.compareTo(to) > 0) {
                to = last;
            }
        }
        if (from == null || to == null || from.compareTo(to) >= 0) {
            return out;
        }
        List<String> bounds = dateBounds(from, to, foldCount);

        for (String id : strategies) {
            // 1) 每个参数组合把所有股票的交易算一遍，后面切窗复用（只算这一次）
            Map<TradeParams, List<SimTrade>> byParams = new LinkedHashMap<>();
            for (TradeParams p : combos) {
                List<SimTrade> all = new ArrayList<>();
                for (StockSeries s : series) {
                    if (s.bars() == null || s.bars().isEmpty()) {
                        continue;
                    }
                    all.addAll(TradeSimulator.simulate(s.bars(), s.signals(), id, benchCloses, p));
                }
                all.sort(Comparator.comparing(SimTrade::entryDate));
                byParams.put(p, all);
            }
            int total = byParams.values().stream().mapToInt(List::size).max().orElse(0);
            if (total == 0) {
                continue;
            }

            // 2) 锚定式前进：训练段 [起点, 测试段起点)，测试段 [起点, 终点)
            List<WalkForwardFold> foldList = new ArrayList<>();
            List<SimTrade> oosPool = new ArrayList<>();
            int positive = 0;
            for (int k = 1; k < foldCount; k++) {
                String testStart = bounds.get(k);
                String testEnd = bounds.get(k + 1);
                TradeParams best = null;
                double bestExp = Double.NEGATIVE_INFINITY;
                int bestN = 0;
                for (TradeParams p : combos) {
                    List<SimTrade> train = inRange(byParams.get(p), bounds.get(0), testStart);
                    if (train.size() < minTrainTrades) {
                        continue;
                    }
                    Double exp = TradeStats.from(train, 0).expectancyR();
                    if (exp != null && exp > bestExp) {
                        bestExp = exp;
                        best = p;
                        bestN = train.size();
                    }
                }
                if (best == null) {
                    continue; // 训练段样本不够，这一折不参与
                }
                List<SimTrade> test = inRange(byParams.get(best), testStart, testEnd);
                TradeStats tt = TradeStats.from(test, 0);
                if (tt.trades() > 0 && tt.expectancyR() != null && tt.expectancyR() > 0) {
                    positive++;
                }
                foldList.add(new WalkForwardFold(
                        k, bounds.get(0), testStart, testEnd, best.label(),
                        bestN, round(bestExp, 3),
                        tt.trades(), tt.expectancyR(), tt.winRate(), tt.totalR(), tt.maxDrawdownR()));
                oosPool.addAll(test);
            }

            // 3) 合并样本外 + 全样本最优 + 网格分布
            TradeStats oos = TradeStats.from(oosPool, 0);
            TradeParams isBest = null;
            double isBestExp = Double.NEGATIVE_INFINITY;
            List<Double> exps = new ArrayList<>();
            for (TradeParams p : combos) {
                Double exp = TradeStats.from(byParams.get(p), 0).expectancyR();
                if (exp == null) {
                    continue;
                }
                exps.add(exp);
                if (exp > isBestExp) {
                    isBestExp = exp;
                    isBest = p;
                }
            }
            exps.sort(Comparator.naturalOrder());
            Double gMin = exps.isEmpty() ? null : round(exps.get(0), 3);
            Double gMax = exps.isEmpty() ? null : round(exps.get(exps.size() - 1), 3);
            Double gMed = exps.isEmpty() ? null : round(exps.get(exps.size() / 2), 3);

            out.add(new WalkForwardStats(
                    id, StrategyEngine.NAMES.getOrDefault(id, id), from, to, stocks, total,
                    foldList, foldList.size(), positive,
                    oos.trades(), oos.expectancyR(), oos.rStdErr(), oos.tStat(), oos.winRate(),
                    oos.profitFactor(), oos.totalR(), oos.maxDrawdownR(),
                    isBest == null ? null : round(isBestExp, 3), isBest == null ? null : isBest.label(),
                    gMin, gMed, gMax, combos.size(),
                    sensitivity(series, id, benchCloses)
            ));
        }
        return out;
    }

    /**
     * 参数敏感性：一次只动一个参数，其余保持默认，看期望值怎么变。
     * 这不是为了「找到更好的参数」，而是为了看<b>结论稳不稳</b>。
     */
    private static List<ParamSensitivity> sensitivity(List<StockSeries> series, String strategyId,
                                                      Map<String, Double> benchCloses) {
        List<ParamSensitivity> rows = new ArrayList<>();
        TradeParams d = TradeParams.DEFAULT;
        // 每组都带上「默认值」那一行，方便对照其它取值
        for (double v : new double[]{d.stopAtr(), 1.0, 1.5, 2.5, 3.0}) {
            rows.add(row(series, strategyId, benchCloses, "止损倍数", v));
        }
        for (double v : new double[]{d.breakevenAtR(), 0.5, 1.5, 3.0}) {
            rows.add(row(series, strategyId, benchCloses, "保本触发(R)", v));
        }
        for (double v : new double[]{d.trailAtR(), 1.5, 3.0, 4.0}) {
            rows.add(row(series, strategyId, benchCloses, "跟踪触发(R)", v));
        }
        for (int v : new int[]{d.maxHoldDays(), 5, 10, 40}) {
            rows.add(row(series, strategyId, benchCloses, "最长持有(日)", v));
        }
        return rows;
    }

    private static ParamSensitivity row(List<StockSeries> series, String strategyId,
                                        Map<String, Double> benchCloses, String param, double value) {
        TradeParams base = TradeParams.DEFAULT;
        TradeParams p = switch (param) {
            case "止损倍数" -> new TradeParams(value, base.breakevenAtR(), base.trailAtR(), base.maxHoldDays());
            case "保本触发(R)" -> new TradeParams(base.stopAtr(), value, base.trailAtR(), base.maxHoldDays());
            case "跟踪触发(R)" -> new TradeParams(base.stopAtr(), base.breakevenAtR(), value, base.maxHoldDays());
            default -> new TradeParams(base.stopAtr(), base.breakevenAtR(), base.trailAtR(), (int) value);
        };
        List<SimTrade> all = new ArrayList<>();
        for (StockSeries s : series) {
            if (s.bars() == null || s.bars().isEmpty()) {
                continue;
            }
            all.addAll(TradeSimulator.simulate(s.bars(), s.signals(), strategyId, benchCloses, p));
        }
        TradeStats t = TradeStats.from(all, 0);
        return new ParamSensitivity(param, value, t.trades(), t.expectancyR(), t.winRate());
    }

    private static List<SimTrade> inRange(List<SimTrade> trades, String fromInclusive, String toExclusive) {
        List<SimTrade> out = new ArrayList<>();
        if (trades == null) {
            return out;
        }
        for (SimTrade t : trades) {
            String d = t.entryDate();
            if (d.compareTo(fromInclusive) >= 0 && d.compareTo(toExclusive) < 0) {
                out.add(t);
            }
        }
        return out;
    }

    /** 把 [from, to] 按日历天等分成 folds 段，返回 folds+1 个边界日期 */
    public static List<String> dateBounds(String from, String to, int folds) {
        LocalDate a = LocalDate.parse(from);
        LocalDate b = LocalDate.parse(to);
        long total = Math.max(1, ChronoUnit.DAYS.between(a, b));
        List<String> out = new ArrayList<>();
        for (int k = 0; k <= folds; k++) {
            out.add(a.plusDays(total * k / folds).toString());
        }
        return out;
    }

    private static Double round(double v, int scale) {
        double p = Math.pow(10, scale);
        return Math.round(v * p) / p;
    }
}
