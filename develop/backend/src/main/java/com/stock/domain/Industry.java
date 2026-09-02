package com.stock.domain;

/** One row of the `industry` dictionary table (specs/dba/industry.md). */
public class Industry {

    private Integer industryId;
    private String industryName;

    public Industry() {
    }

    public Industry(String industryName) {
        this.industryName = industryName;
    }

    public Integer getIndustryId() {
        return industryId;
    }

    public void setIndustryId(Integer industryId) {
        this.industryId = industryId;
    }

    public String getIndustryName() {
        return industryName;
    }

    public void setIndustryName(String industryName) {
        this.industryName = industryName;
    }
}
