package com.stock.exception;

/**
 * MA_SQUEEZE_BREAKOUT's `squeezeDays` is not an integer in [1, 60] — see specs/backend/strategy-scan.md, "均線糾結突破" and
 * 驗證與錯誤.
 */
public class InvalidSqueezeDaysException extends RuntimeException {

    private final String strategy;

    public InvalidSqueezeDaysException(String strategy) {
        super("Invalid squeezeDays for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
