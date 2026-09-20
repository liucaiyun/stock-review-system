package com.yan.stockreview.dto;

import java.util.List;

/** 成交对照策略信号的汇总 */
public record TradeSignalCompare(
        String summary,
        int total,
        int hit,
        int chase,
        int late,
        int early,
        int against,
        int none,
        Double avgDaysAfter,
        List<TradeSignalRow> items
) {}
