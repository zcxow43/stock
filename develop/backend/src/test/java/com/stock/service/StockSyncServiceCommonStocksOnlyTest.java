package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.domain.StockSyncProgress;
import com.stock.dto.BackfillRequest;
import com.stock.dto.BackfillResponse;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockSyncProgressMapper;
import com.stock.service.external.TwseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context, no DB, no HTTP — {@link BackfillRunner} is mocked so the
 * batch is never actually dispatched) for the full-market backfill population defaulting to
 * listed common stocks (spec: specs/backend/stock-price-ingestion.md, 母體預設普通股與並行抓取). These
 * exercise {@link StockSyncService#startBackfill}'s target-list resolution and the {@code
 * commonStocksOnly} echoed back on the {@code 202} response, using the SAME
 * {@link com.stock.util.CommonStockCodeUtil} regex the write-side universe import and the
 * strategy-scan/momentum-gain read-side filters already share — no second filter implementation.
 *
 * Complements {@code StockPriceIngestionCommonStocksIntegrationTest}, which proves the same
 * behavior end-to-end against the real database and mocked external HTTP.
 */
class StockSyncServiceCommonStocksOnlyTest {

    private static final String JOB_TYPE = StockSyncProgress.JOB_PRICE_BACKFILL;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 1);

    private TwseClient twseClient;
    private StockMapper stockMapper;
    private StockSyncProgressMapper progressMapper;
    private PriceIngestionService priceIngestionService;
    private BackfillRunner backfillRunner;
    private StockSyncService service;

    @BeforeEach
    void setUp() {
        twseClient = mock(TwseClient.class);
        stockMapper = mock(StockMapper.class);
        progressMapper = mock(StockSyncProgressMapper.class);
        priceIngestionService = mock(PriceIngestionService.class);
        backfillRunner = mock(BackfillRunner.class);
        when(backfillRunner.run(anyString(), anyList(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));

        JobRunningRegistry jobRunningRegistry = new JobRunningRegistry();
        BackfillProperties properties = new BackfillProperties();

        service = new StockSyncService(twseClient, stockMapper, progressMapper, priceIngestionService,
                backfillRunner, jobRunningRegistry, properties);
    }

    @Test
    void allMode_omittedCommonStocksOnly_filtersOutNonCommonCodes_targetCountSmallerThanTotalActive() {
        List<String> allActive = Arrays.asList("2330", "2317", "0050", "00878", "2881A", "910322");
        List<String> expectedCommon = Arrays.asList("2330", "2317");
        when(stockMapper.findActiveStockIds()).thenReturn(allActive);
        when(progressMapper.findProcessableStockIds(eq(JOB_TYPE), eq(expectedCommon), anyInt()))
                .thenReturn(expectedCommon);

        BackfillRequest request = new BackfillRequest();
        request.setStartDate(START);
        request.setEndDate(END);
        // stockIds and commonStocksOnly both omitted -> ALL active, filtered to common stocks.

        BackfillResponse response = service.startBackfill(request);

        assertEquals("ALL", response.getMode());
        assertTrue(response.isCommonStocksOnly());
        assertEquals(2, response.getTargetCount());
        assertTrue(response.getTargetCount() < allActive.size(),
                "targetCount must be smaller than the total active count once ETF/special/TDR are excluded");

        verify(progressMapper).upsertPendingReset(eq(expectedCommon), eq(JOB_TYPE), eq(START), eq(END));
        verify(backfillRunner).run(eq(JOB_TYPE), eq(expectedCommon), eq(false));
        // The excluded codes never reach the progress-table write or the batch runner at all --
        // and since BackfillRunner is the ONLY thing that ever issues an external request per
        // stock, never receiving them there IS "no external request issued for them".
        verify(progressMapper, never()).upsertPendingReset(
                org.mockito.ArgumentMatchers.argThat(ids -> ids.contains("0050")), anyString(), any(), any());
        // This preparation step writes/deletes nothing in `stock` or `stock_daily_price` (spec:
        // 本次變更不寫入、不刪除任何資料) -- it only ever reads the population, then hands a (possibly
        // filtered) id list to the progress mapper / batch runner.
        verify(stockMapper, never()).upsert(any());
        verify(stockMapper, never()).update(any());
        verify(stockMapper, never()).insert(any());
        Mockito.verifyNoInteractions(priceIngestionService);
    }

    @Test
    void allMode_commonStocksOnlyFalse_targetsEveryActiveStock_matchesPreFeatureBehavior() {
        List<String> allActive = Arrays.asList("2330", "2317", "0050", "00878", "2881A", "910322");
        when(stockMapper.findActiveStockIds()).thenReturn(allActive);
        when(progressMapper.findProcessableStockIds(eq(JOB_TYPE), eq(allActive), anyInt())).thenReturn(allActive);

        BackfillRequest request = new BackfillRequest();
        request.setStartDate(START);
        request.setEndDate(END);
        request.setCommonStocksOnly(false);

        BackfillResponse response = service.startBackfill(request);

        assertEquals("ALL", response.getMode());
        assertFalse(response.isCommonStocksOnly());
        assertEquals(allActive.size(), response.getTargetCount());
        verify(progressMapper).upsertPendingReset(eq(allActive), eq(JOB_TYPE), eq(START), eq(END));
        verify(backfillRunner).run(eq(JOB_TYPE), eq(allActive), eq(false));
    }

    @Test
    void selectedMode_namedEtfStockId_isNeverFilteredOut_commonStocksOnlyEchoedFalse() {
        when(stockMapper.findExistingStockIds(Collections.singletonList("0050")))
                .thenReturn(Collections.singletonList("0050"));
        when(progressMapper.findProcessableStockIds(eq(JOB_TYPE), eq(Collections.singletonList("0050")), anyInt()))
                .thenReturn(Collections.singletonList("0050"));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Collections.singletonList("0050"));
        request.setStartDate(START);
        request.setEndDate(END);
        // commonStocksOnly omitted -- must not matter at all in SELECTED mode.

        BackfillResponse response = service.startBackfill(request);

        assertEquals("SELECTED", response.getMode());
        assertEquals(1, response.getTargetCount());
        assertFalse(response.isCommonStocksOnly(), "SELECTED mode never applies the common-stock filter");
        verify(stockMapper, never()).findActiveStockIds();
        verify(backfillRunner).run(eq(JOB_TYPE), eq(Collections.singletonList("0050")), eq(false));
    }

    @Test
    void allMode_everyActiveStockIsNonCommon_targetCountZero_noExceptionRaised() {
        List<String> allActive = Arrays.asList("0050", "00878");
        when(stockMapper.findActiveStockIds()).thenReturn(allActive);
        when(progressMapper.findProcessableStockIds(eq(JOB_TYPE), eq(Collections.emptyList()), anyInt()))
                .thenReturn(Collections.emptyList());

        BackfillRequest request = new BackfillRequest();
        request.setStartDate(START);
        request.setEndDate(END);

        BackfillResponse response = service.startBackfill(request);

        assertEquals(0, response.getTargetCount());
        assertTrue(response.isCommonStocksOnly());
        verify(backfillRunner).run(eq(JOB_TYPE), eq(Collections.emptyList()), eq(false));
    }

    /**
     * Mirrors exactly what {@link StartupCatchUpRunner} constructs (stockIds omitted,
     * commonStocksOnly omitted, catchUp=true) via {@link StockSyncService#startBackfillWithLockAlreadyHeld}
     * -- proving the startup catch-up population defaults to common stocks too (spec: 啟動補齊的母體
     * 同樣預設只含普通股), since it shares this exact code path rather than a parallel implementation.
     */
    @Test
    void startupCatchUpStyleRequest_omittedCommonStocksOnly_stillFiltersToCommonStocks() {
        List<String> allActive = Arrays.asList("2330", "2317", "0050", "00878", "2881A", "910322");
        List<String> expectedCommon = Arrays.asList("2330", "2317");
        when(stockMapper.findActiveStockIds()).thenReturn(allActive);
        when(progressMapper.findCaughtUpStockIds(eq(JOB_TYPE), eq(expectedCommon), eq(END)))
                .thenReturn(Collections.emptyList());

        BackfillRequest request = new BackfillRequest();
        request.setStartDate(START);
        request.setEndDate(END);
        request.setCatchUp(true);
        // stockIds and commonStocksOnly both omitted, exactly like StartupCatchUpRunner builds it.

        BackfillOutcome outcome = service.startBackfillWithLockAlreadyHeld(request);

        assertEquals(2, outcome.getResponse().getTargetCount());
        assertTrue(outcome.getResponse().isCommonStocksOnly());
        verify(progressMapper).findCaughtUpStockIds(eq(JOB_TYPE), eq(expectedCommon), eq(END));
        verify(backfillRunner).run(eq(JOB_TYPE), eq(expectedCommon), eq(false));
    }
}
