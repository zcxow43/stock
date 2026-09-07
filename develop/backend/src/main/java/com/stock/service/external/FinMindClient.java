package com.stock.service.external;

import com.stock.service.external.dto.FinMindResponse;
import com.stock.service.external.dto.FinMindRow;
import com.stock.service.external.dto.NormalizedPriceRow;
import com.stock.util.NormalizeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Fetches a single stock's daily history from FinMind's TaiwanStockPrice dataset — the fallback
 * source in the {@link PriceHistorySource} priority order (YAHOO -> FINMIND, spec: 來源順位).
 * The free tier requires data_id to be present (whole-market queries return HTTP 400)
 * and enforces a request rate that callers must throttle against (see
 * {@code app.backfill.rate-limit.*}).
 *
 * <p>HTTP 403/429 (or a body indicating quota/ban, e.g. FinMind's {@code {"msg":"ip banned"}})
 * are classified as {@link SourceBlockedException} — not retried on this source at all — per
 * spec: 封鎖類回應的判定. This is a deliberate change from treating 429 as same-source
 * backoff-retryable: this incident is exactly what motivated the multi-source design (see
 * specs/backend/stock-price-ingestion.md, 逐檔歷史為什麼必須有兩個來源). Only a timeout remains
 * retryable via {@link RateLimitedException}.
 */
@Component
public class FinMindClient implements PriceHistorySource {

    private static final Logger log = LoggerFactory.getLogger(FinMindClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String token;

    public FinMindClient(RestTemplate externalApiRestTemplate,
                          @Value("${app.external.finmind-base-url}") String baseUrl,
                          @Value("${app.external.finmind-token:}") String token) {
        this.restTemplate = externalApiRestTemplate;
        this.baseUrl = baseUrl;
        this.token = token;
    }

    /**
     * Warns at startup when no token is configured (spec: token 必須設定...未設定時應在啟動時留下明確的
     * 警告紀錄，而不是安靜地以匿名身分運作). The token itself must come from the deployment environment
     * (e.g. an {@code APP_EXTERNAL_FINMIND_TOKEN} env var overriding the blank default in
     * application.yml) and must never be hardcoded in a version-controlled config file.
     */
    @PostConstruct
    public void warnIfTokenMissing() {
        if (token == null || token.trim().isEmpty()) {
            log.warn("FinMind API token (app.external.finmind-token) is not set. Requests will be sent "
                    + "anonymously and subject to the lowest free-tier quota, which is what triggered the "
                    + "2026-09-06 IP ban incident. Set it via the deployment environment "
                    + "(e.g. APP_EXTERNAL_FINMIND_TOKEN) -- never hardcode a real token in a "
                    + "version-controlled config file.");
        }
    }

    @Override
    public String getCode() {
        return SOURCE_FINMIND;
    }

    /** {@link PriceHistorySource} entry point; FinMind doesn't need the market suffix Yahoo does. */
    @Override
    public List<NormalizedPriceRow> fetchDailyHistory(String stockId, String market, LocalDate startDate,
                                                        LocalDate endDate) {
        return fetchHistory(stockId, startDate, endDate);
    }

    /**
     * Fetches normalized daily price rows for one stock in [startDate, endDate].
     * Dates with no trading data are simply absent from the result (never zero-filled).
     *
     * @throws SourceBlockedException on HTTP 403/429 or a quota-indicating body (caller should
     *                                switch to the next source, never retry this one)
     * @throws SymbolNotFoundException on HTTP 404 (caller should try the next source)
     * @throws RateLimitedException  on a timeout (caller may back off and retry this source)
     * @throws ExternalApiException  on any other non-retryable failure
     */
    public List<NormalizedPriceRow> fetchHistory(String stockId, LocalDate startDate, LocalDate endDate) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("dataset", "TaiwanStockPrice")
                .queryParam("data_id", stockId)
                .queryParam("start_date", startDate.toString())
                .queryParam("end_date", endDate.toString());
        if (token != null && !token.trim().isEmpty()) {
            uriBuilder.queryParam("token", token);
        }

        FinMindResponse response;
        try {
            response = restTemplate.getForObject(uriBuilder.toUriString(), FinMindResponse.class);
        } catch (HttpStatusCodeException e) {
            if (RetryAfterExtractor.isBlockedResponse(e)) {
                throw new SourceBlockedException(
                        "FinMind blocked for stock " + stockId + ": HTTP " + e.getRawStatusCode(),
                        RetryAfterExtractor.extract(e));
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new SymbolNotFoundException("FinMind has no data for stock " + stockId);
            }
            throw new ExternalApiException(
                    "FinMind request failed for stock " + stockId + ": HTTP " + e.getRawStatusCode(), e);
        } catch (ResourceAccessException e) {
            // Connect/read timeout - treat as retryable, same as rate limiting.
            throw new RateLimitedException("FinMind request timed out for stock " + stockId, e);
        } catch (RestClientException e) {
            throw new ExternalApiException("FinMind request failed for stock " + stockId + ": " + e.getMessage(), e);
        }

        if (response == null) {
            throw new ExternalApiException("FinMind returned an empty response for stock " + stockId);
        }
        if (response.getData() == null || response.getData().isEmpty()) {
            return Collections.emptyList();
        }

        List<NormalizedPriceRow> rows = new ArrayList<>();
        for (FinMindRow raw : response.getData()) {
            normalize(stockId, raw).ifPresent(rows::add);
        }
        return rows;
    }

    private Optional<NormalizedPriceRow> normalize(String stockId, FinMindRow raw) {
        Optional<BigDecimal> open = NormalizeUtil.toBigDecimal(raw.getOpen());
        Optional<BigDecimal> high = NormalizeUtil.toBigDecimal(raw.getMax());
        Optional<BigDecimal> low = NormalizeUtil.toBigDecimal(raw.getMin());
        Optional<BigDecimal> close = NormalizeUtil.toBigDecimal(raw.getClose());
        if (!open.isPresent() || !high.isPresent() || !low.isPresent() || !close.isPresent() || raw.getDate() == null) {
            return Optional.empty();
        }
        long volume = NormalizeUtil.toBigDecimal(raw.getTradingVolume()).map(BigDecimal::longValue).orElse(0L);
        BigDecimal turnover = NormalizeUtil.toBigDecimal(raw.getTradingMoney()).orElse(BigDecimal.ZERO);
        int transactionCount = NormalizeUtil.toBigDecimal(raw.getTradingTurnover()).map(BigDecimal::intValue).orElse(0);

        LocalDate tradeDate = NormalizeUtil.toIsoDate(raw.getDate());
        return Optional.of(new NormalizedPriceRow(
                stockId, tradeDate, open.get(), high.get(), low.get(), close.get(),
                volume, turnover, transactionCount));
    }
}
