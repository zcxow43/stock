package com.stock.mapper;

import com.stock.domain.StockInstitutionalTrade;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface StockInstitutionalTradeMapper {

    /** Insert-or-update a single (stock_id, trade_date) row; all 17 numeric columns overwritten on conflict. */
    int upsert(StockInstitutionalTrade row);

    /** Single row lookup, or null if that security has no institutional-trade row for that date. */
    StockInstitutionalTrade findOne(@Param("stockId") String stockId, @Param("tradeDate") LocalDate tradeDate);

    /** Row count for a single trade date; used by tests/diagnostics to confirm all-or-nothing writes. */
    int countByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /**
     * Trading days within [startDate, endDate] that have at least one {@code stock_daily_price} row
     * but zero {@code stock_institutional_trade} rows — the exact set this module still needs to
     * fetch, resolved in a single query rather than one query per candidate date (spec: 缺少日期必須
     * 以一次查詢求出，不得對區間內每個日期各查一次). A weekend/holiday never appears here because it never
     * has a stock_daily_price row either (spec: 以日線的交易日為準，而不是逐個日曆日請求). Ascending order so
     * the caller processes oldest-missing-first (spec: 缺少的日期由舊到新逐日處理).
     */
    List<LocalDate> findMissingTradeDates(@Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate);

    /**
     * Full rows for the given stock ids within [startDate, endDate] — one batched range query over
     * the (stock_id, trade_date) composite primary key, not one query per stock. Ordered by stock_id
     * then trade_date ascending. Shared by all three institutional strategy-scan patterns within one
     * scan — see specs/backend/strategy-scan.md, "法人籌碼型態另有兩個批次讀取...同一次掃描中三個型態共用同一份讀取結果".
     */
    List<StockInstitutionalTrade> findByStockIdsAndDateRange(@Param("stockIds") List<String> stockIds,
                                                              @Param("startDate") LocalDate startDate,
                                                              @Param("endDate") LocalDate endDate);

    /**
     * Distinct trade dates within [startDate, endDate] that have at least one row for ANY security
     * (not scoped to any particular stock id) — the "已抓取" signal a strategy-scan D relies on
     * (spec: "該日法人資料已抓取（stock_institutional_trade 在該日有任何一列）"). One query regardless of how many
     * stocks are being scanned.
     */
    List<LocalDate> findFetchedTradeDates(@Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate);
}
