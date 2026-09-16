package com.stock.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * detail payload for an INSTITUTIONAL_STRENGTH_RANK hit — see specs/backend/strategy-scan.md,
 * "法人買超強度排名". `foreign`/`trust` are {@code null} when that investor was not selected, or was
 * selected but did not itself land in the top `topN` on this hit's signal day.
 */
public class InstitutionalStrengthRankDetailDto {

    private LocalDate windowStartDate;
    private long volumeShares;
    private List<String> matchedInvestors;
    private InvestorStrengthDto foreign;
    private InvestorStrengthDto trust;

    public InstitutionalStrengthRankDetailDto() {
    }

    public InstitutionalStrengthRankDetailDto(LocalDate windowStartDate, long volumeShares,
                                               List<String> matchedInvestors, InvestorStrengthDto foreign,
                                               InvestorStrengthDto trust) {
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

    public InvestorStrengthDto getForeign() {
        return foreign;
    }

    public void setForeign(InvestorStrengthDto foreign) {
        this.foreign = foreign;
    }

    public InvestorStrengthDto getTrust() {
        return trust;
    }

    public void setTrust(InvestorStrengthDto trust) {
        this.trust = trust;
    }
}
