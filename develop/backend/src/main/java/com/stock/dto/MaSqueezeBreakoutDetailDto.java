package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * `detail` of one MA_SQUEEZE_BREAKOUT hit — see specs/backend/strategy-scan.md, "均線糾結突破".
 * `close` and `ma5`/`ma20`/`ma60` are the signal day D, `prevMa5` is D-1's MA5 (all two decimals);
 * `squeezeStartDate` is the squeeze window's first trading day (D-squeezeDays) and
 * `maxSpreadPercent` the largest daily (max - min) / mean of the three lines within that window, in %,
 * two decimals. The judgment itself always uses the un-rounded values; these are reference figures.
 */
public class MaSqueezeBreakoutDetailDto {

    private final BigDecimal close;
    private final BigDecimal ma5;
    private final BigDecimal ma20;
    private final BigDecimal ma60;
    private final BigDecimal prevMa5;
    private final LocalDate squeezeStartDate;
    private final BigDecimal maxSpreadPercent;

    public MaSqueezeBreakoutDetailDto(BigDecimal close, BigDecimal ma5, BigDecimal ma20, BigDecimal ma60,
                                      BigDecimal prevMa5, LocalDate squeezeStartDate, BigDecimal maxSpreadPercent) {
        this.close = close;
        this.ma5 = ma5;
        this.ma20 = ma20;
        this.ma60 = ma60;
        this.prevMa5 = prevMa5;
        this.squeezeStartDate = squeezeStartDate;
        this.maxSpreadPercent = maxSpreadPercent;
    }

    public BigDecimal getClose() {
        return close;
    }

    public BigDecimal getMa5() {
        return ma5;
    }

    public BigDecimal getMa20() {
        return ma20;
    }

    public BigDecimal getMa60() {
        return ma60;
    }

    public BigDecimal getPrevMa5() {
        return prevMa5;
    }

    public LocalDate getSqueezeStartDate() {
        return squeezeStartDate;
    }

    public BigDecimal getMaxSpreadPercent() {
        return maxSpreadPercent;
    }
}
