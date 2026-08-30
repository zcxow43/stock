package com.stock.exception;

/** stockIds exceeds the strategy scan's 200-stock cap — distinct wire code from TooManyStockIdsException. */
public class TooManyStocksException extends RuntimeException {

    private final int limit;

    public TooManyStocksException(int limit) {
        super("stockIds must not exceed " + limit);
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }
}
