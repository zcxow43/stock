package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.config.MasterSyncProperties;
import com.stock.dto.BackfillRequest;
import com.stock.dto.BackfillResponse;
import com.stock.dto.DailySyncResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context, no DB) for the ordering/failure-isolation contract of
 * StartupCatchUpRunner: the stock master sync step (spec: 啟動時同步股票主檔), and chaining the
 * indicator rebuild onto the price catch-up's completion (spec: 啟動補齊後自動重算 in
 * specs/backend/stock-indicator-statistics.md).
 */
class StartupCatchUpRunnerTest {

    private StockSyncService stockSyncService;
    private IndicatorRebuildService indicatorRebuildService;
    private BackfillProperties properties;
    private MasterSyncProperties masterSyncProperties;
    private JobRunningRegistry jobRunningRegistry;
    private StartupCatchUpRunner runner;

    @BeforeEach
    void setUp() {
        stockSyncService = mock(StockSyncService.class);
        indicatorRebuildService = mock(IndicatorRebuildService.class);
        properties = new BackfillProperties();
        properties.getStartupCatchUp().setEnabled(true);
        properties.getStartupCatchUp().setStartDate(LocalDate.of(2026, 1, 1));
        masterSyncProperties = new MasterSyncProperties();
        // Off by default here so the pre-existing price-catch-up/rebuild tests below don't need
        // to know or care about the master-sync step; the dedicated tests further down turn it
        // on explicitly to exercise it in isolation.
        masterSyncProperties.getStartup().setEnabled(false);
        jobRunningRegistry = new JobRunningRegistry();
        runner = new StartupCatchUpRunner(stockSyncService, indicatorRebuildService, properties,
                masterSyncProperties, jobRunningRegistry);
    }

    @Test
    void jobLock_isHeldSynchronously_beforeOnApplicationReadyReturns() {
        // Root of the fix this test guards: the PRICE_BACKFILL job lock must already be held by
        // the time this event listener returns control to its caller -- not merely by the time
        // some later @Async task happens to start running -- otherwise a manual
        // POST /api/stocks/sync/backfill arriving in that gap would wrongly see the registry as
        // free and get 202 instead of 409 (spec: 啟動補齊執行期間手動觸發回補會得到 409).
        CompletableFuture<Void> priceBatchCompletion = new CompletableFuture<>();
        when(stockSyncService.startBackfillWithLockAlreadyHeld(any(BackfillRequest.class)))
                .thenReturn(new BackfillOutcome(dummyBackfillResponse(), priceBatchCompletion));

        runner.onApplicationReady();

        assertTrue(jobRunningRegistry.isRunning("PRICE_BACKFILL"));

        priceBatchCompletion.complete(null);
    }

    @Test
    void indicatorRebuild_firesOnlyAfterPriceBatchCompletes_notBeforeOrInParallel() {
        CompletableFuture<Void> priceBatchCompletion = new CompletableFuture<>();
        when(stockSyncService.startBackfillWithLockAlreadyHeld(any(BackfillRequest.class)))
                .thenReturn(new BackfillOutcome(dummyBackfillResponse(), priceBatchCompletion));

        runner.onApplicationReady();

        // The price batch was scheduled, but has not finished yet: the rebuild must not have
        // fired in parallel with it.
        verify(stockSyncService, times(1)).startBackfillWithLockAlreadyHeld(any(BackfillRequest.class));
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
        when(stockSyncService.startBackfillWithLockAlreadyHeld(any(BackfillRequest.class)))
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
        when(stockSyncService.startBackfillWithLockAlreadyHeld(any(BackfillRequest.class)))
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
        when(stockSyncService.startBackfillWithLockAlreadyHeld(any(BackfillRequest.class)))
                .thenThrow(new RuntimeException("e.g. JOB_ALREADY_RUNNING"));

        assertDoesNotThrow(() -> runner.onApplicationReady());

        verifyNoInteractions(indicatorRebuildService);
    }

    @Test
    void masterSync_runsBeforePriceCatchUp_inThatOrder() {
        masterSyncProperties.getStartup().setEnabled(true);
        when(stockSyncService.syncDaily()).thenReturn(dummyDailySyncResponse());
        CompletableFuture<Void> priceBatchCompletion = new CompletableFuture<>();
        when(stockSyncService.startBackfillWithLockAlreadyHeld(any(BackfillRequest.class)))
                .thenReturn(new BackfillOutcome(dummyBackfillResponse(), priceBatchCompletion));

        runner.onApplicationReady();

        // Master sync must complete before the price catch-up's target list is resolved, so a
        // stock it adds is included in this same startup's catch-up (spec: 主檔同步在日線補齊之前完成).
        InOrder order = inOrder(stockSyncService);
        order.verify(stockSyncService).syncDaily();
        order.verify(stockSyncService).startBackfillWithLockAlreadyHeld(any(BackfillRequest.class));

        priceBatchCompletion.complete(null);
    }

    @Test
    void masterSync_disabled_skipsSyncDaily_butPriceCatchUpStillRuns() {
        masterSyncProperties.getStartup().setEnabled(false);
        CompletableFuture<Void> priceBatchCompletion = new CompletableFuture<>();
        when(stockSyncService.startBackfillWithLockAlreadyHeld(any(BackfillRequest.class)))
                .thenReturn(new BackfillOutcome(dummyBackfillResponse(), priceBatchCompletion));

        runner.onApplicationReady();

        verify(stockSyncService, times(0)).syncDaily();
        verify(stockSyncService, times(1)).startBackfillWithLockAlreadyHeld(any(BackfillRequest.class));

        priceBatchCompletion.complete(null);
    }

    @Test
    void masterSync_syncDailyThrows_doesNotPropagate_andPriceCatchUpStillRuns() {
        masterSyncProperties.getStartup().setEnabled(true);
        when(stockSyncService.syncDaily()).thenThrow(new RuntimeException("upstream offline or timed out"));
        CompletableFuture<Void> priceBatchCompletion = new CompletableFuture<>();
        when(stockSyncService.startBackfillWithLockAlreadyHeld(any(BackfillRequest.class)))
                .thenReturn(new BackfillOutcome(dummyBackfillResponse(), priceBatchCompletion));

        assertDoesNotThrow(() -> runner.onApplicationReady());

        // Master sync failing must never stop the price catch-up from running for existing stocks
        // (spec: 主檔同步失敗只代表 universe 沒更新，不代表既有標的的行情不該補).
        verify(stockSyncService, times(1)).startBackfillWithLockAlreadyHeld(any(BackfillRequest.class));

        priceBatchCompletion.complete(null);
    }

    @Test
    void masterSync_independentOfPriceCatchUpSwitch_stillRunsWhenCatchUpDisabled() {
        masterSyncProperties.getStartup().setEnabled(true);
        properties.getStartupCatchUp().setEnabled(false);
        when(stockSyncService.syncDaily()).thenReturn(dummyDailySyncResponse());

        runner.onApplicationReady();

        // The two switches are independent (spec: 可停用，與啟動補齊各自獨立開關): disabling price
        // catch-up must not suppress master sync.
        verify(stockSyncService, times(1)).syncDaily();
        verify(stockSyncService, times(0)).startBackfillWithLockAlreadyHeld(any(BackfillRequest.class));
        verifyNoInteractions(indicatorRebuildService);
    }

    private BackfillResponse dummyBackfillResponse() {
        return new BackfillResponse("PRICE_BACKFILL", 34, 0,
                LocalDate.of(2026, 1, 1), LocalDate.now(), "ALL");
    }

    private DailySyncResponse dummyDailySyncResponse() {
        return new DailySyncResponse(LocalDate.now(), 1000, 966, 34, 1000);
    }
}
