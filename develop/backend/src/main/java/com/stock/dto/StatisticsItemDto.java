package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public class StatisticsItemDto {

    private String stockId;
    private String stockName;
    private int tradingDays;
    private boolean warmupSufficient;
    private StatisticsSummaryDto summary;

    // Omitted entirely (not serialized as null) for whole-market ("ALL") scope responses —
    // spec: "全市場查詢時各 item 不含 series 欄位".
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<StatisticsSeriesRowDto> series;

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public String getStockName() {
        return stockName;
    }

    public void setStockName(String stockName) {
        this.stockName = stockName;
    }

    public int getTradingDays() {
        return tradingDays;
    }

    public void setTradingDays(int tradingDays) {
        this.tradingDays = tradingDays;
    }

    public boolean isWarmupSufficient() {
        return warmupSufficient;
    }

    public void setWarmupSufficient(boolean warmupSufficient) {
        this.warmupSufficient = warmupSufficient;
    }

    public StatisticsSummaryDto getSummary() {
        return summary;
    }

    public void setSummary(StatisticsSummaryDto summary) {
        this.summary = summary;
    }

    public List<StatisticsSeriesRowDto> getSeries() {
        return series;
    }

    public void setSeries(List<StatisticsSeriesRowDto> series) {
        this.series = series;
    }
}
