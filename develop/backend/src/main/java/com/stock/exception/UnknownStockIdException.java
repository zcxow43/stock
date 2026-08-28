package com.stock.exception;

import java.util.List;

public class UnknownStockIdException extends RuntimeException {

    private final List<String> unknownIds;

    public UnknownStockIdException(List<String> unknownIds) {
        super("Unknown stock ids: " + unknownIds);
        this.unknownIds = unknownIds;
    }

    public List<String> getUnknownIds() {
        return unknownIds;
    }
}
