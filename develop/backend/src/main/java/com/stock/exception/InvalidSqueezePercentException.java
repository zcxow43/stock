package com.stock.exception;

/**
 * MA_SQUEEZE_BREAKOUT's `squeezePercent` falls outside [0.1, 10], or carries more than one decimal digit — see specs/backend/strategy-scan.md, "均線糾結突破" and
 * 驗證與錯誤.
 */
public class InvalidSqueezePercentException extends RuntimeException {

    private final String strategy;

    public InvalidSqueezePercentException(String strategy) {
        super("Invalid squeezePercent for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
