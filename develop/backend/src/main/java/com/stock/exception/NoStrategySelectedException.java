package com.stock.exception;

public class NoStrategySelectedException extends RuntimeException {

    public NoStrategySelectedException() {
        super("strategies must not be empty");
    }
}
