package com.stock.dto;

/**
 * One parsed, filtered candidate link from the industry source (source 2) of
 * {@code POST /api/stocks/universe/import}, pairing an ordinary-share stock id with its
 * (trimmed, non-empty) industry name, before it is resolved to an {@code industry_id} and written
 * to `stock_industry`. See specs/backend/stock-universe-import.md 產業別的寫入語意.
 */
public class IndustryLinkCandidate {

    private final String stockId;
    private final String industryName;

    public IndustryLinkCandidate(String stockId, String industryName) {
        this.stockId = stockId;
        this.industryName = industryName;
    }

    public String getStockId() {
        return stockId;
    }

    public String getIndustryName() {
        return industryName;
    }
}
