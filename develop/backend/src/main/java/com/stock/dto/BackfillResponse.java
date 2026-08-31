package com.stock.dto;

import java.time.LocalDate;

public class BackfillResponse {

    private final String jobType;
    private final int targetCount;
    private final int caughtUpCount;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String mode;

    public BackfillResponse(String jobType, int targetCount, int caughtUpCount, LocalDate startDate,
                             LocalDate endDate, String mode) {
        this.jobType = jobType;
        this.targetCount = targetCount;
        this.caughtUpCount = caughtUpCount;
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
