package com.stock.exception;

/**
 * POST /api/simulated-trades's `stockId` is missing, or blank after trimming (specs/backend/
 * simulated-trade.md, "驗證與錯誤").
 */
public class InvalidStockIdException extends RuntimeException {

    public InvalidStockIdException() {
        super("stockId is missing or blank");
    }
}
