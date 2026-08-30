package com.stock.exception;

public class StockAlreadyExistsException extends RuntimeException {

    private final String stockId;

    public StockAlreadyExistsException(String stockId) {
        super("Stock already exists: " + stockId);
        this.stockId = stockId;
    }

    public String getStockId() {
        return stockId;
    }
}
