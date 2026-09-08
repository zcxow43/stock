package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * detail payload for a REBOUND hit. `risePercent` is omitted (not just null) when `requireRise`
 * was `false` for this scan — see specs/backend/strategy-scan.md, "反彈改為跌段＋選用漲段的雙段參數、移除靈敏度".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReboundDetailDto {

    private LocalDate peakDate;
    private BigDecimal peakClose;
    private LocalDate troughDate;
    private BigDecimal troughClose;
    private BigDecimal dropPercent;
    private BigDecimal risePercent;

    public ReboundDetailDto() {
    }

    public ReboundDetailDto(LocalDate peakDate, BigDecimal peakClose, LocalDate troughDate, BigDecimal troughClose,
                             BigDecimal dropPercent, BigDecimal risePercent) {
        this.peakDate = peakDate;
        this.peakClose = peakClose;
        this.troughDate = troughDate;
        this.troughClose = troughClose;
        this.dropPercent = dropPercent;
        this.risePercent = risePercent;
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

    public BigDecimal getDropPercent() {
        return dropPercent;
    }

    public void setDropPercent(BigDecimal dropPercent) {
        this.dropPercent = dropPercent;
    }

    public BigDecimal getRisePercent() {
        return risePercent;
    }

    public void setRisePercent(BigDecimal risePercent) {
        this.risePercent = risePercent;
    }
}
