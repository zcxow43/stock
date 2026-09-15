package com.stock;

import com.stock.dto.MinuteBarResponse;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * End-to-end (real DB + mocked external HTTP) coverage for the "30 天以前的分 K 改由富果補抓" increment
 * to specs/backend/stock-minute-price.md: source routing by date (Yahoo / Fugle / neither), the
 * availableFrom boundary and its presence on every response, Fugle's own error classification and
 * request pacing, and that Fugle never falls back to Yahoo or vice versa.
 *
 * Runs against the SHARED default test context (fake key `test-fugle-key-DO-NOT-USE`, availableFrom
 * 2023-05-23, Fugle interval-ms 100 — see src/test/resources/application.yml) so it reuses the same
 * cached context as {@link StockMinutePriceIntegrationTest} rather than spinning up a new one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockMinutePriceFugleIntegrationTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String TEST_FUGLE_KEY = "test-fugle-key-DO-NOT-USE";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("externalApiRestTemplate")
    private RestTemplate externalApiRestTemplate;

    @Autowired
    private DataSource dataSource;

    @Value("${app.external.yahoo-finance-base-url}")
    private String yahooBaseUrl;

    @Value("${app.external.fugle-base-url}")
    private String fugleBaseUrl;

    @Value("${app.minute-price.available-from}")
    private String availableFromRaw;

    private MockRestServiceServer mockServer;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        mockServer = MockRestServiceServer.bindTo(externalApiRestTemplate).ignoreExpectOrder(true).build();
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    private void cleanupTestData() {
        jdbc.update("DELETE FROM stock_minute_price WHERE stock_id LIKE 'ZF%'");
        jdbc.update("DELETE FROM stock_minute_fetch_status WHERE stock_id LIKE 'ZF%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'ZF%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'ZF%'");
    }

    // ---------- 1. Fugle path: success, 266 bars, source=FUGLE, header/params, DB rows tagged FUGLE ----------

    @Test
    void fugleWindowDate_firstFetch_triggersFugleRequest_available266Bars_sourceFugle() {
        String stockId = "ZF01";
        LocalDate tradeDate = fugleWindowWeekday(40);
        seedStock(stockId, "測試富果一");
        seedDailyPrice(stockId, tradeDate);

        mockServer.expect(requestTo(fugleUrl(stockId, tradeDate)))
                .andExpect(header("X-API-KEY", TEST_FUGLE_KEY))
                .andRespond(withSuccess(fugleFixture(tradeDate, full266Bars()), MediaType.APPLICATION_JSON));

        ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, tradeDate, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        MinuteBarResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("AVAILABLE", body.getDataStatus());
        assertEquals("FUGLE", body.getSource());
        assertEquals(266, body.getBarCount());
        assertEquals("09:00", body.getBars().get(0).getBarTime());
        assertEquals("13:30", body.getBars().get(body.getBars().size() - 1).getBarTime());
        assertEquals(LocalDate.parse(availableFromRaw), body.getAvailableFrom());
        mockServer.verify();

        String dbSource = jdbc.queryForObject(
                "SELECT source FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                String.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals("FUGLE", dbSource);

        Integer barsWithFugleSource = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_minute_price WHERE stock_id = ? AND trade_date = ? AND source = 'FUGLE'",
                Integer.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals(266, barsWithFugleSource);
    }

    // ---------- 2. Yahoo path unaffected: within 30 days still goes to Yahoo, never Fugle ----------

    @Test
    void withinWindowDate_stillUsesYahoo_neverCallsFugle() {
        String stockId = "ZF02";
        LocalDate tradeDate = recentWeekday(3);
        seedStock(stockId, "測試富果二");
        seedDailyPrice(stockId, tradeDate);

        mockServer.expect(requestTo(yahooUrl(stockId, tradeDate)))
                .andRespond(withSuccess(yahooFixture(tradeDate), MediaType.APPLICATION_JSON));

        // No Fugle expectation registered: if the service mistakenly called Fugle instead of/as well
        // as Yahoo, MockRestServiceServer would fail the request with an unmatched-request error.
        ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("AVAILABLE", response.getBody().getDataStatus());
        assertEquals("YAHOO", response.getBody().getSource());
        mockServer.verify();
    }

    // ---------- 3. availableFrom boundary: date before it -> OUT_OF_WINDOW, no request to either source ----------

    @Test
    void dateBeforeAvailableFrom_returnsOutOfWindow_noRequestToEitherSource() {
        String stockId = "ZF03";
        LocalDate tradeDate = LocalDate.parse(availableFromRaw).minusDays(1);
        while (tradeDate.getDayOfWeek() == DayOfWeek.SATURDAY || tradeDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            tradeDate = tradeDate.minusDays(1);
        }
        seedStock(stockId, "測試富果三");
        seedDailyPrice(stockId, tradeDate);

        // No expectations registered against either Yahoo's or Fugle's base URL.
        ResponseEntity<MinuteBarResponse> response = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("OUT_OF_WINDOW", response.getBody().getDataStatus());
        assertTrue(response.getBody().getBars().isEmpty());
        assertEquals(LocalDate.parse(availableFromRaw), response.getBody().getAvailableFrom());

        // Repeated calls stay at zero requests too.
        ResponseEntity<MinuteBarResponse> second = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("OUT_OF_WINDOW", second.getBody().getDataStatus());
    }

    // ---------- 4. Fugle NO_DATA (404): repeated calls stay at zero requests ----------

    @Test
    void fugle404_returnsNoData_cachesAcrossRepeatedCalls() {
        String stockId = "ZF04";
        LocalDate tradeDate = fugleWindowWeekday(45);
        seedStock(stockId, "測試富果四");
        seedDailyPrice(stockId, tradeDate);

        mockServer.expect(requestTo(fugleUrl(stockId, tradeDate))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Resource Not Found\"}"));

        ResponseEntity<MinuteBarResponse> first = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("NO_DATA", first.getBody().getDataStatus());
        assertNotNull(first.getBody().getDailySummary());
        mockServer.verify();

        // No further expectation registered -> a second Fugle request here would fail the test.
        ResponseEntity<MinuteBarResponse> second = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("NO_DATA", second.getBody().getDataStatus());
    }

    // ---------- 5. Fugle failure classification + attempt cap + refresh bypass, never touches Yahoo ----------

    @Test
    void fugleFailure_mapsToFetchFailed_capsRetries_refreshRetries_neverCallsYahoo() {
        String stockId = "ZF05";
        LocalDate tradeDate = fugleWindowWeekday(50);
        seedStock(stockId, "測試富果五");
        seedDailyPrice(stockId, tradeDate);

        for (int i = 1; i <= 3; i++) {
            mockServer.expect(requestTo(fugleUrl(stockId, tradeDate)))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                            .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Unauthorized\"}"));
        }
        mockServer.expect(requestTo(fugleUrl(stockId, tradeDate)))
                .andRespond(withSuccess(fugleFixture(tradeDate, full266Bars()), MediaType.APPLICATION_JSON));

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

        // Cap reached (default max-attempt-count=3): no further automatic request.
        ResponseEntity<MinuteBarResponse> capped = getMinuteBars(stockId, tradeDate, null, null);
        assertEquals("FETCH_FAILED", capped.getBody().getDataStatus());
        Integer stillThree = jdbc.queryForObject(
                "SELECT attempt_count FROM stock_minute_fetch_status WHERE stock_id = ? AND trade_date = ?",
                Integer.class, stockId, java.sql.Date.valueOf(tradeDate));
        assertEquals(3, stillThree);

        // refresh=true forces a retry despite the cap -> succeeds via Fugle, never touches Yahoo
        // (no Yahoo expectation was ever registered in this test).
        ResponseEntity<MinuteBarResponse> refreshed = getMinuteBars(stockId, tradeDate, null, true);
        assertEquals("AVAILABLE", refreshed.getBody().getDataStatus());
        assertEquals("FUGLE", refreshed.getBody().getSource());
        mockServer.verify();
    }

    // ---------- 6. availableFrom is present on every dataStatus variant ----------

    @Test
    void availableFrom_isPresentOnEveryResponseVariant() {
        LocalDate expected = LocalDate.parse(availableFromRaw);

        String available = "ZF61";
        LocalDate availDate = fugleWindowWeekday(55);
        String noData = "ZF62";
        LocalDate noDataDate = fugleWindowWeekday(56);
        String outOfWindow = "ZF63";
        LocalDate oowDate = expected.minusDays(10);
        while (oowDate.getDayOfWeek() == DayOfWeek.SATURDAY || oowDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            oowDate = oowDate.minusDays(1);
        }
        String notTrading = "ZF64";
        LocalDate saturday = mostRecentSaturday();
        String failed = "ZF65";
        LocalDate failedDate = fugleWindowWeekday(57);

        seedStock(available, "AF-available");
        seedDailyPrice(available, availDate);
        seedStock(noData, "AF-nodata");
        seedDailyPrice(noData, noDataDate);
        seedStock(outOfWindow, "AF-oow");
        seedDailyPrice(outOfWindow, oowDate);
        seedStock(notTrading, "AF-nottrading");
        seedStock(failed, "AF-failed");
        seedDailyPrice(failed, failedDate);

        // MockRestServiceServer requires every expectation registered before any actual request is
        // made, so all three Fugle mocks this test drives are declared up front.
        mockServer.expect(requestTo(fugleUrl(available, availDate)))
                .andRespond(withSuccess(fugleFixture(availDate, full266Bars()), MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(fugleUrl(noData, noDataDate))).andRespond(withStatus(HttpStatus.NOT_FOUND));
        mockServer.expect(requestTo(fugleUrl(failed, failedDate)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // AVAILABLE (Fugle)
        assertEquals(expected, getMinuteBars(available, availDate, null, null).getBody().getAvailableFrom());
        // NO_DATA (Fugle 404)
        assertEquals(expected, getMinuteBars(noData, noDataDate, null, null).getBody().getAvailableFrom());
        // OUT_OF_WINDOW (no request)
        assertEquals(expected, getMinuteBars(outOfWindow, oowDate, null, null).getBody().getAvailableFrom());
        // NOT_A_TRADING_DAY (no request)
        assertEquals(expected, getMinuteBars(notTrading, saturday, null, null).getBody().getAvailableFrom());
        // FETCH_FAILED (Fugle 500)
        assertEquals(expected, getMinuteBars(failed, failedDate, null, null).getBody().getAvailableFrom());
        mockServer.verify();
    }

    // ---------- 7. Fugle request spacing: concurrent calls stay >= configured interval apart ----------

    @Test
    void concurrentFugleRequests_staySpacedByAtLeastTheConfiguredInterval() throws InterruptedException {
        int workerCount = 3;
        long intervalMs = 100; // app.minute-price.fugle.interval-ms in test profile
        List<String> stockIds = List.of("ZF71", "ZF72", "ZF73");
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            String id = stockIds.get(i);
            LocalDate date = fugleWindowWeekday(60 + i);
            dates.add(date);
            seedStock(id, "並行富果" + i);
            seedDailyPrice(id, date);
        }

        List<Long> arrivalTimes = new CopyOnWriteArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            String id = stockIds.get(i);
            LocalDate date = dates.get(i);
            mockServer.expect(requestTo(fugleUrl(id, date))).andRespond(request -> {
                arrivalTimes.add(System.nanoTime());
                return withSuccess(fugleFixture(date, full266Bars()), MediaType.APPLICATION_JSON).createResponse(request);
            });
        }

        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        try {
            for (int i = 0; i < workerCount; i++) {
                int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    getMinuteBars(stockIds.get(idx), dates.get(idx), null, null);
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "workers did not finish in time");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(workerCount, arrivalTimes.size());
        List<Long> sorted = new ArrayList<>(arrivalTimes);
        java.util.Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            long gapMs = (sorted.get(i) - sorted.get(i - 1)) / 1_000_000;
            assertTrue(gapMs >= intervalMs - 30,
                    "consecutive Fugle requests must stay >= " + intervalMs + "ms apart; got " + gapMs + "ms");
        }
    }

    // ---------- 8. Fugle's pacing never slows Yahoo ----------

    @Test
    void fugleRequest_doesNotSlowDown_aSubsequentYahooRequest() {
        String fugleStock = "ZF81";
        LocalDate fugleDate = fugleWindowWeekday(65);
        seedStock(fugleStock, "節流富果");
        seedDailyPrice(fugleStock, fugleDate);

        String yahooStock = "ZF82";
        LocalDate yahooDate = recentWeekday(5);
        seedStock(yahooStock, "節流Yahoo");
        seedDailyPrice(yahooStock, yahooDate);

        // Both expectations registered up front (MockRestServiceServer requirement), fired in order.
        mockServer.expect(requestTo(fugleUrl(fugleStock, fugleDate)))
                .andRespond(withSuccess(fugleFixture(fugleDate, full266Bars()), MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(yahooUrl(yahooStock, yahooDate)))
                .andRespond(withSuccess(yahooFixture(yahooDate), MediaType.APPLICATION_JSON));

        getMinuteBars(fugleStock, fugleDate, null, null); // starts Fugle's rate-limit cooldown

        long start = System.nanoTime();
        ResponseEntity<MinuteBarResponse> response = getMinuteBars(yahooStock, yahooDate, null, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("AVAILABLE", response.getBody().getDataStatus());
        assertTrue(elapsedMs < 80, "a Yahoo request right after a Fugle request must not be delayed by "
                + "Fugle's own interval; took " + elapsedMs + "ms");
    }

    // ---------- helpers ----------

    private void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void seedStock(String stockId, String name) {
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES (?, ?, 'TSE', 1)", stockId, name);
    }

    private void seedDailyPrice(String stockId, LocalDate tradeDate) {
        jdbc.update("INSERT INTO stock_daily_price "
                        + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, turnover, transaction_count, source) "
                        + "VALUES (?, ?, 100.00, 105.00, 99.00, 104.00, 500000, 0, 0, 'TEST')",
                stockId, java.sql.Date.valueOf(tradeDate));
    }

    /** A weekday within 30 days of today (guaranteed Yahoo window). */
    private LocalDate recentWeekday(int minDaysAgo) {
        LocalDate d = LocalDate.now(TAIPEI).minusDays(minDaysAgo);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** A weekday strictly more than 30 days ago but not earlier than availableFrom (guaranteed Fugle window). */
    private LocalDate fugleWindowWeekday(int minDaysAgo) {
        LocalDate d = LocalDate.now(TAIPEI).minusDays(Math.max(minDaysAgo, 31));
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
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
        List<FBar> bars = List.of(new FBar(9, 0, 100.0, 100.5, 99.8, 100.2, 100L));
        StringBuilder ts = new StringBuilder();
        StringBuilder open = new StringBuilder();
        StringBuilder high = new StringBuilder();
        StringBuilder low = new StringBuilder();
        StringBuilder close = new StringBuilder();
        StringBuilder volume = new StringBuilder();
        for (int i = 0; i < bars.size(); i++) {
            FBar b = bars.get(i);
            long epoch = tradeDate.atTime(b.hour, b.minute).atZone(TAIPEI).toEpochSecond();
            ts.append(epoch);
            open.append(b.open);
            high.append(b.high);
            low.append(b.low);
            close.append(b.close);
            volume.append(b.volume);
        }
        return "{\"chart\":{\"result\":[{\"timestamp\":[" + ts + "],\"indicators\":{\"quote\":[{"
                + "\"open\":[" + open + "],\"high\":[" + high + "],\"low\":[" + low + "],"
                + "\"close\":[" + close + "],\"volume\":[" + volume + "]}]}}],\"error\":null}}";
    }

    /** Full 09:00-13:30 session, skipping the 13:25-13:29 closing call auction gap -- 266 bars. */
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
        StringBuilder sb = new StringBuilder("{\"symbol\":\"2330\",\"type\":\"EQUITY\",\"exchange\":\"TWSE\","
                + "\"market\":\"TSE\",\"timeframe\":\"1\",\"data\":[");
        for (int i = 0; i < bars.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            FBar b = bars.get(i);
            String time = String.format("%02d:%02d:00.000+08:00", b.hour, b.minute);
            sb.append("{\"date\":\"").append(tradeDate).append("T").append(time).append("\",");
            sb.append("\"open\":").append(b.open).append(",");
            sb.append("\"high\":").append(b.high).append(",");
            sb.append("\"low\":").append(b.low).append(",");
            sb.append("\"close\":").append(b.close).append(",");
            sb.append("\"volume\":").append(b.volume).append("}");
        }
        sb.append("],\"sort\":\"asc\"}");
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
