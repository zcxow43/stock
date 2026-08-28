package com.stock.exception;

/** Thrown when tradeDate cannot be parsed as an ISO-8601 date (yyyy-MM-dd). */
public class InvalidDateFormatException extends RuntimeException {

    public InvalidDateFormatException(String rawValue) {
        super("Invalid date format: " + rawValue);
    }
}
