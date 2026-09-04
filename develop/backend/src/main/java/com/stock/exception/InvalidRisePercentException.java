package com.stock.exception;

/**
 * A strategy selection's `risePercent` override is outside [0, 50], or has more than one decimal
 * digit — see specs/backend/strategy-scan.md, POST /api/strategies/scan validation table.
 * `strategy` names the one offending strategy code, per the wire contract.
 */
public class InvalidRisePercentException extends RuntimeException {

    private final String strategy;

    public InvalidRisePercentException(String strategy) {
        super("Invalid risePercent override for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
