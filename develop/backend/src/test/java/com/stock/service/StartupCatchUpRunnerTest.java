package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.dto.BackfillRequest;
import com.stock.dto.BackfillResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context, no DB) for the ordering/failure-isolation contract of
 * StartupCatchUpRunner chaining the indicator rebuild onto the price catch-up's completion.
 * See specs/backend/stock-indicator-statistics.md, "啟動補齊後自動重算".
 */
class StartupCatchUpRunnerTest {

    private StockSyncService stockSyncService;
    private IndicatorRebuildService indicatorRebuildService;
    private BackfillProperties properties;
    private StartupCatchUpRunner runner;

    @BeforeEach
    void setUp() {
        stockSyncService = mock(StockSyncService.class);
        indicatorRebuildService = mock(IndicatorRebuildService.class);
        properties = new BackfillProperties();
        properties.getStartupCatchUp().setEnabled(true);
        properties.getStartupCatchUp().setStartDate(LocalDate.of(2026, 1, 1));
        runner = new StartupCatchUpRunner(stockSyncService, indicatorRebuildService, properties);
    }

    @Test
    void indicatorRebuild_firesOnlyAfterPriceBatchCompletes_notBeforeOrInParallel() {
        CompletableFuture<Void> priceBatchCompletion = new CompletableFuture<>();
        when(stockSyncService.startBackfillTrackingCompletion(any(BackfillRequest.class)))
                .thenReturn(new BackfillOutcome(dummyBackfillResponse(), priceBatchCompletion));

        runner.onApplicationReady();

        // The price batch was scheduled, but has not finished yet: the rebuild must not have
        // fired in parallel with it.
        verify(stockSyncService, times(1)).startBackfillTrackingCompletion(any(BackfillRequest.class));
        verifyNoInteractions(indicatorRebuildService);

        // Only once the price batch's own completion future actually completes does the rebuild fire.
        priceBatchCompletion.complete(null);

        // Startup triggers the per-stock-mode variant, not the manual endpoint's fixed-mode rebuild —
        // per-stock FULL-vs-INCREMENTAL routing is covered separately in IndicatorRebuildServiceTest.
        verify(indicatorRebuildService, times(1)).rebuildForStartup();
    }

    @Test
    void indicatorRebuild_stillFires_whenPriceBatchCompletesExceptionally() {
        // Spec: even a price batch that wrote no new rows (e.g. external source unreachable for
        // every stock) must still trigger the rebuild — it is a documented no-op in that case,
        // not something to skip. Model the rare case where the batch's future itself completes
        // exceptionally (e.g. an interrupted thread) the same way: still fire the rebuild.
        CompletableFuture<Void> priceBatchCompletion = new CompletableFuture<>();
        when(stockSyncService.startBackfillTrackingCompletion(any(BackfillRequest.class)))
                .thenReturn(new BackfillOutcome(dummyBackfillResponse(), priceBatchCompletion));

        runner.onApplicationReady();
        priceBatchCompletion.completeExceptionally(new RuntimeException("simulated interruption"));

        verify(indicatorRebuildService, times(1)).rebuildForStartup();
    }

    @Test
    void suppressedWhenStartupCatchUpDisabled_neitherPriceBatchNorRebuildTriggers() {
        properties.getStartupCatchUp().setEnabled(false);

        runner.onApplicationReady();

        verifyNoInteractions(stockSyncService);
        verifyNoInteractions(indicatorRebuildService);
    }

    @Test
    void rebuildFailure_doesNotPropagate() {
        CompletableFuture<Void> priceBatchCompletion = new CompletableFuture<>();
        when(stockSyncService.startBackfillTrackingCompletion(any(BackfillRequest.class)))
                .thenReturn(new BackfillOutcome(dummyBackfillResponse(), priceBatchCompletion));
        when(indicatorRebuildService.rebuildForStartup())
                .thenThrow(new RuntimeException("JOB_ALREADY_RUNNING or similar"));

        runner.onApplicationReady();

        // Completing the future runs the whenComplete callback (including the throwing rebuild
        // call) synchronously on this thread; it must not surface as an exception here, exactly
        // as an application-startup thread must never be broken by a rebuild failure.
        assertDoesNotThrow(() -> priceBatchCompletion.complete(null));
        verify(indicatorRebuildService, times(1)).rebuildForStartup();
    }

    @Test
    void priceBatchSchedulingFailure_doesNotPropagate_andNeverTriggersRebuild() {
        when(stockSyncService.startBackfillTrackingCompletion(any(BackfillRequest.class)))
                .thenThrow(new RuntimeException("e.g. JOB_ALREADY_RUNNING"));

        assertDoesNotThrow(() -> runner.onApplicationReady());

        verifyNoInteractions(indicatorRebuildService);
    }

    private BackfillResponse dummyBackfillResponse() {
        return new BackfillResponse("PRICE_BACKFILL", 34,
                LocalDate.of(2026, 1, 1), LocalDate.now(), "ALL");
    }
}
