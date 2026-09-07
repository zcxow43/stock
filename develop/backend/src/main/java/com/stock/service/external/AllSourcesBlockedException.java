package com.stock.service.external;

/**
 * Signals that every configured {@link PriceHistorySource} is currently blocked (or otherwise
 * unavailable) for a stock's fetch attempt (spec: 兩個來源同時不可用). This is not a stock failure:
 * the caller ({@link com.stock.service.BackfillRunner}) must leave the stock's progress
 * untouched/PENDING, never mark it FAILED, never increment attempt_count, and end the whole batch
 * as a normal completion — the remaining stocks stay PENDING for the next resume/catchUp call.
 */
public class AllSourcesBlockedException extends RuntimeException {

    public AllSourcesBlockedException(String message) {
        super(message);
    }
}
