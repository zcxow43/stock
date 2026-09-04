package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/momentum/gain response body (specs/backend/industry-gain-ranking.md). A read-only gain
 * statistic grouped by industry — never a buy/sell recommendation.
 */
public class MomentumGainResponseDto {

    private String metric;
    private String mode;
    private String sort;
    private LocalDate startDate;
    private LocalDate endDate;
    private int tradingDays;
    private BigDecimal minGain;
    private int scannedStocks;
    private int matchedStockCount;
    private int insufficientDataCount;
    private List<IndustryGainGroupDto> industries;

    public MomentumGainResponseDto() {
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public int getTradingDays() {
        return tradingDays;
    }

    public void setTradingDays(int tradingDays) {
        this.tradingDays = tradingDays;
    }

    public BigDecimal getMinGain() {
        return minGain;
    }

    public void setMinGain(BigDecimal minGain) {
        this.minGain = minGain;
    }

    public int getScannedStocks() {
        return scannedStocks;
    }

    public void setScannedStocks(int scannedStocks) {
        this.scannedStocks = scannedStocks;
    }

    public int getMatchedStockCount() {
        return matchedStockCount;
    }

    public void setMatchedStockCount(int matchedStockCount) {
        this.matchedStockCount = matchedStockCount;
    }

    public int getInsufficientDataCount() {
        return insufficientDataCount;
    }

    public void setInsufficientDataCount(int insufficientDataCount) {
        this.insufficientDataCount = insufficientDataCount;
    }

    public List<IndustryGainGroupDto> getIndustries() {
        return industries;
    }

    public void setIndustries(List<IndustryGainGroupDto> industries) {
        this.industries = industries;
    }
}
