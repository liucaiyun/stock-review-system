package com.yan.stockreview.strategy;

import com.yan.stockreview.dto.ChartOverlay;
import com.yan.stockreview.dto.KlineBar;
import com.yan.stockreview.dto.ScenarioCard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中短线常用情景：回踩 MA20、放量突破。
 * 只描述「现在像不像」，不往买卖点列表里记信号，避免震荡市天天交叉。
 */
public final class ScenarioEngine {

    private static final int LOOKBACK = 20;

    private ScenarioEngine() {}

    public static ChartOverlay structure(List<KlineBar> bars, Double ma20) {
        ChartOverlay overlay = new ChartOverlay();
        overlay.setLookback(LOOKBACK);
        overlay.setMa20(ma20);
        if (bars == null || bars.size() < 3) {
            return overlay;
        }
        int last = bars.size() - 1;
        int end = Math.max(0, last - 1);
        int from = Math.max(0, end - LOOKBACK + 1);
        double high = -Double.MAX_VALUE;
        double low = Double.MAX_VALUE;
        String highDate = null;
        String lowDate = null;
        for (int i = from; i <= end; i++) {
            KlineBar b = bars.get(i);
            if (b.high() >= high) {
                high = b.high();
                highDate = b.date();
            }
            if (b.low() <= low) {
                low = b.low();
                lowDate = b.date();
            }
        }
        if (highDate != null) {
            overlay.setPrevHigh(IndicatorEngine.round(high, 3));
            overlay.setPrevHighDate(highDate);
        }
        if (lowDate != null) {
            overlay.setPrevLow(IndicatorEngine.round(low, 3));
            overlay.setPrevLowDate(lowDate);
        }
        return overlay;
    }

    public static List<ScenarioCard> evaluate(List<KlineBar> bars, double[] close, double[] vol,
                                              double[] ma20, double[] volMa20) {
        List<ScenarioCard> list = new ArrayList<>();
        list.add(pullbackMa20(bars, close, ma20));
        list.add(volumeBreak(bars, close, vol, ma20, volMa20));
        return list;
    }

    private static ScenarioCard pullbackMa20(List<KlineBar> bars, double[] close, double[] ma20) {
        ScenarioCard card = base("MA20_PULLBACK", "回踩 MA20",
                "上涨或横在均线上方之后，价格回到 MA20 附近再观察，而不是等金叉。震荡市金叉死叉会反复出现，回踩看的是「还在均线上方、且碰到了生命线」。");
        int i = close.length - 1;
        if (i < 20 || !valid(ma20[i])) {
            card.setStatus("NONE");
            card.setStatusLabel("均线未满");
            card.setExplain("MA20 还没算满，先多看几天。");
            return card;
        }
        int above = 0;
        int n = Math.min(10, i);
        for (int p = i - n + 1; p <= i; p++) {
            if (valid(ma20[p]) && close[p] > ma20[p]) {
                above++;
            }
        }
        double dist = (close[i] - ma20[i]) / ma20[i] * 100;
        double lowDist = (bars.get(i).low() - ma20[i]) / ma20[i] * 100;
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("收盘", r(close[i]));
        snap.put("MA20", r(ma20[i]));
        snap.put("距MA20", r(dist) + "%");
        snap.put("近10日站上", above + "/" + n);
        card.setSnapshot(snap);

        boolean trendUp = above >= 6;
        boolean touch = lowDist <= 0.8 && lowDist >= -1.8;
        boolean held = dist >= -0.8;
        if (trendUp && touch && held) {
            card.setStatus("HIT");
            card.setStatusLabel("像回踩");
            card.setExplain("近 10 日多数收在 MA20 上方，今天最低价碰到或略穿均线（" + r(lowDist)
                    + "%），收盘仍挨着或站上生命线。这是中短线常用来对照「回踩」的样子，不是金叉，也不等于该买。");
        } else if (trendUp && dist > 0 && dist <= 3.5) {
            card.setStatus("NEAR");
            card.setStatusLabel("接近回踩");
            card.setExplain("价格还在 MA20 上方约 " + r(dist) + "%，还没碰到均线。回踩要等低点靠近生命线；若直接远离，就不是这一课。");
        } else if (dist < -1.2 && above <= 4) {
            card.setStatus("BROKEN");
            card.setStatusLabel("已跌破");
            card.setExplain("收盘在 MA20 下方 " + r(Math.abs(dist)) + "%，近 10 日也没有稳站均线。这更像跌破生命线，不要当成回踩。");
        } else if (trendUp && dist > 3.5) {
            card.setStatus("NONE");
            card.setStatusLabel("尚未回踩");
            card.setExplain("仍在 MA20 上方较远处（" + r(dist) + "%）。回踩还没发生；金叉即使出现，也和「回踩生命线」不是同一件事。");
        } else {
            card.setStatus("NONE");
            card.setStatusLabel("未出现");
            card.setExplain("均线附近没有形成「先站上、再回到均线」的样子。缠绕市里不要把每一次靠近 MA20 都看成回踩。");
        }
        return card;
    }

    private static ScenarioCard volumeBreak(List<KlineBar> bars, double[] close, double[] vol,
                                            double[] ma20, double[] volMa20) {
        ScenarioCard card = base("VOL_BREAK", "放量突破",
                "收盘创近 20 日新高，同时成交量明显放大，并且还在 MA20 上方。用来体会量价是否配合，不记卖点，也不和金叉混在一起。");
        int i = close.length - 1;
        if (i < 20 || !valid(volMa20[i]) || volMa20[i] == 0) {
            card.setStatus("NONE");
            card.setStatusLabel("均量未满");
            card.setExplain("20 日均量还没算满。");
            return card;
        }
        double prevCloseHigh = maxClose(close, i - 20, i - 1);
        double ratio = vol[i] / volMa20[i];
        boolean newHigh = close[i] > prevCloseHigh;
        boolean hot = ratio > 1.5;
        boolean above = valid(ma20[i]) && close[i] > ma20[i];
        double gap = prevCloseHigh == 0 ? 0 : (close[i] - prevCloseHigh) / prevCloseHigh * 100;
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("量比", r(ratio));
        snap.put("近20日收盘高", r(prevCloseHigh));
        snap.put("距前高", r(gap) + "%");
        snap.put("MA20", r(ma20[i]));
        card.setSnapshot(snap);

        if (hot && newHigh && above) {
            card.setStatus("HIT");
            card.setStatusLabel("像放量突破");
            card.setExplain("量比约 " + r(ratio) + " 倍，收盘创了近 20 日新高，且在 MA20 上方。这是量价配合的练习样本；利好日、情绪日也会放量，不等于趋势成立。");
        } else if (above && (hot || newHigh) && gap >= -1.5) {
            card.setStatus("NEAR");
            card.setStatusLabel("接近突破");
            card.setExplain("站在 MA20 上方，距前高 " + r(gap) + "%，量比 " + r(ratio)
                    + " 倍。还没同时满足「放量 + 收盘新高」。缺哪一条就对照哪一条，不要用金叉代替。");
        } else {
            card.setStatus("NONE");
            card.setStatusLabel("未出现");
            card.setExplain("量比 " + r(ratio) + " 倍，收盘" + (newHigh ? "已创新高" : "未过前高")
                    + "，价格在 MA20 " + (above ? "上方" : "下方") + "。今天不像放量突破。");
        }
        return card;
    }

    private static ScenarioCard base(String id, String name, String summary) {
        ScenarioCard card = new ScenarioCard();
        card.setId(id);
        card.setName(name);
        card.setSummary(summary);
        return card;
    }

    private static double maxClose(double[] close, int from, int to) {
        double max = -Double.MAX_VALUE;
        for (int p = from; p <= to; p++) {
            max = Math.max(max, close[p]);
        }
        return max;
    }

    private static Double r(double v) {
        return valid(v) ? IndicatorEngine.round(v, 3) : null;
    }

    private static boolean valid(double v) {
        return !Double.isNaN(v);
    }
}
