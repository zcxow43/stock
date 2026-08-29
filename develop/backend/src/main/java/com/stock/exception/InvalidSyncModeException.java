package com.stock.exception;

public class InvalidSyncModeException extends RuntimeException {

    public InvalidSyncModeException() {
        super("resume and catchUp must not both be true");
    }
}
