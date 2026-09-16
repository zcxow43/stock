package com.stock.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * detail payload for an INSTITUTIONAL_NET_RATIO hit — see specs/backend/strategy-scan.md,
 * "法人買賣超佔比". `foreign`/`trust` are {@code null} when that investor was not selected, or was
 * selected but did not itself clear `ratioPercent` on this hit's signal day.
 */
public class InstitutionalNetRatioDetailDto {

    private LocalDate windowStartDate;
    private long volumeShares;
    private List<String> matchedInvestors;
    private InvestorNetRatioDto foreign;
    private InvestorNetRatioDto trust;

    public InstitutionalNetRatioDetailDto() {
    }

    public InstitutionalNetRatioDetailDto(LocalDate windowStartDate, long volumeShares,
                                           List<String> matchedInvestors, InvestorNetRatioDto foreign,
                                           InvestorNetRatioDto trust) {
        this.windowStartDate = windowStartDate;
        this.volumeShares = volumeShares;
        this.matchedInvestors = matchedInvestors;
        this.foreign = foreign;
        this.trust = trust;
    }

    public LocalDate getWindowStartDate() {
        return windowStartDate;
    }

    public void setWindowStartDate(LocalDate windowStartDate) {
        this.windowStartDate = windowStartDate;
    }

    public long getVolumeShares() {
        return volumeShares;
    }

    public void setVolumeShares(long volumeShares) {
        this.volumeShares = volumeShares;
    }

    public List<String> getMatchedInvestors() {
        return matchedInvestors;
    }

    public void setMatchedInvestors(List<String> matchedInvestors) {
        this.matchedInvestors = matchedInvestors;
    }

    public InvestorNetRatioDto getForeign() {
        return foreign;
    }

    public void setForeign(InvestorNetRatioDto foreign) {
        this.foreign = foreign;
    }

    public InvestorNetRatioDto getTrust() {
        return trust;
    }

    public void setTrust(InvestorNetRatioDto trust) {
        this.trust = trust;
    }
}
