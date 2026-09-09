package com.stock.service.external;

import com.stock.service.external.dto.MiIndexResponse;
import com.stock.service.external.dto.MiIndexTable;
import com.stock.service.external.dto.NormalizedPriceRow;
import com.stock.util.NormalizeUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Fetches the exchange's whole-market snapshot for ONE specific past trading day (spec: 逐日全市場
 *快照（交易所 MI_INDEX）— 已測事實). This is the ALL-mode backfill's primary fetch strategy
 * ({@link com.stock.service.SnapshotBackfillRunner}): one request per candidate trading day covers
 * the entire listed common-stock population, replacing one request per stock.
 *
 * Unlike {@link TwseClient#fetchDailyAll()} (no date parameter, only "the latest published day"),
 * MI_INDEX accepts {@code date=YYYYMMDD} for any past trading day — the whole reason this client
 * exists alongside that one.
 *
 * The response carries several tables; the per-stock quote table is located by its {@code
 * fields[0]} being exactly {@code "證券代號"} — NEVER by array index, since table count/order shift
 * with exchange page revisions and an index-based lookup would fail silently with wrong data
 * (spec: 不得以陣列索引取表). Column values within that table are likewise looked up by field NAME
 * (via {@code fields.indexOf(...)}), not by a hardcoded position, for the same reason.
 *
 * A non-trading day (weekend/holiday) has the quote table absent entirely, or present with empty
 * {@code data} — both are normal, not errors, and both map to an empty result here; the caller
 * treats an empty result as "nothing to write, but the day was still processed" (spec: 逐日回補的
 * 處理流程 step 3).
 */
@Component
public class TwseMiIndexClient {

    /** As recorded in {@code stock_daily_price.source} and used as the key into
     * {@link SourceAvailabilityTracker}/{@link SourceRateLimiter} — this source is independently
     * rate-limited/block-tracked from YAHOO/FINMIND (spec: 逐日快照來源（MI_INDEX）同樣套用「每來源獨立
     * 間隔」這套機制). */
    public static final String SOURCE_CODE = "TWSE";

    private static final String STOCK_ID_FIELD = "證券代號";
    private static final String VOLUME_FIELD = "成交股數";
    private static final String TRANSACTION_COUNT_FIELD = "成交筆數";
    private static final String TURNOVER_FIELD = "成交金額";
    private static final String OPEN_FIELD = "開盤價";
    private static final String HIGH_FIELD = "最高價";
    private static final String LOW_FIELD = "最低價";
    private static final String CLOSE_FIELD = "收盤價";

    private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestTemplate restTemplate;
    private final String miIndexUrl;

    public TwseMiIndexClient(RestTemplate externalApiRestTemplate,
                              @Value("${app.external.twse-mi-index-url}") String miIndexUrl) {
        this.restTemplate = externalApiRestTemplate;
        this.miIndexUrl = miIndexUrl;
    }

    /**
     * Fetches and normalizes every stock's OHLCV + turnover/transaction-count row for the given
     * trading day. Returns an empty list on a non-trading day (spec: 非交易日...這是正常情形，不是錯誤) —
     * the caller distinguishes "no data because it wasn't a trading day" from "no data because
     * every row failed normalization" only insofar as neither is ever treated as a failure.
     *
     * @throws SourceBlockedException on HTTP 403/429 or a quota-indicating body — caller must mark
     *                                this source unavailable and, per spec, degrade to the per-stock
     *                                path rather than end the batch (spec: 逐日快照不可用時降級為逐檔)
     * @throws RateLimitedException   on a timeout — caller may retry
     * @throws ExternalApiException   on any other non-retryable connectivity failure
     * @throws ExternalApiMalformedException on a response that could not be parsed into the
     *                                        expected shape at all
     */
    public List<NormalizedPriceRow> fetchSnapshot(LocalDate date) {
        String url = UriComponentsBuilder.fromHttpUrl(miIndexUrl)
                .queryParam("date", date.format(DATE_PARAM))
                .queryParam("type", "ALL")
                .queryParam("response", "json")
                .toUriString();

        MiIndexResponse response;
        try {
            response = restTemplate.getForObject(url, MiIndexResponse.class);
        } catch (HttpStatusCodeException e) {
            if (RetryAfterExtractor.isBlockedResponse(e)) {
                throw new SourceBlockedException(
                        "TWSE MI_INDEX blocked for date " + date + ": HTTP " + e.getRawStatusCode(),
                        RetryAfterExtractor.extract(e));
            }
            throw new ExternalApiException(
                    "TWSE MI_INDEX request failed for date " + date + ": HTTP " + e.getRawStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new RateLimitedException("TWSE MI_INDEX request timed out for date " + date, e);
        } catch (RestClientException e) {
            throw new ExternalApiMalformedException(
                    "TWSE MI_INDEX response for date " + date + " could not be parsed: " + e.getMessage(), e);
        }

        MiIndexTable table = findStockTable(response);
        if (table == null || table.getData() == null || table.getData().isEmpty()) {
            return Collections.emptyList();
        }
        return normalize(table, date);
    }

    /** Locates the per-stock quote table by {@code fields[0] == "證券代號"}, never by index. */
    private MiIndexTable findStockTable(MiIndexResponse response) {
        if (response == null || response.getTables() == null) {
            return null;
        }
        for (MiIndexTable table : response.getTables()) {
            List<String> fields = table.getFields();
            if (fields != null && !fields.isEmpty() && STOCK_ID_FIELD.equals(fields.get(0))) {
                return table;
            }
        }
        return null;
    }

    private List<NormalizedPriceRow> normalize(MiIndexTable table, LocalDate date) {
        List<String> fields = table.getFields();
        int stockIdIdx = fields.indexOf(STOCK_ID_FIELD);
        int volumeIdx = fields.indexOf(VOLUME_FIELD);
        int transactionCountIdx = fields.indexOf(TRANSACTION_COUNT_FIELD);
        int turnoverIdx = fields.indexOf(TURNOVER_FIELD);
        int openIdx = fields.indexOf(OPEN_FIELD);
        int highIdx = fields.indexOf(HIGH_FIELD);
        int lowIdx = fields.indexOf(LOW_FIELD);
        int closeIdx = fields.indexOf(CLOSE_FIELD);

        List<NormalizedPriceRow> rows = new ArrayList<>();
        for (List<String> row : table.getData()) {
            String stockId = valueAt(row, stockIdIdx);
            if (stockId == null || stockId.trim().isEmpty()) {
                continue;
            }
            stockId = stockId.trim();

            Optional<BigDecimal> open = NormalizeUtil.toBigDecimal(valueAt(row, openIdx));
            Optional<BigDecimal> high = NormalizeUtil.toBigDecimal(valueAt(row, highIdx));
            Optional<BigDecimal> low = NormalizeUtil.toBigDecimal(valueAt(row, lowIdx));
            Optional<BigDecimal> close = NormalizeUtil.toBigDecimal(valueAt(row, closeIdx));
            // No-trade / suspended stocks: MI_INDEX omits or blanks price fields for that row.
            // Do not backfill with zero (spec: 不得補零).
            if (!open.isPresent() || !high.isPresent() || !low.isPresent() || !close.isPresent()) {
                continue;
            }

            long volume = NormalizeUtil.toBigDecimal(valueAt(row, volumeIdx)).map(BigDecimal::longValue).orElse(0L);
            BigDecimal turnover = NormalizeUtil.toBigDecimal(valueAt(row, turnoverIdx)).orElse(BigDecimal.ZERO);
            int transactionCount = NormalizeUtil.toBigDecimal(valueAt(row, transactionCountIdx))
                    .map(BigDecimal::intValue).orElse(0);

            rows.add(new NormalizedPriceRow(stockId, date, open.get(), high.get(), low.get(), close.get(),
                    volume, turnover, transactionCount));
        }
        return rows;
    }

    private String valueAt(List<String> row, int index) {
        return (index < 0 || index >= row.size()) ? null : row.get(index);
    }

    public String getCode() {
        return SOURCE_CODE;
    }
}
