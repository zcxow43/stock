package com.stock.exception;

import java.util.List;

public class InvalidSortFieldException extends RuntimeException {

    private final List<String> allowed;

    public InvalidSortFieldException(List<String> allowed) {
        super("Invalid sort field, allowed: " + allowed);
        this.allowed = allowed;
    }

    public List<String> getAllowed() {
        return allowed;
    }
}
