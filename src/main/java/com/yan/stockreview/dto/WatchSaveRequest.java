package com.yan.stockreview.dto;

/** 手动录入 / 编辑自选与持仓 */
public class WatchSaveRequest {
    private Long id;
    private String code;
    private String name;
    private String notes;
    private Double shares;
    private Double costPrice;
    /** 成本金额，可手填；不填则按份额×成本价计算 */
    private Double costAmount;
    /** 个性化纪律线幅度（百分比，如 8 表示 -8%）。空 = 按类别用默认（个股 -8%，ETF -12%） */
    private Double stopPct;
    /** 资产类别：STOCK / ETF。空 = 按代码自动识别 */
    private String assetType;
    /** true = 明知超限仍保存 */
    private Boolean force;
    /** 账户总资金（含现金），录入拦截用来算仓位/风险占比；不传则用净值快照 */
    private Double capital;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Double getShares() { return shares; }
    public void setShares(Double shares) { this.shares = shares; }
    public Double getCostPrice() { return costPrice; }
    public void setCostPrice(Double costPrice) { this.costPrice = costPrice; }
    public Double getCostAmount() { return costAmount; }
    public void setCostAmount(Double costAmount) { this.costAmount = costAmount; }
    public Double getStopPct() { return stopPct; }
    public void setStopPct(Double stopPct) { this.stopPct = stopPct; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public Boolean getForce() { return force; }
    public void setForce(Boolean force) { this.force = force; }
    public Double getCapital() { return capital; }
    public void setCapital(Double capital) { this.capital = capital; }
}
