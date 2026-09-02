package com.stock.exception;

/** Thrown when GET /api/momentum/gain's `mode` param is missing or not DAYS/WEEKS. */
public class InvalidModeException extends RuntimeException {

    public InvalidModeException(String mode) {
        super("Invalid mode: " + mode);
    }
}
