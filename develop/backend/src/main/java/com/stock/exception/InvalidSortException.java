package com.stock.exception;

/** Thrown when GET /api/momentum/gain's `sort` param is present but not MATCH_COUNT/AVG_GAIN. */
public class InvalidSortException extends RuntimeException {

    public InvalidSortException(String sort) {
        super("Invalid sort: " + sort);
    }
}
