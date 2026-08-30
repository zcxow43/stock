package com.stock.dto;

/** POST /api/stocks request body. isActive is intentionally absent: new stocks are always active. */
public class CreateStockRequest {

    private String stockId;
    private String stockName;
    private String market;

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

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
}
