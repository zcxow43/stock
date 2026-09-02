package com.stock.dto;

import java.util.List;

/**
 * One industry block of GET /api/momentum/gain (specs/backend/industry-gain-ranking.md).
 * `industryId` / `industryName` are {@code null} / "未分類" for the trailing block of matched
 * stocks with no `stock_industry` link.
 */
public class IndustryGainGroupDto {

    private Integer industryId;
    private String industryName;
    private int matchedCount;
    private List<StockGainItemDto> items;

    public IndustryGainGroupDto() {
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

    public int getMatchedCount() {
        return matchedCount;
    }

    public void setMatchedCount(int matchedCount) {
        this.matchedCount = matchedCount;
    }

    public List<StockGainItemDto> getItems() {
        return items;
    }

    public void setItems(List<StockGainItemDto> items) {
        this.items = items;
    }
}
