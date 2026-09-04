package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** detail payload for a CUMULATIVE_RISE hit. */
public class CumulativeRiseDetailDto {

    private LocalDate troughDate;
    private BigDecimal troughClose;
    private BigDecimal peakClose;
    private BigDecimal risePercent;

    public CumulativeRiseDetailDto() {
    }

    public CumulativeRiseDetailDto(LocalDate troughDate, BigDecimal troughClose, BigDecimal peakClose,
                                    BigDecimal risePercent) {
        this.troughDate = troughDate;
        this.troughClose = troughClose;
        this.peakClose = peakClose;
        this.risePercent = risePercent;
    }

    public LocalDate getTroughDate() {
        return troughDate;
    }

    public void setTroughDate(LocalDate troughDate) {
        this.troughDate = troughDate;
    }

    public BigDecimal getTroughClose() {
        return troughClose;
    }

    public void setTroughClose(BigDecimal troughClose) {
        this.troughClose = troughClose;
    }

    public BigDecimal getPeakClose() {
        return peakClose;
    }

    public void setPeakClose(BigDecimal peakClose) {
        this.peakClose = peakClose;
    }

    public BigDecimal getRisePercent() {
        return risePercent;
    }

    public void setRisePercent(BigDecimal risePercent) {
        this.risePercent = risePercent;
    }
}
