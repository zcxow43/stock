package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * One element of Fugle's `data[]`. `date` is a full ISO 8601 timestamp with a `+08:00` offset
 * (e.g. `2026-09-10T09:00:00.000+08:00`) marking the START of that minute — same grid as Yahoo
 * (spec: 富果 Fugle). `volume` is this bar's own trade count, never a cumulative running total
 * (confirmed live: 2026-09-10 6488 bar volumes are non-monotonic across the session), and its unit
 * is 張 (1000 shares) — FugleClient converts it to shares before storing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FugleCandle {

    private String date;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long volume;

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
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

    public Long getVolume() {
        return volume;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }
}
