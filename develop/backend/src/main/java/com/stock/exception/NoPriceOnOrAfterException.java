package com.stock.exception;

import java.time.LocalDate;

/**
 * POST /api/simulated-trades with `buyOnOrAfter`: the stock has no row with a positive price on
 * or after that date up to today - see specs/backend/simulated-trade.md.
 */
public class NoPriceOnOrAfterException extends RuntimeException {

    private final String stockId;
    private final LocalDate buyOnOrAfter;

    public NoPriceOnOrAfterException(String stockId, LocalDate buyOnOrAfter) {
        super("No positive price for stock " + stockId + " on or after " + buyOnOrAfter);
        this.stockId = stockId;
        this.buyOnOrAfter = buyOnOrAfter;
    }

    public String getStockId() {
        return stockId;
    }

    public LocalDate getBuyOnOrAfter() {
        return buyOnOrAfter;
    }
}
