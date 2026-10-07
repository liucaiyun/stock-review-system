package com.yan.stockreview.strategy;

/**
 * 实盘纪律线：默认用入场成本 − 2×ATR(14)；只有用户手填幅度时才用固定百分比。
 * ATR 拿不到时才退回个股 −8% / ETF −12%。
 */
public final class StopRules {

    public static final double ATR_MULT = 2.0;
    public static final int ATR_PERIOD = 14;
    public static final double DEFAULT_STOCK_PCT = 8.0;
    public static final double DEFAULT_ETF_PCT = 12.0;

    private StopRules() {}

    public record Result(double stopLine, double stopPct, String source) {}

    /**
     * @param cost      成本价
     * @param manualPct 用户手填的幅度（百分比，如 8）；null 表示未覆盖
     * @param etf       是否 ETF
     * @param atr       ATR(14)，可空
     */
    public static Result of(Double cost, Double manualPct, boolean etf, Double atr) {
        if (cost == null || cost <= 0) {
            return null;
        }
        if (manualPct != null && manualPct > 0) {
            double line = cost * (1 - manualPct / 100);
            return new Result(round3(Math.max(0.01, line)), round2(manualPct), "MANUAL");
        }
        if (atr != null && atr > 0) {
            double line = cost - ATR_MULT * atr;
            line = Math.max(cost * 0.01, line);
            double pct = (cost - line) / cost * 100;
            return new Result(round3(line), round2(pct), "ATR");
        }
        double pct = etf ? DEFAULT_ETF_PCT : DEFAULT_STOCK_PCT;
        double line = cost * (1 - pct / 100);
        return new Result(round3(line), pct, "DEFAULT_PCT");
    }

    public static String sourceLabel(String source) {
        return switch (source == null ? "" : source) {
            case "ATR" -> "2×ATR";
            case "MANUAL" -> "手填";
            case "DEFAULT_PCT" -> "默认%";
            default -> source;
        };
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
