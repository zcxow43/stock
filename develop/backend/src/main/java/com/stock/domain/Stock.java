package com.stock.domain;

public class Stock {

    private String stockId;
    private String stockName;
    private String market;
    private Boolean active;

    public Stock() {
    }

    public Stock(String stockId, String stockName, String market, Boolean active) {
        this.stockId = stockId;
        this.stockName = stockName;
        this.market = market;
        this.active = active;
    }

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

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
