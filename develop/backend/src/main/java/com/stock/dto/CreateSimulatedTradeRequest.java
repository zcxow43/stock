package com.stock.dto;

/**
 * POST /api/simulated-trades request body. Only `stockId` is read — buyDate/buyPrice/shares are
 * always system-decided and never accepted from the caller, even if present in the JSON body (see
 * specs/backend/simulated-trade.md, "三者都不接受呼叫端指定"), so this DTO deliberately has no
 * properties for them.
 */
public class CreateSimulatedTradeRequest {

    private String stockId;

    public CreateSimulatedTradeRequest() {
    }

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }
}
