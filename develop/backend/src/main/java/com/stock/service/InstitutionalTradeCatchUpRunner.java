package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.config.InstitutionalTradeProperties;
import com.stock.mapper.StockInstitutionalTradeMapper;
import com.stock.service.external.ExternalApiException;
import com.stock.service.external.ExternalApiMalformedException;
import com.stock.service.external.RateLimitedException;
import com.stock.service.external.SourceAvailabilityTracker;
import com.stock.service.external.SourceBlockedException;
import com.stock.service.external.SourceRateLimiter;
import com.stock.service.external.TwseInstitutionalTradeClient;
import com.stock.service.external.TwseMiIndexClient;
import com.stock.service.external.dto.NormalizedInstitutionalTradeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background-only catch-up for `stock_institutional_trade` (spec:
 * specs/backend/institutional-trade-ingestion.md). No HTTP endpoint and no progress table: "which
 * dates are still missing" is answered directly from the data (spec: 缺少日期的認定), and this runner
 * is only ever triggered as a side effect of the daily-price paths finishing (spec: 觸發時機) — it
 * never schedules itself.
 *
 * <p>Fetch cost is one T86 request per missing trading day, processed sequentially oldest-first
 * (spec: 缺少的日期由舊到新逐日處理), sharing {@link TwseMiIndexClient#SOURCE_CODE} ("TWSE") with
 * {@link TwseMiIndexClient} for both the request-interval gate ({@link SourceRateLimiter}) and the
 * block state ({@link SourceAvailabilityTracker}) — both endpoints live on the same host, and a
 * block from that host applies to both (spec: 與 MI_INDEX 共用同一道請求間隔閘門，封鎖狀態也共用).
 *
 * <p>Mutual exclusion is a single in-memory flag, not {@link JobRunningRegistry}: this is not a
 * {@code stock_sync_progress} job type, and the spec explicitly calls for "同一時間只跑一個補齊" with a
 * skip-and-log on overlap, never a queue (spec: 觸發時機 — 執行中的那一個結束後若仍有缺少的日期，下一次觸發自然
 * 會補上；排隊只會讓同一段日期被連續查兩次).
 */
@Component
public class InstitutionalTradeCatchUpRunner {

    private static final Logger log = LoggerFactory.getLogger(InstitutionalTradeCatchUpRunner.class);

    private final TwseInstitutionalTradeClient client;
    private final StockInstitutionalTradeMapper tradeMapper;
    private final InstitutionalTradeIngestionService ingestionService;
    private final SourceAvailabilityTracker availabilityTracker;
    private final SourceRateLimiter rateLimiter;
    private final BackfillProperties backfillProperties;
    private final InstitutionalTradeProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public InstitutionalTradeCatchUpRunner(TwseInstitutionalTradeClient client,
                                            StockInstitutionalTradeMapper tradeMapper,
                                            InstitutionalTradeIngestionService ingestionService,
                                            SourceAvailabilityTracker availabilityTracker,
                                            SourceRateLimiter rateLimiter,
                                            BackfillProperties backfillProperties,
                                            InstitutionalTradeProperties properties) {
        this.client = client;
        this.tradeMapper = tradeMapper;
        this.ingestionService = ingestionService;
        this.availabilityTracker = availabilityTracker;
        this.rateLimiter = rateLimiter;
        this.backfillProperties = backfillProperties;
        this.properties = properties;
    }

    /**
     * Fire-and-forget trigger, called from the two points the spec names (spec: 觸發時機): every
     * {@code PRICE_BACKFILL} batch's completion, and every successful daily increment. Runs
     * entirely off the calling thread ({@code @Async}), so neither caller's response time changes
     * (spec: 兩條日線路徑的 API 回應內容與回應時間都不因此改變).
     */
    @Async("institutionalTradeCatchUpExecutor")
    public void triggerAsync() {
        if (!properties.getCatchUp().isEnabled()) {
            log.info("Institutional trade catch-up disabled (app.institutional-trade.catch-up.enabled=false); "
                    + "skipping.");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Institutional trade catch-up already running; skipping this trigger (spec: 同一時間只跑一個補齊).");
            return;
        }
        try {
            runCatchUp();
        } catch (RuntimeException e) {
            // A failure here must never surface anywhere else (spec: 補齊的任何失敗都不影響日線作業的結果、
            // 進度或 API 回應) -- this method is only ever invoked fire-and-forget from an @Async
            // context, so an uncaught exception would just vanish into a log line anyway; catching
            // it here makes that contract explicit rather than incidental.
            log.error("Institutional trade catch-up ended abnormally; the next trigger will retry the "
                    + "remaining missing dates.", e);
        } finally {
            running.set(false);
        }
    }

    private void runCatchUp() {
        LocalDate startDate = backfillProperties.getStartupCatchUp().getStartDate();
        LocalDate endDate = LocalDate.now();
        List<LocalDate> missingDates = tradeMapper.findMissingTradeDates(startDate, endDate);
        if (missingDates.isEmpty()) {
            log.info("Institutional trade catch-up: no missing trading dates in [{}, {}].", startDate, endDate);
            return;
        }

        int written = 0;
        int noData = 0;
        int failed = 0;
        int processed = 0;
        for (LocalDate date : missingDates) {
            if (!availabilityTracker.isAvailable(TwseMiIndexClient.SOURCE_CODE)) {
                log.warn("Institutional trade catch-up: TWSE host currently blocked; ending this run early. "
                        + "{} of {} missing dates were not attempted.", missingDates.size() - processed,
                        missingDates.size());
                break;
            }
            processed++;

            List<NormalizedInstitutionalTradeRow> rows;
            try {
                rows = fetchWithRetry(date);
            } catch (SourceBlockedException e) {
                availabilityTracker.markBlocked(TwseMiIndexClient.SOURCE_CODE, e.getRetryAfterSeconds());
                log.warn("Institutional trade catch-up: TWSE T86 blocked fetching {}: {}. Ending this run "
                        + "without retrying (spec: 立即結束本次補齊，不重試).", date, e.getMessage());
                break;
            } catch (RateLimitedException | ExternalApiException | ExternalApiMalformedException e) {
                // Retries (for timeouts) already exhausted inside fetchWithRetry; a persistent
                // timeout, a non-block connectivity failure, or a malformed/unparsable response all
                // land here and only cost this one day (spec: 失敗處置 — 逾時/5xx 用盡重試後跳過該日；
                // 格式錯誤該日整日不寫入), never the rest of the run.
                failed++;
                log.error("Institutional trade catch-up: failed to fetch/parse TWSE T86 for {}: {}. Skipping "
                        + "this date and continuing.", date, e.getMessage());
                continue;
            }

            if (rows.isEmpty()) {
                // Non-trading day, or the day's report not yet published -- normal, not a failure
                // (spec: 尚未發布與尚未抓取，在這個判定下沒有差別).
                noData++;
                continue;
            }

            int rowsWritten = ingestionService.applyDay(rows);
            if (rowsWritten > 0) {
                written++;
            } else {
                // Every row's stock_id was outside the stock master -- not observed in practice
                // (spec: 某日的回應經主檔過濾後為零列（實務上不會發生），視同未抓，不寫入). No row is written for
                // this date, so the next trigger's findMissingTradeDates will simply ask for it again.
                noData++;
            }
        }

        log.info("Institutional trade catch-up finished: {} missing dates, {} written, {} no-data, {} failed.",
                missingDates.size(), written, noData, failed);
    }

    /**
     * Backoff-retries a timeout on T86. Every actual attempt -- including retries -- goes through
     * {@link SourceRateLimiter#acquire}, sharing TWSE's gate with {@link TwseMiIndexClient} (spec:
     * 與 MI_INDEX 共用同一道請求間隔閘門).
     */
    private List<NormalizedInstitutionalTradeRow> fetchWithRetry(LocalDate date) {
        BackfillProperties.RateLimit rateLimit = backfillProperties.getRateLimit();
        long backoffMs = rateLimit.getInitialBackoffMs();
        int attempt = 0;
        while (true) {
            try {
                rateLimiter.acquire(TwseMiIndexClient.SOURCE_CODE);
                return client.fetchTrades(date);
            } catch (RateLimitedException e) {
                attempt++;
                if (attempt > rateLimit.getMaxRetries()) {
                    throw e;
                }
                log.info("Institutional trade catch-up: TWSE T86 timed out fetching {} (attempt {}/{}), "
                        + "backing off {}ms", date, attempt, rateLimit.getMaxRetries(), backoffMs);
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
            throw new RuntimeException("Institutional trade catch-up retry interrupted", e);
        }
    }

    /** Test-only hook: true while a catch-up run is actually in flight. */
    public boolean isRunning() {
        return running.get();
    }
}
