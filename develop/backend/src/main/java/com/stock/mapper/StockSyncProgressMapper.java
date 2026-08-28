package com.stock.mapper;

import com.stock.domain.StatusCount;
import com.stock.domain.StockSyncProgress;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
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

    StockSyncProgress findOne(@Param("stockId") String stockId, @Param("jobType") String jobType);

    int markRunning(@Param("stockId") String stockId, @Param("jobType") String jobType);

    int markDone(@Param("stockId") String stockId, @Param("jobType") String jobType,
                  @Param("lastSyncedDate") LocalDate lastSyncedDate);

    int markFailed(@Param("stockId") String stockId, @Param("jobType") String jobType,
                    @Param("lastError") String lastError);

    int markSkipped(@Param("stockId") String stockId, @Param("jobType") String jobType);

    int countTotal(@Param("jobType") String jobType);

    List<StatusCount> countByStatus(@Param("jobType") String jobType);

    List<StockSyncProgress> findFailedItems(@Param("jobType") String jobType, @Param("limit") int limit);
}
