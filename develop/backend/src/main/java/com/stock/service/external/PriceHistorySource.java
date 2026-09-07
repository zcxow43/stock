package com.stock.service.external;

import com.stock.service.external.dto.NormalizedPriceRow;

import java.time.LocalDate;
import java.util.List;

/**
 * One interchangeable per-stock daily-history data source (spec: 逐檔歷史有兩個可互換的外部來源).
 * Implementations are {@link YahooFinanceClient} (priority source) and {@link FinMindClient}
 * (fallback source) — see {@link PriceHistoryFetcher} for the priority-ordered, block-aware
 * selection between them.
 *
 * <p>Every implementation must classify its own failures using the exceptions in this package
 * so {@link PriceHistoryFetcher} can react correctly without knowing which concrete source it is
 * talking to:
 * <ul>
 *   <li>{@link SourceBlockedException} — this source has throttled/banned this IP (HTTP 403/429
 *       or a body indicating quota); the caller must stop calling this source until the block
 *       expires and try the next source instead (spec: 封鎖絕不消耗 attempt_count).</li>
 *   <li>{@link SymbolNotFoundException} — this source reports no data at all for the symbol,
 *       which is ambiguous (spec: Yahoo 的 404 帶有歧義) and must not be trusted on its own.</li>
 *   <li>{@link RateLimitedException} — a transient, non-block failure (timeout) that the caller
 *       may retry with backoff on the same source.</li>
 *   <li>{@link ExternalApiException} — any other non-retryable failure.</li>
 * </ul>
 */
public interface PriceHistorySource {

    String SOURCE_YAHOO = "YAHOO";
    String SOURCE_FINMIND = "FINMIND";

    /** This source's code, as recorded in {@code stock_daily_price.source}. */
    String getCode();

    /**
     * Fetches normalized daily price rows for one stock in [startDate, endDate]. Dates with no
     * trading data are simply absent from the result (never zero-filled). {@code market} is
     * {@code stock.market} ("TSE"/"OTC"); sources that don't need it (FinMind) ignore it.
     */
    List<NormalizedPriceRow> fetchDailyHistory(String stockId, String market, LocalDate startDate, LocalDate endDate);
}
