package com.stock.dto;

import java.util.List;

public class ProgressResponse {

    private final String jobType;
    private final int total;
    private final int pending;
    private final int running;
    private final int done;
    private final int failed;
    private final int skipped;
    private final List<FailedItemDto> failedItems;

    public ProgressResponse(String jobType, int total, int pending, int running, int done,
                             int failed, int skipped, List<FailedItemDto> failedItems) {
        this.jobType = jobType;
        this.total = total;
        this.pending = pending;
        this.running = running;
        this.done = done;
        this.failed = failed;
        this.skipped = skipped;
        this.failedItems = failedItems;
    }

    public String getJobType() {
        return jobType;
    }

    public int getTotal() {
        return total;
    }

    public int getPending() {
        return pending;
    }

    public int getRunning() {
        return running;
    }

    public int getDone() {
        return done;
    }

    public int getFailed() {
        return failed;
    }

    public int getSkipped() {
        return skipped;
    }

    public List<FailedItemDto> getFailedItems() {
        return failedItems;
    }
}
