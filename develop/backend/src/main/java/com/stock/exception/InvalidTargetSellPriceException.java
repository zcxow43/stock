package com.stock.exception;

import java.math.BigDecimal;

/** PATCH /api/real-trades' targetSellPrice was given as a number that is not above 0 or has more than two decimals. */
public class InvalidTargetSellPriceException extends RuntimeException {

    private final BigDecimal targetSellPrice;

    public InvalidTargetSellPriceException(BigDecimal targetSellPrice) {
        super("Invalid targetSellPrice: " + targetSellPrice);
        this.targetSellPrice = targetSellPrice;
    }

    public BigDecimal getTargetSellPrice() {
        return targetSellPrice;
    }
}
