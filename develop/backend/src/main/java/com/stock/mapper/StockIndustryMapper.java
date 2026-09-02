package com.stock.mapper;

import com.stock.domain.StockIndustry;
import com.stock.domain.StockIndustryLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collections;
import java.util.List;

/**
 * Maintains the `stock_industry` many-to-many table (specs/dba/stock-industry.md). Written only by
 * the universe import's "whole-set replace" step (specs/backend/stock-universe-import.md 產業別的
 * 寫入語意): for exactly the set of stocks the industry source covers this run, existing links are
 * deleted and this run's links inserted, in that order, inside the same transaction.
 */
@Mapper
public interface StockIndustryMapper {

    /**
     * Deletes every existing stock_industry row for the given stock ids — the "先刪" half of the
     * whole-set-replace semantics. Must only be called with the set of stocks this run's industry
     * source actually covers (spec: 範圍嚴格限制在本次來源有涵蓋到的股票); a stock absent from that
     * set must never be passed here, or its untouched-on-purpose links would be wiped. No-op when
     * stockIds is empty (an empty SQL {@code IN ()} is invalid — see
     * {@link StockMapper#findExistingStockIds}).
     */
    default void deleteByStockIds(List<String> stockIds) {
        if (!stockIds.isEmpty()) {
            deleteByStockIdsForNonEmpty(stockIds);
        }
    }

    void deleteByStockIdsForNonEmpty(@Param("stockIds") List<String> stockIds);

    /**
     * Batch-inserts the replacement links. {@code ON DUPLICATE KEY UPDATE} is a no-op safety guard
     * against the same (stock_id, industry_id) pair appearing twice within one source response, not
     * a real update path. No-op when links is empty.
     */
    default void insertBatch(List<StockIndustry> links) {
        if (!links.isEmpty()) {
            insertBatchForNonEmpty(links);
        }
    }

    void insertBatchForNonEmpty(@Param("links") List<StockIndustry> links);

    /**
     * Count of distinct *active* (is_active = 1) stocks with at least one stock_industry link, used
     * as `industryLinkedStockCount` in the universe import response.
     */
    int countLinkedActiveStocks();

    /**
     * `stock_industry` JOIN `industry` for the given stock ids — one batched query, not one per
     * stock. A stock in two industries yields two rows here; a stock with no link at all yields no
     * rows (caller must treat its absence as "未分類" — see
     * specs/backend/industry-gain-ranking.md, "產業別分組"). No-op when stockIds is empty (an empty
     * SQL {@code IN ()} is invalid — see {@link StockMapper#findExistingStockIds}).
     */
    default List<StockIndustryLink> findLinksByStockIds(List<String> stockIds) {
        if (stockIds.isEmpty()) {
            return Collections.emptyList();
        }
        return findLinksByStockIdsForNonEmpty(stockIds);
    }

    List<StockIndustryLink> findLinksByStockIdsForNonEmpty(@Param("stockIds") List<String> stockIds);
}
