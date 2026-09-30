package com.stock.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * `detail` of one MA_BREAKOUT hit — see specs/backend/strategy-scan.md, "站上均線". `close`/`prevClose`
 * are the signal day D and the trading day before it; `matchedPeriods` lists the selected lines that
 * actually crossed, in fixed MA5 -> MA20 -> MA60 order. `maN`/`prevMaN` are that line's value on D /
 * D-1, two decimals: {@code null} for a line that was not selected, but still populated for a
 * selected line that did not cross (reference information — the reader sees how far it missed by).
 * Always serialized, {@code null} included, so the shape is identical for every hit.
 */
public class MaBreakoutDetailDto {

    private final BigDecimal close;
    private final BigDecimal prevClose;
    private final List<String> matchedPeriods;
    private final BigDecimal ma5;
    private final BigDecimal ma20;
    private final BigDecimal ma60;
    private final BigDecimal prevMa5;
    private final BigDecimal prevMa20;
    private final BigDecimal prevMa60;

    public MaBreakoutDetailDto(BigDecimal close, BigDecimal prevClose, List<String> matchedPeriods,
                                BigDecimal ma5, BigDecimal ma20, BigDecimal ma60,
                                BigDecimal prevMa5, BigDecimal prevMa20, BigDecimal prevMa60) {
        this.close = close;
        this.prevClose = prevClose;
        this.matchedPeriods = matchedPeriods;
        this.ma5 = ma5;
        this.ma20 = ma20;
        this.ma60 = ma60;
        this.prevMa5 = prevMa5;
        this.prevMa20 = prevMa20;
        this.prevMa60 = prevMa60;
    }

    public BigDecimal getClose() {
        return close;
    }

    public BigDecimal getPrevClose() {
        return prevClose;
    }

    public List<String> getMatchedPeriods() {
        return matchedPeriods;
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

    public BigDecimal getPrevMa20() {
        return prevMa20;
    }

    public BigDecimal getPrevMa60() {
        return prevMa60;
    }
}
