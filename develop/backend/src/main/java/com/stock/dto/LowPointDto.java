package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One swing low point reported in a HIGHER_LOWS hit's detail.lows. `ma5` is the 5-day close moving
 * average the swing-low judgment is actually based on; `low` is the day's raw low price, reported
 * for reference only and never used in detection — see specs/backend/strategy-scan.md, "底底高改以
 * MA5 平滑線為判定基準".
 */
public class LowPointDto {

    private LocalDate tradeDate;
    private BigDecimal ma5;
    private BigDecimal low;

    public LowPointDto() {
    }

    public LowPointDto(LocalDate tradeDate, BigDecimal ma5, BigDecimal low) {
        this.tradeDate = tradeDate;
        this.ma5 = ma5;
        this.low = low;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public BigDecimal getMa5() {
        return ma5;
    }

    public void setMa5(BigDecimal ma5) {
        this.ma5 = ma5;
    }

    public BigDecimal getLow() {
        return low;
    }

    public void setLow(BigDecimal low) {
        this.low = low;
    }
}
