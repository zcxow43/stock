package com.stock.exception;

import java.math.BigDecimal;

/** POST /api/real-trades' buyPrice is missing, not above 0, or has more than two decimals. */
public class InvalidBuyPriceException extends RuntimeException {

    private final BigDecimal buyPrice;

    public InvalidBuyPriceException(BigDecimal buyPrice) {
        super("Invalid buyPrice: " + buyPrice);
        this.buyPrice = buyPrice;
    }

    /** The offending value, or {@code null} when the field was missing. */
    public BigDecimal getBuyPrice() {
        return buyPrice;
    }
}
