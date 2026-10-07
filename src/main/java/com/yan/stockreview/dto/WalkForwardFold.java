package com.yan.stockreview.dto;

/**
 * 样本外验证的一折（walk-forward fold）。
 *
 * <p>做法是锚定式（anchored）：第 k 折只允许用 {@code trainStart ~ testStart} 这段时间的交易去挑参数，
 * 挑完之后在 {@code testStart ~ testEnd} 上看结果。测试段的数据在挑参数时完全没被看过。
 */
public record WalkForwardFold(
        int index,
        String trainStart,
        String testStart,
        String testEnd,
        /** 在这一折的训练段上挑出来的参数（如 2×ATR/20日） */
        String params,
        int trainTrades,
        Double trainExpectancyR,
        int testTrades,
        Double testExpectancyR,
        Double testWinRate,
        Double testTotalR,
        Double testMaxDrawdownR
) {}
