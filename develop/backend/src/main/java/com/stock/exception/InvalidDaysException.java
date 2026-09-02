package com.stock.exception;

/** Thrown when GET /api/momentum/gain's `mode=DAYS` but `days` is missing or not in 1-120. */
public class InvalidDaysException extends RuntimeException {

    public InvalidDaysException(Integer days) {
        super("Invalid days: " + days);
    }
}
