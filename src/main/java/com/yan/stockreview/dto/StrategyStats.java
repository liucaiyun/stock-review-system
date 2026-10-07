package com.yan.stockreview.dto;

/**
 * 单个策略的回测结果。
 *
 * <p>{@code d5/d10/d20} 是买入信号在 5/10/20 日固定持有期上的统计（扣费 + 次日开盘成交 + 对比沪深300）；
 * {@code sellD5} 是卖出信号之后 5 日的跌幅，只用来衡量离场质量，<b>不是做空收益</b>；
 * {@code trade} 是<b>逐笔交易模拟</b>的结果（ATR 止损/保本/跟踪/时间/卖点出场，一次只持一笔），
 * 里面的 R 倍数与期望值才是「这套打法能不能赚钱」的答案。
 */
public record StrategyStats(
        String strategy,
        String strategyName,
        int buyCount,
        int sellCount,
        HorizonStats d5,
        HorizonStats d10,
        HorizonStats d20,
        HorizonStats sellD5,
        TradeStats trade
) {}
