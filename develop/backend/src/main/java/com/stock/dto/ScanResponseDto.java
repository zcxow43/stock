package com.stock.dto;

import java.time.LocalDate;
import java.util.List;

/** POST /api/strategies/scan response body. */
public class ScanResponseDto {

    private LocalDate startDate;
    private LocalDate endDate;
    private int scannedStocks;
    private List<StrategyResultDto> results;

    public ScanResponseDto() {
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

    public int getScannedStocks() {
        return scannedStocks;
    }

    public void setScannedStocks(int scannedStocks) {
        this.scannedStocks = scannedStocks;
    }

    public List<StrategyResultDto> getResults() {
        return results;
    }

    public void setResults(List<StrategyResultDto> results) {
        this.results = results;
    }
}
