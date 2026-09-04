package com.stock.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/strategies/scan request body. `commonStocksOnly` is nullable on the wire so omission
 * can be distinguished from an explicit value — the service layer treats null as `true` (see
 * specs/backend/strategy-scan.md, "掃描範圍"). It is ignored entirely whenever `stockIds` is given.
 */
public class ScanRequestDto {

    private List<StrategySelectionDto> strategies;
    private List<String> stockIds;
    private Boolean commonStocksOnly;
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

    public Boolean getCommonStocksOnly() {
        return commonStocksOnly;
    }

    public void setCommonStocksOnly(Boolean commonStocksOnly) {
        this.commonStocksOnly = commonStocksOnly;
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
