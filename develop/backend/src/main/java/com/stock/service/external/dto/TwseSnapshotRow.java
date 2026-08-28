package com.stock.service.external.dto;

/** A single normalized stock's row from the TWSE all-market daily snapshot. */
public class TwseSnapshotRow {

    private final String stockId;
    private final String stockName;
    private final NormalizedPriceRow price;

    public TwseSnapshotRow(String stockId, String stockName, NormalizedPriceRow price) {
        this.stockId = stockId;
        this.stockName = stockName;
        this.price = price;
    }

    public String getStockId() {
        return stockId;
    }

    public String getStockName() {
        return stockName;
    }

    public NormalizedPriceRow getPrice() {
        return price;
    }
}
