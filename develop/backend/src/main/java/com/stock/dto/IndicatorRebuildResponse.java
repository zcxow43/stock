package com.stock.dto;

public class IndicatorRebuildResponse {

    private final String jobType;
    private final int targetCount;
    private final String mode;
    private final String computeMode;
    private final int warmupTradingDays;
    private final String paramKey;

    public IndicatorRebuildResponse(String jobType, int targetCount, String mode, String computeMode,
                                     int warmupTradingDays, String paramKey) {
        this.jobType = jobType;
        this.targetCount = targetCount;
        this.mode = mode;
        this.computeMode = computeMode;
        this.warmupTradingDays = warmupTradingDays;
        this.paramKey = paramKey;
    }

    public String getJobType() {
        return jobType;
    }

    public int getTargetCount() {
        return targetCount;
    }

    public String getMode() {
        return mode;
    }

    public String getComputeMode() {
        return computeMode;
    }

    public int getWarmupTradingDays() {
        return warmupTradingDays;
    }

    public String getParamKey() {
        return paramKey;
    }
}
