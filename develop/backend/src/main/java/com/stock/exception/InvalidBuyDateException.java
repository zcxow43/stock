package com.stock.exception;

/**
 * POST /api/strategies/backtest's `items[].buyDate` is later than today (Asia/Taipei), or
 * missing — see specs/backend/strategy-backtest.md, "驗證與錯誤". `stockId` names the one offending
 * item, per the wire contract.
 */
public class InvalidBuyDateException extends RuntimeException {

    private final String stockId;

    public InvalidBuyDateException(String stockId) {
        super("Invalid buyDate for stock: " + stockId);
        this.stockId = stockId;
    }

    public String getStockId() {
        return stockId;
    }
}
