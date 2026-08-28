package com.stock.service.external.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Fully normalized single trade-date price row, ready to be persisted. */
public class NormalizedPriceRow {

    private final String stockId;
    private final LocalDate tradeDate;
    private final BigDecimal open;
    private final BigDecimal high;
    private final BigDecimal low;
    private final BigDecimal close;
    private final long volume;
    private final BigDecimal turnover;
    private final int transactionCount;

    public NormalizedPriceRow(String stockId, LocalDate tradeDate, BigDecimal open, BigDecimal high,
                               BigDecimal low, BigDecimal close, long volume, BigDecimal turnover,
                               int transactionCount) {
        this.stockId = stockId;
        this.tradeDate = tradeDate;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.turnover = turnover;
        this.transactionCount = transactionCount;
    }

    public String getStockId() {
        return stockId;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public BigDecimal getOpen() {
        return open;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public BigDecimal getClose() {
        return close;
    }

    public long getVolume() {
        return volume;
    }

    public BigDecimal getTurnover() {
        return turnover;
    }

    public int getTransactionCount() {
        return transactionCount;
    }
}
