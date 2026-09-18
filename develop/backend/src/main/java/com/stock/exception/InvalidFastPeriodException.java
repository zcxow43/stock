package com.stock.exception;

/**
 * MACD_GOLDEN_CROSS's `fastPeriod` is not an integer, or falls outside [2, 50] — see
 * specs/backend/strategy-scan.md, "驗證與用語".
 */
public class InvalidFastPeriodException extends RuntimeException {

    private final String strategy;

    public InvalidFastPeriodException(String strategy) {
        super("Invalid fastPeriod for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
