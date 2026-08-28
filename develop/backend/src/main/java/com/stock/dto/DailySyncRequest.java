package com.stock.dto;

import java.time.LocalDate;

public class DailySyncRequest {

    private LocalDate tradeDate;

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }
}
