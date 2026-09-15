package com.stock.service.external;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stock.service.external.dto.NormalizedMinuteBar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pure unit tests (no Spring context, no DB) for {@link FugleClient}: request shape, 09:00-13:30
 * session normalization / null-OHLC drop / timezone handling, HTTP status classification, the
 * startup key warning, and — the acceptance criterion this file exists specifically to prove —
 * that the API key value never leaks into a log message or an exception message.
 */
class FugleClientTest {

    private static final String BASE_URL = "https://api.fugle.tw/marketdata/v1.0/stock/historical/candles";

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
    }

    private String url(String stockId, LocalDate tradeDate) {
        return BASE_URL + "/" + stockId + "?from=" + tradeDate + "&to=" + tradeDate
                + "&timeframe=1&fields=open,high,low,close,volume&sort=asc";
    }

    // ---------- Request shape ----------

    @Test
    void request_usesPlainCode_correctParams_andApiKeyHeader() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "a-real-key-value");
        LocalDate tradeDate = LocalDate.of(2026, 8, 5);

        mockServer.expect(requestTo(url("2330", tradeDate)))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-API-KEY", "a-real-key-value"))
                .andRespond(withSuccess(fixture(tradeDate, List.of(bar(9, 0, 100.0, 101.0, 99.0, 100.5, 10L))),
                        MediaType.APPLICATION_JSON));

        List<NormalizedMinuteBar> bars = client.fetchMinuteBars("2330", tradeDate);
        assertEquals(1, bars.size());
        mockServer.verify();
    }

    @Test
    void otcStock_alsoUsesPlainCode_noTwoSuffix() {
        // Fugle takes the SAME plain code for both TWSE and TPEx -- unlike Yahoo's .TW/.TWO split
        // (confirmed live against 6488 on 2026-09-15; spec: 上櫃股票以純代號查詢富果).
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "key");
        LocalDate tradeDate = LocalDate.of(2026, 8, 5);

        mockServer.expect(requestTo(url("6488", tradeDate)))
                .andRespond(withSuccess(fixture(tradeDate, List.of(bar(9, 0, 900.0, 905.0, 899.0, 903.0, 20L))),
                        MediaType.APPLICATION_JSON));

        List<NormalizedMinuteBar> bars = client.fetchMinuteBars("6488", tradeDate);
        assertEquals(1, bars.size());
        mockServer.verify();
    }

    // ---------- Normalization ----------

    @Test
    void normalizesFullSession_266Bars_firstAt0900_lastAt1330_gapAt1325to1329() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "key");
        LocalDate tradeDate = LocalDate.of(2026, 8, 5);

        mockServer.expect(requestTo(url("2330", tradeDate)))
                .andRespond(withSuccess(fixture(tradeDate, full266Bars()), MediaType.APPLICATION_JSON));

        List<NormalizedMinuteBar> bars = client.fetchMinuteBars("2330", tradeDate);
        assertEquals(266, bars.size());
        assertEquals(java.time.LocalTime.of(9, 0), bars.get(0).getBarTime());
        assertEquals(java.time.LocalTime.of(13, 30), bars.get(bars.size() - 1).getBarTime());
        assertTrue(bars.stream().noneMatch(b -> b.getBarTime().isAfter(java.time.LocalTime.of(13, 24))
                && b.getBarTime().isBefore(java.time.LocalTime.of(13, 30))),
                "no bar may fall in the 13:25-13:29 closing call auction gap");
    }

    @Test
    void offsetPlus0800_convertsToTaipeiLocalTime_notUtcOrServerDefault() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "key");
        LocalDate tradeDate = LocalDate.of(2026, 8, 5);
        String body = "{\"data\":[{\"date\":\"2026-08-05T09:00:00.000+08:00\","
                + "\"open\":100,\"high\":101,\"low\":99,\"close\":100.5,\"volume\":10}]}";

        mockServer.expect(requestTo(url("2330", tradeDate))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<NormalizedMinuteBar> bars = client.fetchMinuteBars("2330", tradeDate);
        assertEquals(1, bars.size());
        assertEquals(java.time.LocalTime.of(9, 0), bars.get(0).getBarTime(),
                "must be 09:00 Asia/Taipei, not 01:00 (UTC) or some other offset");
    }

    @Test
    void volume_writtenDirectly_asPerBarValue_notCumulative() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "key");
        LocalDate tradeDate = LocalDate.of(2026, 8, 5);
        List<Bar> raw = List.of(
                bar(9, 0, 100.0, 101.0, 99.0, 100.5, 140L),
                bar(9, 1, 100.5, 101.5, 100.0, 101.0, 78L)); // smaller than the previous bar's volume

        mockServer.expect(requestTo(url("2330", tradeDate)))
                .andRespond(withSuccess(fixture(tradeDate, raw), MediaType.APPLICATION_JSON));

        List<NormalizedMinuteBar> bars = client.fetchMinuteBars("2330", tradeDate);
        assertEquals(140L, bars.get(0).getVolume());
        assertEquals(78L, bars.get(1).getVolume(), "must be this bar's own volume, never summed/cumulative");
    }

    @Test
    void nullOhlcMinute_isDropped_neverZeroFilled() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "key");
        LocalDate tradeDate = LocalDate.of(2026, 8, 5);
        List<Bar> raw = List.of(
                bar(9, 0, 100.0, 101.0, 99.0, 100.5, 10L),
                bar(9, 1, null, null, null, null, 0L),
                bar(9, 2, 100.3, 101.3, 99.3, 100.8, 12L));

        mockServer.expect(requestTo(url("2330", tradeDate)))
                .andRespond(withSuccess(fixture(tradeDate, raw), MediaType.APPLICATION_JSON));

        List<NormalizedMinuteBar> bars = client.fetchMinuteBars("2330", tradeDate);
        assertEquals(2, bars.size());
        assertEquals(java.time.LocalTime.of(9, 0), bars.get(0).getBarTime());
        assertEquals(java.time.LocalTime.of(9, 2), bars.get(1).getBarTime());
    }

    // ---------- HTTP status classification ----------

    @Test
    void http404_returnsEmptyList_notAnException() {
        // "Resource Not Found" -- no minute data for this date (spec: HTTP 404 -> NO_DATA, not an
        // error the caller needs to catch).
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "key");
        LocalDate tradeDate = LocalDate.of(2023, 5, 22);

        mockServer.expect(requestTo(url("2330", tradeDate))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Resource Not Found\"}"));

        List<NormalizedMinuteBar> bars = client.fetchMinuteBars("2330", tradeDate);
        assertTrue(bars.isEmpty());
    }

    @Test
    void http429_throwsRateLimited() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "key");
        LocalDate tradeDate = LocalDate.of(2026, 8, 5);

        mockServer.expect(requestTo(url("2330", tradeDate))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThrows(RateLimitedException.class, () -> client.fetchMinuteBars("2330", tradeDate));
    }

    @Test
    void timeout_throwsRateLimited() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "key");
        LocalDate tradeDate = LocalDate.of(2026, 8, 5);

        mockServer.expect(requestTo(url("2330", tradeDate))).andRespond(request -> {
            throw new java.io.IOException("simulated timeout");
        });

        RateLimitedException ex = assertThrows(RateLimitedException.class,
                () -> client.fetchMinuteBars("2330", tradeDate));
        assertTrue(ex.getMessage().toLowerCase().contains("timed out") || ex.getMessage().toLowerCase().contains("timeout"),
                "last_error should record the timeout reason");
    }

    @Test
    void http401_throwsExternalApiException() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "bad-key");
        LocalDate tradeDate = LocalDate.of(2026, 8, 5);

        mockServer.expect(requestTo(url("2330", tradeDate))).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Unauthorized\"}"));

        assertThrows(ExternalApiException.class, () -> client.fetchMinuteBars("2330", tradeDate));
    }

    @Test
    void http403_throwsExternalApiException() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "key");
        LocalDate tradeDate = LocalDate.of(2026, 8, 5);

        mockServer.expect(requestTo(url("2330", tradeDate))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThrows(ExternalApiException.class, () -> client.fetchMinuteBars("2330", tradeDate));
    }

    @Test
    void http500_throwsExternalApiException() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "key");
        LocalDate tradeDate = LocalDate.of(2026, 8, 5);

        mockServer.expect(requestTo(url("2330", tradeDate))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(ExternalApiException.class, () -> client.fetchMinuteBars("2330", tradeDate));
    }

    // ---------- API key warning (spec: 未設定 API Key 時應在啟動階段留下明確的警告紀錄) ----------

    private ListAppender<ILoggingEvent> logAppender;
    private Logger fugleLogger;

    @BeforeEach
    void attachLogAppender() {
        fugleLogger = (Logger) LoggerFactory.getLogger(FugleClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        fugleLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (fugleLogger != null && logAppender != null) {
            fugleLogger.detachAppender(logAppender);
        }
    }

    @Test
    void emptyApiKey_logsWarningAtStartup_andIsApiKeyConfiguredIsFalse() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "");
        client.warnIfApiKeyMissing();

        boolean warned = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().toLowerCase().contains("key"));
        assertTrue(warned, "expected a WARN log mentioning the missing API key");
        assertFalse(client.isApiKeyConfigured());
    }

    @Test
    void blankApiKey_logsWarningAtStartup() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "   ");
        client.warnIfApiKeyMissing();

        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN));
        assertFalse(client.isApiKeyConfigured());
    }

    @Test
    void presentApiKey_logsNoWarning_andIsApiKeyConfiguredIsTrue() {
        FugleClient client = new FugleClient(restTemplate, BASE_URL, "a-real-key-from-the-environment");
        client.warnIfApiKeyMissing();

        assertFalse(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN));
        assertTrue(client.isApiKeyConfigured());
    }

    // ---------- Key never leaks (spec: 金鑰值不出現在應用程式日誌...或任何回應中) ----------

    /**
     * Drives both a success and a 401 request with a recognizable fake key, then scans every log
     * message and every exception message this client produced for that key's literal value. Uses
     * a fake key, never the real one (the real key is verified separately, live, without ever
     * being logged or written to any file in this repo).
     */
    @Test
    void apiKeyValue_neverAppearsInLogsOrExceptionMessages_onSuccessOrOn401() {
        String recognizableFakeKey = "FGL-TEST-KEY-9f8e7d6c5b4a3f2e1d0c";
        FugleClient client = new FugleClient(restTemplate, BASE_URL, recognizableFakeKey);
        LocalDate successDate = LocalDate.of(2026, 8, 5);
        LocalDate failureDate = LocalDate.of(2026, 8, 6);

        mockServer.expect(requestTo(url("2330", successDate)))
                .andExpect(header("X-API-KEY", recognizableFakeKey))
                .andRespond(withSuccess(fixture(successDate, List.of(bar(9, 0, 100.0, 101.0, 99.0, 100.5, 10L))),
                        MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(url("2330", failureDate)))
                .andExpect(header("X-API-KEY", recognizableFakeKey))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Unauthorized\"}"));

        client.fetchMinuteBars("2330", successDate);
        ExternalApiException ex = assertThrows(ExternalApiException.class,
                () -> client.fetchMinuteBars("2330", failureDate));

        assertFalse(ex.getMessage().contains(recognizableFakeKey), "exception message must not contain the key");
        boolean leakedInLogs = logAppender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains(recognizableFakeKey));
        assertFalse(leakedInLogs, "the API key value must never appear in a log message");
    }

    // ---------- fixtures ----------

    private String fixture(LocalDate tradeDate, List<Bar> bars) {
        StringBuilder sb = new StringBuilder("{\"symbol\":\"2330\",\"type\":\"EQUITY\",\"exchange\":\"TWSE\","
                + "\"market\":\"TSE\",\"timeframe\":\"1\",\"data\":[");
        for (int i = 0; i < bars.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            Bar b = bars.get(i);
            String time = String.format("%02d:%02d:00.000+08:00", b.hour, b.minute);
            sb.append("{\"date\":\"").append(tradeDate).append("T").append(time).append("\",");
            sb.append("\"open\":").append(numOrNull(b.open)).append(",");
            sb.append("\"high\":").append(numOrNull(b.high)).append(",");
            sb.append("\"low\":").append(numOrNull(b.low)).append(",");
            sb.append("\"close\":").append(numOrNull(b.close)).append(",");
            sb.append("\"volume\":").append(b.volume).append("}");
        }
        sb.append("],\"sort\":\"asc\"}");
        return sb.toString();
    }

    private String numOrNull(Double value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private List<Bar> full266Bars() {
        List<Bar> list = new java.util.ArrayList<>();
        java.time.LocalTime t = java.time.LocalTime.of(9, 0);
        int i = 0;
        while (!t.isAfter(java.time.LocalTime.of(13, 30))) {
            // Skip the 13:25-13:29 closing call auction gap, same as the real API (confirmed live).
            if (!(t.isAfter(java.time.LocalTime.of(13, 24)) && t.isBefore(java.time.LocalTime.of(13, 30)))) {
                double v = 100 + i * 0.01;
                list.add(bar(t.getHour(), t.getMinute(), v, v + 0.05, v - 0.05, v, 100L + i));
            }
            t = t.plusMinutes(1);
            i++;
        }
        return list;
    }

    private Bar bar(int hour, int minute, Double open, Double high, Double low, Double close, long volume) {
        return new Bar(hour, minute, open, high, low, close, volume);
    }

    private static final class Bar {
        final int hour;
        final int minute;
        final Double open;
        final Double high;
        final Double low;
        final Double close;
        final long volume;

        Bar(int hour, int minute, Double open, Double high, Double low, Double close, long volume) {
            this.hour = hour;
            this.minute = minute;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }
}
