package com.stock.exception;

/**
 * POST /api/strategies/backtest's `items[].signalDate` is later than today (Asia/Taipei), or
 * missing — see specs/backend/strategy-backtest.md, "驗證與錯誤". `stockId` names the one offending
 * item, per the wire contract.
 */
public class InvalidSignalDateException extends RuntimeException {

    private final String stockId;

    public InvalidSignalDateException(String stockId) {
        super("Invalid signalDate for stock: " + stockId);
        this.stockId = stockId;
    }

    public String getStockId() {
        return stockId;
    }
}
