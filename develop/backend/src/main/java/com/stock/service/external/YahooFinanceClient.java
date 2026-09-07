package com.stock.service.external;

import com.stock.service.external.dto.NormalizedMinuteBar;
import com.stock.service.external.dto.NormalizedPriceRow;
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
 * Fetches one stock's 1-minute intraday bars for a single trade date, and one stock's whole-range
 * daily history, from Yahoo Finance's public chart endpoint — the SAME endpoint and parallel-array
 * response shape for both (spec: Yahoo（主來源）的已測事實), differing only in {@code interval}
 * (1m vs 1d). FinMind's TaiwanStockKBar dataset rejects free-tier callers outright (HTTP 400), so
 * minute data has no FinMind equivalent — see specs/backend/stock-minute-price.md, 資料源與其限制.
 * Daily history, unlike minute bars, has FinMind as a fallback (see {@link PriceHistorySource}),
 * with Yahoo as the priority source (spec: 來源順位).
 *
 * `interval=1m` only serves the most recent 30 days; the caller is responsible for the local
 * "is this date within the window" check before calling {@link #fetchMinuteBars} (spec: 超窗判斷在
 * 本地完成，不發請求) — this client does not special-case HTTP 422. `interval=1d` has no such
 * window (spec: 逐檔歷史為什麼必須有兩個來源 / 已測事實), so {@link #fetchDailyHistory} needs no
 * equivalent check.
 */
@Component
public class YahooFinanceClient implements PriceHistorySource {

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
        String symbol = buildSymbol(stockId, market);
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
        YahooQuote quote = quoteOf(result);
        if (timestamps == null || quote == null) {
            return Collections.emptyList();
        }

        List<NormalizedMinuteBar> bars = new ArrayList<>();
        for (int i = 0; i < timestamps.length; i++) {
            // Index-paired with timestamp[i] (spec: 正規化必須沿用該 spec 既有的索引配對機制); a null
            // OHLC entry means no trade that bar and the whole bar is dropped (never zero-filled).
            Bar bar = barAt(quote, i);
            if (bar == null) {
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

            bars.add(new NormalizedMinuteBar(stockId, tradeDate, barTime,
                    money(bar.open), money(bar.high), money(bar.low), money(bar.close), bar.volume));
        }
        return bars;
    }

    /**
     * Fetches normalized daily price rows for one stock in [startDate, endDate] — the
     * {@link PriceHistorySource} priority entry point (spec: 來源順位 — YAHOO first). Reuses the
     * exact same endpoint and index-pairing normalization as {@link #fetchMinuteBars}, just with
     * {@code interval=1d} and no 30-day window (spec: Yahoo（主來源）的已測事實). Takes the response's
     * `quote` values only, never `adjclose` (spec: 一律取 quote) — `adjclose` isn't even parsed by
     * {@link com.stock.service.external.dto.YahooQuote}, so there is nothing to filter out.
     *
     * @throws SourceBlockedException on HTTP 403/429 or a quota-indicating body (caller should
     *                                switch to the next source, never retry this one)
     * @throws SymbolNotFoundException on HTTP 404 — ambiguous (spec: Yahoo 的 404 帶有歧義), caller
     *                                 must try the next source rather than concluding delisted
     * @throws RateLimitedException  on a timeout (caller may back off and retry this source)
     * @throws ExternalApiException  on any other non-retryable failure
     */
    @Override
    public List<NormalizedPriceRow> fetchDailyHistory(String stockId, String market, LocalDate startDate,
                                                        LocalDate endDate) {
        String symbol = buildSymbol(stockId, market);
        long period1 = startDate.atStartOfDay(TAIPEI).toEpochSecond();
        long period2 = endDate.plusDays(1).atStartOfDay(TAIPEI).toEpochSecond();

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/{symbol}")
                .queryParam("interval", "1d")
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
            if (RetryAfterExtractor.isBlockedResponse(e)) {
                throw new SourceBlockedException(
                        "Yahoo Finance blocked for stock " + stockId + ": HTTP " + e.getRawStatusCode(),
                        RetryAfterExtractor.extract(e));
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new SymbolNotFoundException("Yahoo Finance has no data for symbol " + symbol);
            }
            throw new ExternalApiException(
                    "Yahoo Finance daily-history request failed for stock " + stockId + ": HTTP "
                            + e.getRawStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new RateLimitedException("Yahoo Finance daily-history request timed out for stock " + stockId, e);
        } catch (RestClientException e) {
            throw new ExternalApiException(
                    "Yahoo Finance daily-history request failed for stock " + stockId + ": " + e.getMessage(), e);
        }

        if (response == null || response.getChart() == null || response.getChart().getResult() == null
                || response.getChart().getResult().isEmpty()) {
            return Collections.emptyList();
        }
        return normalizeDaily(stockId, response.getChart().getResult().get(0));
    }

    private List<NormalizedPriceRow> normalizeDaily(String stockId, YahooChartResult result) {
        long[] timestamps = result.getTimestamp();
        YahooQuote quote = quoteOf(result);
        if (timestamps == null || quote == null) {
            return Collections.emptyList();
        }

        List<NormalizedPriceRow> rows = new ArrayList<>();
        for (int i = 0; i < timestamps.length; i++) {
            Bar bar = barAt(quote, i);
            if (bar == null) {
                // No trade that day (holiday/suspension): source omits it entirely from the
                // arrays we'd expect a row at; never zero-fill (spec: 不得補零).
                continue;
            }
            LocalDate tradeDate = Instant.ofEpochSecond(timestamps[i]).atZone(TAIPEI).toLocalDate();
            // Yahoo supplies OHLCV only; turnover/transactionCount are the accepted-tradeoff 0
            // (spec: 成交金額與成交筆數是選填的).
            rows.add(new NormalizedPriceRow(stockId, tradeDate,
                    money(bar.open), money(bar.high), money(bar.low), money(bar.close), bar.volume,
                    BigDecimal.ZERO, 0));
        }
        return rows;
    }

    private String buildSymbol(String stockId, String market) {
        return stockId + (MARKET_OTC.equals(market) ? ".TWO" : ".TW");
    }

    @Override
    public String getCode() {
        return SOURCE_YAHOO;
    }

    private YahooQuote quoteOf(YahooChartResult result) {
        YahooIndicators indicators = result.getIndicators();
        if (indicators == null || indicators.getQuote() == null || indicators.getQuote().isEmpty()) {
            return null;
        }
        return indicators.getQuote().get(0);
    }

    /**
     * Index-paired OHLCV extraction shared by both the minute and daily normalizers (spec: 正規化
     * 必須沿用該 spec 既有的索引配對機制，不得另寫一套解析). Returns null when any of OHLC is null at this
     * index — no trade that bar/day, and the caller must drop it rather than zero-fill.
     */
    private Bar barAt(YahooQuote quote, int index) {
        Double open = at(quote.getOpen(), index);
        Double high = at(quote.getHigh(), index);
        Double low = at(quote.getLow(), index);
        Double close = at(quote.getClose(), index);
        if (open == null || high == null || low == null || close == null) {
            return null;
        }
        Long rawVolume = at(quote.getVolume(), index);
        return new Bar(open, high, low, close, rawVolume == null ? 0L : rawVolume);
    }

    private <T> T at(List<T> list, int index) {
        return (list == null || index >= list.size()) ? null : list.get(index);
    }

    /** One index's worth of OHLCV pulled out of the parallel {@link YahooQuote} arrays. */
    private static final class Bar {
        final double open;
        final double high;
        final double low;
        final double close;
        final long volume;

        Bar(double open, double high, double low, double close, long volume) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }

    private BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
