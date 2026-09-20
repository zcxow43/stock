package com.stock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of {@code simulated_trade} — see specs/dba/simulated-trade.md. Only the facts fixed at
 * creation time are stored here (stock, buy date, buy price, shares); current price, unrealized
 * profit and return are computed on every read by {@code SimulatedTradeService} and never
 * persisted (specs/backend/simulated-trade.md, "只存建立當下的事實").
 */
public class SimulatedTrade {

    private Long id;
    private String stockId;
    private LocalDate buyDate;
    private BigDecimal buyPrice;
    private Integer shares;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public SimulatedTrade() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public LocalDate getBuyDate() {
        return buyDate;
    }

    public void setBuyDate(LocalDate buyDate) {
        this.buyDate = buyDate;
    }

    public BigDecimal getBuyPrice() {
        return buyPrice;
    }

    public void setBuyPrice(BigDecimal buyPrice) {
        this.buyPrice = buyPrice;
    }

    public Integer getShares() {
        return shares;
    }

    public void setShares(Integer shares) {
        this.shares = shares;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
