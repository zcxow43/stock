package com.stock.domain;

import java.time.LocalDate;

/** One row of a GROUP BY stock_id aggregate: a single stock's own MAX(trade_date). */
public class StockLatestTradeDate {

    private String stockId;
    private LocalDate tradeDate;

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }
}
