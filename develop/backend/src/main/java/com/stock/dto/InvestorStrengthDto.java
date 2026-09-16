package com.stock.dto;

import java.math.BigDecimal;

/**
 * INSTITUTIONAL_STRENGTH_RANK's per-investor judged value — see specs/backend/strategy-scan.md,
 * "法人買超強度排名". `rank` is this investor's 1-based rank among that trading day's net-buying
 * candidates within the scanned population; `strengthPercent` is {@code netBuyShares / volumeShares}
 * as a percentage rounded to 2 decimals; `netBuyShares` is the window's net-buy-share total.
 */
public class InvestorStrengthDto {

    private int rank;
    private BigDecimal strengthPercent;
    private long netBuyShares;

    public InvestorStrengthDto() {
    }

    public InvestorStrengthDto(int rank, BigDecimal strengthPercent, long netBuyShares) {
        this.rank = rank;
        this.strengthPercent = strengthPercent;
        this.netBuyShares = netBuyShares;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public BigDecimal getStrengthPercent() {
        return strengthPercent;
    }

    public void setStrengthPercent(BigDecimal strengthPercent) {
        this.strengthPercent = strengthPercent;
    }

    public long getNetBuyShares() {
        return netBuyShares;
    }

    public void setNetBuyShares(long netBuyShares) {
        this.netBuyShares = netBuyShares;
    }
}
