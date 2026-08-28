package com.stock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the statistics series query: stock_daily_price LEFT JOIN stock_daily_indicator.
 * Price fields are always present; indicator fields are null when not yet computed for that day.
 */
public class StatSeriesRow {

    private String stockId;
    private LocalDate tradeDate;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private long volume;

    private BigDecimal dif;
    private BigDecimal dea;
    private BigDecimal osc;
    private BigDecimal kValue;
    private BigDecimal dValue;
    private BigDecimal jValue;

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

    public BigDecimal getOpenPrice() {
        return openPrice;
    }

    public void setOpenPrice(BigDecimal openPrice) {
        this.openPrice = openPrice;
    }

    public BigDecimal getHighPrice() {
        return highPrice;
    }

    public void setHighPrice(BigDecimal highPrice) {
        this.highPrice = highPrice;
    }

    public BigDecimal getLowPrice() {
        return lowPrice;
    }

    public void setLowPrice(BigDecimal lowPrice) {
        this.lowPrice = lowPrice;
    }

    public BigDecimal getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(BigDecimal closePrice) {
        this.closePrice = closePrice;
    }

    public long getVolume() {
        return volume;
    }

    public void setVolume(long volume) {
        this.volume = volume;
    }

    public BigDecimal getDif() {
        return dif;
    }

    public void setDif(BigDecimal dif) {
        this.dif = dif;
    }

    public BigDecimal getDea() {
        return dea;
    }

    public void setDea(BigDecimal dea) {
        this.dea = dea;
    }

    public BigDecimal getOsc() {
        return osc;
    }

    public void setOsc(BigDecimal osc) {
        this.osc = osc;
    }

    public BigDecimal getKValue() {
        return kValue;
    }

    public void setKValue(BigDecimal kValue) {
        this.kValue = kValue;
    }

    public BigDecimal getDValue() {
        return dValue;
    }

    public void setDValue(BigDecimal dValue) {
        this.dValue = dValue;
    }

    public BigDecimal getJValue() {
        return jValue;
    }

    public void setJValue(BigDecimal jValue) {
        this.jValue = jValue;
    }
}
