package com.yan.stockreview.dto;

public record StockSuggest(
        String code,
        String name,
        String market,
        String secid,
        String typeName
) {}
