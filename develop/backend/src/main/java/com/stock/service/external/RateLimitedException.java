package com.stock.service.external;

/** Thrown when an external data source responds with HTTP 429 (or equivalent throttling signal). */
public class RateLimitedException extends RuntimeException {

    public RateLimitedException(String message) {
        super(message);
    }

    public RateLimitedException(String message, Throwable cause) {
        super(message, cause);
    }
}
