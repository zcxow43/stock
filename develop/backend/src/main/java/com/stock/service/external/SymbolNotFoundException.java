package com.stock.service.external;

/**
 * Thrown by a {@link PriceHistorySource} when it reports "no such symbol" (HTTP 404). This is
 * ambiguous on its own — Yahoo returns the identical 404 for a genuinely delisted/nonexistent
 * stock and for a correct symbol with the wrong market suffix (spec: Yahoo 的 404 帶有歧義，不得逕自
 * 判定為下市) — so a single source reporting this must never be treated as a conclusion.
 * {@link PriceHistoryFetcher} retries the same stock on the next source; only when every source
 * reports this does the stock get marked {@code SKIPPED}.
 */
public class SymbolNotFoundException extends RuntimeException {

    public SymbolNotFoundException(String message) {
        super(message);
    }
}
