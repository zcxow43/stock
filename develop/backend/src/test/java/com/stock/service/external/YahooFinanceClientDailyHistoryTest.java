package com.stock.service.external;

import com.stock.service.external.dto.NormalizedPriceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pure unit tests (no Spring context, no DB) for {@link YahooFinanceClient#fetchDailyHistory},
 * the {@link PriceHistorySource} entry point used for per-stock daily history (spec: 逐檔歷史為什麼
 * 必須有兩個來源 / Yahoo（主來源）的已測事實). Uses a standalone RestTemplate bound to
 * MockRestServiceServer -- no live network calls.
 */
class YahooFinanceClientDailyHistoryTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart";

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private YahooFinanceClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        client = new YahooFinanceClient(restTemplate, BASE_URL);
    }

    @Test
    void takesQuoteValues_neverAdjclose_evenWhenBothArePresent() {
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        long epoch = start.atTime(9, 0).atZone(TAIPEI).toEpochSecond();

        // A stock with an ex-dividend adjustment in range: adjclose diverges sharply from the raw
        // quote close. The written value must equal quote.close, never adjclose (spec: 一律取 quote).
        String body = "{\"chart\":{\"result\":[{\"timestamp\":[" + epoch + "],"
                + "\"indicators\":{"
                + "\"quote\":[{\"open\":[100.0],\"high\":[101.0],\"low\":[99.0],\"close\":[100.5],"
                + "\"volume\":[1000]}],"
                + "\"adjclose\":[{\"adjclose\":[45.2]}]"
                + "}}]}}";
        mockServer.expect(requestTo(BASE_URL + "/2330.TW?interval=1d&period1=" + epochStartOfDay(start)
                        + "&period2=" + epochStartOfDay(end.plusDays(1))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<NormalizedPriceRow> rows = client.fetchDailyHistory("2330", "TSE", start, end);

        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("100.50").compareTo(rows.get(0).getClose()));
        mockServer.verify();
    }

    @Test
    void otcStock_usesTwoSuffix_tseStock_usesTwSuffix() {
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        String emptyBody = "{\"chart\":{\"result\":[{\"timestamp\":[],\"indicators\":{\"quote\":[{}]}}]}}";

        mockServer.expect(requestTo(BASE_URL + "/6488.TWO?interval=1d&period1=" + epochStartOfDay(start)
                        + "&period2=" + epochStartOfDay(end.plusDays(1))))
                .andRespond(withSuccess(emptyBody, MediaType.APPLICATION_JSON));

        client.fetchDailyHistory("6488", "OTC", start, end);

        mockServer.verify();
    }

    @Test
    void requestRangeOlderThan30Days_stillFetchedNormally_noWindowCheck() {
        // interval=1d has no 30-day window (spec: 逐檔歷史請求區間早於今日30天仍正常取得資料);
        // a range from a decade ago must be requested exactly like any other, with no special
        // "out of window" short-circuit.
        LocalDate start = LocalDate.of(2015, 1, 1);
        LocalDate end = LocalDate.of(2015, 1, 10);
        long epoch = start.atTime(9, 0).atZone(TAIPEI).toEpochSecond();
        String body = "{\"chart\":{\"result\":[{\"timestamp\":[" + epoch + "],"
                + "\"indicators\":{\"quote\":[{\"open\":[10.0],\"high\":[10.5],\"low\":[9.5],"
                + "\"close\":[10.2],\"volume\":[500]}]}}]}}";

        mockServer.expect(requestTo(BASE_URL + "/2330.TW?interval=1d&period1=" + epochStartOfDay(start)
                        + "&period2=" + epochStartOfDay(end.plusDays(1))))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<NormalizedPriceRow> rows = client.fetchDailyHistory("2330", "TSE", start, end);

        assertEquals(1, rows.size());
        mockServer.verify();
    }

    @Test
    void yahooRow_hasZeroTurnoverAndTransactionCount_butValidOhlcv_noValidationError() {
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        long epoch = start.atTime(9, 0).atZone(TAIPEI).toEpochSecond();
        String body = "{\"chart\":{\"result\":[{\"timestamp\":[" + epoch + "],"
                + "\"indicators\":{\"quote\":[{\"open\":[100.0],\"high\":[101.0],\"low\":[99.0],"
                + "\"close\":[100.5],\"volume\":[123456]}]}}]}}";
        mockServer.expect(requestTo(BASE_URL + "/2330.TW?interval=1d&period1=" + epochStartOfDay(start)
                        + "&period2=" + epochStartOfDay(end.plusDays(1))))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<NormalizedPriceRow> rows = client.fetchDailyHistory("2330", "TSE", start, end);

        assertEquals(1, rows.size());
        NormalizedPriceRow row = rows.get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getTurnover()));
        assertEquals(0, row.getTransactionCount());
        assertEquals(0, new BigDecimal("100.00").compareTo(row.getOpen()));
        assertEquals(0, new BigDecimal("101.00").compareTo(row.getHigh()));
        assertEquals(0, new BigDecimal("99.00").compareTo(row.getLow()));
        assertEquals(0, new BigDecimal("100.50").compareTo(row.getClose()));
        assertEquals(123456L, row.getVolume());
    }

    @Test
    void http403_throwsSourceBlocked_withRetryAfterFromHeader() {
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        HttpHeaders retryAfterHeader = new HttpHeaders();
        retryAfterHeader.add("Retry-After", "120");
        mockServer.expect(requestTo(BASE_URL + "/2330.TW?interval=1d&period1=" + epochStartOfDay(start)
                        + "&period2=" + epochStartOfDay(end.plusDays(1))))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).headers(retryAfterHeader));

        SourceBlockedException ex = assertThrows(SourceBlockedException.class,
                () -> client.fetchDailyHistory("2330", "TSE", start, end));
        assertEquals(120L, ex.getRetryAfterSeconds());
    }

    @Test
    void http404_throwsSymbolNotFound_notTreatedAsExternalApiError() {
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        mockServer.expect(requestTo(BASE_URL + "/9999.TW?interval=1d&period1=" + epochStartOfDay(start)
                        + "&period2=" + epochStartOfDay(end.plusDays(1))))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\","
                                + "\"description\":\"No data found, symbol may be delisted\"}}}"));

        assertThrows(SymbolNotFoundException.class, () -> client.fetchDailyHistory("9999", "TSE", start, end));
    }

    @Test
    void getCode_returnsYahoo() {
        assertEquals(PriceHistorySource.SOURCE_YAHOO, client.getCode());
    }

    private long epochStartOfDay(LocalDate date) {
        return date.atStartOfDay(TAIPEI).toEpochSecond();
    }
}
