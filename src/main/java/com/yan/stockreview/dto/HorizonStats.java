package com.yan.stockreview.dto;

/**
 * 一个期限上的回测结果。所有收益都是「百分数」（如 0.51 表示 0.51%）。
 *
 * <p>口径说明：
 * <ul>
 *   <li>{@code netAvg}：按「信号次日开盘价」入场，扣双边佣金/过户费/印花税/滑点后的平均收益；</li>
 *   <li>{@code grossAvg}：旧口径对照值（信号当日收盘价入场、不扣任何费用），保留它是为了让你看清
 *       「不扣费 + 按收盘价成交」把结果高估了多少；</li>
 *   <li>{@code benchAvg}：同期沪深300 的平均收益，{@code excessAvg = netAvg - benchAvg}；</li>
 *   <li>卖出信号（buySide=false）不做空（A 股现货不能做空）：{@code winRate} 表示「卖出后 N 日下跌」
 *       的比例，{@code netAvg} 表示平均跌幅（正数=卖对了），此时 gross/bench/excess 均为 null。</li>
 * </ul>
 */
public record HorizonStats(
        int samples,
        boolean insufficient,
        Double winRate,
        Double netAvg,
        Double grossAvg,
        Double benchAvg,
        Double excessAvg
) {

    public static HorizonStats empty() {
        return new HorizonStats(0, true, null, null, null, null, null);
    }
}
