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
import com.stock.util.CommonStockCodeUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
        Prepared prepared = prepare(request);

        String jobType = StockSyncProgress.JOB_PRICE_BACKFILL;
        if (!jobRunningRegistry.tryStart(jobType)) {
            throw new JobAlreadyRunningException(jobType);
        }
        try {
            return runBackfill(request, jobType, prepared);
        } catch (RuntimeException e) {
            jobRunningRegistry.finish(jobType);
            throw e;
        }
    }

    /**
     * Variant for callers that must guarantee mutual exclusion with the manual endpoint before
     * this method is even invoked, rather than acquiring the lock inside it — the startup
     * catch-up runner acquires {@link JobRunningRegistry#tryStart} synchronously, on the same
     * thread that handles {@code ApplicationReadyEvent}, *before* dispatching any work. If the
     * lock were instead acquired only once inside this (or an {@code @Async}) method, there would
     * be a real window between the application becoming ready to serve HTTP traffic and that
     * async work actually starting, during which a manual {@code POST /api/stocks/sync/backfill}
     * could slip in and wrongly receive {@code 202} instead of {@code 409} (spec:
     * 啟動補齊執行期間手動觸發回補會得到 409 JOB_ALREADY_RUNNING). This method must never call
     * {@link JobRunningRegistry#tryStart} itself; it only releases the lock (via
     * {@link JobRunningRegistry#finish}) if something fails before the batch is actually handed
     * off to {@link BackfillRunner} — the batch itself releases the lock in its own
     * {@code finally} once every stock has been processed.
     */
    public BackfillOutcome startBackfillWithLockAlreadyHeld(BackfillRequest request) {
        String jobType = StockSyncProgress.JOB_PRICE_BACKFILL;
        try {
            return runBackfill(request, jobType, prepare(request));
        } catch (RuntimeException e) {
            jobRunningRegistry.finish(jobType);
            throw e;
        }
    }

    /** Validates the request and resolves its target stock ids/mode; touches no job lock. */
    private Prepared prepare(BackfillRequest request) {
        if (request.isResume() && request.isCatchUp()) {
            throw new InvalidSyncModeException();
        }
        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new InvalidDateRangeException("startDate must not be after endDate");
        }

        boolean selected = request.getStockIds() != null && !request.getStockIds().isEmpty();
        String mode = selected ? "SELECTED" : "ALL";

        List<String> targetIds;
        boolean commonStocksOnly;
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
            // A named stockIds list is always fetched as-is, filter or not (spec: 提供 stockIds
            // 時本參數不生效——指名的標的一律照跑); the echoed value is therefore always false.
            commonStocksOnly = false;
        } else {
            // Omitted -> true (spec: 省略時視為 true); same shared regex the write-side universe
            // import and the strategy-scan/momentum-gain read-side filters already use
            // (CommonStockCodeUtil) — no second common-stock filter implementation.
            commonStocksOnly = !Boolean.FALSE.equals(request.getCommonStocksOnly());
            List<String> activeIds = stockMapper.findActiveStockIds();
            if (commonStocksOnly) {
                targetIds = new ArrayList<>(activeIds.size());
                for (String id : activeIds) {
                    if (CommonStockCodeUtil.isCommonStockCode(id)) {
                        targetIds.add(id);
                    }
                }
            } else {
                targetIds = activeIds;
            }
        }

        return new Prepared(mode, targetIds, commonStocksOnly);
    }

    /** Resolves per-stock progress rows for the already-validated target list, then hands the
     * processable ids off to {@link BackfillRunner}. Assumes the jobType lock is already held. */
    private BackfillOutcome runBackfill(BackfillRequest request, String jobType, Prepared prepared) {
        List<String> targetIds = prepared.targetIds;

        List<String> processableIds;
        int caughtUpCount = 0;
        if (request.isCatchUp()) {
            // Per-stock decision by last_synced_date vs endDate (spec: catchUp 的語意):
            // already-caught-up stocks are skipped entirely, left untouched, no request at all.
            List<String> caughtUpIds = progressMapper.findCaughtUpStockIds(
                    jobType, targetIds, request.getEndDate());
            caughtUpCount = caughtUpIds.size();
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

        CompletableFuture<Void> completion = backfillRunner.run(jobType, processableIds, request.isResume());

        BackfillResponse response = new BackfillResponse(jobType, targetIds.size(), caughtUpCount,
                prepared.commonStocksOnly, request.getStartDate(), request.getEndDate(), prepared.mode);
        return new BackfillOutcome(response, completion);
    }

    /** Immutable holder for a backfill request's validated mode + resolved target stock ids. */
    private static final class Prepared {
        private final String mode;
        private final List<String> targetIds;
        private final boolean commonStocksOnly;

        private Prepared(String mode, List<String> targetIds, boolean commonStocksOnly) {
            this.mode = mode;
            this.targetIds = targetIds;
            this.commonStocksOnly = commonStocksOnly;
        }
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

        LocalDateTime lastSyncedAt = progressMapper.findLastSyncedAt(jobType);

        return new ProgressResponse(jobType, total, pending, running, done, failed, skipped, lastSyncedAt, failedItems);
    }
}
