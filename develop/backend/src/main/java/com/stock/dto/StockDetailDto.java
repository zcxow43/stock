package com.stock.dto;

import java.time.LocalDate;

/** GET /api/stocks/{stockId}: list item fields plus summary aggregates only affordable for a single-row query. */
public class StockDetailDto extends StockListItemDto {

    private LocalDate firstTradeDate;
    private int tradingDayCount;

    public LocalDate getFirstTradeDate() {
        return firstTradeDate;
    }

    public void setFirstTradeDate(LocalDate firstTradeDate) {
        this.firstTradeDate = firstTradeDate;
    }

    public int getTradingDayCount() {
        return tradingDayCount;
    }

    public void setTradingDayCount(int tradingDayCount) {
        this.tradingDayCount = tradingDayCount;
    }
}
