package com.stock.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;

/**
 * POST /api/real-trades body (specs/backend/real-trade.md). All four fields are required and none
 * has a default. {@code buyDate} stays a raw String so a malformed value reaches the service and
 * comes back as 400 INVALID_BUY_DATE echoing it; {@code buyPrice}/{@code shares} are exact
 * {@link BigDecimal}s read through {@link StrictNumberDeserializer} so a non-number is a body error
 * while 0 / negative / fractional values are the field-specific errors.
 */
public class CreateRealTradeRequest {

    private String stockId;
    private String buyDate;
    @JsonDeserialize(using = StrictNumberDeserializer.class)
    private BigDecimal buyPrice;
    @JsonDeserialize(using = StrictNumberDeserializer.class)
    private BigDecimal shares;

    public CreateRealTradeRequest() {
    }

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public String getBuyDate() {
        return buyDate;
    }

    public void setBuyDate(String buyDate) {
        this.buyDate = buyDate;
    }

    public BigDecimal getBuyPrice() {
        return buyPrice;
    }

    public void setBuyPrice(BigDecimal buyPrice) {
        this.buyPrice = buyPrice;
    }

    public BigDecimal getShares() {
        return shares;
    }

    public void setShares(BigDecimal shares) {
        this.shares = shares;
    }
}
