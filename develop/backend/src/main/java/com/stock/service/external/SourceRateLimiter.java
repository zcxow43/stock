package com.stock.service.external;

import com.stock.config.BackfillProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces, independently per source code, a minimum interval between consecutive actual HTTP
 * attempts against that source (spec: 節流的單位是「來源」...每個來源各自維持至少 0.5 秒的請求間隔). A single
 * Spring-managed instance is shared by every concurrent backfill worker, so N workers in flight
 * compete for the SAME per-source gate rather than each keeping their own clock — a per-worker
 * throttle would silently turn the configured interval into 1/N of itself, which is the one
 * implementation mistake the spec calls out by name (並行執行下的封鎖處置).
 *
 * Deliberately separate from {@link SourceAvailabilityTracker}: that tracks whether a source may
 * be called AT ALL right now (block / no-block); this tracks how DENSELY it may be called once it
 * is available. The two are independent per spec (兩個來源各自獨立計算請求間隔、退避與封鎖狀態) — a source
 * being throttled here never marks it unavailable there, and vice versa — and {@link
 * PriceHistoryFetcher} consults each for a different question.
 */
@Component
public class SourceRateLimiter {

    private final BackfillProperties properties;

    // One lock object per source code: consecutive callers to the SAME source serialize on it so
    // "read the last request time, maybe sleep, then record the new request time" is atomic
    // across threads. Different sources never contend with each other's lock, so one source being
    // throttled/slow never delays the other (spec: 兩個來源各自獨立計算請求間隔).
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastRequestAtMillis = new ConcurrentHashMap<>();

    public SourceRateLimiter(BackfillProperties properties) {
        this.properties = properties;
    }

    /**
     * Blocks the calling thread, if necessary, until at least
     * {@code app.backfill.rate-limit.interval-ms} has elapsed since the start of the last call to
     * this source made by ANY worker, then records this call's start time. Call this immediately
     * before each actual request attempt against {@code sourceCode} — including a timeout retry
     * of the same stock — never once per stock, so the interval genuinely reflects the request
     * rate the source sees.
     */
    public void acquire(String sourceCode) {
        acquire(sourceCode, properties.getRateLimit().getIntervalMs());
    }

    /**
     * Same as {@link #acquire(String)}, but with an explicit minimum interval instead of {@link
     * BackfillProperties}'s shared one — used by sources that need their own configured spacing
     * (spec: 富果相鄰兩次請求的間隔不小於 1 秒，數值取自設定；與 Yahoo 各自計算間隔). The lock/last-call-time map is
     * still keyed by {@code sourceCode} alone, so this and {@link #acquire(String)} on the SAME
     * source code still serialize against each other.
     */
    public void acquire(String sourceCode, long intervalMs) {
        Object lock = locks.computeIfAbsent(sourceCode, code -> new Object());
        synchronized (lock) {
            Long last = lastRequestAtMillis.get(sourceCode);
            if (last != null) {
                long waitMs = (last + intervalMs) - System.currentTimeMillis();
                if (waitMs > 0) {
                    sleep(waitMs);
                }
            }
            lastRequestAtMillis.put(sourceCode, System.currentTimeMillis());
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Source rate limiter interrupted", e);
        }
    }

    /** Test-only reset hook; never invoked in production code. */
    public void reset() {
        lastRequestAtMillis.clear();
    }
}
