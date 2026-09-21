package com.stock.exception;

/**
 * POST /api/simulated-trades' optional `buyDate` is malformed or later than today (Asia/Taipei) —
 * see specs/backend/simulated-trade.md, "指定買進日（本次新增）". Distinct from
 * {@link InvalidBuyDateException} (POST /api/strategies/backtest's own same-named error code, whose
 * wire shape carries a `stockId` instead): the two endpoints share the JSON `code` value
 * `INVALID_BUY_DATE` but not the response body shape, so each gets its own exception/handler/factory
 * rather than being forced to agree on one field set.
 *
 * <p>{@code buyDate} is the raw, as-submitted string, not a parsed {@link java.time.LocalDate} — a
 * malformed value cannot be represented as one, and the wire contract echoes back exactly what the
 * caller sent.
 */
public class InvalidSimulatedTradeBuyDateException extends RuntimeException {

    private final String buyDate;

    public InvalidSimulatedTradeBuyDateException(String buyDate) {
        super("Invalid buyDate for simulated trade: " + buyDate);
        this.buyDate = buyDate;
    }

    public String getBuyDate() {
        return buyDate;
    }
}
