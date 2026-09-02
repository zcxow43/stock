package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One matched stock within an industry block of GET /api/momentum/gain
 * (specs/backend/industry-gain-ranking.md). Reports a gain statistic, never a trading
 * recommendation — see the spec's Overview.
 */
public class StockGainItemDto {

    private String stockId;
    private String stockName;
    private BigDecimal gain;
    private int tradingDays;
    private BigDecimal startClose;
    private BigDecimal endClose;
    private LocalDate firstTradeDate;
    private LocalDate lastTradeDate;

    public StockGainItemDto() {
    }

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public String getStockName() {
        return stockName;
    }

    public void setStockName(String stockName) {
        this.stockName = stockName;
    }

    public BigDecimal getGain() {
        return gain;
    }

    public void setGain(BigDecimal gain) {
        this.gain = gain;
    }

    public int getTradingDays() {
        return tradingDays;
    }

    public void setTradingDays(int tradingDays) {
        this.tradingDays = tradingDays;
    }

    public BigDecimal getStartClose() {
        return startClose;
    }

    public void setStartClose(BigDecimal startClose) {
        this.startClose = startClose;
    }

    public BigDecimal getEndClose() {
        return endClose;
    }

    public void setEndClose(BigDecimal endClose) {
        this.endClose = endClose;
    }

    public LocalDate getFirstTradeDate() {
        return firstTradeDate;
    }

    public void setFirstTradeDate(LocalDate firstTradeDate) {
        this.firstTradeDate = firstTradeDate;
    }

    public LocalDate getLastTradeDate() {
        return lastTradeDate;
    }

    public void setLastTradeDate(LocalDate lastTradeDate) {
        this.lastTradeDate = lastTradeDate;
    }
}
