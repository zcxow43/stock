package com.stock.exception;

public class InvalidPaginationException extends RuntimeException {

    public InvalidPaginationException() {
        super("page and size must be positive integers");
    }
}
