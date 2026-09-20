package com.yan.stockreview.dto;

/** K 线对照线：持仓成本、计划止损/目标、近 20 日前高前低 */
public class ChartOverlay {
    private Double costPrice;
    private Double planPrice;
    private Double stopPrice;
    private Double targetPrice;
    private Double prevHigh;
    private String prevHighDate;
    private Double prevLow;
    private String prevLowDate;
    private Double ma20;
    private int lookback = 20;

    public Double getCostPrice() { return costPrice; }
    public void setCostPrice(Double costPrice) { this.costPrice = costPrice; }
    public Double getPlanPrice() { return planPrice; }
    public void setPlanPrice(Double planPrice) { this.planPrice = planPrice; }
    public Double getStopPrice() { return stopPrice; }
    public void setStopPrice(Double stopPrice) { this.stopPrice = stopPrice; }
    public Double getTargetPrice() { return targetPrice; }
    public void setTargetPrice(Double targetPrice) { this.targetPrice = targetPrice; }
    public Double getPrevHigh() { return prevHigh; }
    public void setPrevHigh(Double prevHigh) { this.prevHigh = prevHigh; }
    public String getPrevHighDate() { return prevHighDate; }
    public void setPrevHighDate(String prevHighDate) { this.prevHighDate = prevHighDate; }
    public Double getPrevLow() { return prevLow; }
    public void setPrevLow(Double prevLow) { this.prevLow = prevLow; }
    public String getPrevLowDate() { return prevLowDate; }
    public void setPrevLowDate(String prevLowDate) { this.prevLowDate = prevLowDate; }
    public Double getMa20() { return ma20; }
    public void setMa20(Double ma20) { this.ma20 = ma20; }
    public int getLookback() { return lookback; }
    public void setLookback(int lookback) { this.lookback = lookback; }
}
