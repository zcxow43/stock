package com.stock.exception;

import java.util.List;

public class InvalidStockPayloadException extends RuntimeException {

    private final List<String> fields;

    public InvalidStockPayloadException(List<String> fields) {
        super("Invalid stock payload, fields: " + fields);
        this.fields = fields;
    }

    public List<String> getFields() {
        return fields;
    }
}
