package com.stock.exception;

public class StockNotFoundException extends RuntimeException {

    private final String stockId;

    public StockNotFoundException(String stockId) {
        super("Stock not found: " + stockId);
        this.stockId = stockId;
    }

    public String getStockId() {
        return stockId;
    }
}
