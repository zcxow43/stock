package com.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Rate-control settings for the per-stock backfill path. Defaults are intentionally
 * conservative (sequential execution, >=1s between requests) per
 * specs/backend/stock-price-ingestion.md — the external data source blocks callers
 * that hammer it without throttling.
 */
@Component
@ConfigurationProperties(prefix = "app.backfill")
public class BackfillProperties {

    private int executorPoolSize = 1;
    private int maxAttemptCount = 5;
    private final RateLimit rateLimit = new RateLimit();
    private final StartupCatchUp startupCatchUp = new StartupCatchUp();

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

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public StartupCatchUp getStartupCatchUp() {
        return startupCatchUp;
    }

    public static class RateLimit {
        private long intervalMs = 1000;
        private int maxRetries = 5;
        private long initialBackoffMs = 2000;
        private int backoffMultiplier = 2;

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public int getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(int backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }
    }

    /**
     * Startup auto catch-up settings (spec: 啟動時自動補齊). Enabled by default so a freshly
     * reset database self-populates without any manual call; must be disabled in the test
     * profile so tests never depend on the real external sources being reachable.
     */
    public static class StartupCatchUp {
        private boolean enabled = true;
        // Without an explicit ISO format, Spring binds this with a locale-sensitive
        // formatter; on a zh_TW machine that rejects "2026-01-01" and aborts startup.
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate startDate = LocalDate.of(2026, 1, 1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public LocalDate getStartDate() {
            return startDate;
        }

        public void setStartDate(LocalDate startDate) {
            this.startDate = startDate;
        }
    }
}
