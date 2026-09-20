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

/** 止损纪律事件：跌破 -8% 纪律线后记录，跟踪是否执行、拖了几天、多亏了多少 */
@Entity
@Table(name = "discipline_event")
public class DisciplineEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(length = 64)
    private String name;

    /** 首次破线日期 */
    @Column(nullable = false)
    private LocalDate triggerDate;

    /** 破线时现价 */
    private Double triggerPrice;

    /** 当时的纪律线（成本×0.92） */
    private Double stopLine;

    private Double costPrice;

    /** OPEN 处理中 / EXECUTED 已执行止损 / IGNORED 选择硬抗 / RECOVERED 未执行但涨回线上 / CLOSED_POS 清仓了结 */
    @Column(nullable = false, length = 16)
    private String status;

    private LocalDate resolvedDate;
    private Double resolvedPrice;

    /** 从破线到处理隔了几个自然日 */
    private Integer daysOpen;

    /** 处理价相对破线时价格又跌了多少（%），正数=多亏 */
    private Double extraLossPct;

    @Column(length = 500)
    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
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
    public LocalDate getTriggerDate() { return triggerDate; }
    public void setTriggerDate(LocalDate triggerDate) { this.triggerDate = triggerDate; }
    public Double getTriggerPrice() { return triggerPrice; }
    public void setTriggerPrice(Double triggerPrice) { this.triggerPrice = triggerPrice; }
    public Double getStopLine() { return stopLine; }
    public void setStopLine(Double stopLine) { this.stopLine = stopLine; }
    public Double getCostPrice() { return costPrice; }
    public void setCostPrice(Double costPrice) { this.costPrice = costPrice; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getResolvedDate() { return resolvedDate; }
    public void setResolvedDate(LocalDate resolvedDate) { this.resolvedDate = resolvedDate; }
    public Double getResolvedPrice() { return resolvedPrice; }
    public void setResolvedPrice(Double resolvedPrice) { this.resolvedPrice = resolvedPrice; }
    public Integer getDaysOpen() { return daysOpen; }
    public void setDaysOpen(Integer daysOpen) { this.daysOpen = daysOpen; }
    public Double getExtraLossPct() { return extraLossPct; }
    public void setExtraLossPct(Double extraLossPct) { this.extraLossPct = extraLossPct; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
