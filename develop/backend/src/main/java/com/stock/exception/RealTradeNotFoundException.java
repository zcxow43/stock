package com.stock.exception;

/**
 * DELETE /api/real-trades/{id} named an id that is not a positive integer or is beyond the current
 * data-row count (specs/backend/real-trade.md, "驗證與錯誤"). {@code id} is {@code null} when the
 * path segment was not a number at all.
 */
public class RealTradeNotFoundException extends RuntimeException {

    private final Long id;

    public RealTradeNotFoundException(Long id) {
        super("Real trade not found: " + id);
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
