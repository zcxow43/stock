package com.stock.exception;

/**
 * POST /api/simulated-trades' `buyOnOrAfter` is malformed or sent together with `buyDate` - see
 * specs/backend/simulated-trade.md. Carries the raw, as-submitted string.
 */
public class InvalidBuyOnOrAfterException extends RuntimeException {

    private final String buyOnOrAfter;

    public InvalidBuyOnOrAfterException(String buyOnOrAfter) {
        super("Invalid buyOnOrAfter for simulated trade: " + buyOnOrAfter);
        this.buyOnOrAfter = buyOnOrAfter;
    }

    public String getBuyOnOrAfter() {
        return buyOnOrAfter;
    }
}
