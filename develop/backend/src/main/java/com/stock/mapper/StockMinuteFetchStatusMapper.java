package com.stock.mapper;

import com.stock.domain.StockMinuteFetchStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Owns stock_minute_fetch_status. Each write method corresponds to exactly one outcome of a fetch
 * decision/attempt (see specs/backend/stock-minute-price.md, 抓取決策) rather than one generic upsert,
 * so that "what changed and why" stays explicit at the call site — same shape as StockSyncProgressMapper.
 */
@Mapper
public interface StockMinuteFetchStatusMapper {

    /** Current status row for one stock x trade date, or null if never attempted. */
    StockMinuteFetchStatus findOne(@Param("stockId") String stockId, @Param("tradeDate") LocalDate tradeDate);

    /** Successful fetch with at least one bar written. Resets attempt_count/last_error (fresh success). */
    int upsertAvailable(@Param("stockId") String stockId, @Param("tradeDate") LocalDate tradeDate,
                         @Param("barCount") int barCount, @Param("source") String source,
                         @Param("fetchedAt") LocalDateTime fetchedAt);

    /** Successful fetch that returned zero bars (trading day, but source has no minute data). */
    int upsertNoData(@Param("stockId") String stockId, @Param("tradeDate") LocalDate tradeDate,
                      @Param("source") String source, @Param("fetchedAt") LocalDateTime fetchedAt);

    /** Target date is older than the source's rolling window; determined locally, no request was made. */
    int upsertOutOfWindow(@Param("stockId") String stockId, @Param("tradeDate") LocalDate tradeDate);

    /** Target date has no row in stock_daily_price for this stock, i.e. not a trading day. */
    int upsertNotATradingDay(@Param("stockId") String stockId, @Param("tradeDate") LocalDate tradeDate);

    /**
     * Records a failed fetch attempt: sets status=FAILED and increments attempt_count (starting at 1
     * for a brand-new row). Leaves bar_count/source/fetched_at untouched on an existing row.
     */
    int markFailed(@Param("stockId") String stockId, @Param("tradeDate") LocalDate tradeDate,
                    @Param("lastError") String lastError);
}
