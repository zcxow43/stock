package com.stock.exception;

/**
 * A strategy's `maPeriods` is an empty array, contains a value other than `MA5`/`MA20`/`MA60`, or
 * has a duplicate — see specs/backend/strategy-scan.md, "驗證與用語".
 */
public class InvalidMaPeriodsException extends RuntimeException {

    private final String strategy;

    public InvalidMaPeriodsException(String strategy) {
        super("Invalid maPeriods for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
