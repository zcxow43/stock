package com.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the indicator rebuild batch job. Unlike price backfill, this job only reads/writes
 * our own database (stock_daily_price / stock_daily_indicator) — there is no external rate limit to
 * respect, so (unlike BackfillProperties) a modest thread pool is safe.
 */
@Component
@ConfigurationProperties(prefix = "app.indicator")
public class IndicatorProperties {

    private int executorPoolSize = 4;
    private int maxAttemptCount = 5;
    /** Trading days of lookback fetched before the requested output start date to let the recursion converge. */
    private int warmupTradingDays = 250;

    public int getExecutorPoolSize() {
        return executorPoolSize;
    }

    public void setExecutorPoolSize(int executorPoolSize) {
        this.executorPoolSize = executorPoolSize;
    }

    public int getMaxAttemptCount() {
        return maxAttemptCount;
    }

    public void setMaxAttemptCount(int maxAttemptCount) {
        this.maxAttemptCount = maxAttemptCount;
    }

    public int getWarmupTradingDays() {
        return warmupTradingDays;
    }

    public void setWarmupTradingDays(int warmupTradingDays) {
        this.warmupTradingDays = warmupTradingDays;
    }
}
