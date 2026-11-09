package com.stock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Per-holding result of the batched settlement query: the sell date (settleDate or the stock's last quoted day before it) and that day's open_price. */
public class SimulatedTradeSettlementPrice {

    private Long tradeId;
    private LocalDate tradeDate;
    private BigDecimal openPrice;

    public Long getTradeId() {
        return tradeId;
    }

    public void setTradeId(Long tradeId) {
        this.tradeId = tradeId;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public BigDecimal getOpenPrice() {
        return openPrice;
    }

    public void setOpenPrice(BigDecimal openPrice) {
        this.openPrice = openPrice;
    }
}
