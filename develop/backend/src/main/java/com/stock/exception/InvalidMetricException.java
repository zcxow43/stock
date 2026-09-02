package com.stock.exception;

/** Thrown when GET /api/momentum/gain's `metric` param is missing or not SUM/AVERAGE. */
public class InvalidMetricException extends RuntimeException {

    public InvalidMetricException(String metric) {
        super("Invalid metric: " + metric);
    }
}
