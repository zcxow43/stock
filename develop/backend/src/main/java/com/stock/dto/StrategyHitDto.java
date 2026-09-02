package com.stock.dto;

import java.time.LocalDate;

/**
 * One matched stock within a strategy's result — the most recent hit for that stock inside the
 * scanned range. `detail` is a {@link BoxBreakoutDetailDto}, {@link HigherLowsDetailDto}, or
 * {@link RisingSupportDetailDto} depending on the strategy; declared as Object here purely for
 * serialization (this DTO is never deserialized from a request).
 */
public class StrategyHitDto {

    private String stockId;
    private String stockName;
    private LocalDate signalDate;
    private Object detail;

    public StrategyHitDto() {
    }

    public StrategyHitDto(String stockId, String stockName, LocalDate signalDate, Object detail) {
        this.stockId = stockId;
        this.stockName = stockName;
        this.signalDate = signalDate;
        this.detail = detail;
    }

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

    public LocalDate getSignalDate() {
        return signalDate;
    }

    public void setSignalDate(LocalDate signalDate) {
        this.signalDate = signalDate;
    }

    public Object getDetail() {
        return detail;
    }

    public void setDetail(Object detail) {
        this.detail = detail;
    }
}
