package com.stock.dto;

import java.time.LocalDate;

/**
 * One matched stock within a strategy's result — the most recent hit for that stock inside the
 * scanned range. `detail` is a {@link BoxBreakoutDetailDto}, {@link HigherLowsDetailDto},
 * {@link RisingSupportDetailDto}, {@link ReboundDetailDto}, or {@link CumulativeRiseDetailDto}
 * depending on the strategy; declared as Object here purely for serialization (this DTO is never
 * deserialized from a request).
 *
 * <p>{@code buyDate} is the entry trading day (specs/backend/strategy-scan.md, "buyDate 為這一次命中
 * 的進場日"): equal to {@code signalDate} for every strategy except RISING_SUPPORT, whose buyDate is
 * D+2, the confirmation day. It may fall after the scan's {@code endDate} when D sits at the end of
 * the scanned range and its confirmation bars were read from beyond it.
 */
public class StrategyHitDto {

    private String stockId;
    private String stockName;
    private LocalDate signalDate;
    private LocalDate buyDate;
    private Object detail;

    public StrategyHitDto() {
    }

    public StrategyHitDto(String stockId, String stockName, LocalDate signalDate, LocalDate buyDate,
                           Object detail) {
        this.stockId = stockId;
        this.stockName = stockName;
        this.signalDate = signalDate;
        this.buyDate = buyDate;
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

    public LocalDate getBuyDate() {
        return buyDate;
    }

    public void setBuyDate(LocalDate buyDate) {
        this.buyDate = buyDate;
    }

    public Object getDetail() {
        return detail;
    }

    public void setDetail(Object detail) {
        this.detail = detail;
    }
}
