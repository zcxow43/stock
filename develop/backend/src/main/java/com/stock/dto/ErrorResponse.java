package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String code;
    private final List<String> unknownIds;
    private final Integer limit;
    // Untyped on purpose: this single "allowed" key is reused for different error codes with
    // different element types (INVALID_SORT_FIELD -> strings, INVALID_INTERVAL -> integers), and
    // the wire contract (specs/backend/stock-minute-price.md) fixes the JSON key name as "allowed"
    // for both.
    private final List<?> allowed;
    private final String stockId;
    private final List<String> fields;
    // strategy-scan.md wire contract: UNKNOWN_STRATEGY uses key "unknown", DUPLICATE_STRATEGY uses
    // key "duplicated" — distinct from unknownIds (UNKNOWN_STOCK_ID), which is a separate error.
    private final List<String> unknown;
    private final List<String> duplicated;
    // INVALID_RISE_PERCENT's "strategy" key — distinct from unknown/duplicated: it names the one
    // strategy whose risePercent override is out of range, not a list of offenders.
    private final String strategy;
    // PARAM_NOT_APPLICABLE's "param" key — names the one REBOUND-only field that was sent to a
    // strategy that does not accept it (or sent alongside requireRise=false).
    private final String param;

    public ErrorResponse(String code) {
        this(code, null, null, null, null, null, null, null, null, null);
    }

    public ErrorResponse(String code, List<String> unknownIds) {
        this(code, unknownIds, null, null, null, null, null, null, null, null);
    }

    // Explicit @JsonCreator: with more than one constructor present, Jackson's implicit
    // single-constructor auto-detection no longer applies, so response deserialization in tests
    // needs this to be unambiguous.
    @JsonCreator
    private ErrorResponse(@JsonProperty("code") String code,
                           @JsonProperty("unknownIds") List<String> unknownIds,
                           @JsonProperty("limit") Integer limit,
                           @JsonProperty("allowed") List<?> allowed,
                           @JsonProperty("stockId") String stockId,
                           @JsonProperty("fields") List<String> fields,
                           @JsonProperty("unknown") List<String> unknown,
                           @JsonProperty("duplicated") List<String> duplicated,
                           @JsonProperty("strategy") String strategy,
                           @JsonProperty("param") String param) {
        this.code = code;
        this.unknownIds = unknownIds;
        this.limit = limit;
        this.allowed = allowed;
        this.stockId = stockId;
        this.fields = fields;
        this.unknown = unknown;
        this.duplicated = duplicated;
        this.strategy = strategy;
        this.param = param;
    }

    public static ErrorResponse pageSizeExceeded(int limit) {
        return new ErrorResponse("PAGE_SIZE_EXCEEDED", null, limit, null, null, null, null, null, null, null);
    }

    public static ErrorResponse tooManyStockIds(int limit) {
        return new ErrorResponse("TOO_MANY_STOCK_IDS", null, limit, null, null, null, null, null, null, null);
    }

    public static ErrorResponse invalidSortField(List<String> allowed) {
        return new ErrorResponse("INVALID_SORT_FIELD", null, null, allowed, null, null, null, null, null, null);
    }

    public static ErrorResponse stockNotFound(String stockId) {
        return new ErrorResponse("STOCK_NOT_FOUND", null, null, null, stockId, null, null, null, null, null);
    }

    public static ErrorResponse invalidInterval(List<Integer> allowedIntervals) {
        return new ErrorResponse("INVALID_INTERVAL", null, null, allowedIntervals, null, null, null, null, null,
                null);
    }

    public static ErrorResponse invalidStockPayload(List<String> fields) {
        return new ErrorResponse("INVALID_STOCK_PAYLOAD", null, null, null, null, fields, null, null, null, null);
    }

    public static ErrorResponse unknownStrategy(List<String> unknown) {
        return new ErrorResponse("UNKNOWN_STRATEGY", null, null, null, null, null, unknown, null, null, null);
    }

    public static ErrorResponse duplicateStrategy(List<String> duplicated) {
        return new ErrorResponse("DUPLICATE_STRATEGY", null, null, null, null, null, null, duplicated, null, null);
    }

    public static ErrorResponse tooManyStocks() {
        return new ErrorResponse("TOO_MANY_STOCKS", null, null, null, null, null, null, null, null, null);
    }

    public static ErrorResponse invalidRisePercent(String strategy) {
        return new ErrorResponse("INVALID_RISE_PERCENT", null, null, null, null, null, null, null, strategy, null);
    }

    public static ErrorResponse presetNotApplicable(String strategy) {
        return new ErrorResponse("PRESET_NOT_APPLICABLE", null, null, null, null, null, null, null, strategy, null);
    }

    public static ErrorResponse daysNotApplicable(String strategy) {
        return new ErrorResponse("DAYS_NOT_APPLICABLE", null, null, null, null, null, null, null, strategy, null);
    }

    public static ErrorResponse invalidStrategyDays(String strategy) {
        return new ErrorResponse("INVALID_DAYS", null, null, null, null, null, null, null, strategy, null);
    }

    public static ErrorResponse paramNotApplicable(String strategy, String param) {
        return new ErrorResponse("PARAM_NOT_APPLICABLE", null, null, null, null, null, null, null, strategy, param);
    }

    public static ErrorResponse invalidDropDays(String strategy) {
        return new ErrorResponse("INVALID_DROP_DAYS", null, null, null, null, null, null, null, strategy, null);
    }

    public static ErrorResponse invalidRiseDays(String strategy) {
        return new ErrorResponse("INVALID_RISE_DAYS", null, null, null, null, null, null, null, strategy, null);
    }

    public static ErrorResponse invalidDropPercent(String strategy) {
        return new ErrorResponse("INVALID_DROP_PERCENT", null, null, null, null, null, null, null, strategy, null);
    }

    public String getCode() {
        return code;
    }

    public List<String> getUnknownIds() {
        return unknownIds;
    }

    public Integer getLimit() {
        return limit;
    }

    public List<?> getAllowed() {
        return allowed;
    }

    public String getStockId() {
        return stockId;
    }

    public List<String> getFields() {
        return fields;
    }

    public List<String> getUnknown() {
        return unknown;
    }

    public List<String> getDuplicated() {
        return duplicated;
    }

    public String getStrategy() {
        return strategy;
    }

    public String getParam() {
        return param;
    }
}
