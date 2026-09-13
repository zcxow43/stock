package com.stock.exception;

import com.stock.dto.BacktestDuplicateItemDto;

import java.util.List;

/**
 * POST /api/strategies/backtest's `items` names the same `(stockId, buyDate)` combination more
 * than once. A stock may legitimately appear multiple times with different buy dates (see
 * specs/backend/strategy-backtest.md, "一筆＝一個買進日") — what is never allowed is sending the
 * exact same buy date twice, even when it was produced by two different strategies or signal
 * dates. This endpoint does not silently merge those; the caller must de-duplicate before sending.
 */
public class DuplicateBacktestItemException extends RuntimeException {

    private final List<BacktestDuplicateItemDto> duplicatedItems;

    public DuplicateBacktestItemException(List<BacktestDuplicateItemDto> duplicatedItems) {
        super("Duplicated (stockId, buyDate) item(s): " + duplicatedItems);
        this.duplicatedItems = duplicatedItems;
    }

    public List<BacktestDuplicateItemDto> getDuplicatedItems() {
        return duplicatedItems;
    }
}
