package com.stock.util;

import java.util.regex.Pattern;

/**
 * Single, shared definition of "普通股" (ordinary/common share) used across the whole system: a
 * stock code that is exactly 4 numeric digits with a non-zero leading digit — excludes ETFs
 * (leading 0, e.g. {@code 0050}/{@code 00878}), special shares (letter suffix, e.g.
 * {@code 2881A}), and TDRs/other non-4-digit codes (e.g. {@code 910322}).
 *
 * MySQL 8's regex engine (ICU-based since 8.0.4) understands the same {@code \d} escape as Java's
 * {@link Pattern}, so {@link #REGEX} is reused verbatim both for in-JVM matching
 * ({@link #isCommonStockCode(String)}) and, passed as a bind parameter, for a MySQL {@code REGEXP}
 * predicate (see {@code StockMapper.xml}'s {@code commonStocksOnly} filter) — there is exactly one
 * place this pattern is written down, per specs/backend/stock-universe-import.md's write-side
 * filter and specs/backend/stock-catalog.md's read-side {@code commonStocksOnly} query filter.
 */
public final class CommonStockCodeUtil {

    public static final String REGEX = "^[1-9]\\d{3}$";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private CommonStockCodeUtil() {
    }

    public static boolean isCommonStockCode(String stockId) {
        return stockId != null && PATTERN.matcher(stockId).matches();
    }
}
