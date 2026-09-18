package com.stock.dto;

import java.math.BigDecimal;

/**
 * `detail` of one MACD_GOLDEN_CROSS hit — see specs/backend/strategy-scan.md, "MACD 黃金交叉". `dif`/
 * `dea`/`osc` are the signal day's own values; `prevOsc` is the trading day immediately before it.
 * All four decimal fields to four places, using the un-rounded values the crossing itself was
 * judged on — see "判定一律使用未經四捨五入的原值".
 */
public class MacdGoldenCrossDetailDto {

    private final BigDecimal dif;
    private final BigDecimal dea;
    private final BigDecimal osc;
    private final BigDecimal prevOsc;

    public MacdGoldenCrossDetailDto(BigDecimal dif, BigDecimal dea, BigDecimal osc, BigDecimal prevOsc) {
        this.dif = dif;
        this.dea = dea;
        this.osc = osc;
        this.prevOsc = prevOsc;
    }

    public BigDecimal getDif() {
        return dif;
    }

    public BigDecimal getDea() {
        return dea;
    }

    public BigDecimal getOsc() {
        return osc;
    }

    public BigDecimal getPrevOsc() {
        return prevOsc;
    }
}
