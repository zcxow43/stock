package com.stock.exception;

import java.math.BigDecimal;

/** POST /api/real-trades' shares is missing, not an integer, or not above 0. */
public class InvalidSharesException extends RuntimeException {

    private final BigDecimal shares;

    public InvalidSharesException(BigDecimal shares) {
        super("Invalid shares: " + shares);
        this.shares = shares;
    }

    /** The offending value, or {@code null} when the field was missing. */
    public BigDecimal getShares() {
        return shares;
    }
}
