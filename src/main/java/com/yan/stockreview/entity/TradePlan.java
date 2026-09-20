package com.yan.stockreview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 中短线交易计划：入场理由、止损、目标、计划持有天数 */
@Entity
@Table(name = "trade_plan")
public class TradePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(length = 64)
    private String name;

    @Column(nullable = false)
    private LocalDate planDate;

    @Column(length = 500)
    private String reason;

    private Double planPrice;
    private Double stopPrice;
    private Double targetPrice;
    private Integer holdDays;

    /** OPEN / CLOSED / CANCELLED */
    @Column(nullable = false, length = 16)
    private String status;

    private Long buyTradeId;
    private LocalDate closedOn;

    @Column(length = 500)
    private String closeNote;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (planDate == null) {
            planDate = LocalDate.now();
        }
        if (status == null) {
            status = "OPEN";
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
    public LocalDate getPlanDate() { return planDate; }
    public void setPlanDate(LocalDate planDate) { this.planDate = planDate; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Double getPlanPrice() { return planPrice; }
    public void setPlanPrice(Double planPrice) { this.planPrice = planPrice; }
    public Double getStopPrice() { return stopPrice; }
    public void setStopPrice(Double stopPrice) { this.stopPrice = stopPrice; }
    public Double getTargetPrice() { return targetPrice; }
    public void setTargetPrice(Double targetPrice) { this.targetPrice = targetPrice; }
    public Integer getHoldDays() { return holdDays; }
    public void setHoldDays(Integer holdDays) { this.holdDays = holdDays; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getBuyTradeId() { return buyTradeId; }
    public void setBuyTradeId(Long buyTradeId) { this.buyTradeId = buyTradeId; }
    public LocalDate getClosedOn() { return closedOn; }
    public void setClosedOn(LocalDate closedOn) { this.closedOn = closedOn; }
    public String getCloseNote() { return closeNote; }
    public void setCloseNote(String closeNote) { this.closeNote = closeNote; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
