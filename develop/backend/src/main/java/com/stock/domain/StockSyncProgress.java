package com.stock.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class StockSyncProgress {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String JOB_PRICE_BACKFILL = "PRICE_BACKFILL";
    public static final String JOB_INDICATOR_REBUILD = "INDICATOR_REBUILD";

    private String stockId;
    private String jobType;
    private String status;
    private LocalDate targetStartDate;
    private LocalDate targetEndDate;
    private LocalDate lastSyncedDate;
    private int attemptCount;
    private String lastError;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getTargetStartDate() {
        return targetStartDate;
    }

    public void setTargetStartDate(LocalDate targetStartDate) {
        this.targetStartDate = targetStartDate;
    }

    public LocalDate getTargetEndDate() {
        return targetEndDate;
    }

    public void setTargetEndDate(LocalDate targetEndDate) {
        this.targetEndDate = targetEndDate;
    }

    public LocalDate getLastSyncedDate() {
        return lastSyncedDate;
    }

    public void setLastSyncedDate(LocalDate lastSyncedDate) {
        this.lastSyncedDate = lastSyncedDate;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
