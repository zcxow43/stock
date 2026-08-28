package com.stock.service;

import com.stock.domain.StockDailyIndicator;
import com.stock.mapper.StockDailyIndicatorMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The only place stock_daily_indicator is written. Split out from IndicatorRebuildRunner as its
 * own bean (rather than an @Transactional method on the runner itself) because @Async methods on
 * the runner call each other via plain self-invocation, which bypasses the Spring AOP proxy that
 * @Transactional relies on — the same reason PriceIngestionService exists as a separate bean from
 * BackfillRunner.
 */
@Service
public class IndicatorPersistenceService {

    private final StockDailyIndicatorMapper indicatorMapper;

    public IndicatorPersistenceService(StockDailyIndicatorMapper indicatorMapper) {
        this.indicatorMapper = indicatorMapper;
    }

    /** FULL rebuild: atomically discard the stock's existing series for this param and write the new one. */
    @Transactional
    public void replaceFull(String stockId, String paramKey, List<StockDailyIndicator> rows) {
        indicatorMapper.deleteByStockAndParam(stockId, paramKey);
        if (!rows.isEmpty()) {
            indicatorMapper.upsertBatch(rows);
        }
    }

    /** INCREMENTAL step(s): append/overwrite the newly computed rows only. */
    @Transactional
    public void appendIncremental(List<StockDailyIndicator> rows) {
        if (!rows.isEmpty()) {
            indicatorMapper.upsertBatch(rows);
        }
    }
}
