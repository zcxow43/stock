package com.stock.domain;

import java.time.LocalDate;

/** Aggregate over a single stock's stock_daily_price rows: first trade date and trading-day count. */
public class PriceStats {

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
