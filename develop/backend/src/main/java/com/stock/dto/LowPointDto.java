package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One swing low point reported in a HIGHER_LOWS hit's detail.lows. */
public class LowPointDto {

    private LocalDate tradeDate;
    private BigDecimal low;

    public LowPointDto() {
    }

    public LowPointDto(LocalDate tradeDate, BigDecimal low) {
        this.tradeDate = tradeDate;
        this.low = low;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public BigDecimal getLow() {
        return low;
    }

    public void setLow(BigDecimal low) {
        this.low = low;
    }
}
