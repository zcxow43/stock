package com.stock.exception;

import java.util.List;

/** An unknown strategy code, or an unknown preset for an otherwise-known code (reported as "code:preset"). */
public class UnknownStrategyException extends RuntimeException {

    private final List<String> unknown;

    public UnknownStrategyException(List<String> unknown) {
        super("Unknown strategy/preset: " + unknown);
        this.unknown = unknown;
    }

    public List<String> getUnknown() {
        return unknown;
    }
}
