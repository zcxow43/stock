package com.stock.mapper;

import com.stock.domain.StatusCount;
import com.stock.domain.StockSyncProgress;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface StockSyncProgressMapper {

    /**
     * Batch-create/reset progress rows to PENDING for every id in stockIds
     * (full reset: attempt_count=0, last_synced_date=NULL). Used when resume=false.
     */
    void upsertPendingReset(@Param("stockIds") List<String> stockIds,
                             @Param("jobType") String jobType,
                             @Param("startDate") LocalDate startDate,
                             @Param("endDate") LocalDate endDate);

    /**
     * Batch-create progress rows only for ids that don't already have one; existing rows
     * (and their status/progress) are left untouched. Used when resume=true.
     */
    void upsertPendingIfAbsent(@Param("stockIds") List<String> stockIds,
                                @Param("jobType") String jobType,
                                @Param("startDate") LocalDate startDate,
                                @Param("endDate") LocalDate endDate);

    /** Ids within stockIds that are still processable: status PENDING/FAILED and under the retry cap. */
    List<String> findProcessableStockIds(@Param("jobType") String jobType,
                                          @Param("stockIds") List<String> stockIds,
                                          @Param("maxAttempts") int maxAttempts);

    /**
     * Ids within stockIds already synced through (or past) endDate. Used by catchUp mode to
     * decide which stocks to skip entirely (no external request at all).
     */
    List<String> findCaughtUpStockIds(@Param("jobType") String jobType,
                                       @Param("stockIds") List<String> stockIds,
                                       @Param("endDate") LocalDate endDate);

    /**
     * Re-opens each id (in stockIds, which must exclude already-caught-up ids) as PENDING for a
     * catchUp run: continues from last_synced_date + 1 day when a prior progress row exists (or
     * from startDate for a brand-new/never-synced row), and resets attempt_count to 0 since
     * falling behind is due to time passing, not prior failures.
     */
    void upsertPendingForCatchUp(@Param("stockIds") List<String> stockIds,
                                  @Param("jobType") String jobType,
                                  @Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate);

    StockSyncProgress findOne(@Param("stockId") String stockId, @Param("jobType") String jobType);

    int markRunning(@Param("stockId") String stockId, @Param("jobType") String jobType);

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
}
