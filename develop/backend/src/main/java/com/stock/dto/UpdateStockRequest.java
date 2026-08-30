package com.stock.dto;

/**
 * PUT /api/stocks/{stockId} request body. stockId is not here — it is the path variable and the
 * primary key; it cannot be changed because stock_daily_price/stock_daily_indicator/stock_sync_progress
 * all hang off it.
 */
public class UpdateStockRequest {

    private String stockName;
    private String market;
    private Boolean isActive;

    public String getStockName() {
        return stockName;
    }

    public void setStockName(String stockName) {
        this.stockName = stockName;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}
