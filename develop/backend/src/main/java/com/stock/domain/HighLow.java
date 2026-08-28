package com.stock.domain;

import java.math.BigDecimal;

/** MAX(high_price)/MIN(low_price) over a recent window of trading days, used for RSV. */
public class HighLow {

    private BigDecimal highPrice;
    private BigDecimal lowPrice;

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
}
