package com.stock.dto;

import java.time.LocalDate;

public class DailySyncResponse {

    private final LocalDate tradeDate;
    private final int stockCount;
    private final int insertedCount;
    private final int updatedCount;
    private final int stockMasterUpserted;

    public DailySyncResponse(LocalDate tradeDate, int stockCount, int insertedCount,
                              int updatedCount, int stockMasterUpserted) {
        this.tradeDate = tradeDate;
        this.stockCount = stockCount;
        this.insertedCount = insertedCount;
        this.updatedCount = updatedCount;
        this.stockMasterUpserted = stockMasterUpserted;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public int getStockCount() {
        return stockCount;
    }

    public int getInsertedCount() {
        return insertedCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getStockMasterUpserted() {
        return stockMasterUpserted;
    }
}
