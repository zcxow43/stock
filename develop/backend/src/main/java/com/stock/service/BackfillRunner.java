package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.domain.StockSyncProgress;
import com.stock.mapper.StockSyncProgressMapper;
import com.stock.service.external.FinMindClient;
import com.stock.service.external.RateLimitedException;
import com.stock.service.external.dto.NormalizedPriceRow;
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
 */
@Component
public class BackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final FinMindClient finMindClient;
    private final StockSyncProgressMapper progressMapper;
    private final PriceIngestionService priceIngestionService;
    private final JobRunningRegistry jobRunningRegistry;
    private final BackfillProperties properties;

    public BackfillRunner(FinMindClient finMindClient, StockSyncProgressMapper progressMapper,
                           PriceIngestionService priceIngestionService, JobRunningRegistry jobRunningRegistry,
                           BackfillProperties properties) {
        this.finMindClient = finMindClient;
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
                // A single stock's failure (of any kind, including a bug surfacing as an Error)
                // must never abort the rest of the batch (spec: 任一檔失敗只影響該檔進度，不中止整批).
                try {
                    processOne(jobType, stockId, resume);
                } catch (Throwable e) {
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
        progressMapper.markRunning(stockId, jobType);
        StockSyncProgress progress = progressMapper.findOne(stockId, jobType);

        LocalDate fetchStart = resume && progress.getLastSyncedDate() != null
                ? progress.getLastSyncedDate().plusDays(1)
                : progress.getTargetStartDate();
        LocalDate fetchEnd = progress.getTargetEndDate();

        if (fetchStart.isAfter(fetchEnd)) {
            progressMapper.markDone(stockId, jobType, fetchEnd);
            return;
        }

        List<NormalizedPriceRow> rows = fetchWithRetry(stockId, fetchStart, fetchEnd);
        if (rows.isEmpty()) {
            // No trading data in the queried range (e.g. it lands entirely on a weekend/holiday).
            // Still record last_synced_date = fetchEnd so a later catchUp for the same endDate
            // recognizes this range as already processed and skips it without a request.
            progressMapper.markSkippedThrough(stockId, jobType, fetchEnd);
        } else {
            priceIngestionService.applyBackfillResult(stockId, jobType, rows, fetchEnd);
        }
    }

    private void safeMarkFailed(String stockId, String jobType, Throwable e) {
        try {
            progressMapper.markFailed(stockId, jobType, truncate(e.getMessage()));
        } catch (Exception markFailureError) {
            log.error("Failed to record failure for stock {} ({})", stockId, jobType, markFailureError);
        }
    }

    private List<NormalizedPriceRow> fetchWithRetry(String stockId, LocalDate startDate, LocalDate endDate) {
        BackfillProperties.RateLimit rateLimit = properties.getRateLimit();
        long backoffMs = rateLimit.getInitialBackoffMs();
        int attempt = 0;
        while (true) {
            try {
                return finMindClient.fetchHistory(stockId, startDate, endDate);
            } catch (RateLimitedException e) {
                attempt++;
                if (attempt > rateLimit.getMaxRetries()) {
                    throw e;
                }
                log.info("Rate limited fetching {} (attempt {}/{}), backing off {}ms",
                        stockId, attempt, rateLimit.getMaxRetries(), backoffMs);
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
