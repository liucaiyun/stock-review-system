package com.yan.stockreview.dto;

import com.yan.stockreview.entity.TradeRecord;

/** 一笔成交对照最近策略信号的结果 */
public record TradeSignalRow(
        Long tradeId,
        String tradeDate,
        String code,
        String name,
        String direction,
        Double tradePrice,
        String verdict,
        String verdictLabel,
        Integer daysDiff,
        Double priceDiffPct,
        String signalDate,
        String strategy,
        String strategyName,
        String signalReason,
        Double signalPrice,
        String label,
        String explain
) {
    public static TradeSignalRow none(TradeRecord t, String explain) {
        String date = t.getTradeDate() == null ? null : t.getTradeDate().toString();
        return new TradeSignalRow(
                t.getId(),
                date,
                t.getCode(),
                t.getName(),
                t.getDirection(),
                t.getPrice(),
                "NONE",
                "附近无信号",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "附近无同向信号",
                explain
        );
    }
}
