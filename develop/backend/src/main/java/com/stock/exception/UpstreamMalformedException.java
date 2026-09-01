package com.stock.exception;

/** Thrown when an upstream data source's response cannot be parsed into the expected shape. */
public class UpstreamMalformedException extends RuntimeException {

    public UpstreamMalformedException(String message, Throwable cause) {
        super(message, cause);
    }
}
