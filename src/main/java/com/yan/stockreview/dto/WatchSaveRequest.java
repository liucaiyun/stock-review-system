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
}
