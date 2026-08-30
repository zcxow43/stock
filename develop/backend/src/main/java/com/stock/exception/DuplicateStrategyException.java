package com.stock.exception;

import java.util.List;

public class DuplicateStrategyException extends RuntimeException {

    private final List<String> duplicated;

    public DuplicateStrategyException(List<String> duplicated) {
        super("Duplicated strategy code(s): " + duplicated);
        this.duplicated = duplicated;
    }

    public List<String> getDuplicated() {
        return duplicated;
    }
}
