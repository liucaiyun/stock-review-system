package com.yan.stockreview.dto;

public record KlineBar(
        String date,
        double open,
        double close,
        double high,
        double low,
        double volume,
        double amount,
        double pctChange,
        double turnover
) {}
