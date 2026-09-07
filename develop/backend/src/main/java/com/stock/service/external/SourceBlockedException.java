package com.stock.service.external;

/**
 * Thrown by a {@link PriceHistorySource} when it reports a block-class response for the calling
 * IP: HTTP 403, HTTP 429, or a response body indicating quota/ban (spec: 封鎖類回應的判定). The
 * caller ({@link PriceHistoryFetcher}) must mark this source unavailable until
 * {@link #getRetryAfterSeconds()} (or a configured default cooldown, when absent) elapses, and
 * must never attribute this to the stock being fetched — it must not touch that stock's progress
 * or attempt_count (spec: 封鎖絕不消耗 attempt_count).
 */
public class SourceBlockedException extends RuntimeException {

    private final Long retryAfterSeconds;

    public SourceBlockedException(String message, Long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Seconds until the block expires, taken from the response's {@code retry_after}; null when absent. */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
