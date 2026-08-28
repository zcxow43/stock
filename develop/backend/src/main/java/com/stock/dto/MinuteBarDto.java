package com.stock.dto;

import java.math.BigDecimal;

/** One bar in GET /api/stocks/{stockId}/minute-bars `bars[]`; barTime is serialized as "HH:mm". */
public class MinuteBarDto {

    private String barTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;

    public MinuteBarDto() {
    }

    public MinuteBarDto(String barTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        this.barTime = barTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public String getBarTime() {
        return barTime;
    }

    public void setBarTime(String barTime) {
        this.barTime = barTime;
    }

    public BigDecimal getOpen() {
        return open;
    }

    public void setOpen(BigDecimal open) {
        this.open = open;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public void setHigh(BigDecimal high) {
        this.high = high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public void setLow(BigDecimal low) {
        this.low = low;
    }

    public BigDecimal getClose() {
        return close;
    }

    public void setClose(BigDecimal close) {
        this.close = close;
    }

    public long getVolume() {
        return volume;
    }

    public void setVolume(long volume) {
        this.volume = volume;
    }
}
