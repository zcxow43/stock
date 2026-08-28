package com.stock.exception;

import java.util.List;

/** Thrown when the `interval` query param is not one of the allowed minute-bar periods. */
public class InvalidIntervalException extends RuntimeException {

    public static final List<Integer> ALLOWED = List.of(1, 5, 15, 30, 60);

    public InvalidIntervalException(int interval) {
        super("Invalid interval: " + interval);
    }
}
