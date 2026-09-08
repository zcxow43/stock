package com.stock.exception;

/**
 * REBOUND's `riseDays` is not an integer, or falls outside [1, 90] — see
 * specs/backend/strategy-scan.md, POST /api/strategies/scan validation table.
 */
public class InvalidRiseDaysException extends RuntimeException {

    private final String strategy;

    public InvalidRiseDaysException(String strategy) {
        super("Invalid riseDays for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
