package com.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Rate-control settings for the per-stock backfill path. Concurrency (executorPoolSize) and the
 * per-source request interval (rateLimit.intervalMs) are separate knobs and must stay that way
 * (spec: 並行度與間隔管的是不同的事，不可互相替代): concurrency only bounds how many stocks are in
 * flight at once, never how densely either external source is actually called — that density is
 * governed solely by the shared, batch-wide {@code SourceRateLimiter}, not by the worker count.
 * Defaults (8 in flight, >=0.5s between requests to the SAME source) come straight from
 * specs/backend/stock-price-ingestion.md and are a deliberate trade-off, not a knob to raise
 * without first confirming the source's actual tolerance.
 */
@Component
@ConfigurationProperties(prefix = "app.backfill")
public class BackfillProperties {

    private int executorPoolSize = 8;
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
        // Per-source minimum interval, shared batch-wide across every concurrent worker (spec:
        // 節流的單位是「來源」...每個來源各自維持至少 0.5 秒的請求間隔 — at most 2 req/s per source).
        private long intervalMs = 500;
        private int maxRetries = 5;
        private long initialBackoffMs = 2000;
        private int backoffMultiplier = 2;
        // Used only when a block-class response (HTTP 403/429 or a quota-indicating body) doesn't
        // itself carry a retry_after value (spec: 到期時間的取得 — 未提供時採可設定的預設冷卻時間).
        // Conservative default: 10 minutes, consistent with this spec's "預設值以保守為準" principle.
        private long defaultBlockCooldownSeconds = 600;

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

        public long getDefaultBlockCooldownSeconds() {
            return defaultBlockCooldownSeconds;
        }

        public void setDefaultBlockCooldownSeconds(long defaultBlockCooldownSeconds) {
            this.defaultBlockCooldownSeconds = defaultBlockCooldownSeconds;
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
