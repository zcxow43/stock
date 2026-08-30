package com.stock.dto;

import java.util.List;

/** GET /api/strategies response body. */
public class StrategyCatalogResponseDto {

    private List<StrategyDto> strategies;

    public StrategyCatalogResponseDto() {
    }

    public StrategyCatalogResponseDto(List<StrategyDto> strategies) {
        this.strategies = strategies;
    }

    public List<StrategyDto> getStrategies() {
        return strategies;
    }

    public void setStrategies(List<StrategyDto> strategies) {
        this.strategies = strategies;
    }
}
