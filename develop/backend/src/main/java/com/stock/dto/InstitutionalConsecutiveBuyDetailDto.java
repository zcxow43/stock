package com.stock.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * detail payload for an INSTITUTIONAL_CONSECUTIVE_BUY hit — see specs/backend/strategy-scan.md,
 * "法人連續買超". `foreign`/`trust` are {@code null} when that investor was not selected, or was
 * selected but did not itself buy every day of the window.
 */
public class InstitutionalConsecutiveBuyDetailDto {

    private LocalDate windowStartDate;
    private List<String> matchedInvestors;
    private InvestorConsecutiveBuyDto foreign;
    private InvestorConsecutiveBuyDto trust;

    public InstitutionalConsecutiveBuyDetailDto() {
    }

    public InstitutionalConsecutiveBuyDetailDto(LocalDate windowStartDate, List<String> matchedInvestors,
                                                 InvestorConsecutiveBuyDto foreign, InvestorConsecutiveBuyDto trust) {
        this.windowStartDate = windowStartDate;
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

    public List<String> getMatchedInvestors() {
        return matchedInvestors;
    }

    public void setMatchedInvestors(List<String> matchedInvestors) {
        this.matchedInvestors = matchedInvestors;
    }

    public InvestorConsecutiveBuyDto getForeign() {
        return foreign;
    }

    public void setForeign(InvestorConsecutiveBuyDto foreign) {
        this.foreign = foreign;
    }

    public InvestorConsecutiveBuyDto getTrust() {
        return trust;
    }

    public void setTrust(InvestorConsecutiveBuyDto trust) {
        this.trust = trust;
    }
}
