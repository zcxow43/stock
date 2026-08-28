package com.stock.service.external;

/** Thrown for non-retryable external data source failures (bad request, unexpected shape, etc). */
public class ExternalApiException extends RuntimeException {

    public ExternalApiException(String message) {
        super(message);
    }

    public ExternalApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
