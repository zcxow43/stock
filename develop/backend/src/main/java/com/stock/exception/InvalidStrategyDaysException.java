package com.stock.exception;

/**
 * CUMULATIVE_RISE's `days` is not an integer, or falls outside [1, 90] — see
 * specs/backend/strategy-scan.md, POST /api/strategies/scan validation table. Distinct from
 * {@link InvalidDaysException} (GET /api/momentum/gain's unrelated `mode=DAYS` validation): same
 * wire error code "INVALID_DAYS", different JSON shape — this one names the offending `strategy`.
 */
public class InvalidStrategyDaysException extends RuntimeException {

    private final String strategy;

    public InvalidStrategyDaysException(String strategy) {
        super("Invalid days for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
