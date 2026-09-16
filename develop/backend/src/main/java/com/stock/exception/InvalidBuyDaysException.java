package com.stock.exception;

/**
 * INSTITUTIONAL_CONSECUTIVE_BUY's `buyDays` is not an integer, or falls outside [1, 20] — see
 * specs/backend/strategy-scan.md, "驗證與用語".
 */
public class InvalidBuyDaysException extends RuntimeException {

    private final String strategy;

    public InvalidBuyDaysException(String strategy) {
        super("Invalid buyDays for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
