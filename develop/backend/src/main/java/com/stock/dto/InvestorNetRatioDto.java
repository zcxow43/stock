package com.stock.dto;

import java.math.BigDecimal;

/**
 * INSTITUTIONAL_NET_RATIO's per-investor judged value — see specs/backend/strategy-scan.md,
 * "法人買賣超佔比". `netShares` is the window's signed net-share total (positive = net buy, negative =
 * net sell); `ratioPercent` is {@code |netShares| / volumeShares} as a percentage rounded to 2
 * decimals; `direction` is {@code "BUY"} or {@code "SELL"} depending on `netShares`'s sign.
 */
public class InvestorNetRatioDto {

    private long netShares;
    private BigDecimal ratioPercent;
    private String direction;

    public InvestorNetRatioDto() {
    }

    public InvestorNetRatioDto(long netShares, BigDecimal ratioPercent, String direction) {
        this.netShares = netShares;
        this.ratioPercent = ratioPercent;
        this.direction = direction;
    }

    public long getNetShares() {
        return netShares;
    }

    public void setNetShares(long netShares) {
        this.netShares = netShares;
    }

    public BigDecimal getRatioPercent() {
        return ratioPercent;
    }

    public void setRatioPercent(BigDecimal ratioPercent) {
        this.ratioPercent = ratioPercent;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }
}
