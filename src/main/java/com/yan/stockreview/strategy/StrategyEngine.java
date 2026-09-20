package com.yan.stockreview.strategy;

import com.yan.stockreview.dto.ChartOverlay;
import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.ScenarioCard;
import com.yan.stockreview.dto.SignalPoint;
import com.yan.stockreview.dto.StrategyLesson;
import com.yan.stockreview.dto.StrategyStats;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 常见交易策略：金叉死叉、趋势、超买超卖、布林带、放量突破 */
public class StrategyEngine {

    public static final String MA_CROSS = "MA_CROSS";
    public static final String MA_TREND = "MA_TREND";
    public static final String MACD = "MACD";
    public static final String RSI = "RSI";
    public static final String KDJ = "KDJ";
    public static final String BOLL = "BOLL";
    public static final String VOL = "VOL";
    public static final String MY_STEADY = "MY_STEADY";
    public static final String MY_ULTRA = "MY_ULTRA";

    public static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put(MY_STEADY, "稳健版（自有）");
        NAMES.put(MY_ULTRA, "超稳健版（自有）");
        NAMES.put(MA_CROSS, "均线金叉死叉");
        NAMES.put(MA_TREND, "均线多空排列");
        NAMES.put(MACD, "MACD金叉死叉");
        NAMES.put(RSI, "RSI超买超卖");
        NAMES.put(KDJ, "KDJ金叉死叉");
        NAMES.put(BOLL, "布林带突破");
        NAMES.put(VOL, "放量突破");
    }

    /** 分歧日判定：振幅>8% 且实体<2%（不追、不建仓） */
    public static final double DIVERGENCE_AMP_PCT = 8.0;
    public static final double DIVERGENCE_BODY_PCT = 2.0;

    public AnalysisResult analyze(List<KlineBar> bars) {
        return analyze(bars, NAMES.keySet());
    }

    public AnalysisResult analyze(List<KlineBar> bars, Collection<String> selected) {
        if (bars == null || bars.size() < 35) {
            throw new IllegalArgumentException("K线数据不足，至少需要约 35 根日线才能计算策略");
        }
        Set<String> use = normalize(selected);
        double[] close = IndicatorEngine.closes(bars);
        double[] vol = IndicatorEngine.volumes(bars);
        double[] ma5 = IndicatorEngine.sma(close, 5);
        double[] ma10 = IndicatorEngine.sma(close, 10);
        double[] ma20 = IndicatorEngine.sma(close, 20);
        double[] ma60 = IndicatorEngine.sma(close, 60);
        double[] volMa5 = IndicatorEngine.sma(vol, 5);
        double[] volMa20 = IndicatorEngine.sma(vol, 20);
        double[] rsi = IndicatorEngine.rsi(close, 14);
        IndicatorEngine.MacdSeries macd = IndicatorEngine.macd(close);
        IndicatorEngine.KdjSeries kdj = IndicatorEngine.kdj(bars, 9);
        IndicatorEngine.BollSeries boll = IndicatorEngine.boll(close, 20, 2);

        List<SignalPoint> signals = new ArrayList<>();
        List<String> divergenceDays = new ArrayList<>();
        int start = 30;
        for (int i = start; i < bars.size(); i++) {
            String date = bars.get(i).date();
            double price = close[i];
            boolean divergence = isDivergenceDay(bars, i);
            if (divergence) {
                divergenceDays.add(date);
            }

            if (use.contains(MA_CROSS)) {
                if (crossedUp(ma5, ma20, i)) {
                    signals.add(sig(date, MA_CROSS, "BUY", "MA5 上穿 MA20（金叉）", price));
                } else if (crossedDown(ma5, ma20, i)) {
                    signals.add(sig(date, MA_CROSS, "SELL", "MA5 下穿 MA20（死叉）", price));
                }
            }

            if (use.contains(MA_TREND)) {
                boolean bull = valid(ma5[i]) && valid(ma10[i]) && valid(ma20[i]) && ma5[i] > ma10[i] && ma10[i] > ma20[i];
                boolean bear = valid(ma5[i]) && valid(ma10[i]) && valid(ma20[i]) && ma5[i] < ma10[i] && ma10[i] < ma20[i];
                boolean bullPrev = valid(ma5[i - 1]) && valid(ma10[i - 1]) && valid(ma20[i - 1])
                        && ma5[i - 1] > ma10[i - 1] && ma10[i - 1] > ma20[i - 1];
                boolean bearPrev = valid(ma5[i - 1]) && valid(ma10[i - 1]) && valid(ma20[i - 1])
                        && ma5[i - 1] < ma10[i - 1] && ma10[i - 1] < ma20[i - 1];
                if (bull && !bullPrev) {
                    signals.add(sig(date, MA_TREND, "BUY", "MA5>MA10>MA20 多头排列形成", price));
                } else if (bear && !bearPrev) {
                    signals.add(sig(date, MA_TREND, "SELL", "MA5<MA10<MA20 空头排列形成", price));
                }
            }

            if (use.contains(MACD)) {
                if (crossedUp(macd.dif(), macd.dea(), i)) {
                    signals.add(sig(date, MACD, "BUY", "DIF 上穿 DEA（MACD 金叉）", price));
                } else if (crossedDown(macd.dif(), macd.dea(), i)) {
                    signals.add(sig(date, MACD, "SELL", "DIF 下穿 DEA（MACD 死叉）", price));
                }
            }

            if (use.contains(RSI) && valid(rsi[i]) && valid(rsi[i - 1])) {
                if (rsi[i - 1] <= 30 && rsi[i] > 30) {
                    signals.add(sig(date, RSI, "BUY", "RSI 由超卖区上穿 30", price));
                } else if (rsi[i - 1] >= 70 && rsi[i] < 70) {
                    signals.add(sig(date, RSI, "SELL", "RSI 由超买区下穿 70", price));
                }
            }

            if (use.contains(KDJ)) {
                if (crossedUp(kdj.k(), kdj.d(), i) && kdj.j()[i] < 40) {
                    signals.add(sig(date, KDJ, "BUY", "K 上穿 D 且 J 偏低（KDJ 金叉）", price));
                } else if (crossedDown(kdj.k(), kdj.d(), i) && kdj.j()[i] > 60) {
                    signals.add(sig(date, KDJ, "SELL", "K 下穿 D 且 J 偏高（KDJ 死叉）", price));
                }
            }

            if (use.contains(BOLL) && valid(boll.dn()[i]) && valid(boll.dn()[i - 1])) {
                if (close[i - 1] > boll.dn()[i - 1] && close[i] <= boll.dn()[i]) {
                    signals.add(sig(date, BOLL, "BUY", "收盘跌破布林下轨，关注超跌反弹", price));
                } else if (close[i - 1] < boll.up()[i - 1] && close[i] >= boll.up()[i]) {
                    signals.add(sig(date, BOLL, "SELL", "收盘突破布林上轨，关注冲高回落", price));
                }
            }

            if (use.contains(VOL) && valid(volMa20[i]) && valid(ma20[i]) && i >= 20) {
                double prevHigh = maxClose(close, i - 20, i - 1);
                if (close[i] > prevHigh && vol[i] > volMa20[i] * 1.5 && close[i] > ma20[i]) {
                    signals.add(sig(date, VOL, "BUY", "放量（>1.5倍均量）突破近20日高点", price));
                }
            }

            if (use.contains(MY_STEADY) || use.contains(MY_ULTRA)) {
                Resonance cur = resonanceAt(bars, i, close, vol, ma5, ma20, rsi, macd, boll, volMa20);
                Resonance prev = resonanceAt(bars, i - 1, close, vol, ma5, ma20, rsi, macd, boll, volMa20);
                boolean bullFormed = cur.bull >= 2 && prev.bull < 2;
                boolean bearFormed = cur.bear >= 2 && prev.bear < 2;
                if (use.contains(MY_STEADY)) {
                    if (bullFormed && !divergence) {
                        signals.add(sig(date, MY_STEADY, "BUY",
                                cur.bull + "零件共振（" + String.join("/", cur.bullNames) + "）", price));
                    } else if (bullFormed) {
                        signals.add(sig(date, MY_STEADY, "WATCH",
                                "共振形成但今天是分歧日（振幅>8%且实体<2%），纪律不建仓", price));
                    }
                    if (bearFormed) {
                        signals.add(sig(date, MY_STEADY, "SELL",
                                cur.bear + "零件转空（" + String.join("/", cur.bearNames) + "）", price));
                    }
                }
                if (use.contains(MY_ULTRA)) {
                    boolean gate = trendGate(close, ma20, ma60, i);
                    if (bullFormed && gate && !divergence) {
                        signals.add(sig(date, MY_ULTRA, "BUY",
                                cur.bull + "零件共振+趋势门（MA20>MA60且收盘>MA20）", price));
                    } else if (bullFormed && !divergence) {
                        signals.add(sig(date, MY_ULTRA, "WATCH",
                                "共振形成但趋势门未过（需MA20>MA60且收盘>MA20），不建仓", price));
                    }
                    // 强卖点：转空共振，或收盘跌破MA20（趋势门破坏）
                    boolean gateBroken = valid(ma20[i]) && valid(ma20[i - 1])
                            && close[i - 1] >= ma20[i - 1] && close[i] < ma20[i];
                    if (bearFormed) {
                        signals.add(sig(date, MY_ULTRA, "SELL",
                                cur.bear + "零件转空（" + String.join("/", cur.bearNames) + "）", price));
                    } else if (gateBroken) {
                        signals.add(sig(date, MY_ULTRA, "SELL", "收盘跌破MA20，趋势门破坏，纪律出场", price));
                    }
                }
            }
        }

        Map<String, Object> indicators = new LinkedHashMap<>();
        indicators.put("ma5", IndicatorEngine.boxed(ma5));
        indicators.put("ma10", IndicatorEngine.boxed(ma10));
        indicators.put("ma20", IndicatorEngine.boxed(ma20));
        indicators.put("ma60", IndicatorEngine.boxed(ma60));
        indicators.put("rsi", IndicatorEngine.boxed(rsi));
        indicators.put("macdDif", IndicatorEngine.boxed(macd.dif()));
        indicators.put("macdDea", IndicatorEngine.boxed(macd.dea()));
        indicators.put("macdHist", IndicatorEngine.boxed(macd.hist()));
        indicators.put("kdjK", IndicatorEngine.boxed(kdj.k()));
        indicators.put("kdjD", IndicatorEngine.boxed(kdj.d()));
        indicators.put("kdjJ", IndicatorEngine.boxed(kdj.j()));
        indicators.put("bollUp", IndicatorEngine.boxed(boll.up()));
        indicators.put("bollMid", IndicatorEngine.boxed(boll.mid()));
        indicators.put("bollDn", IndicatorEngine.boxed(boll.dn()));
        indicators.put("volMa5", IndicatorEngine.boxed(volMa5));
        indicators.put("volMa20", IndicatorEngine.boxed(volMa20));

        List<StrategyStats> stats = backtest(bars, signals, use);
        List<StrategyLesson> lessons = buildLessons(use, bars, close, vol, ma5, ma10, ma20, ma60, volMa20,
                rsi, macd, kdj, boll, signals, stats);
        Double lastMa20 = Double.isNaN(ma20[ma20.length - 1]) ? null : IndicatorEngine.round(ma20[ma20.length - 1], 3);
        ChartOverlay structure = ScenarioEngine.structure(bars, lastMa20);
        List<ScenarioCard> scenarios = ScenarioEngine.evaluate(bars, close, vol, ma20, volMa20);
        return new AnalysisResult(signals, indicators, stats, lessons, new ArrayList<>(use), structure, scenarios,
                divergenceDays);
    }

    public List<StrategyStats> backtest(List<KlineBar> bars, List<SignalPoint> signals) {
        return backtest(bars, signals, NAMES.keySet());
    }

    public List<StrategyStats> backtest(List<KlineBar> bars, List<SignalPoint> signals, Collection<String> selected) {
        Set<String> use = normalize(selected);
        Map<String, List<SignalPoint>> grouped = new LinkedHashMap<>();
        for (String id : use) {
            grouped.put(id, new ArrayList<>());
        }
        for (SignalPoint s : signals) {
            if (use.contains(s.strategy())) {
                grouped.computeIfAbsent(s.strategy(), k -> new ArrayList<>()).add(s);
            }
        }
        Map<String, Integer> dateIndex = new LinkedHashMap<>();
        for (int i = 0; i < bars.size(); i++) {
            dateIndex.put(bars.get(i).date(), i);
        }
        List<StrategyStats> list = new ArrayList<>();
        for (Map.Entry<String, List<SignalPoint>> e : grouped.entrySet()) {
            List<SignalPoint> pts = e.getValue().stream()
                    .filter(p -> "BUY".equals(p.action()) || "SELL".equals(p.action()))
                    .toList();
            int buy = 0;
            int sell = 0;
            for (SignalPoint p : pts) {
                if ("BUY".equals(p.action())) buy++;
                else sell++;
            }
            Horizon h5 = eval(bars, dateIndex, pts, 5);
            Horizon h10 = eval(bars, dateIndex, pts, 10);
            Horizon h20 = eval(bars, dateIndex, pts, 20);
            list.add(new StrategyStats(
                    e.getKey(), NAMES.getOrDefault(e.getKey(), e.getKey()),
                    buy, sell, h5.count,
                    h5.winRate, h5.avgReturn,
                    h10.winRate, h10.avgReturn,
                    h20.winRate, h20.avgReturn
            ));
        }
        return list;
    }

    private Horizon eval(List<KlineBar> bars, Map<String, Integer> dateIndex, List<SignalPoint> pts, int horizon) {
        int count = 0;
        int win = 0;
        double sum = 0;
        for (SignalPoint p : pts) {
            Integer i = dateIndex.get(p.date());
            if (i == null || i + horizon >= bars.size()) {
                continue;
            }
            double ret = (bars.get(i + horizon).close() - p.price()) / p.price();
            if ("SELL".equals(p.action())) {
                ret = -ret;
            }
            count++;
            sum += ret;
            if (ret > 0) {
                win++;
            }
        }
        if (count == 0) {
            return new Horizon(0, null, null);
        }
        return new Horizon(count,
                IndicatorEngine.round(win * 100.0 / count, 1),
                IndicatorEngine.round(sum / count * 100, 2));
    }

    private static SignalPoint sig(String date, String strategy, String action, String reason, double price) {
        return new SignalPoint(date, strategy, NAMES.get(strategy), action, reason, IndicatorEngine.round(price, 4));
    }

    private static Set<String> normalize(Collection<String> selected) {
        Set<String> use = new LinkedHashSet<>();
        if (selected == null || selected.isEmpty()) {
            use.addAll(NAMES.keySet());
            return use;
        }
        for (String id : selected) {
            if (id != null && NAMES.containsKey(id.trim())) {
                use.add(id.trim());
            }
        }
        if (use.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个有效策略");
        }
        return use;
    }

    private List<StrategyLesson> buildLessons(Set<String> use, List<KlineBar> bars, double[] close, double[] vol,
                                             double[] ma5, double[] ma10, double[] ma20, double[] ma60, double[] volMa20,
                                             double[] rsi, IndicatorEngine.MacdSeries macd,
                                             IndicatorEngine.KdjSeries kdj, IndicatorEngine.BollSeries boll,
                                             List<SignalPoint> signals, List<StrategyStats> stats) {
        int i = bars.size() - 1;
        String lastDate = bars.get(i).date();
        Map<String, SignalPoint> lastBy = new LinkedHashMap<>();
        Map<String, SignalPoint> todayBy = new LinkedHashMap<>();
        for (SignalPoint s : signals) {
            lastBy.put(s.strategy(), s);
            if (lastDate.equals(s.date())) {
                todayBy.put(s.strategy(), s);
            }
        }
        Map<String, StrategyStats> statsBy = new LinkedHashMap<>();
        for (StrategyStats s : stats) {
            statsBy.put(s.strategy(), s);
        }
        Map<String, StrategyCatalog.Def> defs = StrategyCatalog.byId();
        List<StrategyLesson> lessons = new ArrayList<>();
        for (String id : use) {
            StrategyCatalog.Def def = defs.get(id);
            if (def == null) {
                continue;
            }
            StrategyLesson lesson = new StrategyLesson();
            lesson.setId(def.id());
            lesson.setName(def.name());
            lesson.setCategory(def.category());
            lesson.setSummary(def.summary());
            lesson.setIdea(def.idea());
            lesson.setHow(def.how());
            lesson.setBuyRule(def.buyRule());
            lesson.setSellRule(def.sellRule());
            lesson.setPitfall(def.pitfall());
            lesson.setSuitable(def.suitable());
            SignalPoint today = todayBy.get(id);
            lesson.setTodaySignal(today);
            lesson.setLastSignal(lastBy.get(id));
            lesson.setStats(statsBy.get(id));
            lesson.setStatus(today == null ? "WATCH" : today.action());
            lesson.setSnapshot(snapshotOf(id, i, close, vol, ma5, ma10, ma20, volMa20,
                    rsi, macd, kdj, boll, bars));
            lesson.setExplain(explainOf(id, i, today, close, vol, ma5, ma10, ma20, volMa20,
                    rsi, macd, kdj, boll, bars, ma60));
            lessons.add(lesson);
        }
        return lessons;
    }

    private Map<String, Object> snapshotOf(String id, int i, double[] close, double[] vol,
                                           double[] ma5, double[] ma10, double[] ma20, double[] volMa20,
                                           double[] rsi, IndicatorEngine.MacdSeries macd,
                                           IndicatorEngine.KdjSeries kdj, IndicatorEngine.BollSeries boll,
                                           List<KlineBar> bars) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("close", r(close[i]));
        switch (id) {
            case MY_STEADY, MY_ULTRA -> {
                Resonance res = resonanceAt(bars, i, close, vol, ma5, ma20, rsi, macd, boll, volMa20);
                snap.put("多头零件", res.bull + (res.bullNames.isEmpty() ? "" : "（" + String.join("/", res.bullNames) + "）"));
                snap.put("空头零件", res.bear + (res.bearNames.isEmpty() ? "" : "（" + String.join("/", res.bearNames) + "）"));
                double[] ab = amplitudeAndBody(bars, i);
                snap.put("振幅", ab[0] + "%");
                snap.put("实体", ab[1] + "%");
                snap.put("分歧日", isDivergenceDay(bars, i) ? "是（不建仓）" : "否");
            }
            case MA_CROSS -> {
                snap.put("MA5", r(ma5[i]));
                snap.put("MA20", r(ma20[i]));
            }
            case MA_TREND -> {
                snap.put("MA5", r(ma5[i]));
                snap.put("MA10", r(ma10[i]));
                snap.put("MA20", r(ma20[i]));
            }
            case MACD -> {
                snap.put("DIF", r(macd.dif()[i]));
                snap.put("DEA", r(macd.dea()[i]));
                snap.put("柱", r(macd.hist()[i]));
            }
            case RSI -> snap.put("RSI14", r(rsi[i]));
            case KDJ -> {
                snap.put("K", r(kdj.k()[i]));
                snap.put("D", r(kdj.d()[i]));
                snap.put("J", r(kdj.j()[i]));
            }
            case BOLL -> {
                snap.put("上轨", r(boll.up()[i]));
                snap.put("中轨", r(boll.mid()[i]));
                snap.put("下轨", r(boll.dn()[i]));
            }
            case VOL -> {
                snap.put("成交量", r(vol[i]));
                snap.put("20日均量", r(volMa20[i]));
                snap.put("量比", valid(volMa20[i]) && volMa20[i] != 0 ? r(vol[i] / volMa20[i]) : null);
                snap.put("MA20", r(ma20[i]));
            }
            default -> {
            }
        }
        return snap;
    }

    private String explainOf(String id, int i, SignalPoint today, double[] close, double[] vol,
                             double[] ma5, double[] ma10, double[] ma20, double[] volMa20,
                             double[] rsi, IndicatorEngine.MacdSeries macd,
                             IndicatorEngine.KdjSeries kdj, IndicatorEngine.BollSeries boll,
                             List<KlineBar> bars, double[] ma60) {
        if (today != null) {
            return "今天触发了信号：" + today.reason() + "。交叉或突破只发生在这一天，后面即使关系仍成立，也不会天天重复记。";
        }
        return switch (id) {
            case MY_STEADY -> explainResonance(bars, i, close, vol, ma5, ma20, rsi, macd, boll, volMa20, false, ma60);
            case MY_ULTRA -> explainResonance(bars, i, close, vol, ma5, ma20, rsi, macd, boll, volMa20, true, ma60);
            case MA_CROSS -> explainCross("MA5", ma5[i], "MA20", ma20[i], "金叉", "死叉");
            case MA_TREND -> explainTrend(ma5[i], ma10[i], ma20[i]);
            case MACD -> explainCross("DIF", macd.dif()[i], "DEA", macd.dea()[i], "金叉", "死叉")
                    + (macd.dif()[i] >= 0 ? " 当前 DIF 在零轴上方，偏多动能。" : " 当前 DIF 在零轴下方，偏空动能。");
            case RSI -> explainRsi(rsi[i]);
            case KDJ -> explainKdj(kdj.k()[i], kdj.d()[i], kdj.j()[i]);
            case BOLL -> explainBoll(close[i], boll.up()[i], boll.mid()[i], boll.dn()[i]);
            case VOL -> explainVol(close, i, vol[i], volMa20[i], ma20[i]);
            default -> "今天没有新信号。";
        };
    }

    private static String explainCross(String aName, double a, String bName, double b, String upName, String downName) {
        if (!valid(a) || !valid(b)) {
            return "均线还没算满，先多看几天。";
        }
        if (a > b) {
            return aName + " 在 " + bName + " 上方（" + r(a) + " > " + r(b) + "），关系偏多，但今天没有交叉，所以没有新的"
                    + upName + "。学习点：信号记在「穿过的那一天」，不是「已经在上面的每一天」。";
        }
        if (a < b) {
            return aName + " 在 " + bName + " 下方（" + r(a) + " < " + r(b) + "），关系偏空，等待" + upName
                    + "。若明天短线重新穿回，才会记" + upName + "。";
        }
        return aName + " 与 " + bName + " 几乎重合，处于缠绕，" + downName + "/" + upName + "都还没发生。";
    }

    private static String explainTrend(double ma5, double ma10, double ma20) {
        if (!valid(ma5) || !valid(ma10) || !valid(ma20)) {
            return "均线还没算满。";
        }
        if (ma5 > ma10 && ma10 > ma20) {
            return "已经是多头排列（MA5>MA10>MA20），但今天不是「刚形成」的日子，所以没有新买点。学习点：排列形成那天记一次，之后属于趋势延续。";
        }
        if (ma5 < ma10 && ma10 < ma20) {
            return "已经是空头排列（MA5<MA10<MA20），今天不是刚转空的日子。可对照价格是否仍在均线下方。";
        }
        return "三条均线缠绕，没有排成多头或空头。这是震荡特征，趋势策略通常应该少动手。";
    }

    private static String explainRsi(double v) {
        if (!valid(v)) {
            return "RSI 还没算满 14 根。";
        }
        if (v < 30) {
            return "当前 RSI=" + r(v) + "，仍在超卖区（<30）。策略要等它上穿 30 才记买点，避免在下跌中途抄底。";
        }
        if (v > 70) {
            return "当前 RSI=" + r(v) + "，仍在超买区（>70）。策略要等它下穿 70 才记卖点。趋势市里它可能很久都不下来，这叫钝化。";
        }
        return "当前 RSI=" + r(v) + "，在 30~70 的中性区，今天没有进入或离开超买超卖，所以没有新信号。";
    }

    private static String explainKdj(double k, double d, double j) {
        if (!valid(k) || !valid(d)) {
            return "KDJ 还在初始化。";
        }
        String rel = k > d ? "K 在 D 上方" : (k < d ? "K 在 D 下方" : "K 与 D 缠绕");
        return "当前 K=" + r(k) + "，D=" + r(d) + "，J=" + r(j) + "。" + rel
                + "，但今天没有满足「交叉 + J 过滤」。J>80 偏热，J<20 偏冷，可用来体会敏感度，不要当成精确点位。";
    }

    private static String explainBoll(double close, double up, double mid, double dn) {
        if (!valid(up) || !valid(dn)) {
            return "布林带还没算满 20 日。";
        }
        if (close > up) {
            return "收盘已在上轨之上（" + r(close) + " > " + r(up) + "），但今天不是「刚突破」的日子。强趋势可以沿着上轨走，不一定马上回落。";
        }
        if (close < dn) {
            return "收盘已在下轨之下（" + r(close) + " < " + r(dn) + "），今天不是刚跌破。可能超跌，也可能趋势向下，先看中轨方向。";
        }
        return "收盘在上下轨之间（下轨 " + r(dn) + " ~ 上轨 " + r(up) + "，中轨 " + r(mid) + "），属于轨道内的正常波动，没有突破信号。";
    }

    private String explainVol(double[] close, int i, double vol, double volMa, double ma20) {
        if (!valid(volMa) || i < 20) {
            return "均量还没算满。";
        }
        double prevHigh = maxClose(close, i - 20, i - 1);
        double ratio = volMa == 0 ? 0 : vol / volMa;
        boolean newHigh = close[i] > prevHigh;
        boolean hot = vol > volMa * 1.5;
        boolean above = valid(ma20) && close[i] > ma20;
        return "量比约 " + r(ratio) + " 倍，收盘" + (newHigh ? "创了" : "没有创") + "近20日新高，价格在 MA20 "
                + (above ? "上方" : "下方") + "。"
                + (hot && newHigh && above ? "条件接近买点。" : "今天还没同时满足「放量 + 新高 + 站上均线」，所以没有记买点。");
    }

    private String explainResonance(List<KlineBar> bars, int i, double[] close, double[] vol,
                                    double[] ma5, double[] ma20, double[] rsi,
                                    IndicatorEngine.MacdSeries macd, IndicatorEngine.BollSeries boll,
                                    double[] volMa20, boolean ultra, double[] ma60) {
        Resonance res = resonanceAt(bars, i, close, vol, ma5, ma20, rsi, macd, boll, volMa20);
        StringBuilder sb = new StringBuilder();
        sb.append("当前多头零件 ").append(res.bull).append(" 个");
        if (!res.bullNames.isEmpty()) {
            sb.append("（").append(String.join("、", res.bullNames)).append("）");
        }
        sb.append("，空头零件 ").append(res.bear).append(" 个");
        if (!res.bearNames.isEmpty()) {
            sb.append("（").append(String.join("、", res.bearNames)).append("）");
        }
        sb.append("。买点需要「从不足 2 个变成 ≥2 个」才记一次，今天没形成新共振。");
        if (isDivergenceDay(bars, i)) {
            double[] ab = amplitudeAndBody(bars, i);
            sb.append(" 今天是分歧日（振幅 ").append(ab[0]).append("%、实体 ").append(ab[1])
                    .append("%），按纪律不追、不建仓。");
        }
        if (ultra) {
            boolean gate = trendGate(close, ma20, ma60, i);
            sb.append(" 趋势门").append(gate ? "已过（MA20>MA60 且收盘>MA20）"
                    : "未过（需要 MA20>MA60 且收盘>MA20），超稳健版只在门内建仓；收盘跌破 MA20 记强卖点。");
        }
        return sb.toString();
    }

    private static Double r(double v) {
        return valid(v) ? IndicatorEngine.round(v, 3) : null;
    }

    private static boolean crossedUp(double[] a, double[] b, int i) {
        return valid(a[i]) && valid(b[i]) && valid(a[i - 1]) && valid(b[i - 1])
                && a[i - 1] <= b[i - 1] && a[i] > b[i];
    }

    private static boolean crossedDown(double[] a, double[] b, int i) {
        return valid(a[i]) && valid(b[i]) && valid(a[i - 1]) && valid(b[i - 1])
                && a[i - 1] >= b[i - 1] && a[i] < b[i];
    }

    private static boolean valid(double v) {
        return !Double.isNaN(v);
    }

    private static double maxClose(double[] close, int from, int to) {
        double max = -Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            max = Math.max(max, close[i]);
        }
        return max;
    }

    private static double minClose(double[] close, int from, int to) {
        double min = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            min = Math.min(min, close[i]);
        }
        return min;
    }

    /** 分歧日：振幅（高-低）/昨收 > 8%，且实体 |收-开|/昨收 < 2% */
    public static boolean isDivergenceDay(List<KlineBar> bars, int i) {
        if (i <= 0 || i >= bars.size()) {
            return false;
        }
        double prevClose = bars.get(i - 1).close();
        if (prevClose <= 0) {
            return false;
        }
        KlineBar b = bars.get(i);
        double amp = (b.high() - b.low()) / prevClose * 100;
        double body = Math.abs(b.close() - b.open()) / prevClose * 100;
        return amp > DIVERGENCE_AMP_PCT && body < DIVERGENCE_BODY_PCT;
    }

    /** 今日振幅/实体（%），用于前端展示 */
    public static double[] amplitudeAndBody(List<KlineBar> bars, int i) {
        if (i <= 0 || i >= bars.size()) {
            return new double[]{0, 0};
        }
        double prevClose = bars.get(i - 1).close();
        if (prevClose <= 0) {
            return new double[]{0, 0};
        }
        KlineBar b = bars.get(i);
        return new double[]{
                IndicatorEngine.round((b.high() - b.low()) / prevClose * 100, 2),
                IndicatorEngine.round(Math.abs(b.close() - b.open()) / prevClose * 100, 2)
        };
    }

    /** 趋势门：MA20>MA60 且收盘>MA20 */
    private static boolean trendGate(double[] close, double[] ma20, double[] ma60, int i) {
        return valid(ma20[i]) && valid(ma60[i]) && ma20[i] > ma60[i] && close[i] > ma20[i];
    }

    /** 6 零件共振计数：双均线/MACD/RSI/布林/突破/量价，各自给多/空一票 */
    private static Resonance resonanceAt(List<KlineBar> bars, int i, double[] close, double[] vol,
                                         double[] ma5, double[] ma20, double[] rsi,
                                         IndicatorEngine.MacdSeries macd, IndicatorEngine.BollSeries boll,
                                         double[] volMa20) {
        Resonance r = new Resonance();
        if (i < 20) {
            return r;
        }
        // 1. 双均线
        if (valid(ma5[i]) && valid(ma20[i])) {
            if (ma5[i] > ma20[i]) r.bull("双均线");
            else if (ma5[i] < ma20[i]) r.bear("双均线");
        }
        // 2. MACD
        if (valid(macd.dif()[i]) && valid(macd.dea()[i])) {
            if (macd.dif()[i] > macd.dea()[i]) r.bull("MACD");
            else if (macd.dif()[i] < macd.dea()[i]) r.bear("MACD");
        }
        // 3. RSI
        if (valid(rsi[i])) {
            if (rsi[i] > 50) r.bull("RSI");
            else if (rsi[i] < 50) r.bear("RSI");
        }
        // 4. 布林（中轨方向）
        if (valid(boll.mid()[i])) {
            if (close[i] > boll.mid()[i]) r.bull("布林");
            else if (close[i] < boll.mid()[i]) r.bear("布林");
        }
        // 5. 突破（贴近20日新高/新低）
        double prevHigh = maxClose(close, i - 20, i - 1);
        double prevLow = minClose(close, i - 20, i - 1);
        if (close[i] >= prevHigh * 0.98) r.bull("突破");
        else if (close[i] <= prevLow * 1.02) r.bear("突破");
        // 6. 量价（量≥20日均量时，红K记多、绿K记空）
        if (valid(volMa20[i]) && vol[i] >= volMa20[i]) {
            if (bars.get(i).close() > bars.get(i).open()) r.bull("量价");
            else if (bars.get(i).close() < bars.get(i).open()) r.bear("量价");
        }
        return r;
    }

    private static final class Resonance {
        int bull;
        int bear;
        final List<String> bullNames = new ArrayList<>();
        final List<String> bearNames = new ArrayList<>();

        void bull(String name) {
            bull++;
            bullNames.add(name);
        }

        void bear(String name) {
            bear++;
            bearNames.add(name);
        }
    }

    private record Horizon(int count, Double winRate, Double avgReturn) {}

    public record AnalysisResult(
            List<SignalPoint> signals,
            Map<String, Object> indicators,
            List<StrategyStats> stats,
            List<StrategyLesson> lessons,
            List<String> selected,
            ChartOverlay overlay,
            List<ScenarioCard> scenarios,
            List<String> divergenceDays
    ) {}
}
