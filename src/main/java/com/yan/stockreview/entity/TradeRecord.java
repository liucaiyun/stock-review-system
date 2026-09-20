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

/** 成交记录（手动录入或同花顺 CSV 导入） */
@Entity
@Table(name = "trade_record")
public class TradeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(length = 64)
    private String name;

    /** buy / sell */
    @Column(nullable = false, length = 8)
    private String direction;

    private Double shares;
    private Double price;
    private Double amount;

    /** MANUAL / THS */
    @Column(length = 16)
    private String source;

    @Column(length = 500)
    private String note;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        if (source == null) {
            source = "MANUAL";
        }
        if (amount == null && shares != null && price != null) {
            amount = shares * price;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Double getShares() { return shares; }
    public void setShares(Double shares) { this.shares = shares; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
