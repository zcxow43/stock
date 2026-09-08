package com.stock.dto;

import java.time.LocalDate;

public class BackfillResponse {

    private final String jobType;
    private final int targetCount;
    private final int caughtUpCount;
    private final boolean commonStocksOnly;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String mode;

    public BackfillResponse(String jobType, int targetCount, int caughtUpCount, boolean commonStocksOnly,
                             LocalDate startDate, LocalDate endDate, String mode) {
        this.jobType = jobType;
        this.targetCount = targetCount;
        this.caughtUpCount = caughtUpCount;
        this.commonStocksOnly = commonStocksOnly;
        this.startDate = startDate;
        this.endDate = endDate;
        this.mode = mode;
    }

    public String getJobType() {
        return jobType;
    }

    public int getTargetCount() {
        return targetCount;
    }

    /**
     * Number of this batch's target stocks already synced through endDate at acceptance time —
     * these trigger zero external requests. Always 0 when catchUp is false. Exists because a
     * caught-up stock's progress row is left exactly as it was (still DONE, still its old
     * finished_at), so "did nothing" and "finished everything" are otherwise indistinguishable
     * from the progress table alone (spec: 「什麼都沒做」與「做完了」在進度表上長得一模一樣).
     */
    public int getCaughtUpCount() {
        return caughtUpCount;
    }

    /**
     * The common-stock population rule actually applied this run (spec: 母體預設普通股與並行抓取).
     * Always {@code false} for {@code SELECTED} mode (a named stockIds list never applies the
     * filter); for {@code ALL} mode it's {@code true} unless the request explicitly passed
     * {@code commonStocksOnly: false}. Exists so a caller can state what the population was when
     * {@code targetCount} is smaller than the "共 N 檔" total shown elsewhere on the same screen.
     */
    public boolean isCommonStocksOnly() {
        return commonStocksOnly;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getMode() {
        return mode;
    }
}
