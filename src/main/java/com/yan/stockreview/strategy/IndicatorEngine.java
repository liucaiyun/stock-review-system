package com.yan.stockreview.strategy;

import com.yan.stockreview.dto.KlineBar;
import java.util.ArrayList;
import java.util.List;

/** 常见技术指标计算（均线 / MACD / RSI / KDJ / 布林带） */
public final class IndicatorEngine {

    private IndicatorEngine() {}

    public static double[] sma(double[] src, int period) {
        double[] out = new double[src.length];
        double sum = 0;
        for (int i = 0; i < src.length; i++) {
            sum += src[i];
            if (i >= period) {
                sum -= src[i - period];
            }
            out[i] = i >= period - 1 ? sum / period : Double.NaN;
        }
        return out;
    }

    public static double[] ema(double[] src, int period) {
        double[] out = new double[src.length];
        double k = 2.0 / (period + 1);
        double prev = src[0];
        out[0] = prev;
        for (int i = 1; i < src.length; i++) {
            prev = src[i] * k + prev * (1 - k);
            out[i] = prev;
        }
        return out;
    }

    public static double[] rsi(double[] close, int period) {
        double[] out = new double[close.length];
        if (close.length == 0) {
            return out;
        }
        out[0] = Double.NaN;
        double avgGain = 0;
        double avgLoss = 0;
        for (int i = 1; i < close.length; i++) {
            double change = close[i] - close[i - 1];
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            if (i <= period) {
                avgGain += gain;
                avgLoss += loss;
                if (i == period) {
                    avgGain /= period;
                    avgLoss /= period;
                    out[i] = avgLoss == 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
                } else {
                    out[i] = Double.NaN;
                }
            } else {
                avgGain = (avgGain * (period - 1) + gain) / period;
                avgLoss = (avgLoss * (period - 1) + loss) / period;
                out[i] = avgLoss == 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
            }
        }
        return out;
    }

    public static MacdSeries macd(double[] close) {
        double[] ema12 = ema(close, 12);
        double[] ema26 = ema(close, 26);
        double[] dif = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            dif[i] = ema12[i] - ema26[i];
        }
        double[] dea = ema(dif, 9);
        double[] hist = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            hist[i] = 2 * (dif[i] - dea[i]);
        }
        return new MacdSeries(dif, dea, hist);
    }

    public static KdjSeries kdj(List<KlineBar> bars, int n) {
        int len = bars.size();
        double[] k = new double[len];
        double[] d = new double[len];
        double[] j = new double[len];
        double prevK = 50;
        double prevD = 50;
        for (int i = 0; i < len; i++) {
            int from = Math.max(0, i - n + 1);
            double high = -Double.MAX_VALUE;
            double low = Double.MAX_VALUE;
            for (int p = from; p <= i; p++) {
                high = Math.max(high, bars.get(p).high());
                low = Math.min(low, bars.get(p).low());
            }
            double rsv = high == low ? 50 : (bars.get(i).close() - low) / (high - low) * 100;
            prevK = 2.0 / 3 * prevK + 1.0 / 3 * rsv;
            prevD = 2.0 / 3 * prevD + 1.0 / 3 * prevK;
            k[i] = prevK;
            d[i] = prevD;
            j[i] = 3 * prevK - 2 * prevD;
        }
        return new KdjSeries(k, d, j);
    }

    public static BollSeries boll(double[] close, int period, double k) {
        double[] mid = sma(close, period);
        double[] up = new double[close.length];
        double[] dn = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            if (i < period - 1 || Double.isNaN(mid[i])) {
                up[i] = Double.NaN;
                dn[i] = Double.NaN;
                continue;
            }
            double mean = mid[i];
            double var = 0;
            for (int p = i - period + 1; p <= i; p++) {
                double diff = close[p] - mean;
                var += diff * diff;
            }
            double std = Math.sqrt(var / period);
            up[i] = mean + k * std;
            dn[i] = mean - k * std;
        }
        return new BollSeries(up, mid, dn);
    }

    public static double[] closes(List<KlineBar> bars) {
        double[] c = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            c[i] = bars.get(i).close();
        }
        return c;
    }

    public static double[] volumes(List<KlineBar> bars) {
        double[] v = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            v[i] = bars.get(i).volume();
        }
        return v;
    }

    public static Double[] boxed(double[] src) {
        Double[] out = new Double[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = Double.isNaN(src[i]) ? null : round(src[i], 4);
        }
        return out;
    }

    public static double round(double v, int scale) {
        double p = Math.pow(10, scale);
        return Math.round(v * p) / p;
    }

    public record MacdSeries(double[] dif, double[] dea, double[] hist) {}
    public record KdjSeries(double[] k, double[] d, double[] j) {}
    public record BollSeries(double[] up, double[] mid, double[] dn) {}
}
