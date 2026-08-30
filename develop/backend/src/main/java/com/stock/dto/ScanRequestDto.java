package com.stock.dto;

import java.time.LocalDate;
import java.util.List;

/** POST /api/strategies/scan request body. */
public class ScanRequestDto {

    private List<StrategySelectionDto> strategies;
    private List<String> stockIds;
    private LocalDate startDate;
    private LocalDate endDate;

    public ScanRequestDto() {
    }

    public List<StrategySelectionDto> getStrategies() {
        return strategies;
    }

    public void setStrategies(List<StrategySelectionDto> strategies) {
        this.strategies = strategies;
    }

    public List<String> getStockIds() {
        return stockIds;
    }

    public void setStockIds(List<String> stockIds) {
        this.stockIds = stockIds;
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
}
