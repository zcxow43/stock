package com.stock.mapper;

import com.stock.domain.Industry;
import org.apache.ibatis.annotations.Mapper;

/**
 * Maintains the `industry` dictionary table (specs/dba/industry.md). Only written by the universe
 * import's industry-source step (specs/backend/stock-universe-import.md); rows are never deleted.
 */
@Mapper
public interface IndustryMapper {

    /**
     * Insert-or-fetch by {@code industry_name}: a brand-new name gets a new {@code industry_id}; an
     * existing name keeps its {@code industry_id} unchanged, via MySQL's
     * {@code ON DUPLICATE KEY UPDATE industry_id = LAST_INSERT_ID(industry_id)} trick (see
     * specs/dba/industry.md 索引說明). After the call, {@code industry.getIndustryId()} is
     * populated either way — this is what lets `stock_industry` be written against a stable id.
     */
    void upsertByName(Industry industry);

    /** Total row count of `industry`, used as `industryCount` in the universe import response. */
    int count();
}
