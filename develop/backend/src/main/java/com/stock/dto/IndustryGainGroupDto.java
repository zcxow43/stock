package com.stock.dto;

import java.math.BigDecimal;
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
    private BigDecimal avgGain;
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

    /**
     * Arithmetic mean of this block's already-rounded {@code items[].gain} values, over hit
     * stocks only (matched but under-threshold or insufficient-data stocks in the same industry
     * are excluded) — reports how much the stocks that moved moved, not the industry's overall
     * performance.
     */
    public BigDecimal getAvgGain() {
        return avgGain;
    }

    public void setAvgGain(BigDecimal avgGain) {
        this.avgGain = avgGain;
    }

    public List<StockGainItemDto> getItems() {
        return items;
    }

    public void setItems(List<StockGainItemDto> items) {
        this.items = items;
    }
}
