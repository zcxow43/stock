package com.stock.exception;

/**
 * POST /api/simulated-trades: {@code priceBasis} is neither CLOSE nor OPEN, or is OPEN without a
 * {@code buyDate} (specs/backend/simulated-trade.md, "以開盤價建立"). Wire body carries only the code.
 */
public class InvalidPriceBasisException extends RuntimeException {

    public InvalidPriceBasisException(String message) {
        super(message);
    }
}
