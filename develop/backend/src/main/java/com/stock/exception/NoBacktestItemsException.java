package com.stock.exception;

/**
 * POST /api/strategies/backtest called with a missing or empty `items` array — see
 * specs/backend/strategy-backtest.md, "驗證與錯誤".
 */
public class NoBacktestItemsException extends RuntimeException {

    public NoBacktestItemsException() {
        super("items must not be empty");
    }
}
