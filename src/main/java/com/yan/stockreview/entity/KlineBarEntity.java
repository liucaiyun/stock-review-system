package com.yan.stockreview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 日 K 落盘：walk-forward / ATR 纪律线不能只靠内存缓存。 */
@Entity
@Table(name = "kline_bar", uniqueConstraints = @UniqueConstraint(columnNames = {"secid", "bar_date"}))
public class KlineBarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String secid;

    @Column(name = "bar_date", nullable = false, length = 16)
    private String barDate;

    private Double openPx;
    private Double highPx;
    private Double lowPx;
    private Double closePx;
    private Double volume;
    private Double amount;
    private Double pctChange;
    private Double turnover;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSecid() { return secid; }
    public void setSecid(String secid) { this.secid = secid; }
    public String getBarDate() { return barDate; }
    public void setBarDate(String barDate) { this.barDate = barDate; }
    public Double getOpenPx() { return openPx; }
    public void setOpenPx(Double openPx) { this.openPx = openPx; }
    public Double getHighPx() { return highPx; }
    public void setHighPx(Double highPx) { this.highPx = highPx; }
    public Double getLowPx() { return lowPx; }
    public void setLowPx(Double lowPx) { this.lowPx = lowPx; }
    public Double getClosePx() { return closePx; }
    public void setClosePx(Double closePx) { this.closePx = closePx; }
    public Double getVolume() { return volume; }
    public void setVolume(Double volume) { this.volume = volume; }
    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }
    public Double getPctChange() { return pctChange; }
    public void setPctChange(Double pctChange) { this.pctChange = pctChange; }
    public Double getTurnover() { return turnover; }
    public void setTurnover(Double turnover) { this.turnover = turnover; }
}
