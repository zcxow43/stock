package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.dto.BackfillRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Automatically catches every active stock's daily price up to today once the application has
 * finished starting, so a freshly reset database (only a stock master, no price history) becomes
 * usable on its own — no manual endpoint call required (spec: 啟動時自動補齊).
 *
 * Reuses the exact same backfill service path as the manual endpoint (stockIds omitted -> all
 * `is_active = 1`, startDate = configured catch-up start date, endDate = today, catchUp = true);
 * there is no parallel fetch/rate-limit/progress implementation here.
 *
 * Runs entirely on the same async backfill executor used by manual backfills, so it never
 * delays the application becoming ready to serve requests, and any failure (offline source,
 * timeout, DNS failure, response-format change, or even a manual backfill already running) is
 * only logged — it must never abort application startup. Affected stocks simply fall into the
 * existing FAILED retry flow.
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

    public StartupCatchUpRunner(StockSyncService stockSyncService, IndicatorRebuildService indicatorRebuildService,
                                 BackfillProperties properties) {
        this.stockSyncService = stockSyncService;
        this.indicatorRebuildService = indicatorRebuildService;
        this.properties = properties;
    }

    @Async("backfillExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        BackfillProperties.StartupCatchUp config = properties.getStartupCatchUp();
        if (!config.isEnabled()) {
            log.info("Startup price catch-up disabled (app.backfill.startup-catch-up.enabled=false); "
                    + "skipping price catch-up and the indicator rebuild that would follow it.");
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
            BackfillOutcome outcome = stockSyncService.startBackfillTrackingCompletion(request);
            // Chain off the batch's actual completion, not off this scheduling call returning —
            // startBackfillTrackingCompletion only kicks the async batch off and returns immediately.
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
            // behind here (offline source, JOB_ALREADY_RUNNING because a manual backfill beat us
            // to it, etc.) is simply picked up by a later manual/resume/catchUp call. Since the price
            // batch never actually started in this case, no indicator rebuild is triggered either.
            log.warn("Startup price catch-up could not be started; it can be triggered manually later.", e);
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
