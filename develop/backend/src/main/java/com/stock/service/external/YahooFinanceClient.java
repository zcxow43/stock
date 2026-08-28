package com.stock.service.external;

import com.stock.service.external.dto.NormalizedMinuteBar;
import com.stock.service.external.dto.YahooChartResponse;
import com.stock.service.external.dto.YahooChartResult;
import com.stock.service.external.dto.YahooIndicators;
import com.stock.service.external.dto.YahooQuote;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fetches one stock's 1-minute intraday bars for a single trade date from Yahoo Finance's
 * public chart endpoint. FinMind's TaiwanStockKBar dataset rejects free-tier callers outright
 * (HTTP 400), so minute data uses a different source than the daily ingestion path — see
 * specs/backend/stock-minute-price.md, 資料源與其限制.
 *
 * `interval=1m` only serves the most recent 30 days; the caller is responsible for the local
 * "is this date within the window" check before calling this client (spec: 超窗判斷在本地完成，
 * 不發請求) — this client does not special-case HTTP 422.
 */
@Component
public class YahooFinanceClient {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final LocalTime SESSION_OPEN = LocalTime.of(9, 0);
    private static final LocalTime SESSION_CLOSE = LocalTime.of(13, 30);
    private static final String MARKET_OTC = "OTC";
    // Yahoo's edge rejects requests whose User-Agent identifies a bare HTTP client (e.g. the JVM's
    // default "Java/17...") with HTTP 429, regardless of actual request rate. A browser-like
    // User-Agent is required for every request, not just as a rate-limit workaround.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public YahooFinanceClient(RestTemplate externalApiRestTemplate,
                               @Value("${app.external.yahoo-finance-base-url}") String baseUrl) {
        this.restTemplate = externalApiRestTemplate;
        this.baseUrl = baseUrl;
    }

    /**
     * Fetches normalized 1-minute bars for one stock on one trade date, restricted to the
     * 09:00-13:30 regular session (spec: 正規化規則). Minutes with no trade (any of OHLC null)
     * are simply absent from the result, never zero-filled.
     *
     * @throws RateLimitedException on HTTP 429 / timeout (caller should back off and retry)
     * @throws ExternalApiException on any other non-retryable failure
     */
    public List<NormalizedMinuteBar> fetchMinuteBars(String stockId, String market, LocalDate tradeDate) {
        String symbol = stockId + (MARKET_OTC.equals(market) ? ".TWO" : ".TW");
        long period1 = tradeDate.atStartOfDay(TAIPEI).toEpochSecond();
        long period2 = tradeDate.plusDays(1).atStartOfDay(TAIPEI).toEpochSecond();

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/{symbol}")
                .queryParam("interval", "1m")
                .queryParam("period1", period1)
                .queryParam("period2", period2)
                .buildAndExpand(symbol)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        YahooChartResponse response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, YahooChartResponse.class).getBody();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new RateLimitedException("Yahoo Finance rate limited stock " + stockId + ": HTTP 429", e);
            }
            throw new ExternalApiException(
                    "Yahoo Finance request failed for stock " + stockId + ": HTTP " + e.getRawStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new RateLimitedException("Yahoo Finance request timed out for stock " + stockId, e);
        } catch (RestClientException e) {
            throw new ExternalApiException("Yahoo Finance request failed for stock " + stockId + ": " + e.getMessage(), e);
        }

        if (response == null || response.getChart() == null || response.getChart().getResult() == null
                || response.getChart().getResult().isEmpty()) {
            return Collections.emptyList();
        }
        return normalize(stockId, tradeDate, response.getChart().getResult().get(0));
    }

    private List<NormalizedMinuteBar> normalize(String stockId, LocalDate tradeDate, YahooChartResult result) {
        long[] timestamps = result.getTimestamp();
        YahooIndicators indicators = result.getIndicators();
        if (timestamps == null || indicators == null || indicators.getQuote() == null || indicators.getQuote().isEmpty()) {
            return Collections.emptyList();
        }
        YahooQuote quote = indicators.getQuote().get(0);

        List<NormalizedMinuteBar> bars = new ArrayList<>();
        for (int i = 0; i < timestamps.length; i++) {
            Double open = at(quote.getOpen(), i);
            Double high = at(quote.getHigh(), i);
            Double low = at(quote.getLow(), i);
            Double close = at(quote.getClose(), i);
            // No trade this minute: source leaves OHLC null for that index. Drop the whole bar,
            // never zero-fill or carry forward the previous close (spec: 正規化規則).
            if (open == null || high == null || low == null || close == null) {
                continue;
            }

            ZonedDateTime taipeiTime = Instant.ofEpochSecond(timestamps[i]).atZone(TAIPEI);
            if (!taipeiTime.toLocalDate().equals(tradeDate)) {
                continue;
            }
            LocalTime barTime = taipeiTime.toLocalTime().withSecond(0).withNano(0);
            if (barTime.isBefore(SESSION_OPEN) || barTime.isAfter(SESSION_CLOSE)) {
                continue;
            }

            Long rawVolume = at(quote.getVolume(), i);
            long volume = rawVolume == null ? 0L : rawVolume;

            bars.add(new NormalizedMinuteBar(stockId, tradeDate, barTime,
                    money(open), money(high), money(low), money(close), volume));
        }
        return bars;
    }

    private <T> T at(List<T> list, int index) {
        return (list == null || index >= list.size()) ? null : list.get(index);
    }

    private BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
