package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.config.InstitutionalTradeProperties;
import com.stock.mapper.StockInstitutionalTradeMapper;
import com.stock.service.external.ExternalApiMalformedException;
import com.stock.service.external.RateLimitedException;
import com.stock.service.external.SourceAvailabilityTracker;
import com.stock.service.external.SourceBlockedException;
import com.stock.service.external.SourceRateLimiter;
import com.stock.service.external.TwseInstitutionalTradeClient;
import com.stock.service.external.TwseMiIndexClient;
import com.stock.service.external.dto.NormalizedInstitutionalTradeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context, no DB, no real HTTP) for {@link InstitutionalTradeCatchUpRunner}
 * — the orchestration/failure-dispatch logic (spec: specs/backend/institutional-trade-ingestion.md,
 * 觸發時機 / 速率控制 / 失敗處置 / 處理流程). {@link SourceAvailabilityTracker} and {@link SourceRateLimiter}
 * are REAL instances (not mocks) so the "shared with MI_INDEX" claim is proven by actually sharing
 * state through {@link TwseMiIndexClient#SOURCE_CODE}, not merely asserted.
 */
class InstitutionalTradeCatchUpRunnerTest {

    private static final LocalDate D1 = LocalDate.of(2026, 1, 5);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 6);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 7);

    private TwseInstitutionalTradeClient client;
    private StockInstitutionalTradeMapper tradeMapper;
    private InstitutionalTradeIngestionService ingestionService;
    private SourceAvailabilityTracker availabilityTracker;
    private SourceRateLimiter rateLimiter;
    private BackfillProperties backfillProperties;
    private InstitutionalTradeProperties properties;
    private InstitutionalTradeCatchUpRunner runner;

    @BeforeEach
    void setUp() {
        client = mock(TwseInstitutionalTradeClient.class);
        tradeMapper = mock(StockInstitutionalTradeMapper.class);
        ingestionService = mock(InstitutionalTradeIngestionService.class);

        backfillProperties = new BackfillProperties();
        backfillProperties.getStartupCatchUp().setStartDate(LocalDate.of(2026, 1, 1));
        backfillProperties.getRateLimit().setIntervalMs(1); // fast tests
        backfillProperties.getRateLimit().setMaxRetries(2);
        backfillProperties.getRateLimit().setInitialBackoffMs(1);
        backfillProperties.getRateLimit().setBackoffMultiplier(2);
        backfillProperties.getRateLimit().setDefaultBlockCooldownSeconds(1);

        availabilityTracker = new SourceAvailabilityTracker(backfillProperties);
        rateLimiter = new SourceRateLimiter(backfillProperties);

        properties = new InstitutionalTradeProperties();
        properties.getCatchUp().setEnabled(true);

        runner = new InstitutionalTradeCatchUpRunner(client, tradeMapper, ingestionService, availabilityTracker,
                rateLimiter, backfillProperties, properties);
    }

    private NormalizedInstitutionalTradeRow dummyRow(LocalDate date) {
        return new NormalizedInstitutionalTradeRow("2609", date, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    // ---------- disabled switch ----------

    @Test
    void disabled_skipsEntirely_neverQueriesMissingDates() {
        properties.getCatchUp().setEnabled(false);
        runner.triggerAsync();
        verifyNoInteractions(tradeMapper, client, ingestionService);
    }

    // ---------- zero missing dates ----------

    @Test
    void noMissingDates_zeroExternalRequests() {
        when(tradeMapper.findMissingTradeDates(any(), any())).thenReturn(Collections.emptyList());
        runner.triggerAsync();
        verify(client, never()).fetchTrades(any());
    }

    // ---------- happy path: one request per missing date, oldest first ----------

    @Test
    void missingDates_oneRequestEach_processedOldestFirst_thenWritten() {
        when(tradeMapper.findMissingTradeDates(any(), any())).thenReturn(Arrays.asList(D1, D2));
        when(client.fetchTrades(D1)).thenReturn(Collections.singletonList(dummyRow(D1)));
        when(client.fetchTrades(D2)).thenReturn(Collections.singletonList(dummyRow(D2)));
        when(ingestionService.applyDay(anyList())).thenReturn(1);

        runner.triggerAsync();

        var inOrder = inOrder(client);
        inOrder.verify(client).fetchTrades(D1);
        inOrder.verify(client).fetchTrades(D2);
        verify(ingestionService, times(2)).applyDay(anyList());
    }

    // ---------- non-trading-day / not-yet-published: empty rows, not a failure, continues ----------

    @Test
    void emptyRowsForADate_notWritten_notAFailure_continuesToNextDate() {
        when(tradeMapper.findMissingTradeDates(any(), any())).thenReturn(Arrays.asList(D1, D2));
        when(client.fetchTrades(D1)).thenReturn(Collections.emptyList());
        when(client.fetchTrades(D2)).thenReturn(Collections.singletonList(dummyRow(D2)));
        when(ingestionService.applyDay(anyList())).thenReturn(1);

        runner.triggerAsync();

        verify(ingestionService, never()).applyDay(eq(Collections.emptyList()));
        verify(ingestionService, times(1)).applyDay(anyList());
        verify(client).fetchTrades(D1);
        verify(client).fetchTrades(D2);
    }

    // ---------- format error (missing field / unparsable value): whole day skipped, continues ----------

    @Test
    void malformedResponse_dayNotWritten_continuesToNextDate() {
        when(tradeMapper.findMissingTradeDates(any(), any())).thenReturn(Arrays.asList(D1, D2));
        when(client.fetchTrades(D1)).thenThrow(new ExternalApiMalformedException("bad shape", null));
        when(client.fetchTrades(D2)).thenReturn(Collections.singletonList(dummyRow(D2)));
        when(ingestionService.applyDay(anyList())).thenReturn(1);

        runner.triggerAsync();

        verify(ingestionService, times(1)).applyDay(anyList());
        verify(client).fetchTrades(D2);
    }

    // ---------- block: ends the run immediately, never retries, never attempts remaining dates ----------

    @Test
    void blockedResponse_endsRunImmediately_marksSourceUnavailable_neverAttemptsRemainingDates() {
        when(tradeMapper.findMissingTradeDates(any(), any())).thenReturn(Arrays.asList(D1, D2, D3));
        when(client.fetchTrades(D1)).thenThrow(new SourceBlockedException("blocked", 999L));

        assertTrue(availabilityTracker.isAvailable(TwseMiIndexClient.SOURCE_CODE));
        runner.triggerAsync();

        verify(client, times(1)).fetchTrades(D1);
        verify(client, never()).fetchTrades(D2);
        verify(client, never()).fetchTrades(D3);
        verify(ingestionService, never()).applyDay(anyList());
        assertFalse(availabilityTracker.isAvailable(TwseMiIndexClient.SOURCE_CODE));
    }

    @Test
    void hostAlreadyBlockedAtStart_zeroRequestsIssued_endsImmediately() {
        availabilityTracker.markBlocked(TwseMiIndexClient.SOURCE_CODE, 999L);
        when(tradeMapper.findMissingTradeDates(any(), any())).thenReturn(Collections.singletonList(D1));

        runner.triggerAsync();

        verify(client, never()).fetchTrades(any());
    }

    // ---------- timeout: retried with backoff, then skipped after exhausting retries ----------

    @Test
    void timeout_retriedUpToMaxRetries_thenSkippedNotFailingTheWholeRun() {
        when(tradeMapper.findMissingTradeDates(any(), any())).thenReturn(Arrays.asList(D1, D2));
        // maxRetries=2 configured in setUp -> 1 initial + 2 retries = 3 attempts total for D1.
        when(client.fetchTrades(D1))
                .thenThrow(new RateLimitedException("timeout 1"))
                .thenThrow(new RateLimitedException("timeout 2"))
                .thenThrow(new RateLimitedException("timeout 3"));
        when(client.fetchTrades(D2)).thenReturn(Collections.singletonList(dummyRow(D2)));
        when(ingestionService.applyDay(anyList())).thenReturn(1);

        runner.triggerAsync();

        verify(client, times(3)).fetchTrades(D1);
        verify(client, times(1)).fetchTrades(D2);
        verify(ingestionService, times(1)).applyDay(anyList());
    }

    // ---------- shared rate limiter/availability tracker with MI_INDEX ----------

    @Test
    void sharesRateLimiterKey_withMiIndexSourceCode_intervalAppliesAcrossBothCallers() throws InterruptedException {
        backfillProperties.getRateLimit().setIntervalMs(150);
        // Simulate MI_INDEX having just made a request on the shared gate.
        rateLimiter.acquire(TwseMiIndexClient.SOURCE_CODE);

        when(tradeMapper.findMissingTradeDates(any(), any())).thenReturn(Collections.singletonList(D1));
        when(client.fetchTrades(D1)).thenReturn(Collections.emptyList());

        long start = System.currentTimeMillis();
        runner.triggerAsync();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 140, "T86 fetch should have waited out MI_INDEX's occupancy of the shared "
                + "TWSE rate-limit gate, elapsed=" + elapsed + "ms");
    }

    @Test
    void sharesAvailabilityTracker_withMiIndexSourceCode_miIndexBlockAlsoBlocksT86() {
        // A block recorded under MI_INDEX's source code must be visible to this runner, which
        // shares the same "TWSE" key (spec: 與 MI_INDEX 共用同一道請求間隔閘門，封鎖狀態也共用).
        availabilityTracker.markBlocked(TwseMiIndexClient.SOURCE_CODE, 999L);
        when(tradeMapper.findMissingTradeDates(any(), any())).thenReturn(Collections.singletonList(D1));

        runner.triggerAsync();

        verify(client, never()).fetchTrades(any());
    }

    // ---------- mutual exclusion: overlapping triggers ----------

    @Test
    void overlappingTrigger_skipsWithoutQueueing_onlyOneRunActuallyExecutes() throws Exception {
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        when(tradeMapper.findMissingTradeDates(any(), any())).thenReturn(Collections.singletonList(D1));
        when(client.fetchTrades(D1)).thenAnswer(invocation -> {
            fetchStarted.countDown();
            releaseFetch.await(5, TimeUnit.SECONDS);
            return Collections.emptyList();
        });

        Thread firstRun = new Thread(runner::triggerAsync);
        firstRun.start();
        assertTrue(fetchStarted.await(5, TimeUnit.SECONDS), "first run should have started fetching");
        assertTrue(runner.isRunning());

        // Second trigger while the first is still in flight -- must skip immediately, not queue.
        runner.triggerAsync();
        verify(tradeMapper, times(1)).findMissingTradeDates(any(), any());

        releaseFetch.countDown();
        firstRun.join(5000);
        assertFalse(runner.isRunning());
    }

    // ---------- a run-ending exception never propagates past triggerAsync ----------

    @Test
    void unexpectedExceptionDuringRun_neverPropagates_andReleasesTheRunningFlag() {
        doThrow(new RuntimeException("boom")).when(tradeMapper).findMissingTradeDates(any(), any());

        runner.triggerAsync(); // must not throw

        assertFalse(runner.isRunning());
    }
}
