package com.stock.dto;

/**
 * POST /api/simulated-trades request body. `buyPrice`/`shares` are always system-decided and never
 * accepted from the caller (see specs/backend/simulated-trade.md, "買進日可指定、買進價與股數不可指定"),
 * so this DTO has no properties for them.
 *
 * <p>`buyDate` is deliberately typed as a raw {@code String}, not {@link java.time.LocalDate} —
 * a malformed value must surface as {@code 400 INVALID_BUY_DATE} carrying the offending string, not
 * as a generic Jackson deserialization failure that would short-circuit before the service ever
 * gets a chance to shape that response (see "指定買進日（本次新增）").
 */
public class CreateSimulatedTradeRequest {

    private String stockId;
    private String buyDate;

    public CreateSimulatedTradeRequest() {
    }

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public String getBuyDate() {
        return buyDate;
    }

    public void setBuyDate(String buyDate) {
        this.buyDate = buyDate;
    }
}
