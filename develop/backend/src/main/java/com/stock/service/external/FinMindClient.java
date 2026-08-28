package com.stock.service.external;

import com.stock.service.external.dto.FinMindResponse;
import com.stock.service.external.dto.FinMindRow;
import com.stock.service.external.dto.NormalizedPriceRow;
import com.stock.util.NormalizeUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Fetches a single stock's daily history from FinMind's TaiwanStockPrice dataset.
 * The free tier requires data_id to be present (whole-market queries return HTTP 400)
 * and enforces a request rate that callers must throttle against (see
 * {@code app.backfill.rate-limit.*}). HTTP 429 must be treated as retryable by the caller.
 */
@Component
public class FinMindClient {

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
     * Fetches normalized daily price rows for one stock in [startDate, endDate].
     * Dates with no trading data are simply absent from the result (never zero-filled).
     *
     * @throws RateLimitedException  on HTTP 429 (caller should back off and retry)
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
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new RateLimitedException("FinMind rate limited stock " + stockId + ": HTTP 429", e);
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
