package com.stock.service.external;

import com.stock.service.external.dto.FugleCandle;
import com.stock.service.external.dto.FugleCandlesResponse;
import com.stock.service.external.dto.NormalizedMinuteBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fetches one stock's 1-minute intraday bars for a single trade date, for dates older than
 * Yahoo's 30-day window but not earlier than {@code app.minute-price.available-from} — see
 * specs/backend/stock-minute-price.md, 富果 Fugle. Deliberately does NOT implement {@link
 * PriceHistorySource}: unlike the daily-history path, minute bars have exactly one source per
 * date (Yahoo XOR Fugle, never both), so there is no fallback/priority selection to plug into.
 *
 * <p>Response shape confirmed live on 2026-09-15 against the real API: plain stock code (no
 * `.TW`/`.TWO` suffix) works for both TWSE (2330) and TPEx (6488) codes; the response's top-level
 * `data[]` holds the bars, each `date` an ISO 8601 timestamp with a `+08:00` offset marking the
 * bar's start minute; a normal trading day is 266 bars (13:25–13:29 has none — closing call
 * auction); a query with no data (pre-2023-05-23, non-trading day) returns HTTP 404, not `[]`.
 *
 * <p>The API key is sent only via the `X-API-KEY` header, never logged or included in any
 * exception message this class constructs (spec: 金鑰值不出現在應用程式日誌...或任何 API 回應中).
 */
@Component
public class FugleClient {

    private static final Logger log = LoggerFactory.getLogger(FugleClient.class);

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final LocalTime SESSION_OPEN = LocalTime.of(9, 0);
    private static final LocalTime SESSION_CLOSE = LocalTime.of(13, 30);
    private static final int PRICE_SCALE = 2;
    private static final long SHARES_PER_LOT = 1000L;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;

    public FugleClient(RestTemplate externalApiRestTemplate,
                        @Value("${app.external.fugle-base-url}") String baseUrl,
                        @Value("${app.minute-price.fugle.api-key:}") String apiKey) {
        this.restTemplate = externalApiRestTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /**
     * Warns at startup when no key is configured (spec: 未設定 API Key 時，應用程式啟動階段留下明確的警告紀錄).
     * The key itself must come from the FUGLE_API_KEY environment variable — never hardcoded in a
     * version-controlled config file. Queries for dates older than 30 days fail fast with
     * FETCH_FAILED when this is unset; see {@code MinuteBarQueryService}.
     */
    @PostConstruct
    public void warnIfApiKeyMissing() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("Fugle API key (app.minute-price.fugle.api-key / FUGLE_API_KEY) is not set. "
                    + "Minute-bar queries for trade dates older than 30 days will fail with FETCH_FAILED "
                    + "until it is configured. Set FUGLE_API_KEY in the deployment environment -- never "
                    + "hardcode a real key in a version-controlled config file.");
        }
    }

    public boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * Fetches normalized 1-minute bars for one stock on one trade date, restricted to the
     * 09:00-13:30 regular session (spec: 正規化規則). Minutes with no trade (any of OHLC null) are
     * simply absent from the result, never zero-filled. An empty result (HTTP 404, "Resource Not
     * Found") means the source has no minute data for this date — the caller records this as
     * NO_DATA, same as Yahoo's empty-result case.
     *
     * @throws RateLimitedException on HTTP 429 or a timeout (caller should back off and retry)
     * @throws ExternalApiException on HTTP 401/403/5xx or any other non-retryable failure
     */
    public List<NormalizedMinuteBar> fetchMinuteBars(String stockId, LocalDate tradeDate) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/{stockId}")
                .queryParam("from", tradeDate)
                .queryParam("to", tradeDate)
                .queryParam("timeframe", "1")
                .queryParam("fields", "open,high,low,close,volume")
                .queryParam("sort", "asc")
                .buildAndExpand(stockId)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        FugleCandlesResponse response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, FugleCandlesResponse.class).getBody();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                // "Resource Not Found" -- no minute data for this date (pre-availableFrom, non-trading
                // day, etc.), not an error condition (spec: HTTP 404 -> NO_DATA).
                return Collections.emptyList();
            }
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new RateLimitedException("Fugle rate limited stock " + stockId + ": HTTP 429", e);
            }
            // 401/403/5xx and everything else non-404: a real failure, recorded as FAILED (spec:
            // HTTP 401／403／429、逾時或 5xx 時記為 FAILED). The response body Fugle sends on 401/403 never
            // echoes the key back, so it is safe to surface HttpStatusCodeException#getMessage() here.
            throw new ExternalApiException(
                    "Fugle request failed for stock " + stockId + ": HTTP " + e.getRawStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new RateLimitedException("Fugle request timed out for stock " + stockId, e);
        } catch (RestClientException e) {
            throw new ExternalApiException("Fugle request failed for stock " + stockId + ": " + e.getMessage(), e);
        }

        if (response == null || response.getData() == null) {
            return Collections.emptyList();
        }
        return normalize(stockId, tradeDate, response.getData());
    }

    private List<NormalizedMinuteBar> normalize(String stockId, LocalDate tradeDate, List<FugleCandle> candles) {
        List<NormalizedMinuteBar> bars = new ArrayList<>();
        for (FugleCandle candle : candles) {
            if (candle.getOpen() == null || candle.getHigh() == null || candle.getLow() == null
                    || candle.getClose() == null || candle.getDate() == null) {
                // No trade that minute -- drop the whole bar, never zero-fill (spec: 正規化規則).
                continue;
            }

            ZonedDateTime taipeiTime = OffsetDateTime.parse(candle.getDate()).atZoneSameInstant(TAIPEI);
            if (!taipeiTime.toLocalDate().equals(tradeDate)) {
                continue;
            }
            LocalTime barTime = taipeiTime.toLocalTime().withSecond(0).withNano(0);
            if (barTime.isBefore(SESSION_OPEN) || barTime.isAfter(SESSION_CLOSE)) {
                continue;
            }

            // Fugle reports 張 (lots); stock_minute_price.volume is 股 like Yahoo and the daily table
            // (spec: 富果 volume 單位是「張」，寫入前必須 × 1000).
            long volume = candle.getVolume() == null ? 0L : candle.getVolume() * SHARES_PER_LOT;
            bars.add(new NormalizedMinuteBar(stockId, tradeDate, barTime,
                    money(candle.getOpen()), money(candle.getHigh()), money(candle.getLow()),
                    money(candle.getClose()), volume));
        }
        return bars;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
