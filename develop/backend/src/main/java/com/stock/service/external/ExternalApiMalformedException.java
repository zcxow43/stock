package com.stock.service.external;

/**
 * Thrown when an external data source's response cannot be parsed into the expected shape
 * (message conversion failure) — distinct from {@link ExternalApiException}, which covers
 * connectivity failures (timeout, connection refused, non-2xx status).
 */
public class ExternalApiMalformedException extends RuntimeException {

    public ExternalApiMalformedException(String message, Throwable cause) {
        super(message, cause);
    }
}
