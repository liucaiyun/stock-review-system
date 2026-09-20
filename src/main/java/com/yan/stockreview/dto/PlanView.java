package com.yan.stockreview.dto;

import java.time.LocalDate;

/** 交易计划 + 现价对照 */
public class PlanView {
    private Long id;
    private String code;
    private String name;
    private LocalDate planDate;
    private String reason;
    private Double planPrice;
    private Double stopPrice;
    private Double targetPrice;
    private Integer holdDays;
    private String status;
    private LocalDate closedOn;
    private String closeNote;
    private Double price;
    private Double pctChange;
    private Integer heldDays;
    private Double stopDistancePct;
    private Double targetDistancePct;
    private boolean hitStop;
    private boolean hitTarget;
    private boolean overdue;
    private String flag;
    private String flagLabel;
    private String boardPath;
    private RelativeStrength strength;

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
    public LocalDate getClosedOn() { return closedOn; }
    public void setClosedOn(LocalDate closedOn) { this.closedOn = closedOn; }
    public String getCloseNote() { return closeNote; }
    public void setCloseNote(String closeNote) { this.closeNote = closeNote; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public Double getPctChange() { return pctChange; }
    public void setPctChange(Double pctChange) { this.pctChange = pctChange; }
    public Integer getHeldDays() { return heldDays; }
    public void setHeldDays(Integer heldDays) { this.heldDays = heldDays; }
    public Double getStopDistancePct() { return stopDistancePct; }
    public void setStopDistancePct(Double stopDistancePct) { this.stopDistancePct = stopDistancePct; }
    public Double getTargetDistancePct() { return targetDistancePct; }
    public void setTargetDistancePct(Double targetDistancePct) { this.targetDistancePct = targetDistancePct; }
    public boolean isHitStop() { return hitStop; }
    public void setHitStop(boolean hitStop) { this.hitStop = hitStop; }
    public boolean isHitTarget() { return hitTarget; }
    public void setHitTarget(boolean hitTarget) { this.hitTarget = hitTarget; }
    public boolean isOverdue() { return overdue; }
    public void setOverdue(boolean overdue) { this.overdue = overdue; }
    public String getFlag() { return flag; }
    public void setFlag(String flag) { this.flag = flag; }
    public String getFlagLabel() { return flagLabel; }
    public void setFlagLabel(String flagLabel) { this.flagLabel = flagLabel; }
    public String getBoardPath() { return boardPath; }
    public void setBoardPath(String boardPath) { this.boardPath = boardPath; }
    public RelativeStrength getStrength() { return strength; }
    public void setStrength(RelativeStrength strength) { this.strength = strength; }
}
