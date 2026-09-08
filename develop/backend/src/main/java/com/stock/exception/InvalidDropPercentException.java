package com.stock.exception;

/**
 * REBOUND's `dropPercent` is outside [0, 50], or has more than one decimal digit — see
 * specs/backend/strategy-scan.md, POST /api/strategies/scan validation table.
 */
public class InvalidDropPercentException extends RuntimeException {

    private final String strategy;

    public InvalidDropPercentException(String strategy) {
        super("Invalid dropPercent for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
