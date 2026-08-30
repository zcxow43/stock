package com.stock.dto;

/** DELETE /api/stocks/{stockId} response: confirms the soft-delete flag flip only. */
public class StockDeleteResponse {

    private final String stockId;
    private final String stockName;
    private final Boolean isActive;

    public StockDeleteResponse(String stockId, String stockName, Boolean isActive) {
        this.stockId = stockId;
        this.stockName = stockName;
        this.isActive = isActive;
    }

    public String getStockId() {
        return stockId;
    }

    public String getStockName() {
        return stockName;
    }

    public Boolean getIsActive() {
        return isActive;
    }
}
