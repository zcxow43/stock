package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One confirmation-day close reported in a RISING_SUPPORT hit's detail.confirmCloses (D+1, D+2). */
public class ConfirmCloseDto {

    private LocalDate tradeDate;
    private BigDecimal close;

    public ConfirmCloseDto() {
    }

    public ConfirmCloseDto(LocalDate tradeDate, BigDecimal close) {
        this.tradeDate = tradeDate;
        this.close = close;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public BigDecimal getClose() {
        return close;
    }

    public void setClose(BigDecimal close) {
        this.close = close;
    }
}
