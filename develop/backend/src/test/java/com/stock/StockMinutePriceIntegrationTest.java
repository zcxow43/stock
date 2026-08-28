package com.stock;

import com.stock.dto.ErrorResponse;
import com.stock.dto.MinuteBarDto;
import com.stock.dto.MinuteBarResponse;
import com.stock.service.MinutePriceIngestionService;
import com.stock.service.external.dto.NormalizedMinuteBar;
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
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockMinutePriceIntegrationTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("externalApiRestTemplate")
    private RestTemplate externalApiRestTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MinutePriceIngestionService ingestionService;

    @Value("${app.external.yahoo-finance-base-url}")
    private String yahooBaseUrl;

    private MockRestServiceServer mockServer;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        mockServer = MockRestServiceServer.bindTo(externalApiRestTemplate).ignoreExpectOrder(false).build();
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    private void cleanupTestData() {
        jdbc.update("DELETE FROM stock_minute_price WHERE stock_id LIKE 'ZM%'");
        jdbc.update("DELETE FROM stock_minute_fetch_status WHERE stock_id LIKE 'ZM%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'ZM%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'ZM%'");
    }

    // ---------- 1. First fetch, full session, 271 bars, session bounds ----------

    @Test
    void firstFetch_triggersExternalRequest_available271Bars_withinSessionBounds() {
        String stockId = "ZM01";
        LocalDate tradeDate = recentWeekday(3);
        seedStock(stockId, "測試分K一");
        seedDailyPrice(stockId, tradeDate, "100.00", "105.00", "99.00", "104.00", 500000);

        List<Bar> bars = full271Bars();
        bars.add(new Bar(8, 59, 200.0, 200.0, 200.0, 200.0, 10L));  // pre-market, must be dropped
        bars.add(new Bar(13, 31, 201.0, 201.0, 201.0, 201.0, 10L)); // after-hours, must be dropped

        mockServer.expect(requestTo(yahooUrl(stockId, tradeDate))).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(buildYahooFixture(tradeDate, bars), MediaType.APPLICATION_JSON));

        ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, tradeDate, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        MinuteBarResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("AVAILABLE", body.getDataStatus());
        assertEquals("YAHOO", body.getSource());
        assertNotNull(body.getFetchedAt());
        assertEquals(271, body.getBarCount());
        assertEquals(271, body.getBars().size());
        assertEquals("09:00", body.getBars().get(0).getBarTime());
        assertEquals("13:30", body.getBars().get(body.getBars().size() - 1).getBarTime());
        assertNotNull(body.getDailySummary());
        assertEquals(0, new BigDecimal("104.00").compareTo(body.getDailySummary().getClose()));

        Integer dbCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_minute_price WHERE stock_id = ? AND trade_date = ?",
                Integer.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals(271, dbCount);

        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                String.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals("AVAILABLE", dbStatus);

        mockServer.verify();
    }

    // ---------- 2. Second call: cache hit, zero external requests ----------

    @Test
    void secondCall_forSameStockAndDate_hitsNoExternalRequest_sameResponse() {
        String stockId = "ZM02";
        LocalDate tradeDate = recentWeekday(4);
        seedStock(stockId, "測試分K二");
        seedDailyPrice(stockId, tradeDate, "50.00", "52.00", "49.50", "51.00", 100000);

        List<Bar> bars = List.of(
                new Bar(9, 0, 50.0, 50.5, 49.8, 50.2, 100L),
                new Bar(9, 1, 50.2, 50.6, 50.0, 50.4, 120L));

        mockServer.expect(requestTo(yahooUrl(stockId, tradeDate)))
                .andRespond(withSuccess(buildYahooFixture(tradeDate, bars), MediaType.APPLICATION_JSON));

        ResponseEntity<MinuteBarResponse> first = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("AVAILABLE", first.getBody().getDataStatus());
        mockServer.verify();

        // No further expectations registered: a second external request here would throw AssertionError.
        ResponseEntity<MinuteBarResponse> second = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertEquals("AVAILABLE", second.getBody().getDataStatus());
        assertEquals(first.getBody().getBarCount(), second.getBody().getBarCount());
        assertEquals(2, second.getBody().getBars().size());

        ResponseEntity<MinuteBarResponse> third = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("AVAILABLE", third.getBody().getDataStatus());
    }

    // ---------- 3. Out of window: no request, repeated calls stay at zero ----------

    @Test
    void outOfWindowDate_returnsOutOfWindow_withoutAnyExternalRequest_repeatedly() {
        String stockId = "ZM03";
        LocalDate tradeDate = weekdayAtLeast(35);
        seedStock(stockId, "測試分K三");
        seedDailyPrice(stockId, tradeDate, "10.00", "10.50", "9.80", "10.20", 5000);

        // No mockServer.expect(...) at all: any external call would fail the test.
        ResponseEntity<MinuteBarResponse> first = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals("OUT_OF_WINDOW", first.getBody().getDataStatus());
        assertTrue(first.getBody().getBars().isEmpty());
        assertNotNull(first.getBody().getDailySummary());

        ResponseEntity<MinuteBarResponse> second = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("OUT_OF_WINDOW", second.getBody().getDataStatus());

        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                String.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals("OUT_OF_WINDOW", dbStatus);
    }

    // ---------- 4. NO_DATA: source has no minute data for a trading day, repeated calls stay at zero ----------

    @Test
    void noMinuteData_returnsNoData_withDailySummary_andCachesAcrossRepeatedCalls() {
        String stockId = "ZM04";
        LocalDate tradeDate = recentWeekday(6);
        seedStock(stockId, "測試分K四");
        seedDailyPrice(stockId, tradeDate, "20.00", "21.00", "19.50", "20.50", 8000);

        mockServer.expect(requestTo(yahooUrl(stockId, tradeDate)))
                .andRespond(withSuccess("{\"chart\":{\"result\":[],\"error\":null}}", MediaType.APPLICATION_JSON));

        ResponseEntity<MinuteBarResponse> first = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("NO_DATA", first.getBody().getDataStatus());
        assertTrue(first.getBody().getBars().isEmpty());
        assertNotNull(first.getBody().getDailySummary());
        mockServer.verify();

        // repeated call: no further mock expectation registered -> must not hit external again
        ResponseEntity<MinuteBarResponse> second = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("NO_DATA", second.getBody().getDataStatus());
    }

    // ---------- 5. Already-AVAILABLE date remains readable after aging past the 30-day window ----------

    @Test
    void alreadyAvailableDate_remainsReadable_afterAgingPastTheWindow() {
        String stockId = "ZM05";
        LocalDate tradeDate = weekdayAtLeast(40);
        seedStock(stockId, "測試分K五");
        seedDailyPrice(stockId, tradeDate, "30.00", "31.00", "29.50", "30.50", 9000);

        jdbc.update("INSERT INTO stock_minute_fetch_status "
                        + "(stock_id, trade_date, status, bar_count, source, attempt_count, last_error, fetched_at) "
                        + "VALUES (?, ?, 'AVAILABLE', 2, 'YAHOO', 0, NULL, ?)",
                stockId, java.sql.Date.valueOf(tradeDate), java.sql.Timestamp.valueOf(LocalDateTime.now()));
        jdbc.update("INSERT INTO stock_minute_price "
                        + "(stock_id, trade_date, bar_time, open_price, high_price, low_price, close_price, volume, source) "
                        + "VALUES (?, ?, '09:00:00', 30.00, 30.20, 29.90, 30.10, 111, 'YAHOO')",
                stockId, java.sql.Date.valueOf(tradeDate));
        jdbc.update("INSERT INTO stock_minute_price "
                        + "(stock_id, trade_date, bar_time, open_price, high_price, low_price, close_price, volume, source) "
                        + "VALUES (?, ?, '09:01:00', 30.10, 30.30, 30.00, 30.20, 222, 'YAHOO')",
                stockId, java.sql.Date.valueOf(tradeDate));

        // No mock expectations: reading previously-fetched, now out-of-window data must never call external.
        ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("AVAILABLE", response.getBody().getDataStatus());
        assertEquals(2, response.getBody().getBarCount());
        assertEquals("09:00", response.getBody().getBars().get(0).getBarTime());
        assertEquals("09:01", response.getBody().getBars().get(1).getBarTime());
    }

    // ---------- 6. interval=5 aggregation, anchored at 09:00, gaps produce no group ----------

    @Test
    void interval5_aggregatesFromOneMinuteBars_anchoredAt0900_skipsEmptyGroups() {
        String stockId = "ZM06";
        LocalDate tradeDate = recentWeekday(2);
        seedStock(stockId, "測試分K六");
        seedDailyPrice(stockId, tradeDate, "40.00", "42.00", "39.00", "41.00", 15000);

        List<Bar> bars = List.of(
                new Bar(9, 0, 40.0, 40.5, 39.8, 40.2, 100L),
                new Bar(9, 1, 40.2, 40.8, 40.1, 40.6, 110L),
                new Bar(9, 2, 40.6, 41.0, 40.5, 40.9, 120L),
                new Bar(9, 3, 40.9, 41.2, 40.7, 41.1, 130L),
                new Bar(9, 4, 41.1, 41.3, 40.9, 41.0, 140L), // group 0: 09:00-09:04
                new Bar(9, 5, 41.0, 41.5, 40.8, 41.4, 150L),
                new Bar(9, 6, 41.4, 41.6, 41.2, 41.3, 160L), // group 1: 09:05-09:09 (partial, 2 bars)
                // group 2 (09:10-09:14) intentionally empty
                new Bar(9, 16, 42.0, 42.2, 41.8, 42.1, 170L)); // group 3: 09:15-09:19 (single bar)

        mockServer.expect(requestTo(yahooUrl(stockId, tradeDate)))
                .andRespond(withSuccess(buildYahooFixture(tradeDate, bars), MediaType.APPLICATION_JSON));

        ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, tradeDate, 5, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<MinuteBarDto> result = response.getBody().getBars();
        assertEquals(3, result.size());
        assertEquals(3, response.getBody().getBarCount());

        MinuteBarDto group0 = result.get(0);
        assertEquals("09:00", group0.getBarTime());
        assertEquals(0, new BigDecimal("40.00").compareTo(group0.getOpen()));
        assertEquals(0, new BigDecimal("41.00").compareTo(group0.getClose()));
        assertEquals(0, new BigDecimal("41.30").compareTo(group0.getHigh()));
        assertEquals(0, new BigDecimal("39.80").compareTo(group0.getLow()));
        assertEquals(100 + 110 + 120 + 130 + 140, group0.getVolume());

        MinuteBarDto group1 = result.get(1);
        assertEquals("09:05", group1.getBarTime());
        assertEquals(0, new BigDecimal("41.00").compareTo(group1.getOpen()));
        assertEquals(0, new BigDecimal("41.30").compareTo(group1.getClose()));
        assertEquals(0, new BigDecimal("41.60").compareTo(group1.getHigh()));
        assertEquals(0, new BigDecimal("40.80").compareTo(group1.getLow()));
        assertEquals(150 + 160, group1.getVolume());

        MinuteBarDto group2 = result.get(2);
        assertEquals("09:15", group2.getBarTime()); // anchored at 09:00, not at the 09:16 bar itself
        assertEquals(0, new BigDecimal("42.00").compareTo(group2.getOpen()));
        assertEquals(0, new BigDecimal("42.10").compareTo(group2.getClose()));
        assertEquals(170, group2.getVolume());
    }

    // ---------- 7. Null OHLC minutes dropped; never zero-filled ----------

    @Test
    void nullOhlcMinutes_areDropped_neverZeroFilled() {
        String stockId = "ZM07";
        LocalDate tradeDate = recentWeekday(7);
        seedStock(stockId, "測試分K七");
        seedDailyPrice(stockId, tradeDate, "60.00", "61.00", "59.50", "60.50", 4000);

        List<Bar> bars = List.of(
                new Bar(9, 0, 60.0, 60.2, 59.9, 60.1, 50L),
                new Bar(9, 1, null, null, null, null, 0L), // no trade this minute
                new Bar(9, 2, 60.3, 60.5, 60.1, 60.4, 60L));

        mockServer.expect(requestTo(yahooUrl(stockId, tradeDate)))
                .andRespond(withSuccess(buildYahooFixture(tradeDate, bars), MediaType.APPLICATION_JSON));

        ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().getBarCount());
        assertEquals("09:00", response.getBody().getBars().get(0).getBarTime());
        assertEquals("09:02", response.getBody().getBars().get(1).getBarTime());

        Integer zeroPriceRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_minute_price WHERE stock_id = ? "
                        + "AND (open_price = 0 OR high_price = 0 OR low_price = 0 OR close_price = 0)",
                Integer.class, stockId);
        assertEquals(0, zeroPriceRows);
    }

    // ---------- 9. Non-trading day ----------

    @Test
    void nonTradingDay_returnsNotATradingDay_withNullDailySummary() {
        String stockId = "ZM09";
        LocalDate saturday = mostRecentSaturday();
        seedStock(stockId, "測試分K九");
        // Deliberately no stock_daily_price row for this date.

        ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, saturday, null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("NOT_A_TRADING_DAY", response.getBody().getDataStatus());
        assertNull(response.getBody().getDailySummary());
        assertTrue(response.getBody().getBars().isEmpty());

        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                String.class, stockId, java.sql.Date.valueOf(saturday));
        assertEquals("NOT_A_TRADING_DAY", dbStatus);
    }

    // ---------- 11 & 12. Validation errors ----------

    @Test
    void invalidInterval_returns400_withAllowedList() {
        ResponseEntity<ErrorResponse> response = getMinuteBarsError("ZM99", "2026-08-01", 3);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_INTERVAL", response.getBody().getCode());
        assertEquals(List.of(1, 5, 15, 30, 60), response.getBody().getAllowed());
    }

    @Test
    void futureTradeDate_returns400() {
        LocalDate tomorrow = LocalDate.now(TAIPEI).plusDays(1);
        ResponseEntity<ErrorResponse> response = getMinuteBarsError("ZM99", tomorrow.toString(), null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("FUTURE_TRADE_DATE", response.getBody().getCode());
    }

    @Test
    void missingTradeDate_returns400() {
        ResponseEntity<ErrorResponse> response = getMinuteBarsError("ZM99", null, null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MISSING_TRADE_DATE", response.getBody().getCode());
    }

    @Test
    void invalidDateFormat_returns400() {
        ResponseEntity<ErrorResponse> response = getMinuteBarsError("ZM99", "not-a-date", null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_DATE_FORMAT", response.getBody().getCode());
    }

    @Test
    void unknownStock_returns404() {
        ResponseEntity<ErrorResponse> response = getMinuteBarsError("ZM-NOPE", "2026-08-01", null);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("STOCK_NOT_FOUND", response.getBody().getCode());
        assertEquals("ZM-NOPE", response.getBody().getStockId());
    }

    // ---------- 13. FETCH_FAILED mapping, attempt cap, refresh bypass ----------

    @Test
    void fetchFailure_mapsToFetchFailed_capsRetries_andRefreshForcesRetry() {
        String stockId = "ZM13";
        LocalDate tradeDate = recentWeekday(8);
        seedStock(stockId, "測試分K十三");
        seedDailyPrice(stockId, tradeDate, "70.00", "71.00", "69.50", "70.50", 6000);

        // MockRestServiceServer requires every expectation to be registered before any actual
        // request is made, so all four external calls this test drives are declared up front:
        // three failures (500, not rate-limited so no internal retry loop kicks in), then one
        // success for the refresh=true call at the end.
        for (int i = 1; i <= 3; i++) {
            mockServer.expect(requestTo(yahooUrl(stockId, tradeDate)))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        }
        List<Bar> bars = List.of(new Bar(9, 0, 70.0, 70.2, 69.9, 70.1, 300L));
        mockServer.expect(requestTo(yahooUrl(stockId, tradeDate)))
                .andRespond(withSuccess(buildYahooFixture(tradeDate, bars), MediaType.APPLICATION_JSON));

        // default app.minute-price.max-attempt-count = 3: three separate failed calls, one external
        // request each.
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, tradeDate, null, null);
            assertEquals("FETCH_FAILED", response.getBody().getDataStatus());
            assertNotNull(response.getBody().getMessage());
            assertNotNull(response.getBody().getDailySummary());

            Integer attemptCount = jdbc.queryForObject(
                    "SELECT attempt_count FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                    Integer.class, stockId, java.sql.Date.valueOf(tradeDate));
            assertEquals(i, attemptCount);
        }

        // 4th call: attempt_count (3) has reached the cap -> must not call external again.
        ResponseEntity<MinuteBarResponse> capped = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("FETCH_FAILED", capped.getBody().getDataStatus());
        Integer stillThree = jdbc.queryForObject(
                "SELECT attempt_count FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                Integer.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals(3, stillThree);

        // refresh=true forces a retry despite the cap.
        ResponseEntity<MinuteBarResponse> refreshed = getMinuteBars(stockId, tradeDate, null, true);
        assertEquals("AVAILABLE", refreshed.getBody().getDataStatus());
        Integer resetAttempt = jdbc.queryForObject(
                "SELECT attempt_count FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                Integer.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals(0, resetAttempt);

        mockServer.verify();
    }

    // ---------- 14. Bars + status write share one transaction ----------

    @Test
    void barsAndStatus_areWrittenInOneTransaction_partialWriteRollsBackCompletely() {
        String stockId = "ZM14";
        LocalDate tradeDate = recentWeekday(9);

        NormalizedMinuteBar valid = new NormalizedMinuteBar(stockId, tradeDate, LocalTime.of(9, 0),
                new BigDecimal("10.00"), new BigDecimal("10.50"), new BigDecimal("9.80"),
                new BigDecimal("10.20"), 100L);
        // high < low violates chk_smp_high_low: this statement fails mid-transaction.
        NormalizedMinuteBar invalid = new NormalizedMinuteBar(stockId, tradeDate, LocalTime.of(9, 1),
                new BigDecimal("10.00"), new BigDecimal("5.00"), new BigDecimal("9.80"),
                new BigDecimal("10.20"), 100L);

        assertThrows(Exception.class, () ->
                ingestionService.applyFetchResult(stockId, tradeDate, List.of(valid, invalid), "YAHOO", LocalDateTime.now()));

        Integer barRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_minute_price WHERE stock_id = ? AND trade_date = ?",
                Integer.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals(0, barRows, "the valid bar inserted before the failing one must be rolled back too");

        Integer statusRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                Integer.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals(0, statusRows, "status must never end up AVAILABLE when its bars didn't persist");
    }

    // ---------- helpers ----------

    private void seedStock(String stockId, String name) {
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES (?, ?, 'TSE', 1)", stockId, name);
    }

    private void seedDailyPrice(String stockId, LocalDate tradeDate, String open, String high, String low,
                                 String close, long volume) {
        jdbc.update("INSERT INTO stock_daily_price "
                        + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, turnover, transaction_count, source) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'TEST')",
                stockId, java.sql.Date.valueOf(tradeDate), new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), volume);
    }

    /** A weekday at least `minDaysAgo` days before today, guaranteed to fall inside the 30-day window. */
    private LocalDate recentWeekday(int minDaysAgo) {
        LocalDate d = LocalDate.now(TAIPEI).minusDays(minDaysAgo);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** A weekday strictly more than 30 days before today (guaranteed out-of-window). */
    private LocalDate weekdayAtLeast(int minDaysAgo) {
        return recentWeekday(Math.max(minDaysAgo, 31));
    }

    private LocalDate mostRecentSaturday() {
        LocalDate d = LocalDate.now(TAIPEI);
        while (d.getDayOfWeek() != DayOfWeek.SATURDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private ResponseEntity<MinuteBarResponse> getMinuteBars(String stockId, LocalDate tradeDate, Integer interval, Boolean refresh) {
        StringBuilder url = new StringBuilder("/api/stocks/").append(stockId).append("/minute-bars?tradeDate=").append(tradeDate);
        if (interval != null) {
            url.append("&interval=").append(interval);
        }
        if (refresh != null) {
            url.append("&refresh=").append(refresh);
        }
        return rest.getForEntity(url.toString(), MinuteBarResponse.class);
    }

    private ResponseEntity<ErrorResponse> getMinuteBarsError(String stockId, String tradeDateRaw, Integer interval) {
        StringBuilder url = new StringBuilder("/api/stocks/").append(stockId).append("/minute-bars");
        List<String> params = new ArrayList<>();
        if (tradeDateRaw != null) {
            params.add("tradeDate=" + tradeDateRaw);
        }
        if (interval != null) {
            params.add("interval=" + interval);
        }
        if (!params.isEmpty()) {
            url.append("?").append(String.join("&", params));
        }
        return rest.getForEntity(url.toString(), ErrorResponse.class);
    }

    private String yahooUrl(String stockId, LocalDate tradeDate) {
        long period1 = tradeDate.atStartOfDay(TAIPEI).toEpochSecond();
        long period2 = tradeDate.plusDays(1).atStartOfDay(TAIPEI).toEpochSecond();
        return yahooBaseUrl + "/" + stockId + ".TW?interval=1m&period1=" + period1 + "&period2=" + period2;
    }

    private List<Bar> full271Bars() {
        List<Bar> list = new ArrayList<>();
        LocalTime t = LocalTime.of(9, 0);
        int i = 0;
        while (!t.isAfter(LocalTime.of(13, 30))) {
            double v = 100 + i * 0.01;
            list.add(new Bar(t.getHour(), t.getMinute(), v, v + 0.05, v - 0.05, v, 100L + i));
            t = t.plusMinutes(1);
            i++;
        }
        return list;
    }

    private String buildYahooFixture(LocalDate tradeDate, List<Bar> bars) {
        StringBuilder ts = new StringBuilder();
        StringBuilder open = new StringBuilder();
        StringBuilder high = new StringBuilder();
        StringBuilder low = new StringBuilder();
        StringBuilder close = new StringBuilder();
        StringBuilder volume = new StringBuilder();
        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            if (i > 0) {
                ts.append(",");
                open.append(",");
                high.append(",");
                low.append(",");
                close.append(",");
                volume.append(",");
            }
            long epoch = tradeDate.atTime(b.hour, b.minute).atZone(TAIPEI).toEpochSecond();
            ts.append(epoch);
            open.append(b.open == null ? "null" : b.open);
            high.append(b.high == null ? "null" : b.high);
            low.append(b.low == null ? "null" : b.low);
            close.append(b.close == null ? "null" : b.close);
            volume.append(b.volume == null ? "null" : b.volume);
        }
        return "{\"chart\":{\"result\":[{\"timestamp\":[" + ts + "],\"indicators\":{\"quote\":[{"
                + "\"open\":[" + open + "],\"high\":[" + high + "],\"low\":[" + low + "],"
                + "\"close\":[" + close + "],\"volume\":[" + volume + "]}]}}],\"error\":null}}";
    }

    private static class Bar {
        final int hour;
        final int minute;
        final Double open;
        final Double high;
        final Double low;
        final Double close;
        final Long volume;

        Bar(int hour, int minute, Double open, Double high, Double low, Double close, Long volume) {
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
