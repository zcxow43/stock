package com.stock.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/strategies/backtest request body. `endDate` is optional: the last day of the sell
 * window, sent by the caller as the `endDate` of the scan that produced `items` (specs/backend/
 * strategy-backtest.md, "賣出窗口的迄日"). Null means the window runs to today.
 */
public class BacktestRequestDto {

    private LocalDate endDate;
    private List<BacktestItemRequestDto> items;

    public BacktestRequestDto() {
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public List<BacktestItemRequestDto> getItems() {
        return items;
    }

    public void setItems(List<BacktestItemRequestDto> items) {
        this.items = items;
    }
}
