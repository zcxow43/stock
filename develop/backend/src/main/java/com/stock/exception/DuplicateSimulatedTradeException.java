package com.stock.exception;

import java.time.LocalDate;

/**
 * POST /api/simulated-trades's `(stockId, buyDate)` already exists in `simulated_trade`
 * (specs/backend/simulated-trade.md, "驗證與錯誤"). The request itself is entirely valid — the
 * holding is just already there — which is why this maps to 409, not 400.
 */
public class DuplicateSimulatedTradeException extends RuntimeException {

    private final String stockId;
    private final LocalDate buyDate;

    public DuplicateSimulatedTradeException(String stockId, LocalDate buyDate) {
        super("Simulated trade already exists for " + stockId + " on " + buyDate);
        this.stockId = stockId;
        this.buyDate = buyDate;
    }

    public String getStockId() {
        return stockId;
    }

    public LocalDate getBuyDate() {
        return buyDate;
    }
}
