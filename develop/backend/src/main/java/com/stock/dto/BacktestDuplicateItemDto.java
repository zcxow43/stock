package com.stock.dto;

import java.time.LocalDate;

/**
 * One `(stockId, buyDate)` pair named in a `DUPLICATE_BACKTEST_ITEM` error's `duplicatedItems`
 * array — see specs/backend/strategy-backtest.md, "一筆＝一個買進日". The uniqueness key for
 * POST /api/strategies/backtest's `items` is this pair, not `stockId` alone: the same stock may
 * legitimately appear more than once with different buy dates.
 */
public class BacktestDuplicateItemDto {

    private final String stockId;
    private final LocalDate buyDate;

    public BacktestDuplicateItemDto(String stockId, LocalDate buyDate) {
        this.stockId = stockId;
        this.buyDate = buyDate;
    }

    public String getStockId() {
        return stockId;
    }

    public LocalDate getBuyDate() {
        return buyDate;
    }
}
