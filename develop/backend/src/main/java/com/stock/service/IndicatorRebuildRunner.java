package com.stock.service;

import com.stock.config.IndicatorProperties;
import com.stock.domain.HighLow;
import com.stock.domain.StockDailyIndicator;
import com.stock.domain.StockDailyPrice;
import com.stock.exception.NeedsFullRebuildException;
import com.stock.mapper.StockDailyIndicatorMapper;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockSyncProgressMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Sequentially works through the target stock list for an INDICATOR_REBUILD batch. Unlike
 * BackfillRunner there is no external rate limit to respect (the only data source is our own
 * database), so this can process the whole list back-to-back on its dedicated executor.
 */
@Component
public class IndicatorRebuildRunner {

    private static final Logger log = LoggerFactory.getLogger(IndicatorRebuildRunner.class);
    private static final int MAX_ERROR_LENGTH = 500;
    private static final LocalDate EARLIEST_POSSIBLE_DATE = LocalDate.of(1900, 1, 1);
    private static final int RSV_WINDOW = 9;

    private final StockDailyPriceMapper priceMapper;
    private final StockDailyIndicatorMapper indicatorMapper;
    private final StockSyncProgressMapper progressMapper;
    private final IndicatorCalculationService calculationService;
    private final IndicatorPersistenceService persistenceService;
    private final JobRunningRegistry jobRunningRegistry;
    private final IndicatorProperties properties;

    public IndicatorRebuildRunner(StockDailyPriceMapper priceMapper, StockDailyIndicatorMapper indicatorMapper,
                                   StockSyncProgressMapper progressMapper,
                                   IndicatorCalculationService calculationService,
                                   IndicatorPersistenceService persistenceService,
                                   JobRunningRegistry jobRunningRegistry, IndicatorProperties properties) {
        this.priceMapper = priceMapper;
        this.indicatorMapper = indicatorMapper;
        this.progressMapper = progressMapper;
        this.calculationService = calculationService;
        this.persistenceService = persistenceService;
        this.jobRunningRegistry = jobRunningRegistry;
        this.properties = properties;
    }

    @Async("indicatorRebuildExecutor")
    public void run(String jobType, List<String> processableStockIds, String computeMode,
                     LocalDate requestStartDate, LocalDate requestEndDate, String paramKey) {
        try {
            for (String stockId : processableStockIds) {
                try {
                    if ("INCREMENTAL".equals(computeMode)) {
                        processIncremental(jobType, stockId, requestEndDate, paramKey);
                    } else {
                        processFull(jobType, stockId, requestStartDate, requestEndDate, paramKey);
                    }
                } catch (Throwable e) {
                    log.warn("Indicator rebuild failed for stock {} ({}, mode={})", stockId, jobType, computeMode, e);
                    safeMarkFailed(stockId, jobType, e);
                }
            }
        } finally {
            jobRunningRegistry.finish(jobType);
        }
    }

    private void processFull(String jobType, String stockId, LocalDate requestStartDate,
                              LocalDate requestEndDate, String paramKey) {
        progressMapper.markRunning(stockId, jobType);

        LocalDate outputEnd = requestEndDate != null ? requestEndDate : priceMapper.findLatestTradeDate(stockId);
        if (outputEnd == null) {
            progressMapper.markSkipped(stockId, jobType);
            return;
        }

        LocalDate fetchStart;
        if (requestStartDate != null) {
            List<LocalDate> warmupDates = priceMapper.findRecentTradeDatesBefore(
                    stockId, requestStartDate, properties.getWarmupTradingDays());
            fetchStart = warmupDates.isEmpty() ? requestStartDate : warmupDates.get(warmupDates.size() - 1);
        } else {
            fetchStart = EARLIEST_POSSIBLE_DATE;
        }

        List<StockDailyPrice> rows = priceMapper.findByStockAndDateRange(stockId, fetchStart, outputEnd);
        if (rows.isEmpty()) {
            progressMapper.markSkipped(stockId, jobType);
            return;
        }

        List<StockDailyIndicator> computed = calculationService.computeFull(stockId, paramKey, rows, requestStartDate);

        persistenceService.replaceFull(stockId, paramKey, computed);
        progressMapper.markDone(stockId, jobType, outputEnd);
    }

    private void processIncremental(String jobType, String stockId, LocalDate requestEndDate, String paramKey) {
        progressMapper.markRunning(stockId, jobType);

        LocalDate outputEnd = requestEndDate != null ? requestEndDate : priceMapper.findLatestTradeDate(stockId);
        if (outputEnd == null) {
            progressMapper.markSkipped(stockId, jobType);
            return;
        }

        // Spec: an incremental step must never seed from a default value when the previous
        // trading day's indicator row is missing — that would silently poison the whole recursive
        // chain from that point forward. Route the stock to a full rebuild instead.
        StockDailyIndicator state = indicatorMapper.findLatestNonWarmup(stockId, paramKey);
        if (state == null) {
            throw new NeedsFullRebuildException(stockId);
        }

        if (!state.getTradeDate().isBefore(outputEnd)) {
            progressMapper.markDone(stockId, jobType, state.getTradeDate());
            return;
        }

        List<StockDailyPrice> toProcess = priceMapper.findByStockAndDateRange(
                stockId, state.getTradeDate().plusDays(1), outputEnd);
        if (toProcess.isEmpty()) {
            progressMapper.markDone(stockId, jobType, state.getTradeDate());
            return;
        }

        List<StockDailyIndicator> newRows = new ArrayList<>(toProcess.size());
        for (StockDailyPrice row : toProcess) {
            HighLow highLow = priceMapper.findRecentHighLow(stockId, row.getTradeDate(), RSV_WINDOW);
            StockDailyIndicator next = calculationService.computeIncrementalStep(stockId, paramKey, state, row, highLow);
            newRows.add(next);
            state = next;
        }

        persistenceService.appendIncremental(newRows);
        progressMapper.markDone(stockId, jobType, state.getTradeDate());
    }

    private void safeMarkFailed(String stockId, String jobType, Throwable e) {
        try {
            progressMapper.markFailed(stockId, jobType, truncate(e.getMessage()));
        } catch (Exception markFailureError) {
            log.error("Failed to record failure for stock {} ({})", stockId, jobType, markFailureError);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
