package com.yan.stockreview.dto;

import java.util.ArrayList;
import java.util.List;

/** 一只股票的板块归属：东财行业三级 + 证监会/申万路径 + 地域 + 概念 */
public class BoardProfile {
    private String code;
    private String name;
    private String exchange;
    /** 面包屑：食品饮料 / 白酒Ⅱ / 白酒Ⅲ */
    private String path;
    private List<String> emIndustry = new ArrayList<>();
    private List<String> em2016 = new ArrayList<>();
    private List<String> csrc = new ArrayList<>();
    private String region;
    private String province;
    private String mainBusiness;
    /** 业务相关概念，已去掉指数成分等噪音 */
    private List<String> concepts = new ArrayList<>();
    /** 指数、风格、成分股等次要标签 */
    private List<String> extraConcepts = new ArrayList<>();
    /** 东财行业板块代码，如 BK0475 */
    private String industryCode;
    private String industrySecid;
    private String industryName;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public List<String> getEmIndustry() { return emIndustry; }
    public void setEmIndustry(List<String> emIndustry) { this.emIndustry = emIndustry; }
    public List<String> getEm2016() { return em2016; }
    public void setEm2016(List<String> em2016) { this.em2016 = em2016; }
    public List<String> getCsrc() { return csrc; }
    public void setCsrc(List<String> csrc) { this.csrc = csrc; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }
    public String getMainBusiness() { return mainBusiness; }
    public void setMainBusiness(String mainBusiness) { this.mainBusiness = mainBusiness; }
    public List<String> getConcepts() { return concepts; }
    public void setConcepts(List<String> concepts) { this.concepts = concepts; }
    public List<String> getExtraConcepts() { return extraConcepts; }
    public void setExtraConcepts(List<String> extraConcepts) { this.extraConcepts = extraConcepts; }
    public String getIndustryCode() { return industryCode; }
    public void setIndustryCode(String industryCode) { this.industryCode = industryCode; }
    public String getIndustrySecid() { return industrySecid; }
    public void setIndustrySecid(String industrySecid) { this.industrySecid = industrySecid; }
    public String getIndustryName() { return industryName; }
    public void setIndustryName(String industryName) { this.industryName = industryName; }
}
