package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
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
    // strategy-backtest.md wire contract: DUPLICATE_BACKTEST_ITEM uses key "duplicatedItems", each
    // element a {stockId, buyDate} pair — distinct from "duplicated" (DUPLICATE_STRATEGY, a
    // different endpoint's error for a different kind of duplicate) and from "unknownIds"
    // (UNKNOWN_STOCK_ID). The uniqueness key here is the (stockId, buyDate) combination, not
    // stockId alone (specs/backend/strategy-backtest.md, "一筆＝一個買進日").
    private final List<BacktestDuplicateItemDto> duplicatedItems;
    // simulated-trade.md wire contract: DUPLICATE_SIMULATED_TRADE's and (this endpoint's own)
    // INVALID_BUY_DATE's and NO_PRICE_ON_BUY_DATE's shared "buyDate" key. Typed as a raw String, not
    // LocalDate: INVALID_BUY_DATE must be able to echo back a malformed, unparseable value exactly
    // as submitted (see specs/backend/simulated-trade.md, "指定買進日（本次新增）"); the other two
    // codes always hold a real date, formatted the same ISO-8601 way either type would serialize to,
    // so this widening changes no byte on the wire for them.
    private final String buyDate;
    // simulated-trade.md wire contract: SIMULATED_TRADE_NOT_FOUND's "id" key — the path {id} that
    // matched no row.
    private final Long id;

    public ErrorResponse(String code) {
        this(code, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public ErrorResponse(String code, List<String> unknownIds) {
        this(code, unknownIds, null, null, null, null, null, null, null, null, null, null, null);
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
                           @JsonProperty("param") String param,
                           @JsonProperty("duplicatedItems") List<BacktestDuplicateItemDto> duplicatedItems,
                           @JsonProperty("buyDate") String buyDate,
                           @JsonProperty("id") Long id) {
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
        this.duplicatedItems = duplicatedItems;
        this.buyDate = buyDate;
        this.id = id;
    }

    public static ErrorResponse pageSizeExceeded(int limit) {
        return new ErrorResponse("PAGE_SIZE_EXCEEDED", null, limit, null, null, null, null, null, null, null, null,
                null, null);
    }

    public static ErrorResponse tooManyStockIds(int limit) {
        return new ErrorResponse("TOO_MANY_STOCK_IDS", null, limit, null, null, null, null, null, null, null, null,
                null, null);
    }

    public static ErrorResponse invalidSortField(List<String> allowed) {
        return new ErrorResponse("INVALID_SORT_FIELD", null, null, allowed, null, null, null, null, null, null,
                null, null, null);
    }

    public static ErrorResponse stockNotFound(String stockId) {
        return new ErrorResponse("STOCK_NOT_FOUND", null, null, null, stockId, null, null, null, null, null, null,
                null, null);
    }

    public static ErrorResponse invalidInterval(List<Integer> allowedIntervals) {
        return new ErrorResponse("INVALID_INTERVAL", null, null, allowedIntervals, null, null, null, null, null,
                null, null, null, null);
    }

    public static ErrorResponse invalidStockPayload(List<String> fields) {
        return new ErrorResponse("INVALID_STOCK_PAYLOAD", null, null, null, null, fields, null, null, null, null,
                null, null, null);
    }

    public static ErrorResponse unknownStrategy(List<String> unknown) {
        return new ErrorResponse("UNKNOWN_STRATEGY", null, null, null, null, null, unknown, null, null, null, null,
                null, null);
    }

    public static ErrorResponse duplicateStrategy(List<String> duplicated) {
        return new ErrorResponse("DUPLICATE_STRATEGY", null, null, null, null, null, null, duplicated, null, null,
                null, null, null);
    }

    public static ErrorResponse tooManyStocks() {
        return new ErrorResponse("TOO_MANY_STOCKS", null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    public static ErrorResponse invalidRisePercent(String strategy) {
        return new ErrorResponse("INVALID_RISE_PERCENT", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse presetNotApplicable(String strategy) {
        return new ErrorResponse("PRESET_NOT_APPLICABLE", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse daysNotApplicable(String strategy) {
        return new ErrorResponse("DAYS_NOT_APPLICABLE", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse invalidStrategyDays(String strategy) {
        return new ErrorResponse("INVALID_DAYS", null, null, null, null, null, null, null, strategy, null, null,
                null, null);
    }

    public static ErrorResponse paramNotApplicable(String strategy, String param) {
        return new ErrorResponse("PARAM_NOT_APPLICABLE", null, null, null, null, null, null, null, strategy, param,
                null, null, null);
    }

    public static ErrorResponse invalidDropDays(String strategy) {
        return new ErrorResponse("INVALID_DROP_DAYS", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse invalidRiseDays(String strategy) {
        return new ErrorResponse("INVALID_RISE_DAYS", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse invalidDropPercent(String strategy) {
        return new ErrorResponse("INVALID_DROP_PERCENT", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse invalidBuyDate(String stockId) {
        return new ErrorResponse("INVALID_BUY_DATE", null, null, null, stockId, null, null, null, null, null,
                null, null, null);
    }

    public static ErrorResponse invalidInvestors(String strategy) {
        return new ErrorResponse("INVALID_INVESTORS", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse invalidWindowDays(String strategy) {
        return new ErrorResponse("INVALID_WINDOW_DAYS", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse invalidRatioPercent(String strategy) {
        return new ErrorResponse("INVALID_RATIO_PERCENT", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse invalidBuyDays(String strategy) {
        return new ErrorResponse("INVALID_BUY_DAYS", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse invalidTopN(String strategy) {
        return new ErrorResponse("INVALID_TOP_N", null, null, null, null, null, null, null, strategy, null, null,
                null, null);
    }

    public static ErrorResponse invalidFastPeriod(String strategy) {
        return new ErrorResponse("INVALID_FAST_PERIOD", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse invalidSlowPeriod(String strategy) {
        return new ErrorResponse("INVALID_SLOW_PERIOD", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse invalidMacdPeriods(String strategy) {
        return new ErrorResponse("INVALID_MACD_PERIODS", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse invalidJThreshold(String strategy) {
        return new ErrorResponse("INVALID_J_THRESHOLD", null, null, null, null, null, null, null, strategy, null,
                null, null, null);
    }

    public static ErrorResponse duplicateBacktestItem(List<BacktestDuplicateItemDto> duplicatedItems) {
        return new ErrorResponse("DUPLICATE_BACKTEST_ITEM", null, null, null, null, null, null, null, null, null,
                duplicatedItems, null, null);
    }

    /** NO_PRICE_BEFORE_TODAY (specs/backend/simulated-trade.md, "驗證與錯誤") reuses the "stockId" key. */
    public static ErrorResponse noPriceBeforeToday(String stockId) {
        return new ErrorResponse("NO_PRICE_BEFORE_TODAY", null, null, null, stockId, null, null, null, null, null,
                null, null, null);
    }

    /** DUPLICATE_SIMULATED_TRADE (specs/backend/simulated-trade.md, "驗證與錯誤"): {stockId, buyDate}. */
    public static ErrorResponse duplicateSimulatedTrade(String stockId, LocalDate buyDate) {
        return new ErrorResponse("DUPLICATE_SIMULATED_TRADE", null, null, null, stockId, null, null, null, null,
                null, null, buyDate == null ? null : buyDate.toString(), null);
    }

    /**
     * INVALID_BUY_DATE for POST /api/simulated-trades (specs/backend/simulated-trade.md, "指定買進日
     * （本次新增）"): {buyDate}, the raw as-submitted value — malformed or later than today. Distinct
     * from {@link #invalidBuyDate(String)}, which is POST /api/strategies/backtest's own
     * same-named-code error with a {@code stockId} key instead.
     */
    public static ErrorResponse invalidSimulatedTradeBuyDate(String buyDate) {
        return new ErrorResponse("INVALID_BUY_DATE", null, null, null, null, null, null, null, null, null,
                null, buyDate, null);
    }

    /** NO_PRICE_ON_BUY_DATE (specs/backend/simulated-trade.md, "指定買進日（本次新增）"): {stockId, buyDate}. */
    public static ErrorResponse noPriceOnBuyDate(String stockId, LocalDate buyDate) {
        return new ErrorResponse("NO_PRICE_ON_BUY_DATE", null, null, null, stockId, null, null, null, null,
                null, null, buyDate == null ? null : buyDate.toString(), null);
    }

    /** SIMULATED_TRADE_NOT_FOUND (specs/backend/simulated-trade.md, "驗證與錯誤"): {id}. */
    public static ErrorResponse simulatedTradeNotFound(Long id) {
        return new ErrorResponse("SIMULATED_TRADE_NOT_FOUND", null, null, null, null, null, null, null, null, null,
                null, null, id);
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

    public List<BacktestDuplicateItemDto> getDuplicatedItems() {
        return duplicatedItems;
    }

    public String getBuyDate() {
        return buyDate;
    }

    public Long getId() {
        return id;
    }
}
