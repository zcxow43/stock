package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/strategies/backtest response body. `totalReturnPercent` is null only when
 * `backtestedCount` is 0 — `totalCost`/`totalProfit` are `0` in that case, never null (see
 * specs/backend/strategy-backtest.md, "彙總").
 */
public class BacktestResponseDto {

    private LocalDate asOfDate;
    private int lotSize;
    private BigDecimal totalCost;
    private BigDecimal totalProfit;
    private BigDecimal totalReturnPercent;
    private int backtestedCount;
    private List<BacktestResultItemDto> items;

    public BacktestResponseDto() {
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public void setAsOfDate(LocalDate asOfDate) {
        this.asOfDate = asOfDate;
    }

    public int getLotSize() {
        return lotSize;
    }

    public void setLotSize(int lotSize) {
        this.lotSize = lotSize;
    }

    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(BigDecimal totalCost) {
        this.totalCost = totalCost;
    }

    public BigDecimal getTotalProfit() {
        return totalProfit;
    }

    public void setTotalProfit(BigDecimal totalProfit) {
        this.totalProfit = totalProfit;
    }

    public BigDecimal getTotalReturnPercent() {
        return totalReturnPercent;
    }

    public void setTotalReturnPercent(BigDecimal totalReturnPercent) {
        this.totalReturnPercent = totalReturnPercent;
    }

    public int getBacktestedCount() {
        return backtestedCount;
    }

    public void setBacktestedCount(int backtestedCount) {
        this.backtestedCount = backtestedCount;
    }

    public List<BacktestResultItemDto> getItems() {
        return items;
    }

    public void setItems(List<BacktestResultItemDto> items) {
        this.items = items;
    }
}
