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

/** 按日复盘记录 */
@Entity
@Table(name = "daily_review")
public class DailyReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate reviewDate;

    /** bull / bear / neutral */
    @Column(length = 16)
    private String marketBias;

    /** 1-5 情绪评分 */
    private Integer sentiment;

    @Column(length = 4000)
    private String content;

    @Column(length = 2000)
    private String indexSnapshot;

    /** 错题本标签，逗号分隔：追高/不止损/分歧日买入/无计划交易/重仓单票/频繁交易 */
    @Column(length = 200)
    private String mistakeTags;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getReviewDate() { return reviewDate; }
    public void setReviewDate(LocalDate reviewDate) { this.reviewDate = reviewDate; }
    public String getMarketBias() { return marketBias; }
    public void setMarketBias(String marketBias) { this.marketBias = marketBias; }
    public Integer getSentiment() { return sentiment; }
    public void setSentiment(Integer sentiment) { this.sentiment = sentiment; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getIndexSnapshot() { return indexSnapshot; }
    public void setIndexSnapshot(String indexSnapshot) { this.indexSnapshot = indexSnapshot; }
    public String getMistakeTags() { return mistakeTags; }
    public void setMistakeTags(String mistakeTags) { this.mistakeTags = mistakeTags; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
