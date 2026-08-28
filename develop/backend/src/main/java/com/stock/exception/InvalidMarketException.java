package com.stock.exception;

public class InvalidMarketException extends RuntimeException {

    public InvalidMarketException(String market) {
        super("Invalid market: " + market);
    }
}
