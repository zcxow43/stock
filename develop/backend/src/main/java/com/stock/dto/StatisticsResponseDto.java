package com.stock.dto;

import java.time.LocalDate;
import java.util.List;

public class StatisticsResponseDto {

    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String paramKey;
    private final String scope;
    private final int stockCount;
    private final List<StatisticsItemDto> items;

    public StatisticsResponseDto(LocalDate startDate, LocalDate endDate, String paramKey, String scope,
                                  int stockCount, List<StatisticsItemDto> items) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.paramKey = paramKey;
        this.scope = scope;
        this.stockCount = stockCount;
        this.items = items;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getParamKey() {
        return paramKey;
    }

    public String getScope() {
        return scope;
    }

    public int getStockCount() {
        return stockCount;
    }

    public List<StatisticsItemDto> getItems() {
        return items;
    }
}
