package com.stock.dto;

import java.math.BigDecimal;

/**
 * `detail` of one KDJ_GOLDEN_CROSS hit — see specs/backend/strategy-scan.md, "KDJ 黃金交叉". `k`/`d`/
 * `j` are the signal day's own values; `prevK`/`prevD`/`prevJ` are the trading day immediately
 * before it. All six decimal fields to four places, using the un-rounded values the crossing and
 * the `jThreshold` check were both judged on.
 */
public class KdjGoldenCrossDetailDto {

    private final BigDecimal k;
    private final BigDecimal d;
    private final BigDecimal j;
    private final BigDecimal prevK;
    private final BigDecimal prevD;
    private final BigDecimal prevJ;

    public KdjGoldenCrossDetailDto(BigDecimal k, BigDecimal d, BigDecimal j, BigDecimal prevK, BigDecimal prevD,
                                    BigDecimal prevJ) {
        this.k = k;
        this.d = d;
        this.j = j;
        this.prevK = prevK;
        this.prevD = prevD;
        this.prevJ = prevJ;
    }

    public BigDecimal getK() {
        return k;
    }

    public BigDecimal getD() {
        return d;
    }

    public BigDecimal getJ() {
        return j;
    }

    public BigDecimal getPrevK() {
        return prevK;
    }

    public BigDecimal getPrevD() {
        return prevD;
    }

    public BigDecimal getPrevJ() {
        return prevJ;
    }
}
