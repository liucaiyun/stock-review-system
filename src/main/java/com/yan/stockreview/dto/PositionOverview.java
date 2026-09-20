package com.yan.stockreview.dto;

import java.util.ArrayList;
import java.util.List;

/** 持仓总览 */
public class PositionOverview {
    private List<PositionRow> items = new ArrayList<>();
    private int count;
    private Double totalCost;
    private Double totalMarket;
    private Double totalPl;
    private Double totalPlPct;
    private int quotedCount;
    private String quotedAt;
    private String quoteError;
    /** 已跌破 -8% 纪律止损线的持仓数量 */
    private int stopBrokenCount;
    /** 最大单一持仓 */
    private String maxSingleName;
    private Double maxSingleWeight;
    /** 占比最高的行业板块（按市值） */
    private String topSector;
    private Double topSectorWeight;
    /** 行业分布：[{name, weight}]，按占比降序 */
    private List<java.util.Map<String, Object>> sectorWeights = new ArrayList<>();

    public List<PositionRow> getItems() { return items; }
    public void setItems(List<PositionRow> items) { this.items = items; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
    public Double getTotalCost() { return totalCost; }
    public void setTotalCost(Double totalCost) { this.totalCost = totalCost; }
    public Double getTotalMarket() { return totalMarket; }
    public void setTotalMarket(Double totalMarket) { this.totalMarket = totalMarket; }
    public Double getTotalPl() { return totalPl; }
    public void setTotalPl(Double totalPl) { this.totalPl = totalPl; }
    public Double getTotalPlPct() { return totalPlPct; }
    public void setTotalPlPct(Double totalPlPct) { this.totalPlPct = totalPlPct; }
    public int getQuotedCount() { return quotedCount; }
    public void setQuotedCount(int quotedCount) { this.quotedCount = quotedCount; }
    public String getQuotedAt() { return quotedAt; }
    public void setQuotedAt(String quotedAt) { this.quotedAt = quotedAt; }
    public String getQuoteError() { return quoteError; }
    public void setQuoteError(String quoteError) { this.quoteError = quoteError; }
    public int getStopBrokenCount() { return stopBrokenCount; }
    public void setStopBrokenCount(int stopBrokenCount) { this.stopBrokenCount = stopBrokenCount; }
    public String getMaxSingleName() { return maxSingleName; }
    public void setMaxSingleName(String maxSingleName) { this.maxSingleName = maxSingleName; }
    public Double getMaxSingleWeight() { return maxSingleWeight; }
    public void setMaxSingleWeight(Double maxSingleWeight) { this.maxSingleWeight = maxSingleWeight; }
    public String getTopSector() { return topSector; }
    public void setTopSector(String topSector) { this.topSector = topSector; }
    public Double getTopSectorWeight() { return topSectorWeight; }
    public void setTopSectorWeight(Double topSectorWeight) { this.topSectorWeight = topSectorWeight; }
    public List<java.util.Map<String, Object>> getSectorWeights() { return sectorWeights; }
    public void setSectorWeights(List<java.util.Map<String, Object>> sectorWeights) { this.sectorWeights = sectorWeights; }
}
