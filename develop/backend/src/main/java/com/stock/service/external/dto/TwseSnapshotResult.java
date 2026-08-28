package com.stock.service.external.dto;

import java.time.LocalDate;
import java.util.List;

public class TwseSnapshotResult {

    private final LocalDate tradeDate;
    private final List<TwseSnapshotRow> rows;

    public TwseSnapshotResult(LocalDate tradeDate, List<TwseSnapshotRow> rows) {
        this.tradeDate = tradeDate;
        this.rows = rows;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public List<TwseSnapshotRow> getRows() {
        return rows;
    }
}
