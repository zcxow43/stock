package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.config.MasterSyncProperties;
import com.stock.domain.StockSyncProgress;
import com.stock.dto.BackfillRequest;
import com.stock.dto.DailySyncResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Runs two independent startup steps, in a fixed order, once the application has finished
 * starting:
 *
 * <ol>
 *   <li><b>Stock master sync</b> (spec: 啟動時同步股票主檔): synchronously UPSERTs the `stock`
 *       table from the exchange's single-request daily snapshot, so a freshly reset database
 *       (only the 34-stock V007 dev seed) grows to the real listed universe on its own.</li>
 *   <li><b>Price catch-up</b> (spec: 啟動時自動補齊): backfills every active stock's daily price
 *       up to today, so a database with only a stock master (no price history) becomes usable on
 *       its own — no manual endpoint call required.</li>
 * </ol>
 *
 * <b>Ordering is deliberate and load-bearing:</b> master sync must complete before the price
 * catch-up's target list (`stock.is_active = 1`) is resolved, otherwise a stock newly added by
 * this very startup's master sync would not get its price backfilled until the *next* restart
 * (spec: 主檔同步在日線補齊之前完成). Master sync is a single external request — seconds, not
 * minutes — so running it synchronously here does not violate "絕不阻塞啟動"; only the
 * long-running per-stock price loop that follows needs to stay asynchronous.
 *
 * Each step has its own independent enable switch ({@link MasterSyncProperties.Startup} /
 * {@link BackfillProperties.StartupCatchUp}) and its own failure isolation: a step failing (source
 * offline, timeout, malformed response) is only logged and never aborts application startup, and
 * never prevents the *other* step from running (spec: 主檔同步失敗只代表 universe 沒更新，不代表既有
 * 標的的行情不該補).
 *
 * Reuses the exact same backfill service path as the manual endpoint (stockIds omitted -> all
 * `is_active = 1`, startDate = configured catch-up start date, endDate = today, catchUp = true);
 * there is no parallel fetch/rate-limit/progress implementation here.
 *
 * The per-jobType concurrency lock ({@link JobRunningRegistry}) is acquired synchronously, on the
 * same thread that handles {@code ApplicationReadyEvent}, before any work is dispatched — not
 * inside an {@code @Async} method — so a manual {@code POST /api/stocks/sync/backfill} arriving
 * at any point while this catch-up is in flight reliably sees the lock held and gets {@code 409
 * JOB_ALREADY_RUNNING} (spec: 啟動補齊執行期間手動觸發回補會得到 409). Only that lock check plus a
 * few quick target-resolution queries run synchronously here; the actual rate-limited per-stock
 * loop still runs on the same async backfill executor used by manual backfills (via
 * {@link BackfillRunner}), so this never delays the application becoming ready to serve requests.
 * Any failure (offline source, timeout, DNS failure, response-format change, or even a manual
 * backfill already running) is only logged — it must never abort application startup. Affected
 * stocks simply fall into the existing FAILED retry flow.
 *
 * Once the whole price batch has actually finished (observed via the batch's completion future,
 * not merely via the scheduling call returning), this also triggers a single whole-market
 * indicator rebuild (spec: 啟動補齊後自動重算 in stock-indicator-statistics.md) — daily OHLC must be
 * complete before indicators are derived from it, so the rebuild must happen after, never in
 * parallel with, the price catch-up. The mode is decided per stock, not fixed for the whole batch
 * (see IndicatorRebuildService#rebuildForStartup): a stock with no indicator rows yet is rebuilt
 * with FULL, a stock that already has indicator rows is advanced with INCREMENTAL — a single fixed
 * mode cannot handle both a freshly reset database and a normal restart. Governed by the same
 * app.backfill.startup-catch-up.enabled switch; no second switch is introduced.
 */
@Component
public class StartupCatchUpRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupCatchUpRunner.class);

    private final StockSyncService stockSyncService;
    private final IndicatorRebuildService indicatorRebuildService;
    private final BackfillProperties properties;
    private final MasterSyncProperties masterSyncProperties;
    private final JobRunningRegistry jobRunningRegistry;

    public StartupCatchUpRunner(StockSyncService stockSyncService, IndicatorRebuildService indicatorRebuildService,
                                 BackfillProperties properties, MasterSyncProperties masterSyncProperties,
                                 JobRunningRegistry jobRunningRegistry) {
        this.stockSyncService = stockSyncService;
        this.indicatorRebuildService = indicatorRebuildService;
        this.properties = properties;
        this.masterSyncProperties = masterSyncProperties;
        this.jobRunningRegistry = jobRunningRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // Step 1: stock master sync. Runs first and synchronously (see class Javadoc for why)
        // so any stock it adds is already in `stock` by the time step 2 resolves its target list.
        syncStockMaster();

        // Step 2: price catch-up. Independent switch from step 1 — a stock master sync failure
        // or being disabled must never affect this step running for the existing stocks.
        BackfillProperties.StartupCatchUp config = properties.getStartupCatchUp();
        if (!config.isEnabled()) {
            log.info("Startup price catch-up disabled (app.backfill.startup-catch-up.enabled=false); "
                    + "skipping price catch-up and the indicator rebuild that would follow it.");
            return;
        }

        // Acquired synchronously, right here, before any work is dispatched — see the class
        // Javadoc for why this must not be deferred into an @Async method.
        String jobType = StockSyncProgress.JOB_PRICE_BACKFILL;
        if (!jobRunningRegistry.tryStart(jobType)) {
            // Not expected in practice (nothing else should be running this early), but stay
            // consistent with the manual endpoint's behavior rather than assuming it can't happen.
            log.info("Startup price catch-up skipped: a {} job is already running.", jobType);
            return;
        }

        LocalDate startDate = config.getStartDate();
        LocalDate endDate = LocalDate.now();
        log.info("Starting startup price catch-up for all active stocks: {}..{}", startDate, endDate);
        try {
            BackfillRequest request = new BackfillRequest();
            request.setStartDate(startDate);
            request.setEndDate(endDate);
            request.setCatchUp(true);
            BackfillOutcome outcome = stockSyncService.startBackfillWithLockAlreadyHeld(request);
            // Chain off the batch's actual completion, not off this scheduling call returning —
            // startBackfillWithLockAlreadyHeld only kicks the async batch off and returns immediately.
            outcome.getCompletion().whenComplete((ignoredResult, ex) -> {
                if (ex != null) {
                    // The batch itself already routes every per-stock failure into FAILED and never
                    // rethrows; reaching here at all would mean something else went wrong (e.g. an
                    // interrupted thread). Still trigger the rebuild — a partially/fully failed price
                    // batch is exactly the "no new rows" case the spec says to rebuild through anyway.
                    log.warn("Startup price catch-up batch ended abnormally; still triggering the "
                            + "indicator rebuild.", ex);
                }
                triggerIndicatorRebuild();
            });
        } catch (Exception e) {
            // Must never fail application startup (spec: 絕不因抓取失敗而讓啟動失敗). A stock left
            // behind here (offline source, DB issue, etc.) is simply picked up by a later
            // manual/resume/catchUp call. startBackfillWithLockAlreadyHeld already released the
            // job lock itself before rethrowing, so no cleanup is needed here. Since the price
            // batch never actually started in this case, no indicator rebuild is triggered either.
            log.warn("Startup price catch-up could not be started; it can be triggered manually later.", e);
        }
    }

    /**
     * Synchronously UPSERTs the stock master via the existing single-request daily-snapshot path
     * (spec: 啟動時同步股票主檔). Reuses {@link StockSyncService#syncDaily()} exactly as-is — no
     * separate fetch/parse/upsert logic is introduced here; {@code stockMasterUpserted} on its
     * response *is* this step's result, per the spec's own wording ("等同於以 tradeDate 省略呼叫每日
     * 增量同步服務"). That path only ever writes `market = 'TSE'` rows (its data source is the TWSE
     * snapshot endpoint), so this step cannot produce any `OTC` row.
     *
     * Any failure (offline source, timeout, malformed response) is only logged — it must never
     * abort application startup, and the price catch-up step that follows still runs normally
     * for the stocks that already exist.
     */
    private void syncStockMaster() {
        if (!masterSyncProperties.getStartup().isEnabled()) {
            log.info("Startup stock master sync disabled (app.master-sync.startup.enabled=false); skipping.");
            return;
        }
        log.info("Starting startup stock master sync (single-request TWSE daily snapshot).");
        try {
            DailySyncResponse response = stockSyncService.syncDaily();
            log.info("Startup stock master sync finished: {} stocks upserted into the stock master.",
                    response.getStockMasterUpserted());
        } catch (Exception e) {
            log.warn("Startup stock master sync failed; the stock master was not updated this run. "
                    + "The price catch-up step still runs normally for existing stocks.", e);
        }
    }

    private void triggerIndicatorRebuild() {
        try {
            log.info("Startup price catch-up finished; triggering an indicator rebuild for the whole "
                    + "market, with FULL/INCREMENTAL decided per stock.");
            indicatorRebuildService.rebuildForStartup();
        } catch (Exception e) {
            // Same contract as the price catch-up above: never fail/block startup, per-stock
            // failures land in the existing INDICATOR_REBUILD FAILED flow, and a whole-batch
            // conflict (e.g. JOB_ALREADY_RUNNING because a manual rebuild raced us) is simply
            // left for a later manual/resume call.
            log.warn("Startup indicator rebuild could not be started; it can be triggered manually later.", e);
        }
    }
}
