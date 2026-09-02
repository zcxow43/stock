package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raw row shape returned by TWSE openapi {@code t187ap03_L} (上市公司產業別資料), before
 * normalization. Only the two fields the universe import uses are mapped here; the real payload
 * carries ~30 more columns of company-registration detail (address, chairman, auditor, ...) that
 * this project has no use for.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TwseCompanyProfileRow {

    @JsonProperty("公司代號")
    private String companyId;

    @JsonProperty("產業別")
    private String industryCode;

    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    public String getIndustryCode() {
        return industryCode;
    }

    public void setIndustryCode(String industryCode) {
        this.industryCode = industryCode;
    }
}
