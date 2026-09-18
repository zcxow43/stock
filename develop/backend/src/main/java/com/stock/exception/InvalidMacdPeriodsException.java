package com.stock.exception;

/**
 * MACD_GOLDEN_CROSS's `fastPeriod` is not strictly less than `slowPeriod` (each resolved to its own
 * default when omitted) — never crosses, so the request could never match. Only raised once each
 * field's own range has already passed — see specs/backend/strategy-scan.md, "各欄自身的範圍錯誤優先".
 */
public class InvalidMacdPeriodsException extends RuntimeException {

    private final String strategy;

    public InvalidMacdPeriodsException(String strategy) {
        super("fastPeriod must be strictly less than slowPeriod for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
