package com.stock.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of stock_minute_fetch_status: the latest fetch outcome for one stock x trade date,
 * used to decide whether GET /api/stocks/{stockId}/minute-bars needs to hit the external source
 * (see specs/backend/stock-minute-price.md, 抓取決策).
 */
public class StockMinuteFetchStatus {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_NO_DATA = "NO_DATA";
    public static final String STATUS_OUT_OF_WINDOW = "OUT_OF_WINDOW";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_NOT_A_TRADING_DAY = "NOT_A_TRADING_DAY";

    private String stockId;
    private LocalDate tradeDate;
    private String status;
    private int barCount;
    private String source;
    private int attemptCount;
    private String lastError;
    private LocalDateTime fetchedAt;

    public StockMinuteFetchStatus() {
    }

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getBarCount() {
        return barCount;
    }

    public void setBarCount(int barCount) {
        this.barCount = barCount;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(LocalDateTime fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
