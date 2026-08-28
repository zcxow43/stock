package com.stock.exception;

/** Thrown when tradeDate is later than today (Asia/Taipei). */
public class FutureTradeDateException extends RuntimeException {

    public FutureTradeDateException() {
        super("tradeDate must not be in the future");
    }
}
