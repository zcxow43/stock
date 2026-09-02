package com.stock.domain;

/** One row of the `stock_industry` many-to-many table (specs/dba/stock-industry.md). */
public class StockIndustry {

    private String stockId;
    private Integer industryId;

    public StockIndustry() {
    }

    public StockIndustry(String stockId, Integer industryId) {
        this.stockId = stockId;
        this.industryId = industryId;
    }

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
}
