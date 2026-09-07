package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.domain.Stock;
import com.stock.domain.StockSyncProgress;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockSyncProgressMapper;
import com.stock.service.external.AllSourcesBlockedException;
import com.stock.service.external.PriceHistoryFetcher;
import com.stock.service.external.SymbolNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Sequentially works through the target stock list for a PRICE_BACKFILL batch, applying
 * the configured request interval and exponential-backoff retry against the per-stock
 * history source. Runs off the request thread (spec: 202 Accepted, async job).
 *
 * Per-stock history now comes from {@link PriceHistoryFetcher}, which tries the configured
 * sources (Yahoo, then FinMind) in priority order and switches between them on a block or a 404
 * (spec: 來源選擇與封鎖切換) — see {@link #processOne} for how its three outcomes (success, "all
 * sources blocked", "no source has this symbol") map onto this batch's progress transitions.
 */
@Component
public class BackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final PriceHistoryFetcher priceHistoryFetcher;
    private final StockMapper stockMapper;
    private final StockSyncProgressMapper progressMapper;
    private final PriceIngestionService priceIngestionService;
    private final JobRunningRegistry jobRunningRegistry;
    private final BackfillProperties properties;

    public BackfillRunner(PriceHistoryFetcher priceHistoryFetcher, StockMapper stockMapper,
                           StockSyncProgressMapper progressMapper, PriceIngestionService priceIngestionService,
                           JobRunningRegistry jobRunningRegistry, BackfillProperties properties) {
        this.priceHistoryFetcher = priceHistoryFetcher;
        this.stockMapper = stockMapper;
        this.progressMapper = progressMapper;
        this.priceIngestionService = priceIngestionService;
        this.jobRunningRegistry = jobRunningRegistry;
        this.properties = properties;
    }

    /**
     * Returns a future that completes once the whole batch (every stock in the list, success or
     * failure) has actually finished — not merely once it has been scheduled. Callers that need
     * to chain work onto the batch's real completion (e.g. StartupCatchUpRunner triggering the
     * indicator rebuild only after price catch-up is done) must observe this future rather than
     * treating the return of the method call itself as completion, since this method is async.
     */
    @Async("backfillExecutor")
    public CompletableFuture<Void> run(String jobType, List<String> processableStockIds, boolean resume) {
        try {
            for (int i = 0; i < processableStockIds.size(); i++) {
                String stockId = processableStockIds.get(i);
                try {
                    processOne(jobType, stockId, resume);
                } catch (AllSourcesBlockedException e) {
                    // Not a stock failure (spec: 兩個來源同時不可用時...結束本批作業，非拋出例外的內部信號):
                    // this stock has already been reverted to PENDING by processOne. Every source
                    // being blocked right now won't un-block itself for the rest of this batch
                    // (the next stock would only hit the exact same wall), so end the batch here
                    // as a normal completion; the remaining stocks simply stay PENDING for the
                    // next resume/catchUp call to pick up once a source recovers.
                    log.info("Ending PRICE_BACKFILL batch early: {}. {} remaining stock(s) stay PENDING "
                            + "for a later resume/catchUp.", e.getMessage(), processableStockIds.size() - i);
                    break;
                } catch (Throwable e) {
                    // A single stock's failure (of any kind, including a bug surfacing as an Error)
                    // must never abort the rest of the batch (spec: 任一檔失敗只影響該檔進度，不中止整批).
                    log.warn("Backfill failed for stock {} ({})", stockId, jobType, e);
                    safeMarkFailed(stockId, jobType, e);
                }
                if (i < processableStockIds.size() - 1) {
                    sleep(properties.getRateLimit().getIntervalMs());
                }
            }
        } finally {
            jobRunningRegistry.finish(jobType);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void processOne(String jobType, String stockId, boolean resume) {
        StockSyncProgress progress = progressMapper.findOne(stockId, jobType);

        LocalDate fetchStart = resume && progress.getLastSyncedDate() != null
                ? progress.getLastSyncedDate().plusDays(1)
                : progress.getTargetStartDate();
        LocalDate fetchEnd = progress.getTargetEndDate();

        if (fetchStart.isAfter(fetchEnd)) {
            progressMapper.markDone(stockId, jobType, fetchEnd);
            return;
        }

        progressMapper.markRunning(stockId, jobType);

        Stock stock = stockMapper.findById(stockId);
        String market = stock != null ? stock.getMarket() : null;

        PriceHistoryFetcher.FetchOutcome outcome;
        try {
            outcome = priceHistoryFetcher.fetchHistory(stockId, market, fetchStart, fetchEnd);
        } catch (AllSourcesBlockedException e) {
            // Revert to exactly the pre-attempt state (spec: 不動這一檔的進度、不累加 attempt_count):
            // PENDING, not RUNNING, with attempt_count/last_error/last_synced_date untouched.
            progressMapper.markPending(stockId, jobType);
            throw e;
        } catch (SymbolNotFoundException e) {
            // Every source reported no such symbol (spec: 所有來源都表示查無此標的才 SKIPPED).
            progressMapper.markSkippedThrough(stockId, jobType, fetchEnd);
            return;
        }

        if (outcome.getRows().isEmpty()) {
            // A source successfully answered with zero rows: no trading data in this range (e.g.
            // it lands entirely on a weekend/holiday) — a normal completion, not "not found".
            // Still record last_synced_date = fetchEnd so a later catchUp for the same endDate
            // recognizes this range as already processed and skips it without a request.
            progressMapper.markSkippedThrough(stockId, jobType, fetchEnd);
        } else {
            priceIngestionService.applyBackfillResult(stockId, jobType, outcome.getRows(), fetchEnd,
                    outcome.getSourceCode());
        }
    }

    private void safeMarkFailed(String stockId, String jobType, Throwable e) {
        try {
            progressMapper.markFailed(stockId, jobType, truncate(e.getMessage()));
        } catch (Exception markFailureError) {
            log.error("Failed to record failure for stock {} ({})", stockId, jobType, markFailureError);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Backfill interrupted", e);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
