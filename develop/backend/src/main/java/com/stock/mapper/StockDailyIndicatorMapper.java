package com.stock.mapper;

import com.stock.domain.StockDailyIndicator;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface StockDailyIndicatorMapper {

    /** Batch insert-or-update; one row per (stock_id, trade_date, param_key). */
    int upsertBatch(@Param("rows") List<StockDailyIndicator> rows);

    /** Discards every stored row for a stock+param combination (full rebuild). */
    int deleteByStockAndParam(@Param("stockId") String stockId, @Param("paramKey") String paramKey);

    /** Single row by exact key, or null if not present (any is_warmup value). */
    StockDailyIndicator findOne(@Param("stockId") String stockId, @Param("tradeDate") LocalDate tradeDate,
                                 @Param("paramKey") String paramKey);

    /** Most recent converged (is_warmup = 0) row for a stock+param, or null if none exists. */
    StockDailyIndicator findLatestNonWarmup(@Param("stockId") String stockId, @Param("paramKey") String paramKey);

    /**
     * Number of converged (is_warmup = 0) rows strictly before beforeDate, capped at `cap` rows scanned
     * (only need to know whether it reaches the warmup threshold, not the exact count beyond it).
     */
    int countNonWarmupBefore(@Param("stockId") String stockId, @Param("paramKey") String paramKey,
                              @Param("beforeDate") LocalDate beforeDate, @Param("cap") int cap);
}
