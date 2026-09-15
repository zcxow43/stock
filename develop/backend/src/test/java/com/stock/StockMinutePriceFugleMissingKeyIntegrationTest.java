package com.stock;

import com.stock.dto.MinuteBarResponse;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockMinuteFetchStatusMapper;
import com.stock.mapper.StockMinutePriceMapper;
import com.stock.service.MinuteBarQueryService;
import com.stock.service.MinutePriceIngestionService;
import com.stock.service.external.FugleClient;
import com.stock.service.external.SourceRateLimiter;
import com.stock.service.external.YahooFinanceClient;
import com.stock.config.BackfillProperties;
import com.stock.config.MinutePriceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Dedicated Spring context with {@code app.minute-price.fugle.api-key} overridden to blank —
 * proves the "no key configured" behavior for dates older than 30 days (spec: 富果 API Key —
 * 未設定時...不發出請求、不寫入狀態表，直接回 FETCH_FAILED). A SEPARATE context from the shared default
 * (which has a fake key configured) is required because the key is bound once at bean-construction
 * time via {@code @Value}; there is no supported way to flip it at runtime within one context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.minute-price.fugle.api-key="})
class StockMinutePriceFugleMissingKeyIntegrationTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String EXPECTED_MESSAGE = "未設定富果 API Key，無法取得 30 天以前的分 K";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("externalApiRestTemplate")
    private RestTemplate externalApiRestTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private FugleClient fugleClientWithMissingKey;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockDailyPriceMapper dailyPriceMapper;

    @Autowired
    private StockMinutePriceMapper minutePriceMapper;

    @Autowired
    private StockMinuteFetchStatusMapper fetchStatusMapper;

    @Autowired
    private MinutePriceIngestionService ingestionService;

    @Autowired
    private YahooFinanceClient yahooFinanceClient;

    @Autowired
    private SourceRateLimiter sourceRateLimiter;

    @Autowired
    private BackfillProperties backfillProperties;

    @Autowired
    private MinutePriceProperties minutePriceProperties;

    @Value("${app.external.yahoo-finance-base-url}")
    private String yahooBaseUrl;

    @Value("${app.external.fugle-base-url}")
    private String fugleBaseUrl;

    private MockRestServiceServer mockServer;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        mockServer = MockRestServiceServer.bindTo(externalApiRestTemplate).build();
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    private void cleanupTestData() {
        jdbc.update("DELETE FROM stock_minute_price WHERE stock_id LIKE 'ZK%'");
        jdbc.update("DELETE FROM stock_minute_fetch_status WHERE stock_id LIKE 'ZK%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'ZK%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'ZK%'");
    }

    @Test
    void missingKey_isDetected() {
        assertFalse(fugleClientWithMissingKey.isApiKeyConfigured());
    }

    @Test
    void missingKey_dateOlderThan30Days_returnsFetchFailed_exactMessage_noRequest_noStatusRow() {
        String stockId = "ZK01";
        LocalDate tradeDate = fugleWindowWeekday(40);
        seedStock(stockId, "缺金鑰測試");
        seedDailyPrice(stockId, tradeDate);

        // No mockServer.expect(...) at all: any request (to Yahoo or Fugle) fails the test.
        ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, tradeDate);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        MinuteBarResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("FETCH_FAILED", body.getDataStatus());
        assertEquals(EXPECTED_MESSAGE, body.getMessage());
        assertNotNull(body.getDailySummary(), "trading day -> dailySummary must still be present");
        assertTrue(body.getBars().isEmpty());

        Integer statusRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                Integer.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals(0, statusRows, "a config problem must never write stock_minute_fetch_status");
    }

    @Test
    void missingKey_repeatedCalls_stayAtZeroStatusRows() {
        String stockId = "ZK02";
        LocalDate tradeDate = fugleWindowWeekday(41);
        seedStock(stockId, "缺金鑰測試二");
        seedDailyPrice(stockId, tradeDate);

        for (int i = 0; i < 3; i++) {
            ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, tradeDate);
            assertEquals("FETCH_FAILED", response.getBody().getDataStatus());
            assertEquals(EXPECTED_MESSAGE, response.getBody().getMessage());
        }
        Integer statusRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                Integer.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals(0, statusRows);
    }

    @Test
    void missingKey_withinYahooWindow_stillFetchesNormallyViaYahoo() {
        String stockId = "ZK03";
        LocalDate tradeDate = recentWeekday(3);
        seedStock(stockId, "缺金鑰但Yahoo正常");
        seedDailyPrice(stockId, tradeDate);

        mockServer.expect(requestTo(yahooUrl(stockId, tradeDate)))
                .andRespond(withSuccess(yahooFixture(tradeDate), MediaType.APPLICATION_JSON));

        ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, tradeDate);
        assertEquals("AVAILABLE", response.getBody().getDataStatus());
        assertEquals("YAHOO", response.getBody().getSource());
        mockServer.verify();
    }

    /**
     * Proves "設定 API Key 後查詢同一日期，可正常由富果抓取，不受先前的失敗影響": since the missing-key path
     * above never wrote a status row, the SAME (stock, tradeDate) is indistinguishable from a
     * never-attempted date once a key IS configured. Because the key is bound at construction time
     * (no supported hot-swap within one context), this builds a second, independently-configured
     * {@link MinuteBarQueryService} wired to a keyed {@link FugleClient} but sharing every other
     * real bean (mappers, DB, rate limiter) from this context — exercising the actual production
     * code path, not a re-implementation of it.
     */
    @Test
    void afterKeyIsConfigured_sameDateFetchesNormally_unaffectedByThePriorMissingKeyFailure() {
        String stockId = "ZK04";
        LocalDate tradeDate = fugleWindowWeekday(42);
        seedStock(stockId, "補金鑰後可抓取");
        seedDailyPrice(stockId, tradeDate);

        // First, confirm the missing-key path leaves no trace (same as the dedicated test above).
        ResponseEntity<MinuteBarResponse> beforeKey = getMinuteBars(stockId, tradeDate);
        assertEquals("FETCH_FAILED", beforeKey.getBody().getDataStatus());
        Integer statusRowsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                Integer.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals(0, statusRowsBefore);

        // Now build an equivalent service with a keyed FugleClient (everything else identical/real).
        FugleClient keyedFugleClient = new FugleClient(externalApiRestTemplate, fugleBaseUrl, "now-configured-key");
        MinuteBarQueryService keyedService = new MinuteBarQueryService(stockMapper, dailyPriceMapper,
                minutePriceMapper, fetchStatusMapper, ingestionService, yahooFinanceClient, keyedFugleClient,
                sourceRateLimiter, backfillProperties, minutePriceProperties);

        mockServer.expect(requestTo(fugleUrl(stockId, tradeDate)))
                .andRespond(withSuccess(fugleFixture(tradeDate, full266Bars()), MediaType.APPLICATION_JSON));

        MinuteBarResponse afterKey = keyedService.getMinuteBars(stockId, tradeDate.toString(), null, false);
        assertEquals("AVAILABLE", afterKey.getDataStatus());
        assertEquals("FUGLE", afterKey.getSource());
        assertEquals(266, afterKey.getBarCount());
        mockServer.verify();
    }

    // ---------- helpers ----------

    private void seedStock(String stockId, String name) {
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES (?, ?, 'TSE', 1)", stockId, name);
    }

    private void seedDailyPrice(String stockId, LocalDate tradeDate) {
        jdbc.update("INSERT INTO stock_daily_price "
                        + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, turnover, transaction_count, source) "
                        + "VALUES (?, ?, 100.00, 105.00, 99.00, 104.00, 500000, 0, 0, 'TEST')",
                stockId, java.sql.Date.valueOf(tradeDate));
    }

    private LocalDate recentWeekday(int minDaysAgo) {
        LocalDate d = LocalDate.now(TAIPEI).minusDays(minDaysAgo);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private LocalDate fugleWindowWeekday(int minDaysAgo) {
        LocalDate d = LocalDate.now(TAIPEI).minusDays(Math.max(minDaysAgo, 31));
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private ResponseEntity<MinuteBarResponse> getMinuteBars(String stockId, LocalDate tradeDate) {
        String url = "/api/stocks/" + stockId + "/minute-bars?tradeDate=" + tradeDate;
        return rest.getForEntity(url, MinuteBarResponse.class);
    }

    private String yahooUrl(String stockId, LocalDate tradeDate) {
        long period1 = tradeDate.atStartOfDay(TAIPEI).toEpochSecond();
        long period2 = tradeDate.plusDays(1).atStartOfDay(TAIPEI).toEpochSecond();
        return yahooBaseUrl + "/" + stockId + ".TW?interval=1m&period1=" + period1 + "&period2=" + period2;
    }

    private String fugleUrl(String stockId, LocalDate tradeDate) {
        return fugleBaseUrl + "/" + stockId + "?from=" + tradeDate + "&to=" + tradeDate
                + "&timeframe=1&fields=open,high,low,close,volume&sort=asc";
    }

    private String yahooFixture(LocalDate tradeDate) {
        long epoch = tradeDate.atTime(9, 0).atZone(TAIPEI).toEpochSecond();
        return "{\"chart\":{\"result\":[{\"timestamp\":[" + epoch + "],\"indicators\":{\"quote\":[{"
                + "\"open\":[100.0],\"high\":[100.5],\"low\":[99.8],\"close\":[100.2],\"volume\":[100]}]}}],"
                + "\"error\":null}}";
    }

    private List<FBar> full266Bars() {
        List<FBar> list = new ArrayList<>();
        LocalTime t = LocalTime.of(9, 0);
        int i = 0;
        while (!t.isAfter(LocalTime.of(13, 30))) {
            if (!(t.isAfter(LocalTime.of(13, 24)) && t.isBefore(LocalTime.of(13, 30)))) {
                double v = 100 + i * 0.01;
                list.add(new FBar(t.getHour(), t.getMinute(), v, v + 0.05, v - 0.05, v, 100L + i));
            }
            t = t.plusMinutes(1);
            i++;
        }
        return list;
    }

    private String fugleFixture(LocalDate tradeDate, List<FBar> bars) {
        StringBuilder sb = new StringBuilder("{\"symbol\":\"2330\",\"exchange\":\"TWSE\",\"data\":[");
        for (int i = 0; i < bars.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            FBar b = bars.get(i);
            String time = String.format("%02d:%02d:00.000+08:00", b.hour, b.minute);
            sb.append("{\"date\":\"").append(tradeDate).append("T").append(time).append("\",");
            sb.append("\"open\":").append(b.open).append(",\"high\":").append(b.high)
                    .append(",\"low\":").append(b.low).append(",\"close\":").append(b.close)
                    .append(",\"volume\":").append(b.volume).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static final class FBar {
        final int hour;
        final int minute;
        final double open;
        final double high;
        final double low;
        final double close;
        final long volume;

        FBar(int hour, int minute, double open, double high, double low, double close, long volume) {
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
