package com.yan.stockreview.dto;


/** 持仓一行：手工录入的份额/成本 + 现价盈亏 */
public class PositionRow {
    private Long id;
    private String code;
    private String name;
    private String market;
    private String notes;
    private Double shares;
    private Double costPrice;
    private Double costAmount;
    private Double price;
    private Double pctChange;
    private Double marketValue;
    private Double floatPl;
    private Double floatPlPct;
    private Double weight;
    /** 纪律止损线 = 成本价 × (1 - stopPct/100)，默认按类别：个股 -8% / ETF -12% */
    private Double stopLine;
    /** 本只生效的纪律线幅度（%） */
    private Double stopPct;
    /** 资产类别：STOCK / ETF */
    private String assetType;
    private String assetTypeLabel;
    /** 现价是否已跌破纪律止损线 */
    private Boolean stopBroken;
    /** 现价相对纪律止损线的距离%，正数=仍在线上方 */
    private Double stopDistancePct;
    private String boardPath;
    private String region;
    private RelativeStrength strength;
    private String planFlag;
    private String planFlagLabel;
    private Double planStop;
    private Double planTarget;
    private Integer planHoldDays;
    private Integer planHeldDays;
    /** ATR / MANUAL / DEFAULT_PCT */
    private String stopSource;
    private String stopSourceLabel;
    /** 用户手填的幅度；空表示未覆盖（表单编辑时用，不要把 ATR 算出来的有效幅度写回去） */
    private Double manualStopPct;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Double getShares() { return shares; }
    public void setShares(Double shares) { this.shares = shares; }
    public Double getCostPrice() { return costPrice; }
    public void setCostPrice(Double costPrice) { this.costPrice = costPrice; }
    public Double getCostAmount() { return costAmount; }
    public void setCostAmount(Double costAmount) { this.costAmount = costAmount; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public Double getPctChange() { return pctChange; }
    public void setPctChange(Double pctChange) { this.pctChange = pctChange; }
    public Double getMarketValue() { return marketValue; }
    public void setMarketValue(Double marketValue) { this.marketValue = marketValue; }
    public Double getFloatPl() { return floatPl; }
    public void setFloatPl(Double floatPl) { this.floatPl = floatPl; }
    public Double getFloatPlPct() { return floatPlPct; }
    public void setFloatPlPct(Double floatPlPct) { this.floatPlPct = floatPlPct; }
    public Double getWeight() { return weight; }
    public void setWeight(Double weight) { this.weight = weight; }
    public Double getStopLine() { return stopLine; }
    public void setStopLine(Double stopLine) { this.stopLine = stopLine; }
    public Double getStopPct() { return stopPct; }
    public void setStopPct(Double stopPct) { this.stopPct = stopPct; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public String getAssetTypeLabel() { return assetTypeLabel; }
    public void setAssetTypeLabel(String assetTypeLabel) { this.assetTypeLabel = assetTypeLabel; }
    public Boolean getStopBroken() { return stopBroken; }
    public void setStopBroken(Boolean stopBroken) { this.stopBroken = stopBroken; }
    public Double getStopDistancePct() { return stopDistancePct; }
    public void setStopDistancePct(Double stopDistancePct) { this.stopDistancePct = stopDistancePct; }
    public String getBoardPath() { return boardPath; }
    public void setBoardPath(String boardPath) { this.boardPath = boardPath; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public RelativeStrength getStrength() { return strength; }
    public void setStrength(RelativeStrength strength) { this.strength = strength; }
    public String getPlanFlag() { return planFlag; }
    public void setPlanFlag(String planFlag) { this.planFlag = planFlag; }
    public String getPlanFlagLabel() { return planFlagLabel; }
    public void setPlanFlagLabel(String planFlagLabel) { this.planFlagLabel = planFlagLabel; }
    public Double getPlanStop() { return planStop; }
    public void setPlanStop(Double planStop) { this.planStop = planStop; }
    public Double getPlanTarget() { return planTarget; }
    public void setPlanTarget(Double planTarget) { this.planTarget = planTarget; }
    public Integer getPlanHoldDays() { return planHoldDays; }
    public void setPlanHoldDays(Integer planHoldDays) { this.planHoldDays = planHoldDays; }
    public Integer getPlanHeldDays() { return planHeldDays; }
    public void setPlanHeldDays(Integer planHeldDays) { this.planHeldDays = planHeldDays; }
    public String getStopSource() { return stopSource; }
    public void setStopSource(String stopSource) { this.stopSource = stopSource; }
    public String getStopSourceLabel() { return stopSourceLabel; }
    public void setStopSourceLabel(String stopSourceLabel) { this.stopSourceLabel = stopSourceLabel; }
    public Double getManualStopPct() { return manualStopPct; }
    public void setManualStopPct(Double manualStopPct) { this.manualStopPct = manualStopPct; }
}
