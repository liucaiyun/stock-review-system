package com.yan.stockreview.dto;

import java.util.Map;

/** 单策略学习卡片：规则 + 当前读数 + 今天为何有/没有信号 */
public class StrategyLesson {
    private String id;
    private String name;
    private String category;
    private String summary;
    private String idea;
    private String how;
    private String buyRule;
    private String sellRule;
    private String pitfall;
    private String suitable;
    private String status;
    private String explain;
    private Map<String, Object> snapshot;
    private SignalPoint lastSignal;
    private SignalPoint todaySignal;
    private StrategyStats stats;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getIdea() { return idea; }
    public void setIdea(String idea) { this.idea = idea; }
    public String getHow() { return how; }
    public void setHow(String how) { this.how = how; }
    public String getBuyRule() { return buyRule; }
    public void setBuyRule(String buyRule) { this.buyRule = buyRule; }
    public String getSellRule() { return sellRule; }
    public void setSellRule(String sellRule) { this.sellRule = sellRule; }
    public String getPitfall() { return pitfall; }
    public void setPitfall(String pitfall) { this.pitfall = pitfall; }
    public String getSuitable() { return suitable; }
    public void setSuitable(String suitable) { this.suitable = suitable; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getExplain() { return explain; }
    public void setExplain(String explain) { this.explain = explain; }
    public Map<String, Object> getSnapshot() { return snapshot; }
    public void setSnapshot(Map<String, Object> snapshot) { this.snapshot = snapshot; }
    public SignalPoint getLastSignal() { return lastSignal; }
    public void setLastSignal(SignalPoint lastSignal) { this.lastSignal = lastSignal; }
    public SignalPoint getTodaySignal() { return todaySignal; }
    public void setTodaySignal(SignalPoint todaySignal) { this.todaySignal = todaySignal; }
    public StrategyStats getStats() { return stats; }
    public void setStats(StrategyStats stats) { this.stats = stats; }
}
