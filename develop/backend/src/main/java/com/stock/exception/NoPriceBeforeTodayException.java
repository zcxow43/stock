package com.stock.exception;

/**
 * POST /api/simulated-trades's stock has no `trade_date < 今日` row with `close_price > 0` in
 * `stock_daily_price` — there is no day to price the buy at (specs/backend/simulated-trade.md,
 * "驗證與錯誤").
 */
public class NoPriceBeforeTodayException extends RuntimeException {

    private final String stockId;

    public NoPriceBeforeTodayException(String stockId) {
        super("No close price before today for stock: " + stockId);
        this.stockId = stockId;
    }

    public String getStockId() {
        return stockId;
    }
}
