package com.stock.exception;

public class TooManyStockIdsException extends RuntimeException {

    private final int limit;

    public TooManyStockIdsException(int limit) {
        super("stockIds must not exceed " + limit);
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }
}
