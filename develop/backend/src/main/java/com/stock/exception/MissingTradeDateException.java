package com.stock.exception;

/** Thrown when GET /api/stocks/{stockId}/minute-bars is called without the required tradeDate param. */
public class MissingTradeDateException extends RuntimeException {

    public MissingTradeDateException() {
        super("tradeDate is required");
    }
}
