package com.stock.dto;

import java.util.List;

/** POST /api/strategies/backtest request body. */
public class BacktestRequestDto {

    private List<BacktestItemRequestDto> items;

    public BacktestRequestDto() {
    }

    public List<BacktestItemRequestDto> getItems() {
        return items;
    }

    public void setItems(List<BacktestItemRequestDto> items) {
        this.items = items;
    }
}
