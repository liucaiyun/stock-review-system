package com.yan.stockreview.strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐笔交易模型的参数。
 *
 * <p>之所以要把它抽出来：原来这些数字（2×ATR、1R 保本、2R 跟踪、20 日）是写死在代码里的，
 * 于是「回测结果好」这件事根本没法检验——你没法知道它是真的有效，还是刚好被我拍的这组数字拟合上了。
 * 抽成参数之后才能做两件事：<b>样本外验证</b>（前一段挑参数、后一段只看结果）和<b>参数敏感性</b>
 * （把参数挪一点，结论还在不在）。
 *
 * @param stopAtr      初始止损距离 = 该倍数 × ATR(14)，同时也是 1R 的定义
 * @param breakevenAtR 盈利达到几个 R 时把止损抬到成本
 * @param trailAtR     盈利达到几个 R 时改用 ATR 跟踪止损
 * @param maxHoldDays  最长持有交易日数（时间止损）
 */
public record TradeParams(double stopAtr, double breakevenAtR, double trailAtR, int maxHoldDays) {

    /** 默认参数：和之前写死的值一致，方便新旧结果对照 */
    public static final TradeParams DEFAULT = new TradeParams(2.0, 1.0, 2.0, 20);

    /** 用于表格展示的短标签 */
    public String label() {
        return trim(stopAtr) + "×ATR/" + maxHoldDays + "日";
    }

    /**
     * 候选参数网格：止损倍数 × 最长持有天数。
     * 保本与跟踪固定在 1R / 2R（否则组合数爆炸，而且这两个维度在敏感性检验里单独扫）。
     */
    public static List<TradeParams> grid() {
        double[] stops = {1.5, 2.0, 2.5, 3.0};
        int[] holds = {10, 20, 40};
        List<TradeParams> out = new ArrayList<>();
        for (double s : stops) {
            for (int h : holds) {
                out.add(new TradeParams(s, DEFAULT.breakevenAtR(), DEFAULT.trailAtR(), h));
            }
        }
        return out;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }
}
