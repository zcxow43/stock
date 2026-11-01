package com.stock.exception;

/**
 * GET /api/simulated-trades: {@code settleDate} is malformed or later than today (Asia/Taipei).
 * Holds the raw string as sent so the error body can echo it (specs/backend/simulated-trade.md,
 * "目標結算日").
 */
public class InvalidSettleDateException extends RuntimeException {

    private final String settleDate;

    public InvalidSettleDateException(String settleDate) {
        super("Invalid settleDate: " + settleDate);
        this.settleDate = settleDate;
    }

    public String getSettleDate() {
        return settleDate;
    }
}
