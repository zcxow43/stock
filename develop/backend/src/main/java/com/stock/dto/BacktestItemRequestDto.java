package com.stock.dto;

import java.time.LocalDate;

/**
 * One line of POST /api/strategies/backtest's `items` request array — a stock plus the buy date
 * it is to be backtested from. `buyDate` is the entry day reported by strategy-scan on each hit
 * (see specs/backend/strategy-scan.md's `items[].buyDate`) and is sent back here verbatim; this
 * endpoint neither knows nor accepts a strategy code or signal date (specs/backend/
 * strategy-backtest.md, "買進日由請求指定，本端點不推算").
 */
public class BacktestItemRequestDto {

    private String stockId;
    private LocalDate buyDate;

    public BacktestItemRequestDto() {
    }

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public LocalDate getBuyDate() {
        return buyDate;
    }

    public void setBuyDate(LocalDate buyDate) {
        this.buyDate = buyDate;
    }
}
