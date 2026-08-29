package com.stock.service;

import com.stock.config.BackfillProperties;
import com.stock.domain.StatusCount;
import com.stock.domain.StockSyncProgress;
import com.stock.dto.BackfillRequest;
import com.stock.dto.BackfillResponse;
import com.stock.dto.DailySyncResponse;
import com.stock.dto.FailedItemDto;
import com.stock.dto.ProgressResponse;
import com.stock.exception.InvalidDateRangeException;
import com.stock.exception.InvalidSyncModeException;
import com.stock.exception.JobAlreadyRunningException;
import com.stock.exception.UnknownStockIdException;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockSyncProgressMapper;
import com.stock.service.external.TwseClient;
import com.stock.service.external.dto.TwseSnapshotResult;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Service
public class StockSyncService {

    private static final int FAILED_ITEMS_LIMIT = 50;

    private final TwseClient twseClient;
    private final StockMapper stockMapper;
    private final StockSyncProgressMapper progressMapper;
    private final PriceIngestionService priceIngestionService;
    private final BackfillRunner backfillRunner;
    private final JobRunningRegistry jobRunningRegistry;
    private final BackfillProperties backfillProperties;

    public StockSyncService(TwseClient twseClient, StockMapper stockMapper, StockSyncProgressMapper progressMapper,
                             PriceIngestionService priceIngestionService, BackfillRunner backfillRunner,
                             JobRunningRegistry jobRunningRegistry, BackfillProperties backfillProperties) {
        this.twseClient = twseClient;
        this.stockMapper = stockMapper;
        this.progressMapper = progressMapper;
        this.priceIngestionService = priceIngestionService;
        this.backfillRunner = backfillRunner;
        this.jobRunningRegistry = jobRunningRegistry;
        this.backfillProperties = backfillProperties;
    }

    /** Single-request whole-market daily snapshot ingestion; synchronous, no progress tracking needed. */
    public DailySyncResponse syncDaily() {
        TwseSnapshotResult snapshot = twseClient.fetchDailyAll();
        return priceIngestionService.applyDailySnapshot(snapshot);
    }

    public BackfillResponse startBackfill(BackfillRequest request) {
        return startBackfillInternal(request).getResponse();
    }

    /**
     * Same behavior as {@link #startBackfill}, but also returns the batch's actual completion
     * future. Used only by in-process callers (the startup catch-up runner) that need to chain
     * work onto the point the whole batch actually finishes; the HTTP controller uses
     * {@link #startBackfill} and never sees the future.
     */
    public BackfillOutcome startBackfillTrackingCompletion(BackfillRequest request) {
        return startBackfillInternal(request);
    }

    private BackfillOutcome startBackfillInternal(BackfillRequest request) {
        if (request.isResume() && request.isCatchUp()) {
            throw new InvalidSyncModeException();
        }
        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new InvalidDateRangeException("startDate must not be after endDate");
        }

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

        String jobType = StockSyncProgress.JOB_PRICE_BACKFILL;
        if (!jobRunningRegistry.tryStart(jobType)) {
            throw new JobAlreadyRunningException(jobType);
        }

        CompletableFuture<Void> completion;
        try {
            List<String> processableIds;
            if (request.isCatchUp()) {
                // Per-stock decision by last_synced_date vs endDate (spec: catchUp 的語意):
                // already-caught-up stocks are skipped entirely, left untouched, no request at all.
                List<String> caughtUpIds = progressMapper.findCaughtUpStockIds(
                        jobType, targetIds, request.getEndDate());
                processableIds = new ArrayList<>(targetIds);
                processableIds.removeAll(caughtUpIds);
                if (!processableIds.isEmpty()) {
                    progressMapper.upsertPendingForCatchUp(
                            processableIds, jobType, request.getStartDate(), request.getEndDate());
                }
            } else if (request.isResume()) {
                progressMapper.upsertPendingIfAbsent(targetIds, jobType, request.getStartDate(), request.getEndDate());
                processableIds = progressMapper.findProcessableStockIds(
                        jobType, targetIds, backfillProperties.getMaxAttemptCount());
            } else {
                progressMapper.upsertPendingReset(targetIds, jobType, request.getStartDate(), request.getEndDate());
                processableIds = progressMapper.findProcessableStockIds(
                        jobType, targetIds, backfillProperties.getMaxAttemptCount());
            }

            completion = backfillRunner.run(jobType, processableIds, request.isResume());
        } catch (RuntimeException e) {
            jobRunningRegistry.finish(jobType);
            throw e;
        }

        BackfillResponse response = new BackfillResponse(
                jobType, targetIds.size(), request.getStartDate(), request.getEndDate(), mode);
        return new BackfillOutcome(response, completion);
    }

    public ProgressResponse getProgress(String jobType) {
        int total = progressMapper.countTotal(jobType);
        List<StatusCount> counts = progressMapper.countByStatus(jobType);

        int pending = 0;
        int running = 0;
        int done = 0;
        int failed = 0;
        int skipped = 0;
        for (StatusCount c : counts) {
            switch (c.getStatus()) {
                case StockSyncProgress.STATUS_PENDING:
                    pending = c.getCount();
                    break;
                case StockSyncProgress.STATUS_RUNNING:
                    running = c.getCount();
                    break;
                case StockSyncProgress.STATUS_DONE:
                    done = c.getCount();
                    break;
                case StockSyncProgress.STATUS_FAILED:
                    failed = c.getCount();
                    break;
                case StockSyncProgress.STATUS_SKIPPED:
                    skipped = c.getCount();
                    break;
                default:
                    break;
            }
        }

        List<FailedItemDto> failedItems = new ArrayList<>();
        for (StockSyncProgress p : progressMapper.findFailedItems(jobType, FAILED_ITEMS_LIMIT)) {
            failedItems.add(new FailedItemDto(p.getStockId(), p.getAttemptCount(), p.getLastError()));
        }

        return new ProgressResponse(jobType, total, pending, running, done, failed, skipped, failedItems);
    }
}
