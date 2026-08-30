package com.stock.dto;

import java.math.BigDecimal;

/** detail payload for a BOX_BREAKOUT hit. */
public class BoxBreakoutDetailDto {

    private BigDecimal boxHigh;
    private BigDecimal boxLow;
    private BigDecimal breakoutClose;
    private BigDecimal breakoutPercent;
    private BigDecimal volumeRatio;

    public BoxBreakoutDetailDto() {
    }

    public BoxBreakoutDetailDto(BigDecimal boxHigh, BigDecimal boxLow, BigDecimal breakoutClose,
                                 BigDecimal breakoutPercent, BigDecimal volumeRatio) {
        this.boxHigh = boxHigh;
        this.boxLow = boxLow;
        this.breakoutClose = breakoutClose;
        this.breakoutPercent = breakoutPercent;
        this.volumeRatio = volumeRatio;
    }

    public BigDecimal getBoxHigh() {
        return boxHigh;
    }

    public void setBoxHigh(BigDecimal boxHigh) {
        this.boxHigh = boxHigh;
    }

    public BigDecimal getBoxLow() {
        return boxLow;
    }

    public void setBoxLow(BigDecimal boxLow) {
        this.boxLow = boxLow;
    }

    public BigDecimal getBreakoutClose() {
        return breakoutClose;
    }

    public void setBreakoutClose(BigDecimal breakoutClose) {
        this.breakoutClose = breakoutClose;
    }

    public BigDecimal getBreakoutPercent() {
        return breakoutPercent;
    }

    public void setBreakoutPercent(BigDecimal breakoutPercent) {
        this.breakoutPercent = breakoutPercent;
    }

    public BigDecimal getVolumeRatio() {
        return volumeRatio;
    }

    public void setVolumeRatio(BigDecimal volumeRatio) {
        this.volumeRatio = volumeRatio;
    }
}
