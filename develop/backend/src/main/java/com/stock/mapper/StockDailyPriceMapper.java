package com.stock.mapper;

import com.stock.domain.HighLow;
import com.stock.domain.PriceStats;
import com.stock.domain.StockDailyPrice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface StockDailyPriceMapper {

    /** Insert-or-update a single trade-date row. */
    int upsert(StockDailyPrice price);

    /** Stock ids that already have a stored row for the given trade date (used to split insert/update counts). */
    List<String> findStockIdsByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /** Number of rows stored for a stock in a date range (used for idempotency verification). */
    int countByStockAndDateRange(@Param("stockId") String stockId,
                                  @Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate);

    /**
     * Latest and previous trade-date row (trade_date, close_price, volume) for each of the given stock
     * ids — at most 2 rows per id. Callers must page the stock ids first: this query's cost is bounded
     * by the size of stockIds, not by the whole stock_daily_price table.
     */
    List<StockDailyPrice> findLatestTwoByStockIds(@Param("stockIds") List<String> stockIds);

    /** Latest and previous trade-date row for a single stock (0, 1 or 2 rows, latest first). */
    List<StockDailyPrice> findLatestTwoByStockId(@Param("stockId") String stockId);

    /** MIN(trade_date) / COUNT(*) for a single stock; tradingDayCount 0 and firstTradeDate null when no rows exist. */
    PriceStats findPriceStats(@Param("stockId") String stockId);

    /** Single OHLCV row for one stock x trade date, or null when that date isn't a trading day for it. */
    StockDailyPrice findOne(@Param("stockId") String stockId, @Param("tradeDate") LocalDate tradeDate);

    /** Full OHLCV rows for one stock in [startDate, endDate], ascending by trade_date. */
    List<StockDailyPrice> findByStockAndDateRange(@Param("stockId") String stockId,
                                                   @Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate);

    /** Up to `limit` trade dates strictly before beforeDate, most recent first. Used to size the warmup window. */
    List<LocalDate> findRecentTradeDatesBefore(@Param("stockId") String stockId,
                                                @Param("beforeDate") LocalDate beforeDate,
                                                @Param("limit") int limit);

    /** MAX(high_price)/MIN(low_price) over the most recent `days` trading days up to and including tradeDate. */
    HighLow findRecentHighLow(@Param("stockId") String stockId,
                               @Param("tradeDate") LocalDate tradeDate,
                               @Param("days") int days);

    /** MAX(trade_date) for a single stock, or null if it has no price rows. */
    LocalDate findLatestTradeDate(@Param("stockId") String stockId);

    /** The trading day immediately before beforeDate, or null if none exists. */
    LocalDate findPreviousTradeDate(@Param("stockId") String stockId, @Param("beforeDate") LocalDate beforeDate);

    /** The trading day immediately after afterDate, or null if none exists. */
    LocalDate findNextTradeDateAfter(@Param("stockId") String stockId, @Param("afterDate") LocalDate afterDate);

    /** MAX(trade_date) across the whole table; used as the default statistics endDate when omitted. */
    LocalDate findGlobalLatestTradeDate();

    /** MIN(trade_date) across the whole table that is on/after candidateStart; used as the default statistics startDate. */
    LocalDate findGlobalFirstTradeDateOnOrAfter(@Param("candidateStart") LocalDate candidateStart);
}
