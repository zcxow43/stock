package com.stock.exception;

/** Thrown when an upstream data source cannot be reached at all: connection failure, timeout, or non-2xx status. */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
