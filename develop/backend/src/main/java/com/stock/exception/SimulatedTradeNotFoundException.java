package com.stock.exception;

/**
 * DELETE /api/simulated-trades/{id} named an id that matched no row (specs/backend/
 * simulated-trade.md, "驗證與錯誤").
 */
public class SimulatedTradeNotFoundException extends RuntimeException {

    private final Long id;

    public SimulatedTradeNotFoundException(Long id) {
        super("Simulated trade not found: " + id);
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
