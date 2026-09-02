package com.stock.domain;

/**
 * One row of a {@code stock_industry} JOIN {@code industry} lookup: a matched stock paired with
 * one of the industries it belongs to. Used by GET /api/momentum/gain
 * (specs/backend/industry-gain-ranking.md) to group hits into industry blocks — a stock with
 * multiple industries yields multiple rows here, one per industry.
 */
public class StockIndustryLink {

    private String stockId;
    private Integer industryId;
    private String industryName;

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
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
