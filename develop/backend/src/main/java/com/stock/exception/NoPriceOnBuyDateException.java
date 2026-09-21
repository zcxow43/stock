package com.stock.exception;

import java.time.LocalDate;

/**
 * POST /api/simulated-trades' explicitly-specified `buyDate` has no `stock_daily_price` row for the
 * given stock, or its `close_price` is `0` (a no-trade day, not a real quote) — see
 * specs/backend/simulated-trade.md, "指定買進日（本次新增）". Must never trigger a silent fallback to a
 * nearby trading day; the caller has to pick a different date themselves.
 */
public class NoPriceOnBuyDateException extends RuntimeException {

    private final String stockId;
    private final LocalDate buyDate;

    public NoPriceOnBuyDateException(String stockId, LocalDate buyDate) {
        super("No positive close price for stock " + stockId + " on " + buyDate);
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
