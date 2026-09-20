package com.yan.stockreview.dto;

import java.util.ArrayList;
import java.util.List;

/** 板块层级树节点：行业节点或股票叶子 */
public class BoardTreeNode {
    private String name;
    /** group / stock */
    private String type;
    private int level;
    private String code;
    private Double price;
    private Double pctChange;
    private Double shares;
    private String path;
    private Double boardPct1d;
    private Double boardPct5d;
    private Double rs1d;
    private String rsLabel;
    private int stockCount;
    private List<BoardTreeNode> children = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public Double getPctChange() { return pctChange; }
    public void setPctChange(Double pctChange) { this.pctChange = pctChange; }
    public Double getShares() { return shares; }
    public void setShares(Double shares) { this.shares = shares; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public Double getBoardPct1d() { return boardPct1d; }
    public void setBoardPct1d(Double boardPct1d) { this.boardPct1d = boardPct1d; }
    public Double getBoardPct5d() { return boardPct5d; }
    public void setBoardPct5d(Double boardPct5d) { this.boardPct5d = boardPct5d; }
    public Double getRs1d() { return rs1d; }
    public void setRs1d(Double rs1d) { this.rs1d = rs1d; }
    public String getRsLabel() { return rsLabel; }
    public void setRsLabel(String rsLabel) { this.rsLabel = rsLabel; }
    public int getStockCount() { return stockCount; }
    public void setStockCount(int stockCount) { this.stockCount = stockCount; }
    public List<BoardTreeNode> getChildren() { return children; }
    public void setChildren(List<BoardTreeNode> children) { this.children = children; }
}
