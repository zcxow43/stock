package com.stock.service;

import com.stock.config.IndicatorProperties;
import com.stock.domain.StockDailyIndicator;
import com.stock.domain.StockSyncProgress;
import com.stock.dto.IndicatorRebuildRequest;
import com.stock.dto.IndicatorRebuildResponse;
import com.stock.exception.InvalidDateRangeException;
import com.stock.exception.JobAlreadyRunningException;
import com.stock.exception.UnknownStockIdException;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockSyncProgressMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates and kicks off an INDICATOR_REBUILD batch (POST /api/stocks/indicators/rebuild); the
 * actual per-stock computation runs asynchronously in IndicatorRebuildRunner. Progress is tracked
 * through the same stock_sync_progress table/endpoint used by price backfill (job_type discriminates).
 */
@Service
public class IndicatorRebuildService {

    // Sentinels stored in stock_sync_progress.target_start_date/target_end_date (NOT NULL columns)
    // when the request omits startDate/endDate. Unlike price backfill, the indicator rebuild runner
    // never relies on these columns to know what to compute — it re-resolves the real per-stock
    // window from stock_daily_price at process time, since (unlike an external API) that data is
    // already ours to query cheaply. These values are purely informational/audit here.
    private static final LocalDate OPEN_START_SENTINEL = LocalDate.of(1900, 1, 1);
    private static final LocalDate OPEN_END_SENTINEL = LocalDate.of(2999, 12, 31);

    private final StockMapper stockMapper;
    private final StockSyncProgressMapper progressMapper;
    private final IndicatorRebuildRunner runner;
    private final JobRunningRegistry jobRunningRegistry;
    private final IndicatorProperties properties;

    public IndicatorRebuildService(StockMapper stockMapper, StockSyncProgressMapper progressMapper,
                                    IndicatorRebuildRunner runner, JobRunningRegistry jobRunningRegistry,
                                    IndicatorProperties properties) {
        this.stockMapper = stockMapper;
        this.progressMapper = progressMapper;
        this.runner = runner;
        this.jobRunningRegistry = jobRunningRegistry;
        this.properties = properties;
    }

    public IndicatorRebuildResponse rebuild(IndicatorRebuildRequest request) {
        if (request.getStartDate() != null && request.getEndDate() != null
                && request.getStartDate().isAfter(request.getEndDate())) {
            throw new InvalidDateRangeException("startDate must not be after endDate");
        }

        String computeMode = "INCREMENTAL".equalsIgnoreCase(request.getMode()) ? "INCREMENTAL" : "FULL";

        boolean selected = request.getStockIds() != null && !request.getStockIds().isEmpty();
        String mode = selected ? "SELECTED" : "ALL";

        List<String> targetIds;
        if (selected) {
            targetIds = new ArrayList<>(new LinkedHashSet<>(request.getStockIds()));
            List<String> existing = stockMapper.findExistingStockIds(targetIds);
            Set<String> existingSet = new HashSet<>(existing);
            List<String> unknown = new ArrayList<>();
            for (String id : targetIds) {
                if (!existingSet.contains(id)) {
                    unknown.add(id);
                }
            }
            if (!unknown.isEmpty()) {
                throw new UnknownStockIdException(unknown);
            }
        } else {
            targetIds = stockMapper.findActiveStockIds();
        }

        String jobType = StockSyncProgress.JOB_INDICATOR_REBUILD;
        if (!jobRunningRegistry.tryStart(jobType)) {
            throw new JobAlreadyRunningException(jobType);
        }

        LocalDate progressStart = request.getStartDate() != null ? request.getStartDate() : OPEN_START_SENTINEL;
        LocalDate progressEnd = request.getEndDate() != null ? request.getEndDate() : OPEN_END_SENTINEL;

        try {
            if (request.isResume()) {
                progressMapper.upsertPendingIfAbsent(targetIds, jobType, progressStart, progressEnd);
            } else {
                progressMapper.upsertPendingReset(targetIds, jobType, progressStart, progressEnd);
            }

            List<String> processableIds = progressMapper.findProcessableStockIds(
                    jobType, targetIds, properties.getMaxAttemptCount());

            runner.run(jobType, processableIds, computeMode, request.getStartDate(), request.getEndDate(),
                    StockDailyIndicator.PARAM_KEY);
        } catch (RuntimeException e) {
            jobRunningRegistry.finish(jobType);
            throw e;
        }

        return new IndicatorRebuildResponse(jobType, targetIds.size(), mode, computeMode,
                properties.getWarmupTradingDays(), StockDailyIndicator.PARAM_KEY);
    }
}
