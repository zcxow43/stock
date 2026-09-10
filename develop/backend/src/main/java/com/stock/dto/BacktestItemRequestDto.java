package com.stock.dto;

import java.time.LocalDate;

/**
 * One line of POST /api/strategies/backtest's `items` request array — a stock plus the signal
 * date it was matched on (see specs/backend/strategy-scan.md's `items[].signalDate` for the
 * upstream shape this is built from).
 */
public class BacktestItemRequestDto {

    private String stockId;
    private LocalDate signalDate;

    public BacktestItemRequestDto() {
    }

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public LocalDate getSignalDate() {
        return signalDate;
    }

    public void setSignalDate(LocalDate signalDate) {
        this.signalDate = signalDate;
    }
}
