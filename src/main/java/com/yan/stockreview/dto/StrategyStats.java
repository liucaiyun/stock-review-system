package com.yan.stockreview.dto;

public record StrategyStats(
        String strategy,
        String strategyName,
        int buyCount,
        int sellCount,
        int evaluated,
        Double winRate5d,
        Double avgReturn5d,
        Double winRate10d,
        Double avgReturn10d,
        Double winRate20d,
        Double avgReturn20d
) {}
