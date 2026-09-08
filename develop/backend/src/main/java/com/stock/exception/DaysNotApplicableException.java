package com.stock.exception;

/**
 * A `days` value was sent for a strategy that does not accept it (every strategy except
 * CUMULATIVE_RISE, which is the only one without sensitivity presets) — see
 * specs/backend/strategy-scan.md, POST /api/strategies/scan validation table.
 */
public class DaysNotApplicableException extends RuntimeException {

    private final String strategy;

    public DaysNotApplicableException(String strategy) {
        super("days is not applicable for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
