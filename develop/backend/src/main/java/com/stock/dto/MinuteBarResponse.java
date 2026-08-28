package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Response body for GET /api/stocks/{stockId}/minute-bars. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MinuteBarResponse {

    private String stockId;
    private String stockName;
    private LocalDate tradeDate;
    private int interval;
    private String dataStatus;
    private String source;
    private LocalDateTime fetchedAt;
    private int barCount;
    private DailySummaryDto dailySummary;
    private List<MinuteBarDto> bars;
    /** Only present when dataStatus = FETCH_FAILED; stock_minute_fetch_status.last_error. */
    private String message;

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

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public int getInterval() {
        return interval;
    }

    public void setInterval(int interval) {
        this.interval = interval;
    }

    public String getDataStatus() {
        return dataStatus;
    }

    public void setDataStatus(String dataStatus) {
        this.dataStatus = dataStatus;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(LocalDateTime fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public int getBarCount() {
        return barCount;
    }

    public void setBarCount(int barCount) {
        this.barCount = barCount;
    }

    public DailySummaryDto getDailySummary() {
        return dailySummary;
    }

    public void setDailySummary(DailySummaryDto dailySummary) {
        this.dailySummary = dailySummary;
    }

    public List<MinuteBarDto> getBars() {
        return bars;
    }

    public void setBars(List<MinuteBarDto> bars) {
        this.bars = bars;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
