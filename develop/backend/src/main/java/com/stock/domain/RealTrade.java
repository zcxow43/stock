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
    private final boolean excluded;
    private final BigDecimal targetSellPrice;

    public RealTrade(int id, int lineIndex, String stockId, LocalDate buyDate, BigDecimal buyPrice, int shares,
                     boolean excluded, BigDecimal targetSellPrice) {
        this.id = id;
        this.lineIndex = lineIndex;
        this.stockId = stockId;
        this.buyDate = buyDate;
        this.buyPrice = buyPrice;
        this.shares = shares;
        this.excluded = excluded;
        this.targetSellPrice = targetSellPrice;
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

    /** {@code true}: left out of the three totals only; the row still reports its own numbers. */
    public boolean isExcluded() {
        return excluded;
    }

    /** The user's own target sell price per share, or {@code null} when unset. Never used in a calculation. */
    public BigDecimal getTargetSellPrice() {
        return targetSellPrice;
    }
}
