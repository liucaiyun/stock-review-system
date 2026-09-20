package com.yan.stockreview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 账户净值每日快照：总资金 + 持仓市值 + 现金，用于净值曲线与回撤 */
@Entity
@Table(name = "equity_snapshot")
public class EquitySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate snapDate;

    /** 账户总资金（含现金），手动录入 */
    @Column(nullable = false)
    private Double totalAsset;

    /** 当日持仓市值（按快照时现价） */
    private Double positionValue;

    /** 现金 = 总资金 - 持仓市值 */
    private Double cash;

    @Column(length = 200)
    private String note;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getSnapDate() { return snapDate; }
    public void setSnapDate(LocalDate snapDate) { this.snapDate = snapDate; }
    public Double getTotalAsset() { return totalAsset; }
    public void setTotalAsset(Double totalAsset) { this.totalAsset = totalAsset; }
    public Double getPositionValue() { return positionValue; }
    public void setPositionValue(Double positionValue) { this.positionValue = positionValue; }
    public Double getCash() { return cash; }
    public void setCash(Double cash) { this.cash = cash; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
