package com.yan.stockreview.dto;

public record QuoteSnapshot(
        String code,
        String name,
        String secid,
        String market,
        Double price,
        Double change,
        Double pctChange,
        Double volume,
        Double amount
) {}
