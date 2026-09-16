package com.stock.exception;

/**
 * INSTITUTIONAL_NET_RATIO's `ratioPercent` is outside [0, 100], or has more than one decimal digit
 * — see specs/backend/strategy-scan.md, "驗證與用語".
 */
public class InvalidRatioPercentException extends RuntimeException {

    private final String strategy;

    public InvalidRatioPercentException(String strategy) {
        super("Invalid ratioPercent for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
