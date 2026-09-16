package com.stock.exception;

/**
 * INSTITUTIONAL_NET_RATIO's or INSTITUTIONAL_STRENGTH_RANK's `windowDays` is not an integer, or
 * falls outside [1, 20] — see specs/backend/strategy-scan.md, "驗證與用語".
 */
public class InvalidWindowDaysException extends RuntimeException {

    private final String strategy;

    public InvalidWindowDaysException(String strategy) {
        super("Invalid windowDays for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
