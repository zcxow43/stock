package com.stock.exception;

/**
 * KDJ_GOLDEN_CROSS's `jThreshold` falls outside [-100, 100], or carries more than one decimal digit
 * — see specs/backend/strategy-scan.md, "驗證與用語".
 */
public class InvalidJThresholdException extends RuntimeException {

    private final String strategy;

    public InvalidJThresholdException(String strategy) {
        super("Invalid jThreshold for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
