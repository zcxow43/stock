package com.stock.service.external;

import com.stock.config.BackfillProperties;
import com.stock.service.external.dto.NormalizedPriceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context, no DB, no HTTP) for {@link PriceHistoryFetcher}'s
 * source-priority/block-switch orchestration (spec: 來源選擇與封鎖切換), driving the two sources
 * through Mockito mocks so block-switching, the 404 fallthrough, and the "all sources blocked"
 * ending are asserted deterministically by call count rather than by elapsed time.
 */
class PriceHistoryFetcherTest {

    private static final String STOCK_ID = "9999";
    private static final String MARKET = "TSE";
    private static final LocalDate START = LocalDate.of(2025, 9, 1);
    private static final LocalDate END = LocalDate.of(2025, 9, 10);

    private YahooFinanceClient yahoo;
    private FinMindClient finMind;
    private SourceAvailabilityTracker tracker;
    private PriceHistoryFetcher fetcher;

    @BeforeEach
    void setUp() {
        yahoo = mock(YahooFinanceClient.class);
        finMind = mock(FinMindClient.class);
        when(yahoo.getCode()).thenReturn(PriceHistorySource.SOURCE_YAHOO);
        when(finMind.getCode()).thenReturn(PriceHistorySource.SOURCE_FINMIND);

        BackfillProperties properties = new BackfillProperties();
        properties.getRateLimit().setMaxRetries(2);
        properties.getRateLimit().setInitialBackoffMs(1);
        properties.getRateLimit().setBackoffMultiplier(2);
        properties.getRateLimit().setDefaultBlockCooldownSeconds(600);
        // Negligible so these pure orchestration tests (call-count assertions) stay fast; the
        // interval value itself is covered by SourceRateLimiterTest.
        properties.getRateLimit().setIntervalMs(1);

        tracker = new SourceAvailabilityTracker(properties);
        fetcher = new PriceHistoryFetcher(yahoo, finMind, tracker, new SourceRateLimiter(properties), properties);
    }

    @Test
    void yahooSucceeds_finMindNeverCalled_defaultPriorityIsYahoo() {
        List<NormalizedPriceRow> rows = List.of(row());
        when(yahoo.fetchDailyHistory(STOCK_ID, MARKET, START, END)).thenReturn(rows);

        PriceHistoryFetcher.FetchOutcome outcome = fetcher.fetchHistory(STOCK_ID, MARKET, START, END);

        assertEquals(PriceHistorySource.SOURCE_YAHOO, outcome.getSourceCode());
        assertEquals(rows, outcome.getRows());
        verify(finMind, never()).fetchDailyHistory(anyString(), any(), any(), any());
    }

    @Test
    void yahooBlocked_fallsBackToFinMind_andSucceeds() {
        when(yahoo.fetchDailyHistory(STOCK_ID, MARKET, START, END))
                .thenThrow(new SourceBlockedException("blocked", 600L));
        List<NormalizedPriceRow> rows = List.of(row());
        when(finMind.fetchDailyHistory(STOCK_ID, MARKET, START, END)).thenReturn(rows);

        PriceHistoryFetcher.FetchOutcome outcome = fetcher.fetchHistory(STOCK_ID, MARKET, START, END);

        assertEquals(PriceHistorySource.SOURCE_FINMIND, outcome.getSourceCode());
        assertEquals(rows, outcome.getRows());
        verify(yahoo, times(1)).fetchDailyHistory(STOCK_ID, MARKET, START, END);
    }

    @Test
    void subsequentStock_neverCallsBlockedSource_untilItRecovers() {
        // First stock: Yahoo blocked, FinMind succeeds.
        when(yahoo.fetchDailyHistory(eq(STOCK_ID), any(), any(), any()))
                .thenThrow(new SourceBlockedException("blocked", 600L));
        when(finMind.fetchDailyHistory(anyString(), any(), any(), any())).thenReturn(List.of(row()));
        fetcher.fetchHistory(STOCK_ID, MARKET, START, END);

        // Second stock: Yahoo must NOT be called at all (still blocked) -- asserted by call
        // count, not elapsed time.
        String secondStockId = "8888";
        fetcher.fetchHistory(secondStockId, MARKET, START, END);

        verify(yahoo, never()).fetchDailyHistory(eq(secondStockId), any(), any(), any());
        verify(finMind, times(1)).fetchDailyHistory(eq(secondStockId), any(), any(), any());
    }

    @Test
    void blockedSourceRecoversAfterCooldownExpires_andIsTriedFirstAgain() throws InterruptedException {
        // Yahoo blocked with a 1-second cooldown.
        when(yahoo.fetchDailyHistory(anyString(), any(), any(), any()))
                .thenThrow(new SourceBlockedException("blocked", 1L))
                .thenReturn(List.of(row()));
        when(finMind.fetchDailyHistory(anyString(), any(), any(), any())).thenReturn(List.of(row()));

        fetcher.fetchHistory(STOCK_ID, MARKET, START, END);
        verify(yahoo, times(1)).fetchDailyHistory(anyString(), any(), any(), any());
        verify(finMind, times(1)).fetchDailyHistory(anyString(), any(), any(), any());

        Thread.sleep(1100); // past the 1-second retry_after

        PriceHistoryFetcher.FetchOutcome outcome = fetcher.fetchHistory("7777", MARKET, START, END);

        // Yahoo (priority source) is tried again automatically -- no restart/manual step needed.
        assertEquals(PriceHistorySource.SOURCE_YAHOO, outcome.getSourceCode());
        verify(yahoo, times(2)).fetchDailyHistory(anyString(), any(), any(), any());
        verify(finMind, times(1)).fetchDailyHistory(anyString(), any(), any(), any()); // unchanged
    }

    @Test
    void missingRetryAfter_usesConfiguredDefaultCooldown_notHardcoded() {
        BackfillProperties properties = new BackfillProperties();
        properties.getRateLimit().setDefaultBlockCooldownSeconds(1); // configurable, small for the test
        properties.getRateLimit().setIntervalMs(1);
        SourceAvailabilityTracker shortCooldownTracker = new SourceAvailabilityTracker(properties);
        PriceHistoryFetcher shortCooldownFetcher = new PriceHistoryFetcher(yahoo, finMind, shortCooldownTracker,
                new SourceRateLimiter(properties), properties);

        when(yahoo.fetchDailyHistory(anyString(), any(), any(), any()))
                .thenThrow(new SourceBlockedException("blocked, no retry_after", null))
                .thenReturn(List.of(row()));
        when(finMind.fetchDailyHistory(anyString(), any(), any(), any())).thenReturn(List.of(row()));

        shortCooldownFetcher.fetchHistory(STOCK_ID, MARKET, START, END);
        assertTrue(shortCooldownTracker.isAvailable(PriceHistorySource.SOURCE_FINMIND));
        assertFalse(shortCooldownTracker.isAvailable(PriceHistorySource.SOURCE_YAHOO));
    }

    @Test
    void bothSourcesBlocked_throwsAllSourcesBlocked_neitherSourceCalledTwice() {
        when(yahoo.fetchDailyHistory(anyString(), any(), any(), any()))
                .thenThrow(new SourceBlockedException("yahoo blocked", 600L));
        when(finMind.fetchDailyHistory(anyString(), any(), any(), any()))
                .thenThrow(new SourceBlockedException("finmind blocked", 600L));

        assertThrows(AllSourcesBlockedException.class,
                () -> fetcher.fetchHistory(STOCK_ID, MARKET, START, END));

        verify(yahoo, times(1)).fetchDailyHistory(anyString(), any(), any(), any());
        verify(finMind, times(1)).fetchDailyHistory(anyString(), any(), any(), any());
    }

    @Test
    void allSourcesAlreadyBlocked_skipsBothEntirely_zeroRequestsIssued() {
        tracker.markBlocked(PriceHistorySource.SOURCE_YAHOO, 600L);
        tracker.markBlocked(PriceHistorySource.SOURCE_FINMIND, 600L);

        assertThrows(AllSourcesBlockedException.class,
                () -> fetcher.fetchHistory(STOCK_ID, MARKET, START, END));

        verify(yahoo, never()).fetchDailyHistory(anyString(), any(), any(), any());
        verify(finMind, never()).fetchDailyHistory(anyString(), any(), any(), any());
    }

    @Test
    void yahoo404_fallsThroughToFinMind_finMindSucceeds_notTreatedAsNotFound() {
        when(yahoo.fetchDailyHistory(anyString(), any(), any(), any()))
                .thenThrow(new SymbolNotFoundException("yahoo 404"));
        List<NormalizedPriceRow> rows = List.of(row());
        when(finMind.fetchDailyHistory(anyString(), any(), any(), any())).thenReturn(rows);

        PriceHistoryFetcher.FetchOutcome outcome = fetcher.fetchHistory(STOCK_ID, MARKET, START, END);

        assertEquals(PriceHistorySource.SOURCE_FINMIND, outcome.getSourceCode());
        assertEquals(rows, outcome.getRows());
    }

    @Test
    void everySourceReportsNotFound_throwsSymbolNotFound() {
        when(yahoo.fetchDailyHistory(anyString(), any(), any(), any()))
                .thenThrow(new SymbolNotFoundException("yahoo 404"));
        when(finMind.fetchDailyHistory(anyString(), any(), any(), any()))
                .thenThrow(new SymbolNotFoundException("finmind: no such data_id"));

        assertThrows(SymbolNotFoundException.class,
                () -> fetcher.fetchHistory(STOCK_ID, MARKET, START, END));
    }

    @Test
    void timeout_retriesOnSameSource_thenSucceeds() {
        when(yahoo.fetchDailyHistory(anyString(), any(), any(), any()))
                .thenThrow(new RateLimitedException("timeout 1"))
                .thenReturn(List.of(row()));

        PriceHistoryFetcher.FetchOutcome outcome = fetcher.fetchHistory(STOCK_ID, MARKET, START, END);

        assertEquals(PriceHistorySource.SOURCE_YAHOO, outcome.getSourceCode());
        verify(yahoo, times(2)).fetchDailyHistory(anyString(), any(), any(), any());
        verify(finMind, never()).fetchDailyHistory(anyString(), any(), any(), any());
    }

    @Test
    void timeoutExhaustsRetries_propagatesAsRealFailure_doesNotFallOverToNextSource() {
        when(yahoo.fetchDailyHistory(anyString(), any(), any(), any()))
                .thenThrow(new RateLimitedException("always times out"));

        assertThrows(RateLimitedException.class, () -> fetcher.fetchHistory(STOCK_ID, MARKET, START, END));
        verify(finMind, never()).fetchDailyHistory(anyString(), any(), any(), any());
    }

    private NormalizedPriceRow row() {
        return new NormalizedPriceRow(STOCK_ID, LocalDate.of(2025, 9, 1),
                new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"),
                new BigDecimal("100.50"), 1000L, BigDecimal.ZERO, 0);
    }
}
