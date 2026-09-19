package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One stock's backtest outcome within POST /api/strategies/backtest's `items` response array. All
 * fields but `stockId`/`buyDate` are null when the stock could not be backtested — see
 * specs/backend/strategy-backtest.md, "無法回測的標的". The stock still appears in `items` in that
 * case; it is simply excluded from the response-level totals. `buyDate` is returned verbatim from
 * the request; the response never carries a `signalDate` field (specs/backend/strategy-backtest.md,
 * "買進日由請求指定，本端點不推算").
 *
 * <p>`buyFee`/`cost` are present whenever `buyPrice` is non-null, even when there is no sell date
 * yet (a buy still costs a fee the moment it happens); `sellFee`/`sellTax`/`profit`/`returnPercent`
 * are present only when `sellDate` is non-null (specs/backend/strategy-backtest.md, "交易成本").
 */
public class BacktestResultItemDto {

    private String stockId;
    private LocalDate buyDate;
    private BigDecimal buyPrice;
    private LocalDate sellDate;
    private BigDecimal sellPrice;
    private BigDecimal buyFee;
    private BigDecimal sellFee;
    private BigDecimal sellTax;
    private BigDecimal cost;
    private BigDecimal returnPercent;
    private BigDecimal profit;

    public BacktestResultItemDto() {
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

    public LocalDate getSellDate() {
        return sellDate;
    }

    public void setSellDate(LocalDate sellDate) {
        this.sellDate = sellDate;
    }

    public BigDecimal getSellPrice() {
        return sellPrice;
    }

    public void setSellPrice(BigDecimal sellPrice) {
        this.sellPrice = sellPrice;
    }

    public BigDecimal getBuyFee() {
        return buyFee;
    }

    public void setBuyFee(BigDecimal buyFee) {
        this.buyFee = buyFee;
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

    public BigDecimal getCost() {
        return cost;
    }

    public void setCost(BigDecimal cost) {
        this.cost = cost;
    }

    public BigDecimal getReturnPercent() {
        return returnPercent;
    }

    public void setReturnPercent(BigDecimal returnPercent) {
        this.returnPercent = returnPercent;
    }

    public BigDecimal getProfit() {
        return profit;
    }

    public void setProfit(BigDecimal profit) {
        this.profit = profit;
    }
}
