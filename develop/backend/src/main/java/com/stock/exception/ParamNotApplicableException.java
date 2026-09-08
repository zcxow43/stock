package com.stock.exception;

/**
 * A REBOUND-only param (`requireRise`/`dropDays`/`dropPercent`/`riseDays`) was sent for a strategy
 * that does not accept it, or `riseDays`/`risePercent` was sent for REBOUND while `requireRise` is
 * `false` — see specs/backend/strategy-scan.md, POST /api/strategies/scan validation table.
 * `param` names the one offending field.
 */
public class ParamNotApplicableException extends RuntimeException {

    private final String strategy;
    private final String param;

    public ParamNotApplicableException(String strategy, String param) {
        super("param " + param + " is not applicable for strategy: " + strategy);
        this.strategy = strategy;
        this.param = param;
    }

    public String getStrategy() {
        return strategy;
    }

    public String getParam() {
        return param;
    }
}
