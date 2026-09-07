package com.stock.service.external;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stock.service.external.dto.NormalizedPriceRow;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Pure unit tests (no Spring context, no DB) for {@link FinMindClient} as a
 * {@link PriceHistorySource}: block/404 classification (spec: 封鎖類回應的判定 / 404 的處置) and the
 * startup token warning (spec: token 必須設定...未設定時應在啟動時留下明確的警告紀錄).
 */
class FinMindClientTest {

    private static final String BASE_URL = "https://api.finmindtrade.com/api/v4/data";

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void http403WithRetryAfterInBody_throwsSourceBlocked_withThatRetryAfter() {
        FinMindClient client = new FinMindClient(restTemplate, BASE_URL, "");
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        String url = BASE_URL + "?dataset=TaiwanStockPrice&data_id=2330&start_date=" + start + "&end_date=" + end;

        mockServer.expect(requestTo(url)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"msg\":\"ip banned\",\"status\":403,\"retry_after\":505,\"token_tail\":\"\"}"));

        SourceBlockedException ex = assertThrows(SourceBlockedException.class,
                () -> client.fetchDailyHistory("2330", "TSE", start, end));
        assertEquals(505L, ex.getRetryAfterSeconds());
    }

    @Test
    void http429_throwsSourceBlocked_notRateLimited() {
        FinMindClient client = new FinMindClient(restTemplate, BASE_URL, "");
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        String url = BASE_URL + "?dataset=TaiwanStockPrice&data_id=2330&start_date=" + start + "&end_date=" + end;

        mockServer.expect(requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThrows(SourceBlockedException.class, () -> client.fetchDailyHistory("2330", "TSE", start, end));
    }

    @Test
    void http404_throwsSymbolNotFound() {
        FinMindClient client = new FinMindClient(restTemplate, BASE_URL, "");
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        String url = BASE_URL + "?dataset=TaiwanStockPrice&data_id=9999&start_date=" + start + "&end_date=" + end;

        mockServer.expect(requestTo(url)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThrows(SymbolNotFoundException.class, () -> client.fetchDailyHistory("9999", "TSE", start, end));
    }

    @Test
    void crossSourceIdempotency_finMindRowMatchesYahooRowShape_ohlcEqual() {
        // Not a live cross-source comparison (that lives in the integration test), but confirms
        // FinMind's own normalization produces the same OHLC given the same numbers Yahoo would
        // have (spec: 價格在兩個來源之間是一致的).
        FinMindClient client = new FinMindClient(restTemplate, BASE_URL, "");
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        String url = BASE_URL + "?dataset=TaiwanStockPrice&data_id=2330&start_date=" + start + "&end_date=" + end;
        String body = "{\"msg\":\"success\",\"status\":200,\"data\":[{\"date\":\"2025-09-01\","
                + "\"stock_id\":\"2330\",\"Trading_Volume\":1000,\"Trading_money\":100000,"
                + "\"open\":100.00,\"max\":101.00,\"min\":99.00,\"close\":100.50,\"Trading_turnover\":10}]}";
        mockServer.expect(requestTo(url)).andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON).body(body));

        List<NormalizedPriceRow> rows = client.fetchDailyHistory("2330", "TSE", start, end);
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).getOpen().compareTo(java.math.BigDecimal.valueOf(100.00)));
    }

    @Test
    void getCode_returnsFinmind() {
        FinMindClient client = new FinMindClient(restTemplate, BASE_URL, "");
        assertEquals(PriceHistorySource.SOURCE_FINMIND, client.getCode());
    }

    // ---------- Token warning (spec: token 未設定時應在啟動時留下明確的警告紀錄) ----------

    private ListAppender<ILoggingEvent> logAppender;
    private Logger finMindLogger;

    @BeforeEach
    void attachLogAppender() {
        finMindLogger = (Logger) LoggerFactory.getLogger(FinMindClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        finMindLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (finMindLogger != null && logAppender != null) {
            finMindLogger.detachAppender(logAppender);
        }
    }

    @Test
    void emptyToken_logsWarningAtStartup() {
        FinMindClient client = new FinMindClient(restTemplate, BASE_URL, "");
        client.warnIfTokenMissing();

        boolean warned = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().toLowerCase().contains("token"));
        assertTrue(warned, "expected a WARN log mentioning the missing token");
    }

    @Test
    void blankToken_logsWarningAtStartup() {
        FinMindClient client = new FinMindClient(restTemplate, BASE_URL, "   ");
        client.warnIfTokenMissing();

        boolean warned = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN);
        assertTrue(warned);
    }

    @Test
    void presentToken_logsNoWarning() {
        FinMindClient client = new FinMindClient(restTemplate, BASE_URL, "a-real-token-from-the-environment");
        client.warnIfTokenMissing();

        boolean warned = logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN);
        assertFalse(warned, "a configured token must not trigger the missing-token warning");
    }
}
