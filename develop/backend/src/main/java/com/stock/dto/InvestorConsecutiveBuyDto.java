package com.stock.dto;

/**
 * INSTITUTIONAL_CONSECUTIVE_BUY's per-investor judged value — see specs/backend/strategy-scan.md,
 * "法人連續買超". `netBuyShares` is the window's net-buy-share total (always positive, since every day
 * in the window had a positive net buy for this hit to occur).
 */
public class InvestorConsecutiveBuyDto {

    private long netBuyShares;

    public InvestorConsecutiveBuyDto() {
    }

    public InvestorConsecutiveBuyDto(long netBuyShares) {
        this.netBuyShares = netBuyShares;
    }

    public long getNetBuyShares() {
        return netBuyShares;
    }

    public void setNetBuyShares(long netBuyShares) {
        this.netBuyShares = netBuyShares;
    }
}
