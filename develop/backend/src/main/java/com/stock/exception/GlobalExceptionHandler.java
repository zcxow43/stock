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

    @ExceptionHandler(StockAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleStockAlreadyExists(StockAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("STOCK_ALREADY_EXISTS"));
    }

    @ExceptionHandler(InvalidStockPayloadException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStockPayload(InvalidStockPayloadException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.invalidStockPayload(e.getFields()));
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

    @ExceptionHandler(NoStrategySelectedException.class)
    public ResponseEntity<ErrorResponse> handleNoStrategySelected(NoStrategySelectedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("NO_STRATEGY_SELECTED"));
    }

    @ExceptionHandler(UnknownStrategyException.class)
    public ResponseEntity<ErrorResponse> handleUnknownStrategy(UnknownStrategyException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.unknownStrategy(e.getUnknown()));
    }

    @ExceptionHandler(DuplicateStrategyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateStrategy(DuplicateStrategyException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.duplicateStrategy(e.getDuplicated()));
    }

    @ExceptionHandler(TooManyStocksException.class)
    public ResponseEntity<ErrorResponse> handleTooManyStocks(TooManyStocksException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.tooManyStocks());
    }

    @ExceptionHandler(InvalidRisePercentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRisePercent(InvalidRisePercentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.invalidRisePercent(e.getStrategy()));
    }

    @ExceptionHandler(PresetNotApplicableException.class)
    public ResponseEntity<ErrorResponse> handlePresetNotApplicable(PresetNotApplicableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.presetNotApplicable(e.getStrategy()));
    }

    @ExceptionHandler(DaysNotApplicableException.class)
    public ResponseEntity<ErrorResponse> handleDaysNotApplicable(DaysNotApplicableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.daysNotApplicable(e.getStrategy()));
    }

    @ExceptionHandler(InvalidStrategyDaysException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStrategyDays(InvalidStrategyDaysException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.invalidStrategyDays(e.getStrategy()));
    }

    @ExceptionHandler(ParamNotApplicableException.class)
    public ResponseEntity<ErrorResponse> handleParamNotApplicable(ParamNotApplicableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.paramNotApplicable(e.getStrategy(), e.getParam()));
    }

    @ExceptionHandler(InvalidDropDaysException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDropDays(InvalidDropDaysException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.invalidDropDays(e.getStrategy()));
    }

    @ExceptionHandler(InvalidRiseDaysException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRiseDays(InvalidRiseDaysException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.invalidRiseDays(e.getStrategy()));
    }

    @ExceptionHandler(InvalidDropPercentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDropPercent(InvalidDropPercentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.invalidDropPercent(e.getStrategy()));
    }

    @ExceptionHandler(InvalidMetricException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMetric(InvalidMetricException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_METRIC"));
    }

    @ExceptionHandler(InvalidModeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMode(InvalidModeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_MODE"));
    }

    @ExceptionHandler(InvalidDaysException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDays(InvalidDaysException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_DAYS"));
    }

    @ExceptionHandler(InvalidMinGainException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMinGain(InvalidMinGainException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_MIN_GAIN"));
    }

    @ExceptionHandler(InvalidSortException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSort(InvalidSortException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_SORT"));
    }

    @ExceptionHandler(UpstreamEmptyException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamEmpty(UpstreamEmptyException e) {
        log.warn("Upstream returned no usable data: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("UPSTREAM_EMPTY"));
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamUnavailable(UpstreamUnavailableException e) {
        log.warn("Upstream could not be reached: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("UPSTREAM_UNAVAILABLE"));
    }

    @ExceptionHandler(UpstreamMalformedException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamMalformed(UpstreamMalformedException e) {
        log.warn("Upstream response could not be parsed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("UPSTREAM_MALFORMED"));
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
