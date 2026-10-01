package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One position within GET /api/real-trades' {@code items[]} and the body of POST's 201
 * (specs/backend/real-trade.md). Same shape as {@link SimulatedTradeItemDto} except that the
 * current-price fields are nullable: a stock with no positive close at all cannot be valued, but is
 * still reported with its {@code buyFee}/{@code cost}.
 */
public class RealTradeItemDto {

    private int id;
    private String stockId;
    private String stockName;
    private LocalDate buyDate;
    private BigDecimal buyPrice;
    private int shares;
    private BigDecimal buyFee;
    private BigDecimal cost;
    private LocalDate currentDate;
    private BigDecimal currentPrice;
    private BigDecimal sellFee;
    private BigDecimal sellTax;
    private BigDecimal unrealizedProfit;
    private BigDecimal returnPercent;

    public RealTradeItemDto() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
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

    public int getShares() {
        return shares;
    }

    public void setShares(int shares) {
        this.shares = shares;
    }

    public BigDecimal getBuyFee() {
        return buyFee;
    }

    public void setBuyFee(BigDecimal buyFee) {
        this.buyFee = buyFee;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public void setCost(BigDecimal cost) {
        this.cost = cost;
    }

    public LocalDate getCurrentDate() {
        return currentDate;
    }

    public void setCurrentDate(LocalDate currentDate) {
        this.currentDate = currentDate;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }

    public BigDecimal getSellFee() {
        return sellFee;
    }

    public void setSellFee(BigDecimal sellFee) {
        this.sellFee = sellFee;
    }

    public BigDecimal getSellTax() {
        return sellTax;
    }

    public void setSellTax(BigDecimal sellTax) {
        this.sellTax = sellTax;
    }

    public BigDecimal getUnrealizedProfit() {
        return unrealizedProfit;
    }

    public void setUnrealizedProfit(BigDecimal unrealizedProfit) {
        this.unrealizedProfit = unrealizedProfit;
    }

    public BigDecimal getReturnPercent() {
        return returnPercent;
    }

    public void setReturnPercent(BigDecimal returnPercent) {
        this.returnPercent = returnPercent;
    }
}
