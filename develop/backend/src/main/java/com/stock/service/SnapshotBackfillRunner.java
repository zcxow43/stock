package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.domain.Stock;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockSyncProgressMapper;
import com.stock.service.external.ExternalApiException;
import com.stock.service.external.ExternalApiMalformedException;
import com.stock.service.external.RateLimitedException;
import com.stock.service.external.SourceAvailabilityTracker;
import com.stock.service.external.SourceBlockedException;
import com.stock.service.external.SourceRateLimiter;
import com.stock.service.external.TwseMiIndexClient;
import com.stock.service.external.dto.NormalizedPriceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The ALL-mode backfill's primary fetch strategy (spec: 逐日全市場快照為 ALL 模式主路徑): walks the
 * candidate trading-day range ONE DAY AT A TIME, issuing a single {@link TwseMiIndexClient}
 * request per day that covers the whole target population, instead of {@link BackfillRunner}'s one
 * request PER STOCK. Runs sequentially — no worker pool — because the request count here is
 * already an order of magnitude smaller (trading days, not stock count); parallelizing it would
 * only have workers queue on the same per-source rate gate for no benefit (spec: 逐日路徑循序執行，
 * 不套用逐檔路徑的並行度設定).
 *
 * <p><b>Market split</b> (spec: 逐日回補的處理流程 — 母體與區間解析完成後，依 {@code stock.market} 拆成上市標的
 * 與上櫃標的): MI_INDEX is a 證交所 endpoint and only ever returns listed (TSE) rows, so an OTC target
 * can never be served by the day-by-day snapshot at all. OTC targets are therefore excluded from
 * the day loop entirely — including from its {@code last_synced_date} advancement, which would
 * otherwise record days the snapshot provably did not cover for them and make {@code catchUp} skip
 * those days forever — and are handed to {@link BackfillRunner}'s per-stock Yahoo/FinMind pipeline
 * after the loop, under exactly {@code SELECTED}'s rules (spec: step 7).
 *
 * <p>Progress stays PENDING for the whole population throughout the loop and flips to DONE only
 * once every candidate day has been processed (spec: 逐日回補的處理流程 step 6 /
 * 進度的顯示語意刻意維持逐檔不變's "中途看起來像沒有進展" trade-off) — {@code last_synced_date} is what
 * actually advances day by day (via {@link com.stock.service.PriceIngestionService#applySnapshotDay}),
 * which is what {@code catchUp} and a crash-resume both key off.
 *
 * <p><b>Degradation</b> (spec: 逐日快照不可用時降級為逐檔): the moment MI_INDEX is blocked (403/429)
 * or otherwise fails persistently, this stops the day loop — it does NOT end the batch — and hands
 * every not-yet-caught-up target off to {@link BackfillRunner#runConcurrently}, the exact same
 * per-stock Yahoo-then-FinMind pipeline {@code SELECTED} mode uses, continuing each stock from its
 * own {@code last_synced_date + 1} (resume semantics). This reuses that pipeline directly rather
 * than duplicating its worker-pool/rate-limit/block-handling wiring a second time.
 */
@Component
public class SnapshotBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBackfillRunner.class);

    /**
     * Only this exact market value routes a target away from the snapshot path. Anything else
     * (TSE, or a row whose market was never classified) keeps the pre-existing snapshot behavior:
     * MI_INDEX returns the whole listed market, so a listed code simply absent from one day's
     * response means it did not trade that day, which is a covered day, not an uncovered one.
     */
    private static final String MARKET_OTC = "OTC";

    private final TwseMiIndexClient miIndexClient;
    private final SourceAvailabilityTracker availabilityTracker;
    private final SourceRateLimiter rateLimiter;
    private final BackfillProperties properties;
    private final StockSyncProgressMapper progressMapper;
    private final PriceIngestionService priceIngestionService;
    private final BackfillRunner backfillRunner;
    private final JobRunningRegistry jobRunningRegistry;
    private final StockMapper stockMapper;

    public SnapshotBackfillRunner(TwseMiIndexClient miIndexClient, SourceAvailabilityTracker availabilityTracker,
                                   SourceRateLimiter rateLimiter, BackfillProperties properties,
                                   StockSyncProgressMapper progressMapper, PriceIngestionService priceIngestionService,
                                   BackfillRunner backfillRunner, JobRunningRegistry jobRunningRegistry,
                                   StockMapper stockMapper) {
        this.stockMapper = stockMapper;
        this.miIndexClient = miIndexClient;
        this.availabilityTracker = availabilityTracker;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.progressMapper = progressMapper;
        this.priceIngestionService = priceIngestionService;
        this.backfillRunner = backfillRunner;
        this.jobRunningRegistry = jobRunningRegistry;
    }

    /**
     * Returns a future that completes once the whole ALL-mode batch — the day-by-day snapshot loop
     * and/or the per-stock degradation it may hand off to — has actually finished, mirroring
     * {@link BackfillRunner#run}'s contract so callers (the HTTP endpoint, startup catch-up) don't
     * need to know which strategy actually ran.
     */
    @Async("backfillDispatchExecutor")
    public CompletableFuture<Void> run(String jobType, List<String> targetIds, LocalDate endDate) {
        try {
            runSequentially(jobType, targetIds, endDate);
        } finally {
            jobRunningRegistry.finish(jobType);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void runSequentially(String jobType, List<String> targetIds, LocalDate endDate) {
        if (targetIds.isEmpty()) {
            return;
        }

        // MI_INDEX covers listed stocks only, so the population splits by stock.market before
        // anything is fetched (spec: 依 stock.market 拆成上市標的與上櫃標的). Only the TSE side walks the
        // day loop; the OTC side goes per-stock afterwards.
        List<String> snapshotIds = new ArrayList<>();
        List<String> perStockIds = new ArrayList<>();
        for (Stock stock : stockMapper.findByIds(targetIds)) {
            if (MARKET_OTC.equals(stock.getMarket())) {
                perStockIds.add(stock.getStockId());
            } else {
                snapshotIds.add(stock.getStockId());
            }
        }

        if (!runSnapshotDays(jobType, snapshotIds, endDate)) {
            // MI_INDEX unusable: the TSE targets it did not get through join the OTC ones in a
            // single per-stock handoff, so both share one worker pool rather than two in sequence.
            perStockIds.addAll(0, notCaughtUp(jobType, snapshotIds, endDate));
        }
        if (!perStockIds.isEmpty()) {
            backfillRunner.runConcurrently(jobType, perStockIds, true);
        }
    }

    /**
     * Walks the candidate day range for the TSE (snapshot-covered) targets only. Returns
     * {@code true} when the whole range was processed, {@code false} when MI_INDEX turned out to be
     * unusable and the caller must degrade the remainder to the per-stock pipeline.
     */
    private boolean runSnapshotDays(String jobType, List<String> snapshotIds, LocalDate endDate) {
        if (snapshotIds.isEmpty()) {
            return true;
        }
        LocalDate loopStart = progressMapper.findMinTargetStartDate(jobType, snapshotIds);
        if (loopStart == null || loopStart.isAfter(endDate)) {
            // Nothing left to fetch for any target (e.g. every one was already caught up by the
            // time this actually ran) -- still converge to DONE, no request issued.
            progressMapper.markDoneForIds(jobType, snapshotIds);
            return true;
        }

        for (LocalDate date = loopStart; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (!availabilityTracker.isAvailable(TwseMiIndexClient.SOURCE_CODE)) {
                log.info("TWSE MI_INDEX unavailable at the start of {}; degrading the rest of this "
                        + "PRICE_BACKFILL batch to the per-stock Yahoo/FinMind pipeline.", date);
                return false;
            }

            List<NormalizedPriceRow> rows;
            try {
                rows = fetchWithRetry(date);
            } catch (SourceBlockedException e) {
                availabilityTracker.markBlocked(TwseMiIndexClient.SOURCE_CODE, e.getRetryAfterSeconds());
                log.info("TWSE MI_INDEX blocked fetching {}: {}. Degrading the rest of this "
                        + "PRICE_BACKFILL batch to the per-stock Yahoo/FinMind pipeline.", date, e.getMessage());
                return false;
            } catch (RateLimitedException | ExternalApiException | ExternalApiMalformedException e) {
                // Any other persistent MI_INDEX trouble (retries exhausted, malformed response, a
                // non-2xx connectivity failure): treated the same as a block so ONE bad day
                // degrades the rest of the batch instead of aborting the whole thing outright
                // (spec: 不結束本批作業). No retry_after to honor here, so the configured default
                // cooldown applies.
                availabilityTracker.markBlocked(TwseMiIndexClient.SOURCE_CODE, null);
                log.warn("TWSE MI_INDEX request failed fetching {}: {}. Degrading the rest of this "
                        + "PRICE_BACKFILL batch to the per-stock Yahoo/FinMind pipeline.", date, e.getMessage());
                return false;
            }

            // Same-transaction write + progress advance (spec: 單日的行情寫入與 last_synced_date 推進
            // 在同一個交易邊界內完成). Only snapshotIds are advanced — an OTC target's
            // last_synced_date must never move on a day this snapshot could not cover it
            // (spec: 上櫃標的的 last_synced_date 不由逐日快照推進).
            priceIngestionService.applySnapshotDay(rows, new HashSet<>(snapshotIds), jobType, date);
        }

        progressMapper.markDoneForIds(jobType, snapshotIds);
        return true;
    }

    /**
     * Backoff-retries a timeout on MI_INDEX. Every actual attempt — including retries — goes
     * through {@link SourceRateLimiter#acquire}, independently keyed from YAHOO/FINMIND (spec:
     * MI_INDEX 的請求間隔與 Yahoo／FinMind 各自獨立計時).
     */
    private List<NormalizedPriceRow> fetchWithRetry(LocalDate date) {
        BackfillProperties.RateLimit rateLimit = properties.getRateLimit();
        long backoffMs = rateLimit.getInitialBackoffMs();
        int attempt = 0;
        while (true) {
            try {
                rateLimiter.acquire(TwseMiIndexClient.SOURCE_CODE);
                return miIndexClient.fetchSnapshot(date);
            } catch (RateLimitedException e) {
                attempt++;
                if (attempt > rateLimit.getMaxRetries()) {
                    throw e;
                }
                log.info("TWSE MI_INDEX timed out fetching {} (attempt {}/{}), backing off {}ms",
                        date, attempt, rateLimit.getMaxRetries(), backoffMs);
                sleep(backoffMs);
                backoffMs *= rateLimit.getBackoffMultiplier();
            }
        }
    }

    /**
     * The subset of the given targets not yet caught up through endDate — what the per-stock
     * pipeline still has to do, each stock continuing from its own last_synced_date + 1
     * (resume=true semantics), exactly where the day-by-day loop left it (spec: 降級不改變任何已處理標的
     * 的狀態，也不重抓已寫入的日期). A target that happens to already be caught up (e.g. the block occurs
     * exactly at endDate) is left out rather than handed to a pipeline that would no-op it anyway.
     */
    private List<String> notCaughtUp(String jobType, List<String> ids, LocalDate endDate) {
        List<String> remaining = new ArrayList<>(ids);
        remaining.removeAll(progressMapper.findCaughtUpStockIds(jobType, ids, endDate));
        return remaining;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("TWSE MI_INDEX fetch retry interrupted", e);
        }
    }
}
