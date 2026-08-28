package com.stock.exception;

public class SeriesNotAllowedForAllScopeException extends RuntimeException {

    public SeriesNotAllowedForAllScopeException() {
        super("includeSeries=true is not allowed when stockIds is omitted (whole-market scope)");
    }
}
