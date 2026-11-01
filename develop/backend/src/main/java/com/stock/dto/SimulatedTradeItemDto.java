package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One holding within GET /api/simulated-trades's `items[]` — and, shaped identically, the body of
 * a successful POST /api/simulated-trades (specs/backend/simulated-trade.md, "回應為與 GET 的
 * items[] 相同的形狀"). `buyDate`/`buyPrice`/`shares` are the facts stored at creation time
 * (specs/dba/simulated-trade.md); every other field is computed fresh from the latest close price
 * on every call and never persisted.
 */
public class SimulatedTradeItemDto {

    private Long id;
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
    private Settlement settlement;

    public SimulatedTradeItemDto() {
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

    /** Null unless GET carried settleDate and this holding was bought on or before it. */
    public Settlement getSettlement() {
        return settlement;
    }

    public void setSettlement(Settlement settlement) {
        this.settlement = settlement;
    }

    /** Sale at the highest high_price in [buyDate, settleDate] (specs/backend/simulated-trade.md). */
    public static class Settlement {

        private LocalDate sellDate;
        private BigDecimal sellPrice;
        private BigDecimal sellFee;
        private BigDecimal sellTax;
        private BigDecimal profit;
        private BigDecimal returnPercent;

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

        public BigDecimal getProfit() {
            return profit;
        }

        public void setProfit(BigDecimal profit) {
            this.profit = profit;
        }

        public BigDecimal getReturnPercent() {
            return returnPercent;
        }

        public void setReturnPercent(BigDecimal returnPercent) {
            this.returnPercent = returnPercent;
        }
    }
}
