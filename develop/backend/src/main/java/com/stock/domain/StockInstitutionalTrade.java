package com.stock.domain;

import java.time.LocalDate;

/**
 * One row of `stock_institutional_trade`: one security's three-major-institutional-investors
 * buy/sell/net share counts for one trading day (spec: specs/dba/stock-institutional-trade.md).
 * Every numeric field is stored exactly as the source reports it, with no aggregation performed
 * here — "外資要不要含外資自營商" etc. is a read-side decision, not this table's job.
 */
public class StockInstitutionalTrade {

    private String stockId;
    private LocalDate tradeDate;
    private long foreignBuyShares;
    private long foreignSellShares;
    private long foreignNetShares;
    private long foreignDealerBuyShares;
    private long foreignDealerSellShares;
    private long foreignDealerNetShares;
    private long trustBuyShares;
    private long trustSellShares;
    private long trustNetShares;
    private long dealerNetShares;
    private long dealerSelfBuyShares;
    private long dealerSelfSellShares;
    private long dealerSelfNetShares;
    private long dealerHedgeBuyShares;
    private long dealerHedgeSellShares;
    private long dealerHedgeNetShares;
    private long totalNetShares;
    private String source;

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

    public long getForeignBuyShares() {
        return foreignBuyShares;
    }

    public void setForeignBuyShares(long foreignBuyShares) {
        this.foreignBuyShares = foreignBuyShares;
    }

    public long getForeignSellShares() {
        return foreignSellShares;
    }

    public void setForeignSellShares(long foreignSellShares) {
        this.foreignSellShares = foreignSellShares;
    }

    public long getForeignNetShares() {
        return foreignNetShares;
    }

    public void setForeignNetShares(long foreignNetShares) {
        this.foreignNetShares = foreignNetShares;
    }

    public long getForeignDealerBuyShares() {
        return foreignDealerBuyShares;
    }

    public void setForeignDealerBuyShares(long foreignDealerBuyShares) {
        this.foreignDealerBuyShares = foreignDealerBuyShares;
    }

    public long getForeignDealerSellShares() {
        return foreignDealerSellShares;
    }

    public void setForeignDealerSellShares(long foreignDealerSellShares) {
        this.foreignDealerSellShares = foreignDealerSellShares;
    }

    public long getForeignDealerNetShares() {
        return foreignDealerNetShares;
    }

    public void setForeignDealerNetShares(long foreignDealerNetShares) {
        this.foreignDealerNetShares = foreignDealerNetShares;
    }

    public long getTrustBuyShares() {
        return trustBuyShares;
    }

    public void setTrustBuyShares(long trustBuyShares) {
        this.trustBuyShares = trustBuyShares;
    }

    public long getTrustSellShares() {
        return trustSellShares;
    }

    public void setTrustSellShares(long trustSellShares) {
        this.trustSellShares = trustSellShares;
    }

    public long getTrustNetShares() {
        return trustNetShares;
    }

    public void setTrustNetShares(long trustNetShares) {
        this.trustNetShares = trustNetShares;
    }

    public long getDealerNetShares() {
        return dealerNetShares;
    }

    public void setDealerNetShares(long dealerNetShares) {
        this.dealerNetShares = dealerNetShares;
    }

    public long getDealerSelfBuyShares() {
        return dealerSelfBuyShares;
    }

    public void setDealerSelfBuyShares(long dealerSelfBuyShares) {
        this.dealerSelfBuyShares = dealerSelfBuyShares;
    }

    public long getDealerSelfSellShares() {
        return dealerSelfSellShares;
    }

    public void setDealerSelfSellShares(long dealerSelfSellShares) {
        this.dealerSelfSellShares = dealerSelfSellShares;
    }

    public long getDealerSelfNetShares() {
        return dealerSelfNetShares;
    }

    public void setDealerSelfNetShares(long dealerSelfNetShares) {
        this.dealerSelfNetShares = dealerSelfNetShares;
    }

    public long getDealerHedgeBuyShares() {
        return dealerHedgeBuyShares;
    }

    public void setDealerHedgeBuyShares(long dealerHedgeBuyShares) {
        this.dealerHedgeBuyShares = dealerHedgeBuyShares;
    }

    public long getDealerHedgeSellShares() {
        return dealerHedgeSellShares;
    }

    public void setDealerHedgeSellShares(long dealerHedgeSellShares) {
        this.dealerHedgeSellShares = dealerHedgeSellShares;
    }

    public long getDealerHedgeNetShares() {
        return dealerHedgeNetShares;
    }

    public void setDealerHedgeNetShares(long dealerHedgeNetShares) {
        this.dealerHedgeNetShares = dealerHedgeNetShares;
    }

    public long getTotalNetShares() {
        return totalNetShares;
    }

    public void setTotalNetShares(long totalNetShares) {
        this.totalNetShares = totalNetShares;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
