package com.stock.service;

import com.stock.config.IndicatorProperties;
import com.stock.domain.StockDailyIndicator;
import com.stock.domain.StockSyncProgress;
import com.stock.exception.JobAlreadyRunningException;
import com.stock.mapper.StockDailyIndicatorMapper;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockSyncProgressMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context, no DB) for {@link IndicatorRebuildService#rebuildForStartup()}
 * — the per-stock FULL-vs-INCREMENTAL decision the startup auto-rebuild relies on (spec:
 * stock-indicator-statistics.md "啟動補齊後自動重算" / "模式逐檔決定"). The manual endpoint's
 * {@link IndicatorRebuildService#rebuild} keeps its own fixed-mode-for-the-whole-batch contract and is
 * covered elsewhere (StockIndicatorStatisticsIntegrationTest); this class only exercises the new
 * startup-only method.
 */
class IndicatorRebuildServiceTest {

    private StockMapper stockMapper;
    private StockSyncProgressMapper progressMapper;
    private StockDailyIndicatorMapper indicatorMapper;
    private IndicatorRebuildRunner runner;
    private JobRunningRegistry jobRunningRegistry;
    private IndicatorProperties properties;
    private IndicatorRebuildService service;

    @BeforeEach
    void setUp() {
        stockMapper = mock(StockMapper.class);
        progressMapper = mock(StockSyncProgressMapper.class);
        indicatorMapper = mock(StockDailyIndicatorMapper.class);
        runner = mock(IndicatorRebuildRunner.class);
        jobRunningRegistry = new JobRunningRegistry();
        properties = new IndicatorProperties();
        service = new IndicatorRebuildService(stockMapper, progressMapper, indicatorMapper, runner,
                jobRunningRegistry, properties);

        when(progressMapper.findProcessableStockIds(anyString(), anyList(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stockWithNoExistingIndicatorRows_isRoutedToFull() {
        when(stockMapper.findActiveStockIds()).thenReturn(Collections.singletonList("1101"));
        when(indicatorMapper.findStockIdsWithAnyIndicator(anyList(), eq(StockDailyIndicator.PARAM_KEY)))
                .thenReturn(Collections.emptyList());

        service.rebuildForStartup();

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(runner).run(eq(StockSyncProgress.JOB_INDICATOR_REBUILD), eq(Collections.singletonList("1101")),
                captor.capture(), isNull(), isNull(), eq(StockDailyIndicator.PARAM_KEY));
        assertEquals("FULL", captor.getValue().get("1101"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stockWithExistingIndicatorRows_isRoutedToIncremental() {
        when(stockMapper.findActiveStockIds()).thenReturn(Collections.singletonList("2330"));
        when(indicatorMapper.findStockIdsWithAnyIndicator(anyList(), eq(StockDailyIndicator.PARAM_KEY)))
                .thenReturn(Collections.singletonList("2330"));

        service.rebuildForStartup();

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(runner).run(eq(StockSyncProgress.JOB_INDICATOR_REBUILD), eq(Collections.singletonList("2330")),
                captor.capture(), isNull(), isNull(), eq(StockDailyIndicator.PARAM_KEY));
        assertEquals("INCREMENTAL", captor.getValue().get("2330"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixedBatch_routesEachStockIndependently() {
        List<String> targetIds = Arrays.asList("1101", "2330", "2317");
        when(stockMapper.findActiveStockIds()).thenReturn(targetIds);
        // 2330 already has indicator rows; 1101 and 2317 do not.
        when(indicatorMapper.findStockIdsWithAnyIndicator(anyList(), eq(StockDailyIndicator.PARAM_KEY)))
                .thenReturn(Collections.singletonList("2330"));

        service.rebuildForStartup();

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(runner).run(eq(StockSyncProgress.JOB_INDICATOR_REBUILD), eq(targetIds), captor.capture(),
                isNull(), isNull(), eq(StockDailyIndicator.PARAM_KEY));
        Map<String, String> modeByStockId = captor.getValue();
        assertEquals("FULL", modeByStockId.get("1101"));
        assertEquals("INCREMENTAL", modeByStockId.get("2330"));
        assertEquals("FULL", modeByStockId.get("2317"));
    }

    @Test
    void noProcessableStocks_doesNotQueryIndicatorExistence_andStillRunsWithEmptyList() {
        when(stockMapper.findActiveStockIds()).thenReturn(Collections.singletonList("1101"));
        when(progressMapper.findProcessableStockIds(anyString(), anyList(), anyInt()))
                .thenReturn(Collections.emptyList());

        service.rebuildForStartup();

        verify(indicatorMapper, never()).findStockIdsWithAnyIndicator(anyList(), anyString());
        verify(runner).run(eq(StockSyncProgress.JOB_INDICATOR_REBUILD), eq(Collections.emptyList()),
                any(Map.class), isNull(), isNull(), eq(StockDailyIndicator.PARAM_KEY));
    }

    @Test
    void alreadyRunning_throwsAndNeverCallsRunner() {
        when(stockMapper.findActiveStockIds()).thenReturn(Collections.singletonList("1101"));
        jobRunningRegistry.tryStart(StockSyncProgress.JOB_INDICATOR_REBUILD);

        assertThrows(JobAlreadyRunningException.class, () -> service.rebuildForStartup());

        verify(runner, never()).run(anyString(), anyList(), any(Map.class), any(), any(), anyString());
    }
}
