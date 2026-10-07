package com.yan.stockreview.dto;

/**
 * 一笔模拟交易（不是信号，是一笔真实的买卖）。
 *
 * <p>口径：
 * <ul>
 *   <li>{@code entryPrice}/{@code exitPrice} 已含滑点；{@code r} 与 {@code netPct} 是<b>扣掉佣金/过户费/印花税之后</b>的净结果；</li>
 *   <li>{@code r} = 每股净盈亏 ÷ 初始风险（每股风险 = 入场价 − 初始止损价 = 2×ATR(14)）；
 *       {@code r = -1} 表示正好在初始止损被打掉，{@code r = +2} 表示赚到 2 倍初始风险；</li>
 *   <li>{@code maeR} = 持有期间最大不利偏移（负数，0 表示从没跌破过成本）；{@code mfeR} 同理取最大有利偏移；</li>
 *   <li>{@code exitReason}：止损 / 移动止损 / 卖点 / 时间 / 末端（数据末尾强制平仓）；</li>
 *   <li>{@code benchPct} = 同一持有区间沪深300 的涨跌幅（%），用来判断这笔赚的是自己的本事还是大盘的。</li>
 * </ul>
 */
public record SimTrade(
        String strategy,
        String entryDate,
        double entryPrice,
        String exitDate,
        double exitPrice,
        String exitReason,
        double r,
        int holdDays,
        double maeR,
        double mfeR,
        double netPct,
        Double benchPct
) {}
