package com.stock.mapper;

import com.stock.domain.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collections;
import java.util.List;

@Mapper
public interface StockMapper {

    /** Insert-or-update by stock_id; stock_name/market are refreshed to latest value. */
    int upsert(Stock stock);

    /**
     * Insert-or-update by stock_id for the universe import (specs/backend/stock-universe-import.md)
     * only: on conflict, only stock_name is refreshed — market and is_active are intentionally left
     * untouched (a new row still gets the given market/is_active values on insert). Deliberately
     * separate from {@link #upsert}, whose ON DUPLICATE KEY UPDATE also overwrites is_active/market,
     * which this endpoint's write semantics forbid (it must never auto-delist or auto-relist).
     */
    int upsertUniverse(Stock stock);

    /** Plain insert; caller (StockCatalogService) must check for an existing row first — duplicates must 409, never silently overwrite. */
    int insert(Stock stock);

    /** Full update of stock_name/market/is_active by stock_id. stock_id itself is never updated — it is the key other tables hang off. */
    int update(Stock stock);

    /** All active (is_active = 1) stock ids, used as the "ALL" backfill target set. */
    List<String> findActiveStockIds();

    /** Count of active (is_active = 1) stocks; used as `totalActiveCount` in the universe import response. */
    int countActive();

    /**
     * Subset of the given ids that actually exist in the stock table. Returns an empty list
     * without querying when stockIds is empty (a bare IN () over an empty collection is not
     * valid SQL — MyBatis's <foreach> emits nothing at all, open/close included, for an empty
     * collection).
     */
    default List<String> findExistingStockIds(List<String> stockIds) {
        if (stockIds.isEmpty()) {
            return Collections.emptyList();
        }
        return findExistingStockIdsForNonEmptyIds(stockIds);
    }

    List<String> findExistingStockIdsForNonEmptyIds(@Param("stockIds") List<String> stockIds);

    /** Single stock master row by id, or null if not found. */
    Stock findById(@Param("stockId") String stockId);

    /**
     * Full stock master rows for the given ids (subset of ids that actually exist). Returns an
     * empty list without querying when stockIds is empty; see {@link #findExistingStockIds}.
     */
    default List<Stock> findByIds(List<String> stockIds) {
        if (stockIds.isEmpty()) {
            return Collections.emptyList();
        }
        return findByIdsForNonEmptyIds(stockIds);
    }

    List<Stock> findByIdsForNonEmptyIds(@Param("stockIds") List<String> stockIds);

    /** Full stock master rows for every active (is_active = 1) stock. */
    List<Stock> findActiveStocks();

    /**
     * One page of the stock master, filtered/sorted by columns of the stock table only
     * (idx_market_active covers market+is_active; sort/order are pre-validated against a whitelist
     * by the caller before reaching this query).
     */
    List<Stock> findPage(@Param("keyword") String keyword,
                          @Param("market") String market,
                          @Param("includeInactive") boolean includeInactive,
                          @Param("sortColumn") String sortColumn,
                          @Param("orderDirection") String orderDirection,
                          @Param("limit") int limit,
                          @Param("offset") int offset);

    /** Total row count for the same filter used by findPage, for pagination metadata. */
    long countPage(@Param("keyword") String keyword,
                   @Param("market") String market,
                   @Param("includeInactive") boolean includeInactive);
}
