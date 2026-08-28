package com.stock.exception;

/**
 * Raised internally by IndicatorRebuildRunner when an INCREMENTAL step cannot find the previous
 * trading day's indicator row for a stock (a gap in the recursive chain, or a stock that has never
 * been rebuilt at all). Never surfaces as an HTTP error — the runner catches it exactly like any
 * other per-stock failure and marks that stock's stock_sync_progress row FAILED with an explanatory
 * message, so a subsequent FULL rebuild can pick it up. It must never be silently swallowed by
 * seeding the recursion with a default value (spec: "不得以初始值 50 起算補上").
 */
public class NeedsFullRebuildException extends RuntimeException {

    public NeedsFullRebuildException(String stockId) {
        super("NEEDS_FULL_REBUILD: no previous indicator state found for stock " + stockId
                + "; incremental mode cannot proceed without it");
    }
}
