package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/simulated-trades response body. `totalReturnPercent` is `null` only when `items` is
 * empty — `totalCost`/`totalUnrealizedProfit` are `0` in that case, never null (specs/backend/
 * simulated-trade.md, "彙總"). `lotSize`/`feeRatePercent`/`taxRatePercent` are constants echoed
 * back so the caller can explain what `unrealizedProfit` was net of without hard-coding the
 * convention itself.
 */
public class SimulatedTradeResponseDto {

    private LocalDate asOfDate;
    private LocalDate defaultBuyDate;
    private int lotSize;
    private BigDecimal feeRatePercent;
    private BigDecimal taxRatePercent;
    private BigDecimal totalCost;
    private BigDecimal totalUnrealizedProfit;
    private BigDecimal totalReturnPercent;
    private List<SimulatedTradeItemDto> items;

    public SimulatedTradeResponseDto() {
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public void setAsOfDate(LocalDate asOfDate) {
        this.asOfDate = asOfDate;
    }

    public LocalDate getDefaultBuyDate() {
        return defaultBuyDate;
    }

    /**
     * Market-wide `trade_date &lt;= 今日` with `close_price &gt; 0`, MAX — deliberately not scoped to
     * any one stock (specs/backend/simulated-trade.md, "預設買進日（defaultBuyDate）"). `null` only
     * when the database holds no price rows at all.
     */
    public void setDefaultBuyDate(LocalDate defaultBuyDate) {
        this.defaultBuyDate = defaultBuyDate;
    }

    public int getLotSize() {
        return lotSize;
    }

    public void setLotSize(int lotSize) {
        this.lotSize = lotSize;
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

    public List<SimulatedTradeItemDto> getItems() {
        return items;
    }

    public void setItems(List<SimulatedTradeItemDto> items) {
        this.items = items;
    }
}
