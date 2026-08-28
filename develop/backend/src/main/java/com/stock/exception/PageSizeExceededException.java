package com.stock.exception;

public class PageSizeExceededException extends RuntimeException {

    private final int limit;

    public PageSizeExceededException(int limit) {
        super("size must not exceed " + limit);
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }
}
