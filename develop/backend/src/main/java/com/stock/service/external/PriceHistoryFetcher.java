package com.stock.service.external;

import com.stock.config.BackfillProperties;
import com.stock.service.external.dto.NormalizedPriceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Coordinates the two interchangeable {@link PriceHistorySource}s (spec: 來源選擇與封鎖切換). For one
 * stock's fetch, walks the sources in priority order (YAHOO -> FINMIND, spec: 來源順位):
 *
 * <ul>
 *   <li>A source currently marked blocked by {@link SourceAvailabilityTracker} is skipped
 *       entirely — never called (spec: 完全不對它發出請求，不是「發了再失敗」).</li>
 *   <li>A block-class response ({@link SourceBlockedException}) marks that source blocked and
 *       moves on to the next source for the SAME stock — never touches this stock's progress
 *       (spec: 封鎖絕不消耗 attempt_count).</li>
 *   <li>A 404 ({@link SymbolNotFoundException}) moves on to the next source too — a single
 *       source's 404 is not a conclusion (spec: Yahoo 的 404 帶有歧義).</li>
 *   <li>A timeout ({@link RateLimitedException}) is retried with backoff on the SAME source
 *       (spec: 非封鎖類的失敗...依既有規則退避重試); once retries are exhausted, or any other
 *       non-retryable failure occurs, this propagates straight out as a real failure for the
 *       caller ({@link com.stock.service.BackfillRunner}) to mark FAILED.</li>
 * </ul>
 *
 * After the loop: if any source was skipped/became blocked, there is no way to reach a confident
 * conclusion this run, so this throws {@link AllSourcesBlockedException} (spec: 所有來源皆不可用 —
 * 該檔維持或回到 PENDING，並結束本批作業). Only when every source was actually asked and all of them
 * answered 404 does this throw {@link SymbolNotFoundException} (spec: 所有來源都表示查無此標的才
 * SKIPPED).
 */
@Component
public class PriceHistoryFetcher {

    private static final Logger log = LoggerFactory.getLogger(PriceHistoryFetcher.class);

    private final List<PriceHistorySource> sourcesInPriorityOrder;
    private final SourceAvailabilityTracker availabilityTracker;
    private final SourceRateLimiter rateLimiter;
    private final BackfillProperties properties;

    public PriceHistoryFetcher(YahooFinanceClient yahooFinanceClient, FinMindClient finMindClient,
                                SourceAvailabilityTracker availabilityTracker, SourceRateLimiter rateLimiter,
                                BackfillProperties properties) {
        // Fixed priority order per spec (來源順位: YAHOO -> FINMIND). An explicit list, not
        // Spring's polymorphic List<PriceHistorySource> injection, so the order is obvious at the
        // call site and never depends on bean-ordering annotations.
        this.sourcesInPriorityOrder = List.of(yahooFinanceClient, finMindClient);
        this.availabilityTracker = availabilityTracker;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    public FetchOutcome fetchHistory(String stockId, String market, LocalDate startDate, LocalDate endDate) {
        boolean hadUnavailableSource = false;
        int attempted = 0;
        int notFoundCount = 0;

        for (PriceHistorySource source : sourcesInPriorityOrder) {
            if (!availabilityTracker.isAvailable(source.getCode())) {
                hadUnavailableSource = true;
                continue;
            }
            attempted++;
            try {
                List<NormalizedPriceRow> rows = fetchWithTimeoutRetry(source, stockId, market, startDate, endDate);
                return new FetchOutcome(rows, source.getCode());
            } catch (SourceBlockedException e) {
                log.info("{} blocked for stock {}: {}. Marking it unavailable and trying the next source.",
                        source.getCode(), stockId, e.getMessage());
                availabilityTracker.markBlocked(source.getCode(), e.getRetryAfterSeconds());
                hadUnavailableSource = true;
            } catch (SymbolNotFoundException e) {
                notFoundCount++;
            }
        }

        if (hadUnavailableSource) {
            throw new AllSourcesBlockedException(
                    "No price history source is currently available for stock " + stockId);
        }
        if (attempted > 0 && notFoundCount == attempted) {
            throw new SymbolNotFoundException(
                    "No configured price history source has data for stock " + stockId);
        }
        // Unreachable in practice (sourcesInPriorityOrder is never empty), but fail safe rather
        // than silently returning nothing.
        throw new AllSourcesBlockedException("No price history source is configured");
    }

    /**
     * Backoff-retries a timeout on the SAME source (spec: 非封鎖類的失敗...依既有規則退避重試). Every actual
     * attempt — including retries — goes through {@link SourceRateLimiter#acquire}, which is the
     * single, batch-wide gate every concurrent worker competes for (spec: 每來源的請求間隔在並行下仍是
     * 全批共用的節流); it is not consulted once per stock, only once per real request.
     */
    private List<NormalizedPriceRow> fetchWithTimeoutRetry(PriceHistorySource source, String stockId, String market,
                                                             LocalDate startDate, LocalDate endDate) {
        BackfillProperties.RateLimit rateLimit = properties.getRateLimit();
        long backoffMs = rateLimit.getInitialBackoffMs();
        int attempt = 0;
        while (true) {
            try {
                rateLimiter.acquire(source.getCode());
                return source.fetchDailyHistory(stockId, market, startDate, endDate);
            } catch (RateLimitedException e) {
                attempt++;
                if (attempt > rateLimit.getMaxRetries()) {
                    throw e;
                }
                log.info("{} timed out fetching {} (attempt {}/{}), backing off {}ms",
                        source.getCode(), stockId, attempt, rateLimit.getMaxRetries(), backoffMs);
                sleep(backoffMs);
                backoffMs *= rateLimit.getBackoffMultiplier();
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Price history fetch retry interrupted", e);
        }
    }

    /** One stock's successful fetch outcome: the rows, and which source actually supplied them. */
    public static final class FetchOutcome {
        private final List<NormalizedPriceRow> rows;
        private final String sourceCode;

        public FetchOutcome(List<NormalizedPriceRow> rows, String sourceCode) {
            this.rows = rows;
            this.sourceCode = sourceCode;
        }

        public List<NormalizedPriceRow> getRows() {
            return rows;
        }

        public String getSourceCode() {
            return sourceCode;
        }
    }
}
