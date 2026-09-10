package com.stock.exception;

import java.util.List;

/**
 * POST /api/strategies/backtest's `items` names the same `stockId` more than once — a stock may
 * hit multiple strategies with different signal dates, but choosing which one to backtest is the
 * caller's decision, not this endpoint's (specs/backend/strategy-backtest.md, "一檔一筆，代號不得重複").
 */
public class DuplicateStockIdException extends RuntimeException {

    private final List<String> duplicatedIds;

    public DuplicateStockIdException(List<String> duplicatedIds) {
        super("Duplicated stock id(s): " + duplicatedIds);
        this.duplicatedIds = duplicatedIds;
    }

    public List<String> getDuplicatedIds() {
        return duplicatedIds;
    }
}
