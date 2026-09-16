package com.stock.exception;

/**
 * An institutional-trade strategy's `investors` is an empty array, contains a value other than
 * `FOREIGN`/`TRUST`, or has a duplicate — see specs/backend/strategy-scan.md, "驗證與用語".
 */
public class InvalidInvestorsException extends RuntimeException {

    private final String strategy;

    public InvalidInvestorsException(String strategy) {
        super("Invalid investors for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
