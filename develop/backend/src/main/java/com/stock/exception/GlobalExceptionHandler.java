package com.stock.exception;

import com.stock.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidDateRangeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDateRange(InvalidDateRangeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_DATE_RANGE"));
    }

    @ExceptionHandler(UnknownStockIdException.class)
    public ResponseEntity<ErrorResponse> handleUnknownStockId(UnknownStockIdException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("UNKNOWN_STOCK_ID", e.getUnknownIds()));
    }

    @ExceptionHandler(InvalidSyncModeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSyncMode(InvalidSyncModeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_SYNC_MODE"));
    }

    @ExceptionHandler(JobAlreadyRunningException.class)
    public ResponseEntity<ErrorResponse> handleJobAlreadyRunning(JobAlreadyRunningException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("JOB_ALREADY_RUNNING"));
    }

    @ExceptionHandler(InvalidPaginationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPagination(InvalidPaginationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_PAGINATION"));
    }

    @ExceptionHandler(PageSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handlePageSizeExceeded(PageSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.pageSizeExceeded(e.getLimit()));
    }

    @ExceptionHandler(InvalidMarketException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMarket(InvalidMarketException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_MARKET"));
    }

    @ExceptionHandler(InvalidSortFieldException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSortField(InvalidSortFieldException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.invalidSortField(e.getAllowed()));
    }

    @ExceptionHandler(StockNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStockNotFound(StockNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.stockNotFound(e.getStockId()));
    }

    @ExceptionHandler(MissingTradeDateException.class)
    public ResponseEntity<ErrorResponse> handleMissingTradeDate(MissingTradeDateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("MISSING_TRADE_DATE"));
    }

    @ExceptionHandler(InvalidDateFormatException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDateFormat(InvalidDateFormatException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_DATE_FORMAT"));
    }

    @ExceptionHandler(FutureTradeDateException.class)
    public ResponseEntity<ErrorResponse> handleFutureTradeDate(FutureTradeDateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("FUTURE_TRADE_DATE"));
    }

    @ExceptionHandler(InvalidIntervalException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInterval(InvalidIntervalException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.invalidInterval(InvalidIntervalException.ALLOWED));
    }

    @ExceptionHandler(TooManyStockIdsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyStockIds(TooManyStockIdsException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.tooManyStockIds(e.getLimit()));
    }

    @ExceptionHandler(SeriesNotAllowedForAllScopeException.class)
    public ResponseEntity<ErrorResponse> handleSeriesNotAllowedForAllScope(SeriesNotAllowedForAllScopeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("SERIES_NOT_ALLOWED_FOR_ALL_SCOPE"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_ERROR"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("INTERNAL_ERROR"));
    }
}
