package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** detail payload for a REBOUND hit. */
public class ReboundDetailDto {

    private LocalDate peakDate;
    private BigDecimal peakClose;
    private BigDecimal troughClose;
    private BigDecimal dropPercent;

    public ReboundDetailDto() {
    }

    public ReboundDetailDto(LocalDate peakDate, BigDecimal peakClose, BigDecimal troughClose,
                             BigDecimal dropPercent) {
        this.peakDate = peakDate;
        this.peakClose = peakClose;
        this.troughClose = troughClose;
        this.dropPercent = dropPercent;
    }

    public LocalDate getPeakDate() {
        return peakDate;
    }

    public void setPeakDate(LocalDate peakDate) {
        this.peakDate = peakDate;
    }

    public BigDecimal getPeakClose() {
        return peakClose;
    }

    public void setPeakClose(BigDecimal peakClose) {
        this.peakClose = peakClose;
    }

    public BigDecimal getTroughClose() {
        return troughClose;
    }

    public void setTroughClose(BigDecimal troughClose) {
        this.troughClose = troughClose;
    }

    public BigDecimal getDropPercent() {
        return dropPercent;
    }

    public void setDropPercent(BigDecimal dropPercent) {
        this.dropPercent = dropPercent;
    }
}
