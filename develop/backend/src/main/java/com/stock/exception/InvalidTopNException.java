package com.stock.exception;

/**
 * INSTITUTIONAL_STRENGTH_RANK's `topN` is not an integer, or falls outside [1, 50] — see
 * specs/backend/strategy-scan.md, "驗證與用語".
 */
public class InvalidTopNException extends RuntimeException {

    private final String strategy;

    public InvalidTopNException(String strategy) {
        super("Invalid topN for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
