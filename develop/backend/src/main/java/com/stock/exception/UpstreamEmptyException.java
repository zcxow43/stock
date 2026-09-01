package com.stock.exception;

/**
 * Thrown when an upstream data source's response, after parsing, carries no usable rows —
 * e.g. TWSE's whole-market snapshot returning an empty array on a non-trading day. Must never be
 * treated as "0 imported" (spec: stock-universe-import.md 空回應的處理).
 */
public class UpstreamEmptyException extends RuntimeException {

    public UpstreamEmptyException(String message) {
        super(message);
    }
}
