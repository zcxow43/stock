package com.stock.mapper;

import com.stock.domain.StockMinutePrice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface StockMinutePriceMapper {

    /** Insert-or-update a single 1-minute bar. Idempotent: re-fetching the same minute overwrites, not duplicates. */
    int upsert(StockMinutePrice bar);

    /** All 1-minute bars for one stock x trade date, ascending by bar_time. Never includes zero-priced rows. */
    List<StockMinutePrice> findByStockAndDate(@Param("stockId") String stockId, @Param("tradeDate") LocalDate tradeDate);
}
