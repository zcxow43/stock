package com.stock.dto;

import java.time.LocalDate;

public class BackfillResponse {

    private final String jobType;
    private final int targetCount;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String mode;

    public BackfillResponse(String jobType, int targetCount, LocalDate startDate, LocalDate endDate, String mode) {
        this.jobType = jobType;
        this.targetCount = targetCount;
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
