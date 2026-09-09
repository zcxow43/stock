package com.stock.mapper;

import com.stock.domain.StatusCount;
import com.stock.domain.StockSyncProgress;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Mapper
public interface StockSyncProgressMapper {

    /**
     * Batch-create/reset progress rows to PENDING for every id in stockIds
     * (full reset: attempt_count=0, last_synced_date=NULL). Used when resume=false.
     *
     * A no-op when stockIds is empty (spec: 目標清單為空是合法情形 — ALL mode with zero
     * is_active=1 rows must complete without issuing any SQL against an empty id list; MyBatis's
     * {@code <foreach>} silently emits nothing at all, open/close included, for an empty
     * collection, which would otherwise produce a syntactically invalid "INSERT ... VALUES" with
     * no rows).
     */
    default void upsertPendingReset(List<String> stockIds, String jobType, LocalDate startDate, LocalDate endDate) {
        if (stockIds.isEmpty()) {
            return;
        }
        upsertPendingResetForNonEmptyIds(stockIds, jobType, startDate, endDate);
    }

    void upsertPendingResetForNonEmptyIds(@Param("stockIds") List<String> stockIds,
                                           @Param("jobType") String jobType,
                                           @Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate);

    /**
     * Batch-create progress rows only for ids that don't already have one; existing rows
     * (and their status/progress) are left untouched. Used when resume=true.
     *
     * No-op when stockIds is empty; see {@link #upsertPendingReset} for why this guard exists.
     */
    default void upsertPendingIfAbsent(List<String> stockIds, String jobType, LocalDate startDate,
                                        LocalDate endDate) {
        if (stockIds.isEmpty()) {
            return;
        }
        upsertPendingIfAbsentForNonEmptyIds(stockIds, jobType, startDate, endDate);
    }

    void upsertPendingIfAbsentForNonEmptyIds(@Param("stockIds") List<String> stockIds,
                                              @Param("jobType") String jobType,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);

    /**
     * Ids within stockIds that are still processable: status PENDING/FAILED and under the retry
     * cap. Returns an empty list without querying when stockIds is empty; see
     * {@link #upsertPendingReset} for why this guard exists.
     */
    default List<String> findProcessableStockIds(String jobType, List<String> stockIds, int maxAttempts) {
        if (stockIds.isEmpty()) {
            return Collections.emptyList();
        }
        return findProcessableStockIdsForNonEmptyIds(jobType, stockIds, maxAttempts);
    }

    List<String> findProcessableStockIdsForNonEmptyIds(@Param("jobType") String jobType,
                                                         @Param("stockIds") List<String> stockIds,
                                                         @Param("maxAttempts") int maxAttempts);

    /**
     * Ids within stockIds already synced through (or past) endDate. Used by catchUp mode to
     * decide which stocks to skip entirely (no external request at all). Returns an empty list
     * without querying when stockIds is empty (this is the exact query that, unguarded, throws
     * BadSqlGrammarException from a truncated "... AND stock_id IN" when ALL mode resolves to zero
     * active stocks — spec: 目標清單為空是合法情形).
     */
    default List<String> findCaughtUpStockIds(String jobType, List<String> stockIds, LocalDate endDate) {
        if (stockIds.isEmpty()) {
            return Collections.emptyList();
        }
        return findCaughtUpStockIdsForNonEmptyIds(jobType, stockIds, endDate);
    }

    List<String> findCaughtUpStockIdsForNonEmptyIds(@Param("jobType") String jobType,
                                                      @Param("stockIds") List<String> stockIds,
                                                      @Param("endDate") LocalDate endDate);

    /**
     * Re-opens each id (in stockIds, which must exclude already-caught-up ids) as PENDING for a
     * catchUp run: continues from last_synced_date + 1 day when a prior progress row exists (or
     * from startDate for a brand-new/never-synced row), and resets attempt_count to 0 since
     * falling behind is due to time passing, not prior failures.
     *
     * No-op when stockIds is empty; see {@link #upsertPendingReset} for why this guard exists.
     */
    default void upsertPendingForCatchUp(List<String> stockIds, String jobType, LocalDate startDate,
                                          LocalDate endDate) {
        if (stockIds.isEmpty()) {
            return;
        }
        upsertPendingForCatchUpForNonEmptyIds(stockIds, jobType, startDate, endDate);
    }

    void upsertPendingForCatchUpForNonEmptyIds(@Param("stockIds") List<String> stockIds,
                                                @Param("jobType") String jobType,
                                                @Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate);

    StockSyncProgress findOne(@Param("stockId") String stockId, @Param("jobType") String jobType);

    int markRunning(@Param("stockId") String stockId, @Param("jobType") String jobType);

    /**
     * Reverts a stock from RUNNING back to PENDING without touching attempt_count/last_error/
     * last_synced_date. Used only when every price history source is currently blocked/unavailable
     * (spec: 所有來源皆不可用 — 該檔維持或回到 PENDING) — this stock's progress must look exactly as if
     * it had never been attempted this run.
     */
    int markPending(@Param("stockId") String stockId, @Param("jobType") String jobType);

    int markDone(@Param("stockId") String stockId, @Param("jobType") String jobType,
                  @Param("lastSyncedDate") LocalDate lastSyncedDate);

    int markFailed(@Param("stockId") String stockId, @Param("jobType") String jobType,
                    @Param("lastError") String lastError);

    int markSkipped(@Param("stockId") String stockId, @Param("jobType") String jobType);

    /**
     * Marks a stock SKIPPED (no trading data in the queried range) while still recording
     * last_synced_date as the range's endDate. Distinct from markSkipped (used by other job
     * types that don't track catch-up continuation) so that a queried range landing entirely on
     * a weekend/holiday is remembered as "already processed through endDate" and a subsequent
     * catchUp call for the same endDate makes no external request at all.
     */
    int markSkippedThrough(@Param("stockId") String stockId, @Param("jobType") String jobType,
                            @Param("lastSyncedDate") LocalDate lastSyncedDate);

    int countTotal(@Param("jobType") String jobType);

    List<StatusCount> countByStatus(@Param("jobType") String jobType);

    /**
     * Latest completion time across this jobType's DONE rows (MAX(finished_at) WHERE status =
     * 'DONE'), or null when none have ever completed. FAILED/SKIPPED rows also set finished_at
     * but are excluded, so a run where every stock fails never advances this value.
     */
    LocalDateTime findLastSyncedAt(@Param("jobType") String jobType);

    List<StockSyncProgress> findFailedItems(@Param("jobType") String jobType, @Param("limit") int limit);

    /**
     * Advances last_synced_date forward, and ONLY forward, for whichever of stockIds already have
     * an existing {@code job_type} progress row (spec: 寫入行情的路徑都必須推進進度). Never inserts a
     * row: a stock_id with no existing row for this jobType is silently left alone, which is
     * exactly what keeps this safe to call from the daily snapshot path (~1,378 stocks, most of
     * which have no PRICE_BACKFILL progress row at all under the default commonStocksOnly
     * population) without inflating {@code total} (spec: 每日增量不新建進度列). Never touches
     * {@code status} — a FAILED/DONE/PENDING row keeps its status exactly as it was; only the date
     * moves. No-op when stockIds is empty; see {@link #upsertPendingReset} for why this guard exists.
     */
    default void advanceLastSyncedDateForExisting(List<String> stockIds, String jobType, LocalDate tradeDate) {
        if (stockIds.isEmpty()) {
            return;
        }
        advanceLastSyncedDateForExistingForNonEmptyIds(stockIds, jobType, tradeDate);
    }

    void advanceLastSyncedDateForExistingForNonEmptyIds(@Param("stockIds") List<String> stockIds,
                                                          @Param("jobType") String jobType,
                                                          @Param("tradeDate") LocalDate tradeDate);

    /**
     * MIN(target_start_date) among the given stockIds' {@code job_type} progress rows — the day the
     * ALL-mode day-by-day snapshot loop ({@code SnapshotBackfillRunner}) must start iterating from.
     * In the steady state every target shares the same target_start_date (the whole population is
     * always advanced together — spec: 逐日快照涵蓋全母體，因此不存在...參差狀態); this takes the MIN so a
     * newly-added target with an earlier/unset start never gets silently skipped. Returns null when
     * stockIds is empty (caller must not query in that case; see {@link #upsertPendingReset}).
     */
    default LocalDate findMinTargetStartDate(String jobType, List<String> stockIds) {
        if (stockIds.isEmpty()) {
            return null;
        }
        return findMinTargetStartDateForNonEmptyIds(jobType, stockIds);
    }

    LocalDate findMinTargetStartDateForNonEmptyIds(@Param("jobType") String jobType,
                                                    @Param("stockIds") List<String> stockIds);

    /**
     * Bulk-marks every id in stockIds DONE for jobType, without touching last_synced_date — used by
     * {@code SnapshotBackfillRunner} once the whole day-by-day loop reaches endDate, since each
     * day's snapshot write already advanced last_synced_date incrementally (spec: 逐日回補的處理流程
     * step 6). No-op when stockIds is empty; see {@link #upsertPendingReset}.
     */
    default void markDoneForIds(String jobType, List<String> stockIds) {
        if (stockIds.isEmpty()) {
            return;
        }
        markDoneForNonEmptyIds(jobType, stockIds);
    }

    void markDoneForNonEmptyIds(@Param("jobType") String jobType, @Param("stockIds") List<String> stockIds);
}
