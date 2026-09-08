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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrently works through the target stock list for a PRICE_BACKFILL batch (spec: 逐檔抓取由序列
 * 改為 8 檔並行). Up to {@code app.backfill.executor-pool-size} stocks (default 8) are in flight at
 * once: a fixed pool of workers ({@code backfillExecutor}) each pull the next unclaimed index off
 * a shared cursor and run {@link #processOne} for it. The request interval between calls to the
 * SAME external source stays a single batch-wide throttle shared by every worker
 * ({@link PriceHistoryFetcher} / {@code SourceRateLimiter}) rather than becoming N independent
 * per-worker clocks — the one implementation mistake that would silently turn the configured
 * interval into 1/N of itself (spec: 節流的單位是「來源」...不因並行度而縮成設定值的 1/8).
 *
 * Per-stock history now comes from {@link PriceHistoryFetcher}, which tries the configured
 * sources (Yahoo, then FinMind) in priority order and switches between them on a block or a 404
 * (spec: 來源選擇與封鎖切換) — see {@link #processOne} for how its three outcomes (success, "all
 * sources blocked", "no source has this symbol") map onto this batch's progress transitions.
 * That block state ({@code SourceAvailabilityTracker}) is itself already batch-wide/shared (a
 * single Spring bean), so every worker sees the same source availability regardless of which one
 * discovered the block (spec: 來源的封鎖狀態是全批共用的單一狀態).
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
    private final Executor backfillExecutor;

    public BackfillRunner(PriceHistoryFetcher priceHistoryFetcher, StockMapper stockMapper,
                           StockSyncProgressMapper progressMapper, PriceIngestionService priceIngestionService,
                           JobRunningRegistry jobRunningRegistry, BackfillProperties properties,
                           @Qualifier("backfillExecutor") Executor backfillExecutor) {
        this.priceHistoryFetcher = priceHistoryFetcher;
        this.stockMapper = stockMapper;
        this.progressMapper = progressMapper;
        this.priceIngestionService = priceIngestionService;
        this.jobRunningRegistry = jobRunningRegistry;
        this.properties = properties;
        this.backfillExecutor = backfillExecutor;
    }

    /**
     * Returns a future that completes once the whole batch (every stock in the list, success or
     * failure) has actually finished — not merely once it has been scheduled. Callers that need
     * to chain work onto the batch's real completion (e.g. StartupCatchUpRunner triggering the
     * indicator rebuild only after price catch-up is done) must observe this future rather than
     * treating the return of the method call itself as completion, since this method is async.
     *
     * Dispatched on {@code backfillDispatchExecutor} — a dedicated single-thread executor, never
     * {@code backfillExecutor} itself. The per-stock workers this method fans out onto (see
     * {@link #runConcurrently}) run on {@code backfillExecutor}; dispatching this coordinator
     * there too would permanently occupy one of that pool's N worker slots for the whole batch,
     * leaving only N-1 stocks ever truly in flight.
     */
    @Async("backfillDispatchExecutor")
    public CompletableFuture<Void> run(String jobType, List<String> processableStockIds, boolean resume) {
        try {
            runConcurrently(jobType, processableStockIds, resume);
        } finally {
            jobRunningRegistry.finish(jobType);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Fans the target list out over a fixed pool of workers (size = the configured executor pool
     * size, capped at the list size so a small batch never spins up idle workers), each pulling
     * the next unclaimed index off a shared cursor. This is what keeps the per-source throttle
     * (spec: 節流的單位是「來源」) meaningful under concurrency: every worker calls into the SAME
     * {@link PriceHistoryFetcher}, which enforces one shared interval per source rather than
     * handing each worker its own.
     *
     * "All sources blocked" (spec: 兩個來源同時不可用時...結束本批作業，指的是不再領取新的標的) is modeled as a
     * shared flag: whichever worker discovers it first stops every worker from claiming new work,
     * but does not cancel work already claimed — each worker finishes whatever stock it is
     * currently processing normally (spec: 已在處理中的標的照常跑完，全部收斂後作業才回報正常結束).
     */
    private void runConcurrently(String jobType, List<String> stockIds, boolean resume) {
        if (stockIds.isEmpty()) {
            return;
        }
        int workerCount = Math.max(1, Math.min(properties.getExecutorPoolSize(), stockIds.size()));
        AtomicInteger cursor = new AtomicInteger(0);
        AtomicBoolean stopTakingNewWork = new AtomicBoolean(false);

        List<CompletableFuture<Void>> workers = new ArrayList<>(workerCount);
        for (int w = 0; w < workerCount; w++) {
            workers.add(CompletableFuture.runAsync(
                    () -> workerLoop(jobType, stockIds, resume, cursor, stopTakingNewWork), backfillExecutor));
        }
        // Waits for every worker to drain its share (or stop) before this batch is considered
        // finished; join() is safe here because this whole method already runs off the caller's
        // thread (see run()'s @Async dispatch).
        CompletableFuture.allOf(workers.toArray(new CompletableFuture[0])).join();
    }

    private void workerLoop(String jobType, List<String> stockIds, boolean resume, AtomicInteger cursor,
                             AtomicBoolean stopTakingNewWork) {
        while (!stopTakingNewWork.get()) {
            int i = cursor.getAndIncrement();
            if (i >= stockIds.size()) {
                return;
            }
            String stockId = stockIds.get(i);
            try {
                processOne(jobType, stockId, resume);
            } catch (AllSourcesBlockedException e) {
                // Not a stock failure (spec: 兩個來源同時不可用時...結束本批作業，非拋出例外的內部信號):
                // this stock has already been reverted to PENDING by processOne. Every source
                // being blocked right now won't un-block itself for the rest of this batch, so
                // stop the whole batch from claiming new work here; other workers' in-flight
                // stocks still run to completion, and the remaining stocks simply stay PENDING
                // for the next resume/catchUp call to pick up once a source recovers.
                stopTakingNewWork.set(true);
                log.info("Ending PRICE_BACKFILL batch early: {}. Remaining stock(s) stay PENDING "
                        + "for a later resume/catchUp.", e.getMessage());
                return;
            } catch (Throwable e) {
                // A single stock's failure (of any kind, including a bug surfacing as an Error)
                // must never abort the rest of the batch (spec: 任一檔失敗只影響該檔進度，不中止整批).
                log.warn("Backfill failed for stock {} ({})", stockId, jobType, e);
                safeMarkFailed(stockId, jobType, e);
            }
        }
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

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
