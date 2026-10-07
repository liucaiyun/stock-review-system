package com.yan.stockreview.strategy;

import com.yan.stockreview.dto.KlineBar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把日 K 收成「周期涨跌 + 趋势/波动/量能环境」。规则写死在这里，方便对照和单测。
 * 这是复盘对照，不是买卖建议：沪深300 在 MA20 上方对超跌反转曾经是负贡献。
 */
public final class MarketRegime {

    public static final int ATR_PERIOD = 14;
    public static final int VOL_LOOKBACK = 60;

    private MarketRegime() {}

    public record Snapshot(
            String date,
            Double close,
            Double ma20,
            Double ma60,
            Double distMa20Pct,
            Double pct5,
            Double pct20,
            Double pct60,
            String trend,
            String trendLabel,
            Double atrPct,
            Double atrPctMedian,
            String vol,
            String volLabel,
            Double volumeRatio,
            String volume,
            String volumeLabel,
            String summary
    ) {}

    public static Snapshot of(List<KlineBar> bars) {
        if (bars == null || bars.size() < 21) {
            return null;
        }
        int n = bars.size();
        int i = n - 1;
        double[] close = IndicatorEngine.closes(bars);
        double[] vol = IndicatorEngine.volumes(bars);
        double[] ma20 = IndicatorEngine.sma(close, 20);
        double[] ma60 = IndicatorEngine.sma(close, 60);
        double[] atr = IndicatorEngine.atr(bars, ATR_PERIOD);
        double[] volMa20 = IndicatorEngine.sma(vol, 20);

        Double ma20v = ok(ma20[i]) ? round2(ma20[i]) : null;
        Double ma60v = ok(ma60[i]) ? round2(ma60[i]) : null;
        double last = close[i];
        Double distMa20 = ma20v != null && ma20v > 0
                ? round2((last - ma20v) / ma20v * 100) : null;

        String trend;
        String trendLabel;
        if (ma20v != null && ma60v != null) {
            boolean above20 = last > ma20v;
            boolean ma20Above60 = ma20v > ma60v;
            if (above20 && ma20Above60) {
                trend = "BULL";
                trendLabel = "多头";
            } else if (!above20 && ma20Above60) {
                trend = "PULLBACK";
                trendLabel = "多头回撤";
            } else if (!above20 && !ma20Above60) {
                trend = "BEAR";
                trendLabel = "空头";
            } else {
                trend = "BOUNCE";
                trendLabel = "空头反弹";
            }
        } else if (ma20v != null) {
            trend = last > ma20v ? "ABOVE20" : "BELOW20";
            trendLabel = last > ma20v ? "站上MA20" : "跌破MA20";
        } else {
            trend = "NA";
            trendLabel = "均线不足";
        }

        Double atrPct = null;
        if (ok(atr[i]) && last > 0) {
            atrPct = round2(atr[i] / last * 100);
        }
        Double atrMed = atrPctMedian(atr, close, i);
        String volLevel;
        String volLabel;
        if (atrPct == null || atrMed == null || atrMed <= 0) {
            volLevel = "NA";
            volLabel = "波动未知";
        } else if (atrPct > atrMed * 1.25) {
            volLevel = "HIGH";
            volLabel = "波动放大";
        } else if (atrPct < atrMed * 0.8) {
            volLevel = "LOW";
            volLabel = "波动收缩";
        } else {
            volLevel = "MID";
            volLabel = "波动正常";
        }

        Double volRatio = null;
        if (ok(volMa20[i]) && volMa20[i] > 0) {
            volRatio = round2(vol[i] / volMa20[i]);
        }
        String volume;
        String volumeLabel;
        if (volRatio == null) {
            volume = "NA";
            volumeLabel = "量能未知";
        } else if (volRatio >= 1.5) {
            volume = "HEAVY";
            volumeLabel = "放量";
        } else if (volRatio <= 0.7) {
            volume = "LIGHT";
            volumeLabel = "缩量";
        } else {
            volume = "NORMAL";
            volumeLabel = "量能正常";
        }

        Double p5 = ret(close, 5);
        Double p20 = ret(close, 20);
        Double p60 = ret(close, 60);
        String summary = buildSummary(trendLabel, volLabel, volumeLabel, p5, p20, p60, distMa20);
        return new Snapshot(
                bars.get(i).date(),
                round2(last),
                ma20v,
                ma60v,
                distMa20,
                p5,
                p20,
                p60,
                trend,
                trendLabel,
                atrPct,
                atrMed,
                volLevel,
                volLabel,
                volRatio,
                volume,
                volumeLabel,
                summary);
    }

    /** 沪深300 近20日 vs 创业板近20日：成长占优 / 大盘占优 / 差不多。 */
    public static String styleLabel(Double csi20, Double chiNext20) {
        if (csi20 == null || chiNext20 == null) {
            return null;
        }
        double diff = chiNext20 - csi20;
        if (diff >= 2) {
            return "成长占优";
        }
        if (diff <= -2) {
            return "大盘占优";
        }
        return "风格接近";
    }

    static Double ret(double[] close, int days) {
        int i = close.length - 1;
        int j = i - days;
        if (j < 0 || close[j] <= 0) {
            return null;
        }
        return round2((close[i] / close[j] - 1) * 100);
    }

    private static Double atrPctMedian(double[] atr, double[] close, int last) {
        List<Double> vals = new ArrayList<>();
        int from = Math.max(ATR_PERIOD, last - VOL_LOOKBACK + 1);
        for (int i = from; i <= last; i++) {
            if (ok(atr[i]) && close[i] > 0) {
                vals.add(atr[i] / close[i] * 100);
            }
        }
        if (vals.size() < 10) {
            return null;
        }
        Collections.sort(vals);
        int mid = vals.size() / 2;
        double med = vals.size() % 2 == 0 ? (vals.get(mid - 1) + vals.get(mid)) / 2 : vals.get(mid);
        return round2(med);
    }

    private static String buildSummary(String trend, String vol, String volume,
                                       Double p5, Double p20, Double p60, Double distMa20) {
        StringBuilder sb = new StringBuilder();
        sb.append("环境：").append(trend).append("，").append(vol).append("，").append(volume).append("。");
        sb.append("周期：");
        sb.append("5日 ").append(fmtPct(p5)).append("，");
        sb.append("20日 ").append(fmtPct(p20)).append("，");
        sb.append("60日 ").append(fmtPct(p60)).append("。");
        if (distMa20 != null) {
            sb.append("现价距 MA20 ").append(fmtPct(distMa20)).append("。");
        }
        sb.append("只供复盘对照，不是买卖建议。");
        return sb.toString();
    }

    private static String fmtPct(Double v) {
        if (v == null) {
            return "—";
        }
        return (v > 0 ? "+" : "") + round2(v) + "%";
    }

    private static boolean ok(double v) {
        return !Double.isNaN(v);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
