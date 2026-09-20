package com.yan.stockreview.dto;

public record SignalPoint(
        String date,
        String strategy,
        String strategyName,
        String action,
        String reason,
        double price
) {}
