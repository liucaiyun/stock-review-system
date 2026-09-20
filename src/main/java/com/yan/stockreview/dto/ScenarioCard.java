package com.yan.stockreview.dto;

import java.util.Map;

/**
 * 中短线情景卡片：观察现在像不像某种走法，不记每日买卖点。
 * status: HIT / NEAR / BROKEN / NONE
 */
public class ScenarioCard {
    private String id;
    private String name;
    private String status;
    private String statusLabel;
    private String summary;
    private String explain;
    private Map<String, Object> snapshot;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusLabel() { return statusLabel; }
    public void setStatusLabel(String statusLabel) { this.statusLabel = statusLabel; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getExplain() { return explain; }
    public void setExplain(String explain) { this.explain = explain; }
    public Map<String, Object> getSnapshot() { return snapshot; }
    public void setSnapshot(Map<String, Object> snapshot) { this.snapshot = snapshot; }
}
