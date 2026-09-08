package com.stock.exception;

/**
 * REBOUND's `dropDays` is not an integer, or falls outside [1, 90] — see
 * specs/backend/strategy-scan.md, POST /api/strategies/scan validation table.
 */
public class InvalidDropDaysException extends RuntimeException {

    private final String strategy;

    public InvalidDropDaysException(String strategy) {
        super("Invalid dropDays for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
