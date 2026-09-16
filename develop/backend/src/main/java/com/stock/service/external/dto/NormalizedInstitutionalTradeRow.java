package com.stock.service.external.dto;

import java.time.LocalDate;

/**
 * Fully normalized single security x trading day institutional-trade row, ready to be persisted
 * into `stock_institutional_trade` (spec: specs/dba/stock-institutional-trade.md). Field names
 * mirror the DB columns 1:1 — see {@link com.stock.service.external.TwseInstitutionalTradeClient}
 * for the source-field-name -> column mapping.
 */
public class NormalizedInstitutionalTradeRow {

    private final String stockId;
    private final LocalDate tradeDate;
    private final long foreignBuyShares;
    private final long foreignSellShares;
    private final long foreignNetShares;
    private final long foreignDealerBuyShares;
    private final long foreignDealerSellShares;
    private final long foreignDealerNetShares;
    private final long trustBuyShares;
    private final long trustSellShares;
    private final long trustNetShares;
    private final long dealerNetShares;
    private final long dealerSelfBuyShares;
    private final long dealerSelfSellShares;
    private final long dealerSelfNetShares;
    private final long dealerHedgeBuyShares;
    private final long dealerHedgeSellShares;
    private final long dealerHedgeNetShares;
    private final long totalNetShares;

    public NormalizedInstitutionalTradeRow(String stockId, LocalDate tradeDate,
                                            long foreignBuyShares, long foreignSellShares, long foreignNetShares,
                                            long foreignDealerBuyShares, long foreignDealerSellShares,
                                            long foreignDealerNetShares, long trustBuyShares, long trustSellShares,
                                            long trustNetShares, long dealerNetShares, long dealerSelfBuyShares,
                                            long dealerSelfSellShares, long dealerSelfNetShares,
                                            long dealerHedgeBuyShares, long dealerHedgeSellShares,
                                            long dealerHedgeNetShares, long totalNetShares) {
        this.stockId = stockId;
        this.tradeDate = tradeDate;
        this.foreignBuyShares = foreignBuyShares;
        this.foreignSellShares = foreignSellShares;
        this.foreignNetShares = foreignNetShares;
        this.foreignDealerBuyShares = foreignDealerBuyShares;
        this.foreignDealerSellShares = foreignDealerSellShares;
        this.foreignDealerNetShares = foreignDealerNetShares;
        this.trustBuyShares = trustBuyShares;
        this.trustSellShares = trustSellShares;
        this.trustNetShares = trustNetShares;
        this.dealerNetShares = dealerNetShares;
        this.dealerSelfBuyShares = dealerSelfBuyShares;
        this.dealerSelfSellShares = dealerSelfSellShares;
        this.dealerSelfNetShares = dealerSelfNetShares;
        this.dealerHedgeBuyShares = dealerHedgeBuyShares;
        this.dealerHedgeSellShares = dealerHedgeSellShares;
        this.dealerHedgeNetShares = dealerHedgeNetShares;
        this.totalNetShares = totalNetShares;
    }

    public String getStockId() {
        return stockId;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public long getForeignBuyShares() {
        return foreignBuyShares;
    }

    public long getForeignSellShares() {
        return foreignSellShares;
    }

    public long getForeignNetShares() {
        return foreignNetShares;
    }

    public long getForeignDealerBuyShares() {
        return foreignDealerBuyShares;
    }

    public long getForeignDealerSellShares() {
        return foreignDealerSellShares;
    }

    public long getForeignDealerNetShares() {
        return foreignDealerNetShares;
    }

    public long getTrustBuyShares() {
        return trustBuyShares;
    }

    public long getTrustSellShares() {
        return trustSellShares;
    }

    public long getTrustNetShares() {
        return trustNetShares;
    }

    public long getDealerNetShares() {
        return dealerNetShares;
    }

    public long getDealerSelfBuyShares() {
        return dealerSelfBuyShares;
    }

    public long getDealerSelfSellShares() {
        return dealerSelfSellShares;
    }

    public long getDealerSelfNetShares() {
        return dealerSelfNetShares;
    }

    public long getDealerHedgeBuyShares() {
        return dealerHedgeBuyShares;
    }

    public long getDealerHedgeSellShares() {
        return dealerHedgeSellShares;
    }

    public long getDealerHedgeNetShares() {
        return dealerHedgeNetShares;
    }

    public long getTotalNetShares() {
        return totalNetShares;
    }
}
