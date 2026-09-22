package com.yan.stockreview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 自选股 */
@Entity
@Table(name = "watch_stock")
public class WatchStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(length = 64)
    private String name;

    @Column(length = 8)
    private String market;

    @Column(nullable = false, length = 32)
    private String secid;

    @Column(length = 500)
    private String notes;

    /** 持仓份额（手动录入） */
    private Double shares;
    /** 成本价（手动录入） */
    private Double costPrice;
    /** 成本金额 = 份额 × 成本价 */
    private Double costAmount;
    /** 个性化纪律线幅度（百分比，如 8 表示 -8%）。null = 用默认 -8% */
    private Double stopPct;

    private Integer sortOrder;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public String getSecid() { return secid; }
    public void setSecid(String secid) { this.secid = secid; }
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
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
