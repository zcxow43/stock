package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/real-trades body (specs/backend/real-trade.md). Unlike the simulated-trade response there
 * is no {@code lotSize} (share counts differ per row) and no {@code defaultBuyDate} (no input has a
 * default). {@code totalReturnPercent} is {@code null} when there is nothing priced to divide by.
 */
public class RealTradeResponseDto {

    private LocalDate asOfDate;
    private BigDecimal feeRatePercent;
    private BigDecimal taxRatePercent;
    private BigDecimal totalCost;
    private BigDecimal totalUnrealizedProfit;
    private BigDecimal totalReturnPercent;
    private List<RealTradeItemDto> items;
    private List<SkippedLineDto> skippedLines;

    public RealTradeResponseDto() {
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public void setAsOfDate(LocalDate asOfDate) {
        this.asOfDate = asOfDate;
    }

    public BigDecimal getFeeRatePercent() {
        return feeRatePercent;
    }

    public void setFeeRatePercent(BigDecimal feeRatePercent) {
        this.feeRatePercent = feeRatePercent;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public void setTaxRatePercent(BigDecimal taxRatePercent) {
        this.taxRatePercent = taxRatePercent;
    }

    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(BigDecimal totalCost) {
        this.totalCost = totalCost;
    }

    public BigDecimal getTotalUnrealizedProfit() {
        return totalUnrealizedProfit;
    }

    public void setTotalUnrealizedProfit(BigDecimal totalUnrealizedProfit) {
        this.totalUnrealizedProfit = totalUnrealizedProfit;
    }

    public BigDecimal getTotalReturnPercent() {
        return totalReturnPercent;
    }

    public void setTotalReturnPercent(BigDecimal totalReturnPercent) {
        this.totalReturnPercent = totalReturnPercent;
    }

    public List<RealTradeItemDto> getItems() {
        return items;
    }

    public void setItems(List<RealTradeItemDto> items) {
        this.items = items;
    }

    public List<SkippedLineDto> getSkippedLines() {
        return skippedLines;
    }

    public void setSkippedLines(List<SkippedLineDto> skippedLines) {
        this.skippedLines = skippedLines;
    }
}
