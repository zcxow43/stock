package com.stock.exception;

/**
 * VOLUME_SURGE's `increasePercent` falls outside [0, 1000], or carries more than one decimal digit
 * — see specs/backend/strategy-scan.md, "量開始變多" and 驗證與錯誤.
 */
public class InvalidIncreasePercentException extends RuntimeException {

    private final String strategy;

    public InvalidIncreasePercentException(String strategy) {
        super("Invalid increasePercent for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
