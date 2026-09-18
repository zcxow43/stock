package com.stock.exception;

/**
 * MACD_GOLDEN_CROSS's `slowPeriod` is not an integer, or falls outside [3, 100] — see
 * specs/backend/strategy-scan.md, "驗證與用語".
 */
public class InvalidSlowPeriodException extends RuntimeException {

    private final String strategy;

    public InvalidSlowPeriodException(String strategy) {
        super("Invalid slowPeriod for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
