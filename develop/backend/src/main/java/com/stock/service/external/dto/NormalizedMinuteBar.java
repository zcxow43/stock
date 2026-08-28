package com.stock.service.external.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/** Fully normalized single 1-minute bar, ready to be persisted into stock_minute_price. */
public class NormalizedMinuteBar {

    private final String stockId;
    private final LocalDate tradeDate;
    private final LocalTime barTime;
    private final BigDecimal open;
    private final BigDecimal high;
    private final BigDecimal low;
    private final BigDecimal close;
    private final long volume;

    public NormalizedMinuteBar(String stockId, LocalDate tradeDate, LocalTime barTime, BigDecimal open,
                                BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        this.stockId = stockId;
        this.tradeDate = tradeDate;
        this.barTime = barTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public String getStockId() {
        return stockId;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public LocalTime getBarTime() {
        return barTime;
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
}
