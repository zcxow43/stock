package com.stock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One position parsed from a data row of data/real-trades.csv. Not a database row: {@code id} is the
 * 1-based ordinal among the file's valid data rows (specs/backend/real-trade.md, "id 是資料行的位
 * 置"), and {@code lineIndex} is the 0-based index of the row among the file's raw lines so a write
 * can find it again.
 */
public final class RealTrade {

    private final int id;
    private final int lineIndex;
    private final String stockId;
    private final LocalDate buyDate;
    private final BigDecimal buyPrice;
    private final int shares;

    public RealTrade(int id, int lineIndex, String stockId, LocalDate buyDate, BigDecimal buyPrice, int shares) {
        this.id = id;
        this.lineIndex = lineIndex;
        this.stockId = stockId;
        this.buyDate = buyDate;
        this.buyPrice = buyPrice;
        this.shares = shares;
    }

    public int getId() {
        return id;
    }

    public int getLineIndex() {
        return lineIndex;
    }

    public String getStockId() {
        return stockId;
    }

    public LocalDate getBuyDate() {
        return buyDate;
    }

    public BigDecimal getBuyPrice() {
        return buyPrice;
    }

    public int getShares() {
        return shares;
    }
}
