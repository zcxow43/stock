package com.stock.dto;

public class FailedItemDto {

    private final String stockId;
    private final int attemptCount;
    private final String lastError;

    public FailedItemDto(String stockId, int attemptCount, String lastError) {
        this.stockId = stockId;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
    }

    public String getStockId() {
        return stockId;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }
}
