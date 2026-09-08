package com.stock.exception;

/**
 * A `preset` was sent for a strategy that does not use sensitivity presets (currently only
 * CUMULATIVE_RISE, which takes `days`/`risePercent` directly instead) — see
 * specs/backend/strategy-scan.md, POST /api/strategies/scan validation table.
 */
public class PresetNotApplicableException extends RuntimeException {

    private final String strategy;

    public PresetNotApplicableException(String strategy) {
        super("preset is not applicable for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
