package com.stock.mapper;

import com.stock.domain.StatSeriesRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * The one query behind GET /api/stocks/statistics: stock_daily_price LEFT JOIN
 * stock_daily_indicator (never INNER JOIN — see specs/backend/stock-indicator-statistics.md,
 * "指標尚未運算時的行為"). Price columns are always present; indicator columns are null
 * whenever that trade date has no computed (non-warmup) indicator row yet.
 */
@Mapper
public interface StockStatisticsMapper {

    List<StatSeriesRow> findSeries(@Param("stockIds") List<String> stockIds,
                                    @Param("startDate") LocalDate startDate,
                                    @Param("endDate") LocalDate endDate,
                                    @Param("paramKey") String paramKey);
}
