package com.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.domain.Stock;
import com.stock.dto.BackfillRequest;
import com.stock.dto.BackfillResponse;
import com.stock.dto.DailySyncRequest;
import com.stock.dto.DailySyncResponse;
import com.stock.dto.ErrorResponse;
import com.stock.dto.ProgressResponse;
import com.stock.domain.StockSyncProgress;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockSyncProgressMapper;
import com.stock.service.JobRunningRegistry;
import com.stock.service.StockSyncService;
import com.stock.service.external.SourceAvailabilityTracker;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockPriceIngestionIntegrationTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("externalApiRestTemplate")
    private RestTemplate externalApiRestTemplate;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockDailyPriceMapper priceMapper;

    @Autowired
    private StockSyncProgressMapper progressMapper;

    @Autowired
    private JobRunningRegistry jobRunningRegistry;

    @Autowired
    private StockSyncService stockSyncService;

    @Autowired
    private SourceAvailabilityTracker sourceAvailabilityTracker;

    @Autowired
    private DataSource dataSource;

    @Value("${app.external.twse-daily-all-url}")
    private String twseUrl;

    @Value("${app.external.finmind-base-url}")
    private String finmindBaseUrl;

    @Value("${app.external.yahoo-finance-base-url}")
    private String yahooBaseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        waitUntilNotRunning(10_000);
        mockServer = MockRestServiceServer.bindTo(externalApiRestTemplate).ignoreExpectOrder(false).build();
        sourceAvailabilityTracker.reset();
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        waitUntilNotRunning(15_000);
        sourceAvailabilityTracker.reset();
        cleanupTestData();
    }

    private void cleanupTestData() {
        jdbc.update("DELETE FROM stock_sync_progress WHERE stock_id LIKE 'T2%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'T1%' OR stock_id LIKE 'T2%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'T1%' OR stock_id LIKE 'T2%'");
    }

    private void waitUntilNotRunning(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (jobRunningRegistry.isRunning("PRICE_BACKFILL") && System.currentTimeMillis() < deadline) {
            sleep(50);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 1. Daily whole-market snapshot ----------

    @Test
    void dailySync_singleRequestUpsertsStockAndPrice_skipsNoTradeRows_andNormalizesRocDateAndCommaPrice() {
        String twseFixture = "["
                + "{\"Date\":\"1140901\",\"Code\":\"T101\",\"Name\":\"測試一\",\"TradeVolume\":\"19,214,481\","
                + "\"TradeValue\":\"46,545,167,227\",\"OpeningPrice\":\"2,430.00\",\"HighestPrice\":\"2,435.00\","
                + "\"LowestPrice\":\"2,410.00\",\"ClosingPrice\":\"2,410.00\",\"Transaction\":\"60,122\"},"
                + "{\"Date\":\"1140901\",\"Code\":\"T102\",\"Name\":\"測試二\",\"TradeVolume\":\"1000\","
                + "\"TradeValue\":\"88000\",\"OpeningPrice\":\"88.00\",\"HighestPrice\":\"89.00\","
                + "\"LowestPrice\":\"87.50\",\"ClosingPrice\":\"88.50\",\"Transaction\":\"12\"},"
                + "{\"Date\":\"1140901\",\"Code\":\"T103\",\"Name\":\"測試無交易\",\"TradeVolume\":\"\","
                + "\"TradeValue\":\"\",\"OpeningPrice\":\"\",\"HighestPrice\":\"\",\"LowestPrice\":\"\","
                + "\"ClosingPrice\":\"\",\"Transaction\":\"\"}"
                + "]";

        mockServer.expect(requestTo(twseUrl)).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(twseFixture, MediaType.APPLICATION_JSON));

        ResponseEntity<DailySyncResponse> response = rest.postForEntity(
                "/api/stocks/sync/daily", new DailySyncRequest(), DailySyncResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        DailySyncResponse body = response.getBody();
        assertEquals(LocalDate.of(2025, 9, 1), body.getTradeDate()); // ROC 1140901 -> 2025-09-01
        assertEquals(2, body.getStockCount());       // T103 (no-trade) excluded
        assertEquals(2, body.getInsertedCount());
        assertEquals(0, body.getUpdatedCount());
        assertEquals(2, body.getStockMasterUpserted());

        // stock master upserted with normalized name/market
        Stock stock = jdbc.queryForObject("SELECT stock_name, market, is_active FROM stock WHERE stock_id = 'T101'",
                (rs, i) -> {
                    Stock s = new Stock();
                    s.setStockName(rs.getString(1));
                    s.setMarket(rs.getString(2));
                    s.setActive(rs.getBoolean(3));
                    return s;
                });
        assertEquals("測試一", stock.getStockName());
        assertEquals("TSE", stock.getMarket());
        assertTrue(stock.getActive());

        // comma-formatted price normalized to a clean decimal
        BigDecimal close = jdbc.queryForObject(
                "SELECT close_price FROM stock_daily_price WHERE stock_id = 'T101' AND trade_date = '2025-09-01'",
                BigDecimal.class);
        assertEquals(0, new BigDecimal("2410.00").compareTo(close));

        // no-trade stock never gets a price row, and no zero-price rows exist anywhere for our test ids
        Integer t103Rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T103'", Integer.class);
        assertEquals(0, t103Rows);
        Integer zeroPriceRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE (stock_id LIKE 'T1%' OR stock_id LIKE 'T2%') "
                        + "AND (open_price = 0 OR high_price = 0 OR low_price = 0 OR close_price = 0)",
                Integer.class);
        assertEquals(0, zeroPriceRows);

        // re-running the same snapshot is idempotent: same row count, values overwritten not duplicated
        mockServer.reset();
        mockServer.expect(requestTo(twseUrl)).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(twseFixture, MediaType.APPLICATION_JSON));

        ResponseEntity<DailySyncResponse> second = rest.postForEntity(
                "/api/stocks/sync/daily", new DailySyncRequest(), DailySyncResponse.class);
        assertEquals(2, second.getBody().getInsertedCount() + second.getBody().getUpdatedCount());
        assertEquals(0, second.getBody().getInsertedCount());
        assertEquals(2, second.getBody().getUpdatedCount());

        Integer totalRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id IN ('T101','T102')", Integer.class);
        assertEquals(2, totalRows);
    }

    // ---------- 2. Backfill validation ----------

    @Test
    void backfill_unknownStockId_rejectedWith400() {
        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T2UNKNOWN"));
        request.setStartDate(LocalDate.of(2025, 9, 1));
        request.setEndDate(LocalDate.of(2025, 9, 10));

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/sync/backfill", request, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("UNKNOWN_STOCK_ID", response.getBody().getCode());
        assertTrue(response.getBody().getUnknownIds().contains("T2UNKNOWN"));
    }

    @Test
    void backfill_invalidDateRange_rejectedWith400() {
        BackfillRequest request = new BackfillRequest();
        request.setStartDate(LocalDate.of(2025, 9, 10));
        request.setEndDate(LocalDate.of(2025, 9, 1));

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/sync/backfill", request, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_DATE_RANGE", response.getBody().getCode());
    }

    // ---------- 3. Backfill selected mode, rate limiting/retry, idempotency ----------

    @Test
    void backfill_selectedMode_onlyProcessesGivenIds_retriesOnTimeout_andIsIdempotent() throws Exception {
        seedStock("T201", "測試回補一", true);
        seedStock("T202", "測試回補二", true);
        // decoy stock that must NOT be touched because it's not in stockIds
        seedStock("T203", "不應處理", true);

        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 10);

        String finmindUrlT201 = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T201&start_date="
                + start + "&end_date=" + end;
        String finmindUrlT202 = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T202&start_date="
                + start + "&end_date=" + end;

        String t201Body = finmindFixture("T201",
                new String[]{"2025-09-01", "2025-09-02"},
                new String[]{"100.00", "101.00"}, new String[]{"102.00", "103.00"},
                new String[]{"99.00", "100.50"}, new String[]{"101.50", "102.50"});
        String t202Body = finmindFixture("T202",
                new String[]{"2025-09-01"},
                new String[]{"50.00"}, new String[]{"51.00"}, new String[]{"49.50"}, new String[]{"50.50"});

        // Yahoo is priority (spec: 來源順位); these synthetic stocks don't exist there, so each
        // stock's very first call is a Yahoo 404 that correctly falls through to FinMind.
        // Registered in actual runtime order (T201 fully settles before T202 starts):
        // Yahoo(T201) 404 -> FinMind(T201) timeout -> FinMind(T201) success -> Yahoo(T202) 404 ->
        // FinMind(T202) success.
        expectYahooNotFound("T201", start, end);
        // T201 on FinMind: first attempt times out (non-block failure -- spec: 依既有規則退避重試),
        // retried once on the SAME source and succeeds. (429/403 are now block-class and switch
        // source instead of retrying in place -- see StockPriceIngestionMultiSourceIntegrationTest
        // for that behavior; a timeout is what still exercises same-source backoff-retry here.)
        mockServer.expect(requestTo(finmindUrlT201)).andRespond(request -> {
            throw new IOException("simulated timeout");
        });
        mockServer.expect(requestTo(finmindUrlT201)).andRespond(withSuccess(t201Body, MediaType.APPLICATION_JSON));
        expectYahooNotFound("T202", start, end);
        mockServer.expect(requestTo(finmindUrlT202)).andRespond(withSuccess(t202Body, MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T201", "T202"));
        request.setStartDate(start);
        request.setEndDate(end);
        request.setResume(false);

        ResponseEntity<BackfillResponse> response = rest.postForEntity(
                "/api/stocks/sync/backfill", request, BackfillResponse.class);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("SELECTED", response.getBody().getMode());
        assertEquals(2, response.getBody().getTargetCount());

        waitUntilProgressDone("PRICE_BACKFILL", Arrays.asList("T201", "T202"), 10_000);
        mockServer.verify(); // confirms the 429-then-200 retry sequence actually happened, in order

        Integer t201RowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T201'", Integer.class);
        assertEquals(2, t201RowCount);
        Integer t202RowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T202'", Integer.class);
        assertEquals(1, t202RowCount);
        // decoy stock (not in stockIds) must have no progress row and no price rows at all
        Integer t203Progress = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_sync_progress WHERE stock_id = 'T203'", Integer.class);
        assertEquals(0, t203Progress);
        Integer t203Price = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T203'", Integer.class);
        assertEquals(0, t203Price);

        String t201Status = jdbc.queryForObject(
                "SELECT status FROM stock_sync_progress WHERE stock_id = 'T201' AND job_type = 'PRICE_BACKFILL'",
                String.class);
        assertEquals("DONE", t201Status);

        // ---- idempotency: re-run same range for T201, row count must stay the same ----
        mockServer.reset();
        expectYahooNotFound("T201", start, end);
        mockServer.expect(requestTo(finmindUrlT201)).andRespond(withSuccess(t201Body, MediaType.APPLICATION_JSON));
        expectYahooNotFound("T202", start, end);
        mockServer.expect(requestTo(finmindUrlT202)).andRespond(withSuccess(t202Body, MediaType.APPLICATION_JSON));

        BackfillRequest rerun = new BackfillRequest();
        rerun.setStockIds(Arrays.asList("T201", "T202"));
        rerun.setStartDate(start);
        rerun.setEndDate(end);
        rerun.setResume(false); // reset & rerun same range

        rest.postForEntity("/api/stocks/sync/backfill", rerun, BackfillResponse.class);
        waitUntilProgressDone("PRICE_BACKFILL", Arrays.asList("T201", "T202"), 10_000);

        Integer t201RowCountAfterRerun = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T201'", Integer.class);
        assertEquals(2, t201RowCountAfterRerun);
    }

    // ---------- 4. Resume: already-done stocks are not re-fetched ----------

    @Test
    void backfill_resume_skipsAlreadyDoneStocks_noExternalCallForThem() throws Exception {
        seedStock("T211", "已完成標的", true);
        seedStock("T212", "待補標的", true);

        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 5);

        String finmindUrl211 = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T211&start_date="
                + start + "&end_date=" + end;
        String finmindUrl212Full = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T212&start_date="
                + start + "&end_date=" + end;

        String body211 = finmindFixture("T211", new String[]{"2025-09-01"},
                new String[]{"10.00"}, new String[]{"11.00"}, new String[]{"9.50"}, new String[]{"10.50"});
        String body212 = finmindFixture("T212", new String[]{"2025-09-01"},
                new String[]{"20.00"}, new String[]{"21.00"}, new String[]{"19.50"}, new String[]{"20.50"});

        expectYahooNotFound("T211", start, end);
        mockServer.expect(requestTo(finmindUrl211)).andRespond(withSuccess(body211, MediaType.APPLICATION_JSON));
        expectYahooNotFound("T212", start, end);
        mockServer.expect(requestTo(finmindUrl212Full)).andRespond(withSuccess(body212, MediaType.APPLICATION_JSON));

        BackfillRequest first = new BackfillRequest();
        first.setStockIds(Arrays.asList("T211", "T212"));
        first.setStartDate(start);
        first.setEndDate(end);
        first.setResume(false);
        rest.postForEntity("/api/stocks/sync/backfill", first, BackfillResponse.class);
        waitUntilProgressDone("PRICE_BACKFILL", Arrays.asList("T211", "T212"), 10_000);

        // simulate T212 having failed before it ever completed a day (never synced), T211 fully DONE
        jdbc.update("UPDATE stock_sync_progress SET status='FAILED', attempt_count=1, last_synced_date=NULL "
                + "WHERE stock_id='T212' AND job_type='PRICE_BACKFILL'");

        mockServer.reset();
        // T211 must NOT be requested again (already DONE); only T212 is fetched on resume
        expectYahooNotFound("T212", start, end);
        mockServer.expect(requestTo(finmindUrl212Full)).andRespond(withSuccess(body212, MediaType.APPLICATION_JSON));

        BackfillRequest resumeRequest = new BackfillRequest();
        resumeRequest.setStockIds(Arrays.asList("T211", "T212"));
        resumeRequest.setStartDate(start);
        resumeRequest.setEndDate(end);
        resumeRequest.setResume(true);
        rest.postForEntity("/api/stocks/sync/backfill", resumeRequest, BackfillResponse.class);
        // T212 starts this round already FAILED, so the generic "not PENDING/RUNNING" wait would
        // return immediately without giving the async runner a chance to pick it up; wait for the
        // concrete terminal state we expect instead.
        waitUntilStatus("T212", "PRICE_BACKFILL", "DONE", 10_000);

        mockServer.verify(); // fails if T211's URL was ever requested during resume

        String t211Status = jdbc.queryForObject(
                "SELECT status FROM stock_sync_progress WHERE stock_id='T211' AND job_type='PRICE_BACKFILL'",
                String.class);
        String t212Status = jdbc.queryForObject(
                "SELECT status FROM stock_sync_progress WHERE stock_id='T212' AND job_type='PRICE_BACKFILL'",
                String.class);
        assertEquals("DONE", t211Status);
        assertEquals("DONE", t212Status);
    }

    // ---------- 5. All (whole-market) mode ----------

    @Test
    void backfill_allMode_targetsActiveStocksOnly() throws Exception {
        seedStock("T221", "全市場-活躍", true);
        seedStock("T222", "全市場-已下市", false);

        // ALL mode targets every is_active=1 row in `stock`, and the live DB also carries real
        // seeded stocks (specs/dba/stock-seed-data.md) unrelated to this test. Deactivate them for
        // the duration of this test (restored in the finally block below) so "ALL mode" here means
        // exactly the T22x fixtures this test controls, regardless of what else is seeded.
        List<String> otherActiveIds = jdbc.queryForList(
                "SELECT stock_id FROM stock WHERE is_active = 1 AND stock_id NOT LIKE 'T%'", String.class);
        jdbc.update("UPDATE stock SET is_active = 0 WHERE stock_id NOT LIKE 'T%'");

        try {
            LocalDate start = LocalDate.of(2025, 9, 1);
            LocalDate end = LocalDate.of(2025, 9, 2);

            String finmindUrl221 = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T221&start_date="
                    + start + "&end_date=" + end;
            String body221 = finmindFixture("T221", new String[]{"2025-09-01"},
                    new String[]{"30.00"}, new String[]{"31.00"}, new String[]{"29.50"}, new String[]{"30.50"});

            expectYahooNotFound("T221", start, end);
            mockServer.expect(requestTo(finmindUrl221)).andRespond(withSuccess(body221, MediaType.APPLICATION_JSON));

            BackfillRequest request = new BackfillRequest();
            request.setStartDate(start);
            request.setEndDate(end);
            // stockIds omitted entirely -> ALL mode

            ResponseEntity<BackfillResponse> response = rest.postForEntity(
                    "/api/stocks/sync/backfill", request, BackfillResponse.class);

            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
            assertEquals("ALL", response.getBody().getMode());

            waitUntilProgressDone("PRICE_BACKFILL", Arrays.asList("T221"), 10_000);
            mockServer.verify(); // T222 (inactive) must never be requested

            Integer t222Progress = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_sync_progress WHERE stock_id = 'T222'", Integer.class);
            assertEquals(0, t222Progress);
        } finally {
            if (!otherActiveIds.isEmpty()) {
                jdbc.update("UPDATE stock SET is_active = 1 WHERE stock_id IN ("
                        + otherActiveIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("")
                        + ")", otherActiveIds.toArray());
            }
        }
    }

    // ---------- 6. Job-already-running conflict ----------

    @Test
    void backfill_secondCallWhileRunning_returns409() throws Exception {
        seedStock("T231", "併發測試一", true);
        seedStock("T232", "併發測試二", true);

        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);

        String url231 = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T231&start_date="
                + start + "&end_date=" + end;
        String url232 = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T232&start_date="
                + start + "&end_date=" + end;
        String body231 = finmindFixture("T231", new String[]{"2025-09-01"},
                new String[]{"1.00"}, new String[]{"1.10"}, new String[]{"0.90"}, new String[]{"1.05"});
        String body232 = finmindFixture("T232", new String[]{"2025-09-01"},
                new String[]{"2.00"}, new String[]{"2.10"}, new String[]{"1.90"}, new String[]{"2.05"});

        expectYahooNotFound("T231", start, end);
        mockServer.expect(requestTo(url231)).andRespond(withSuccess(body231, MediaType.APPLICATION_JSON));
        expectYahooNotFound("T232", start, end);
        mockServer.expect(requestTo(url232)).andRespond(withSuccess(body232, MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T231", "T232"));
        request.setStartDate(start);
        request.setEndDate(end);

        ResponseEntity<BackfillResponse> first = rest.postForEntity(
                "/api/stocks/sync/backfill", request, BackfillResponse.class);
        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());

        // fired immediately, while the first batch is (very likely) still running
        ResponseEntity<ErrorResponse> second = rest.postForEntity(
                "/api/stocks/sync/backfill", request, ErrorResponse.class);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertEquals("JOB_ALREADY_RUNNING", second.getBody().getCode());

        waitUntilProgressDone("PRICE_BACKFILL", Arrays.asList("T231", "T232"), 10_000);
    }

    @Test
    void backfill_whileStartupCatchUpJobInFlight_returns409() throws Exception {
        // Startup catch-up (StartupCatchUpRunner) never goes through this HTTP endpoint; it calls
        // StockSyncService directly. What it MUST share with this endpoint is the PRICE_BACKFILL
        // job lock: acquire it synchronously via JobRunningRegistry.tryStart, exactly as
        // StartupCatchUpRunner.onApplicationReady() now does, then start the batch through the
        // same lock-already-held entry point it uses (StockSyncService#startBackfillWithLockAlreadyHeld) --
        // never through startBackfill()/this controller. A CountDownLatch gates the mocked
        // external call so the "startup" job is deterministically still in flight (not merely
        // "probably still running", as the sibling manual-vs-manual test above tolerates) when the
        // concurrent manual call fires, and reports back the moment it has actually started so
        // this test never depends on sleep-based timing.
        seedStock("T261", "啟動補齊併發測試", true);

        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        String url = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T261&start_date=" + start + "&end_date=" + end;
        String body = finmindFixture("T261", new String[]{"2025-09-01"},
                new String[]{"1.00"}, new String[]{"1.10"}, new String[]{"0.90"}, new String[]{"1.05"});

        // Yahoo is priority now (spec: 來源順位), so the gate moves to Yahoo's request -- it's the
        // first (and here, only) external call this in-flight job makes before the concurrent
        // manual call fires; FinMind's success response, ungated, follows once Yahoo's 404 falls
        // through to it.
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        mockServer.expect(requestTo(yahooDailyUrl("T261", start, end))).andRespond(request -> {
            requestReceived.countDown();
            try {
                assertTrue(releaseResponse.await(10, TimeUnit.SECONDS),
                        "test did not release the gated in-flight response in time");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("interrupted while gating the in-flight response", e);
            }
            return withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\","
                            + "\"description\":\"No data found, symbol may be delisted\"}}}")
                    .createResponse(request);
        });
        mockServer.expect(requestTo(url)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertTrue(jobRunningRegistry.tryStart("PRICE_BACKFILL"), "test setup: registry should have been free");
        try {
            BackfillRequest startupLikeRequest = new BackfillRequest();
            startupLikeRequest.setStockIds(Arrays.asList("T261"));
            startupLikeRequest.setStartDate(start);
            startupLikeRequest.setEndDate(end);
            stockSyncService.startBackfillWithLockAlreadyHeld(startupLikeRequest);

            assertTrue(requestReceived.await(10, TimeUnit.SECONDS),
                    "in-flight startup-catch-up-like job never reached its external call");

            // Any manual call -- regardless of which stocks it targets -- must be rejected while
            // the PRICE_BACKFILL lock is held; omit stockIds (ALL mode) to prove that.
            BackfillRequest manualRequest = new BackfillRequest();
            manualRequest.setStartDate(start);
            manualRequest.setEndDate(end);
            ResponseEntity<ErrorResponse> manual = rest.postForEntity(
                    "/api/stocks/sync/backfill", manualRequest, ErrorResponse.class);

            assertEquals(HttpStatus.CONFLICT, manual.getStatusCode());
            assertEquals("JOB_ALREADY_RUNNING", manual.getBody().getCode());
        } finally {
            releaseResponse.countDown();
        }

        waitUntilProgressDone("PRICE_BACKFILL", Arrays.asList("T261"), 10_000);
    }

    // ---------- 7. Progress endpoint ----------

    @Test
    void progress_statusCountsSumToTotal() throws Exception {
        seedStock("T241", "進度測試一", true);
        seedStock("T242", "進度測試二", true);

        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);

        String url241 = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T241&start_date="
                + start + "&end_date=" + end;
        String url242 = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T242&start_date="
                + start + "&end_date=" + end;
        String body241 = finmindFixture("T241", new String[]{"2025-09-01"},
                new String[]{"5.00"}, new String[]{"5.10"}, new String[]{"4.90"}, new String[]{"5.05"});
        // T242 has no trading data in range at all -> must be marked SKIPPED
        String body242 = "{\"msg\":\"success\",\"status\":200,\"data\":[]}";

        expectYahooNotFound("T241", start, end);
        mockServer.expect(requestTo(url241)).andRespond(withSuccess(body241, MediaType.APPLICATION_JSON));
        expectYahooNotFound("T242", start, end);
        mockServer.expect(requestTo(url242)).andRespond(withSuccess(body242, MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T241", "T242"));
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);
        waitUntilProgressDone("PRICE_BACKFILL", Arrays.asList("T241", "T242"), 10_000);

        ResponseEntity<ProgressResponse> progress = rest.getForEntity(
                "/api/stocks/sync/progress?jobType=PRICE_BACKFILL", ProgressResponse.class);
        assertEquals(HttpStatus.OK, progress.getStatusCode());
        ProgressResponse body = progress.getBody();
        assertTrue(body.getTotal() >= 2);
        assertEquals(body.getTotal(),
                body.getPending() + body.getRunning() + body.getDone() + body.getFailed() + body.getSkipped());

        String t242Status = jdbc.queryForObject(
                "SELECT status FROM stock_sync_progress WHERE stock_id='T242' AND job_type='PRICE_BACKFILL'",
                String.class);
        assertEquals("SKIPPED", t242Status);
    }

    @Test
    void progress_lastSyncedAt_equalsMaxFinishedAtAmongDoneRows() throws Exception {
        seedStock("T243", "lastSyncedAt-最大值", true);
        // Far in the future so it is guaranteed to be the max finished_at across every real
        // PRICE_BACKFILL DONE row already in the live database, regardless of when this test runs.
        LocalDateTime future = LocalDateTime.now().plusYears(50).withNano(0);
        jdbc.update("INSERT INTO stock_sync_progress "
                        + "(stock_id, job_type, status, target_start_date, target_end_date, last_synced_date, "
                        + "attempt_count, last_error, started_at, finished_at) "
                        + "VALUES ('T243', 'PRICE_BACKFILL', 'DONE', '2025-09-01', '2025-09-01', '2025-09-01', "
                        + "0, NULL, ?, ?)",
                future, future);

        LocalDateTime expectedMax = jdbc.queryForObject(
                "SELECT MAX(finished_at) FROM stock_sync_progress WHERE job_type = 'PRICE_BACKFILL' AND status = 'DONE'",
                LocalDateTime.class);
        assertEquals(future, expectedMax);

        ResponseEntity<ProgressResponse> progress = rest.getForEntity(
                "/api/stocks/sync/progress?jobType=PRICE_BACKFILL", ProgressResponse.class);
        assertEquals(HttpStatus.OK, progress.getStatusCode());
        assertEquals(expectedMax, progress.getBody().getLastSyncedAt());
    }

    @Test
    @Transactional
    void progress_lastSyncedAt_isNull_whenJobTypeHasNeverCompletedARun() {
        // Rolled back automatically at the end of this test (see @Transactional): temporarily
        // clears away every DONE row's completion for PRICE_BACKFILL so the "never successfully
        // synced" branch can be observed without permanently touching the live DONE rows that
        // exist outside this test.
        jdbc.update("UPDATE stock_sync_progress SET status = 'FAILED', finished_at = NULL "
                + "WHERE job_type = 'PRICE_BACKFILL' AND status = 'DONE'");

        ProgressResponse progress = stockSyncService.getProgress("PRICE_BACKFILL");
        assertNull(progress.getLastSyncedAt());
    }

    @Test
    void progress_lastSyncedAt_unchanged_whenBackfillRunFailsCompletely() throws Exception {
        ProgressResponse before = rest.getForEntity(
                "/api/stocks/sync/progress?jobType=PRICE_BACKFILL", ProgressResponse.class).getBody();
        LocalDateTime lastSyncedAtBefore = before.getLastSyncedAt();

        seedStock("T244", "全部失敗不推進", true);
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        String url = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T244&start_date="
                + start + "&end_date=" + end;
        expectYahooNotFound("T244", start, end);
        // max-retries=3 in test config -> 4 total attempts; queue a timeout for every one so the
        // stock ends up FAILED without ever reaching DONE. (429/403 are now block-class and
        // switch source instead of exhausting retries into FAILED -- see
        // StockPriceIngestionMultiSourceIntegrationTest for that behavior; a timeout is what still
        // exhausts same-source backoff-retry into FAILED here.)
        for (int i = 0; i < 4; i++) {
            mockServer.expect(requestTo(url)).andRespond(request -> {
                throw new IOException("simulated timeout");
            });
        }

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T244"));
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        waitUntilStatus("T244", "PRICE_BACKFILL", "FAILED", 10_000);
        mockServer.verify();

        ProgressResponse after = rest.getForEntity(
                "/api/stocks/sync/progress?jobType=PRICE_BACKFILL", ProgressResponse.class).getBody();
        assertEquals(lastSyncedAtBefore, after.getLastSyncedAt());
    }

    // ---------- 8. catchUp mode ----------

    @Test
    void catchUp_reopensLaggingDoneStock_fetchesOnlyGapAfterLastSyncedDate_andRecordsEndDateAsLastSynced()
            throws Exception {
        seedStock("T251", "追趕-落後標的", true);
        seedProgress("T251", "DONE", LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 5),
                LocalDate.of(2025, 9, 5), 2, "stale error from a previous run");

        LocalDate requestStart = LocalDate.of(2025, 9, 1); // must be ignored: stock already has progress
        LocalDate requestEnd = LocalDate.of(2025, 9, 10);
        LocalDate gapStart = LocalDate.of(2025, 9, 6); // last_synced_date + 1
        String gapUrl = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T251&start_date="
                + gapStart + "&end_date=" + requestEnd;
        String body = finmindFixture("T251", new String[]{"2025-09-08"},
                new String[]{"40.00"}, new String[]{"42.00"}, new String[]{"39.50"}, new String[]{"41.00"});
        // Only the gap URL is registered: a request for the full 2025-09-01..2025-09-10 range
        // (i.e. re-fetching the already-synced part) would fail with "No further requests expected".
        expectYahooNotFound("T251", gapStart, requestEnd);
        mockServer.expect(requestTo(gapUrl)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T251"));
        request.setStartDate(requestStart);
        request.setEndDate(requestEnd);
        request.setCatchUp(true);

        ResponseEntity<BackfillResponse> response = rest.postForEntity(
                "/api/stocks/sync/backfill", request, BackfillResponse.class);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

        waitUntilStatus("T251", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();

        StockSyncProgress progress = progressMapper.findOne("T251", "PRICE_BACKFILL");
        // last_synced_date is the *requested* endDate, not 2025-09-08 (the last row actually written).
        assertEquals(requestEnd, progress.getLastSyncedDate());
        assertEquals(0, progress.getAttemptCount()); // reset on catchUp reopen, not carried over from before
        assertNull(progress.getLastError());

        Integer priceRowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T251'", Integer.class);
        assertEquals(1, priceRowCount);
    }

    @Test
    void catchUp_stockAlreadySyncedThroughEndDate_makesNoExternalRequest_andLeavesProgressUntouched()
            throws Exception {
        seedStock("T252", "追趕-已補齊標的", true);
        LocalDate lastSynced = LocalDate.of(2025, 9, 10);
        seedProgress("T252", "DONE", LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 10),
                lastSynced, 0, null);

        // No mockServer.expect(...) registered at all: any external request at all fails the test.
        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T252"));
        request.setStartDate(LocalDate.of(2025, 9, 1));
        request.setEndDate(lastSynced); // equal to last_synced_date -> must be skipped entirely
        request.setCatchUp(true);

        ResponseEntity<BackfillResponse> response = rest.postForEntity(
                "/api/stocks/sync/backfill", request, BackfillResponse.class);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

        waitUntilNotRunning(5_000);
        mockServer.verify(); // no expectations registered; would only fail if verify() itself errors

        StockSyncProgress progress = progressMapper.findOne("T252", "PRICE_BACKFILL");
        assertEquals("DONE", progress.getStatus());
        assertEquals(lastSynced, progress.getLastSyncedDate());
        assertEquals(0, progress.getAttemptCount());
    }

    @Test
    void catchUp_endDateOnWeekend_recordsLastSyncedDateAsEndDate_andRepeatCatchUpMakesNoRequest()
            throws Exception {
        seedStock("T253", "追趕-週末迄日", true);

        LocalDate start = LocalDate.of(2025, 9, 4);
        LocalDate weekendEnd = LocalDate.of(2025, 9, 6); // Saturday: no trading data for this range
        String url = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T253&start_date="
                + start + "&end_date=" + weekendEnd;
        String emptyBody = "{\"msg\":\"success\",\"status\":200,\"data\":[]}";
        expectYahooNotFound("T253", start, weekendEnd);
        mockServer.expect(requestTo(url)).andRespond(withSuccess(emptyBody, MediaType.APPLICATION_JSON));

        BackfillRequest first = new BackfillRequest();
        first.setStockIds(Arrays.asList("T253"));
        first.setStartDate(start);
        first.setEndDate(weekendEnd);
        first.setCatchUp(true);
        rest.postForEntity("/api/stocks/sync/backfill", first, BackfillResponse.class);

        waitUntilStatus("T253", "PRICE_BACKFILL", "SKIPPED", 10_000);
        mockServer.verify();

        StockSyncProgress afterFirst = progressMapper.findOne("T253", "PRICE_BACKFILL");
        assertEquals(weekendEnd, afterFirst.getLastSyncedDate());

        // Second catchUp call with the identical endDate must make zero external requests.
        mockServer.reset();
        BackfillRequest second = new BackfillRequest();
        second.setStockIds(Arrays.asList("T253"));
        second.setStartDate(start);
        second.setEndDate(weekendEnd);
        second.setCatchUp(true);
        ResponseEntity<BackfillResponse> secondResponse = rest.postForEntity(
                "/api/stocks/sync/backfill", second, BackfillResponse.class);
        assertEquals(HttpStatus.ACCEPTED, secondResponse.getStatusCode());

        waitUntilNotRunning(5_000);
        mockServer.verify();

        StockSyncProgress afterSecond = progressMapper.findOne("T253", "PRICE_BACKFILL");
        assertEquals(weekendEnd, afterSecond.getLastSyncedDate());
        assertEquals("SKIPPED", afterSecond.getStatus());
    }

    @Test
    void backfill_resumeAndCatchUpBothTrue_returns400InvalidSyncMode() {
        BackfillRequest request = new BackfillRequest();
        request.setStartDate(LocalDate.of(2025, 9, 1));
        request.setEndDate(LocalDate.of(2025, 9, 2));
        request.setResume(true);
        request.setCatchUp(true);

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/sync/backfill", request, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_SYNC_MODE", response.getBody().getCode());
    }

    // ---------- 9. Timezone alignment (Asia/Taipei end to end) ----------

    @Test
    void timezone_progressFinishedAt_matchesApplicationLocalNow_withinOneMinute() throws Exception {
        seedStock("T271", "時區-finishedAt", true);
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        String url = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T271&start_date="
                + start + "&end_date=" + end;
        String body = finmindFixture("T271", new String[]{"2025-09-01"},
                new String[]{"10.00"}, new String[]{"10.50"}, new String[]{"9.50"}, new String[]{"10.20"});
        expectYahooNotFound("T271", start, end);
        mockServer.expect(requestTo(url)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T271"));
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        waitUntilStatus("T271", "PRICE_BACKFILL", "DONE", 10_000);

        LocalDateTime finishedAt = jdbc.queryForObject(
                "SELECT finished_at FROM stock_sync_progress WHERE stock_id = 'T271' AND job_type = 'PRICE_BACKFILL'",
                LocalDateTime.class);
        LocalDateTime appNow = LocalDateTime.now();
        // Regression guard for the historical DB(UTC)/app(host default) timezone mismatch: an
        // 8-hour offset would fail this by ~8 hours, far outside the 1-minute tolerance.
        Duration diff = Duration.between(finishedAt, appNow).abs();
        assertTrue(diff.toMinutes() < 1, "finished_at (" + finishedAt + ") should be within one "
                + "minute of the application's local clock (" + appNow + "); diff=" + diff);
    }

    @Test
    void timezone_progressLastSyncedAt_matchesApplicationLocalNow_noEightHourOffset() throws Exception {
        seedStock("T272", "時區-lastSyncedAt", true);
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        String url = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T272&start_date="
                + start + "&end_date=" + end;
        String body = finmindFixture("T272", new String[]{"2025-09-01"},
                new String[]{"11.00"}, new String[]{"11.50"}, new String[]{"10.50"}, new String[]{"11.20"});
        expectYahooNotFound("T272", start, end);
        mockServer.expect(requestTo(url)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T272"));
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        waitUntilStatus("T272", "PRICE_BACKFILL", "DONE", 10_000);

        ResponseEntity<ProgressResponse> progress = rest.getForEntity(
                "/api/stocks/sync/progress?jobType=PRICE_BACKFILL", ProgressResponse.class);
        LocalDateTime lastSyncedAt = progress.getBody().getLastSyncedAt();
        LocalDateTime appNow = LocalDateTime.now();
        Duration diff = Duration.between(lastSyncedAt, appNow).abs();
        assertTrue(diff.toMinutes() < 1, "lastSyncedAt (" + lastSyncedAt + ") from a sync that just "
                + "completed should read as the application's local now (" + appNow + "), not offset "
                + "by 8 hours; diff=" + diff);
    }

    // ---------- 10. caughtUpCount on the 202 response ----------

    @Test
    void backfill_catchUp_allTargetsAlreadyCaughtUp_caughtUpCountEqualsTargetCount_zeroExternalRequests()
            throws Exception {
        seedStock("T281", "caughtUpCount-已補齊一", true);
        seedStock("T282", "caughtUpCount-已補齊二", true);
        LocalDate endDate = LocalDate.of(2025, 9, 10);
        seedProgress("T281", "DONE", LocalDate.of(2025, 9, 1), endDate, endDate, 0, null);
        seedProgress("T282", "DONE", LocalDate.of(2025, 9, 1), endDate, endDate, 0, null);

        // No mockServer.expect(...) registered at all: any external request at all fails the test.
        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T281", "T282"));
        request.setStartDate(LocalDate.of(2025, 9, 1));
        request.setEndDate(endDate);
        request.setCatchUp(true);

        ResponseEntity<BackfillResponse> response = rest.postForEntity(
                "/api/stocks/sync/backfill", request, BackfillResponse.class);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(2, response.getBody().getTargetCount());
        assertEquals(2, response.getBody().getCaughtUpCount());

        waitUntilNotRunning(5_000);
        mockServer.verify();
    }

    @Test
    void backfill_catchUp_partiallyCaughtUp_caughtUpCountEqualsCaughtUpSubset_laggingStockStillSynced()
            throws Exception {
        seedStock("T283", "caughtUpCount-已補齊", true);
        seedStock("T284", "caughtUpCount-落後", true);
        LocalDate endDate = LocalDate.of(2025, 9, 10);
        seedProgress("T283", "DONE", LocalDate.of(2025, 9, 1), endDate, endDate, 0, null);
        seedProgress("T284", "DONE", LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 5),
                LocalDate.of(2025, 9, 5), 1, null);

        LocalDate gapStart = LocalDate.of(2025, 9, 6);
        String gapUrl = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T284&start_date="
                + gapStart + "&end_date=" + endDate;
        String body = finmindFixture("T284", new String[]{"2025-09-08"},
                new String[]{"20.00"}, new String[]{"21.00"}, new String[]{"19.50"}, new String[]{"20.50"});
        expectYahooNotFound("T284", gapStart, endDate);
        mockServer.expect(requestTo(gapUrl)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T283", "T284"));
        request.setStartDate(LocalDate.of(2025, 9, 1));
        request.setEndDate(endDate);
        request.setCatchUp(true);

        ResponseEntity<BackfillResponse> response = rest.postForEntity(
                "/api/stocks/sync/backfill", request, BackfillResponse.class);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(2, response.getBody().getTargetCount());
        assertEquals(1, response.getBody().getCaughtUpCount());

        waitUntilStatus("T284", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify(); // T283 never requested; T284's gap request was made exactly once

        StockSyncProgress t283 = progressMapper.findOne("T283", "PRICE_BACKFILL");
        assertEquals(endDate, t283.getLastSyncedDate());
        assertEquals(0, t283.getAttemptCount());
    }

    @Test
    void backfill_catchUpFalse_caughtUpCountAlwaysZero_evenWhenStockAlreadyCaughtUp() throws Exception {
        seedStock("T285", "caughtUpCount-catchUp關閉", true);
        LocalDate endDate = LocalDate.of(2025, 9, 1);
        seedProgress("T285", "DONE", endDate, endDate, endDate, 0, null);

        String url = finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=T285&start_date="
                + endDate + "&end_date=" + endDate;
        String body = finmindFixture("T285", new String[]{"2025-09-01"},
                new String[]{"5.00"}, new String[]{"5.50"}, new String[]{"4.50"}, new String[]{"5.20"});
        expectYahooNotFound("T285", endDate, endDate);
        mockServer.expect(requestTo(url)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T285"));
        request.setStartDate(endDate);
        request.setEndDate(endDate);
        // catchUp omitted -> defaults to false, so the stock is fully reset and refetched
        // regardless of last_synced_date; caughtUpCount must still read 0.

        ResponseEntity<BackfillResponse> response = rest.postForEntity(
                "/api/stocks/sync/backfill", request, BackfillResponse.class);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(0, response.getBody().getCaughtUpCount());

        waitUntilStatus("T285", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();
    }

    // ---------- 11. Empty target list (ALL mode, zero is_active=1 stocks) ----------

    @Test
    void backfill_allMode_noActiveStocks_catchUpTrue_targetCountZero_completesWithoutException_noExternalRequest()
            throws Exception {
        List<String> previouslyActiveIds = deactivateAllStocks();
        try {
            // No mockServer.expect(...) registered at all: any external request fails the test.
            // This is the exact reported bug reproduction: findCaughtUpStockIds(jobType, [], endDate)
            // previously threw BadSqlGrammarException from a truncated "... AND stock_id IN" when
            // ALL mode resolved to zero targets (spec: 目標清單為空是合法情形).
            BackfillRequest request = new BackfillRequest();
            request.setStartDate(LocalDate.of(2026, 1, 1));
            request.setEndDate(LocalDate.of(2026, 8, 31));
            request.setCatchUp(true);
            // stockIds omitted -> ALL mode -> empty target list

            ResponseEntity<BackfillResponse> response = rest.postForEntity(
                    "/api/stocks/sync/backfill", request, BackfillResponse.class);

            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
            assertEquals("ALL", response.getBody().getMode());
            assertEquals(0, response.getBody().getTargetCount());
            assertEquals(0, response.getBody().getCaughtUpCount());

            waitUntilNotRunning(5_000);
            mockServer.verify();
        } finally {
            restoreActiveStocks(previouslyActiveIds);
        }
    }

    @Test
    void backfill_allMode_noActiveStocks_catchUpFalse_targetCountZero_completesWithoutException_noExternalRequest()
            throws Exception {
        List<String> previouslyActiveIds = deactivateAllStocks();
        try {
            // Reproduces the same empty-collection <foreach> hazard, but on the resume=false/
            // catchUp=false reset path (upsertPendingReset's INSERT ... VALUES <foreach>, which -
            // unguarded - would otherwise leave a dangling "VALUES" with zero tuples).
            BackfillRequest request = new BackfillRequest();
            request.setStartDate(LocalDate.of(2026, 1, 1));
            request.setEndDate(LocalDate.of(2026, 8, 31));

            ResponseEntity<BackfillResponse> response = rest.postForEntity(
                    "/api/stocks/sync/backfill", request, BackfillResponse.class);

            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
            assertEquals(0, response.getBody().getTargetCount());
            assertEquals(0, response.getBody().getCaughtUpCount());

            waitUntilNotRunning(5_000);
            mockServer.verify();
        } finally {
            restoreActiveStocks(previouslyActiveIds);
        }
    }

    @Test
    void startupCatchUpPath_noActiveStocks_locksAndCompletesWithoutError() throws Exception {
        // Reproduces the exact call StartupCatchUpRunner.onApplicationReady() makes (lock acquired
        // synchronously, then startBackfillWithLockAlreadyHeld) against a database with zero
        // is_active=1 stocks -- e.g. a freshly /reset-env'd database whose seed data hasn't landed
        // yet. Must not throw, and must release the lock once the (empty) batch settles.
        List<String> previouslyActiveIds = deactivateAllStocks();
        try {
            String jobType = "PRICE_BACKFILL";
            assertTrue(jobRunningRegistry.tryStart(jobType));

            BackfillRequest request = new BackfillRequest();
            request.setStartDate(LocalDate.of(2026, 1, 1));
            request.setEndDate(LocalDate.now());
            request.setCatchUp(true);

            assertDoesNotThrow(() -> stockSyncService.startBackfillWithLockAlreadyHeld(request));

            waitUntilNotRunning(5_000);
            assertFalse(jobRunningRegistry.isRunning(jobType));
            mockServer.verify();
        } finally {
            restoreActiveStocks(previouslyActiveIds);
        }
    }

    // ---------- helpers ----------

    private void seedStock(String stockId, String name, boolean active) {
        Stock s = new Stock(stockId, name, "TSE", active);
        stockMapper.upsert(s);
    }

    /**
     * Deactivates every row currently in `stock` (test methods that seed T-prefixed stocks always
     * clean them up beforehand via {@link #cleanupTestData()}, so in practice this only touches
     * the live seed data) and returns the ids that were active beforehand, for
     * {@link #restoreActiveStocks} to restore afterward. Used by the "ALL mode with zero
     * is_active=1 stocks" tests, which must never leave the live database's real stocks
     * permanently deactivated.
     */
    private List<String> deactivateAllStocks() {
        List<String> previouslyActiveIds = jdbc.queryForList(
                "SELECT stock_id FROM stock WHERE is_active = 1", String.class);
        jdbc.update("UPDATE stock SET is_active = 0");
        return previouslyActiveIds;
    }

    private void restoreActiveStocks(List<String> ids) {
        if (!ids.isEmpty()) {
            jdbc.update("UPDATE stock SET is_active = 1 WHERE stock_id IN ("
                    + ids.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("")
                    + ")", ids.toArray());
        }
    }

    /** Directly seeds a stock_sync_progress row to simulate a pre-existing PRICE_BACKFILL state. */
    private void seedProgress(String stockId, String status, LocalDate targetStartDate, LocalDate targetEndDate,
                               LocalDate lastSyncedDate, int attemptCount, String lastError) {
        jdbc.update("INSERT INTO stock_sync_progress "
                        + "(stock_id, job_type, status, target_start_date, target_end_date, last_synced_date, "
                        + "attempt_count, last_error, started_at, finished_at) "
                        + "VALUES (?, 'PRICE_BACKFILL', ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                stockId, status, targetStartDate, targetEndDate, lastSyncedDate, attemptCount, lastError);
    }

    /**
     * Since this increment, Yahoo is the priority per-stock history source (spec: 來源順位 —
     * YAHOO -> FINMIND) and every real request now goes through it first. This test file's
     * stocks are synthetic (T-prefixed) and don't exist on Yahoo, so every test below registers
     * a 404 for the Yahoo URL before its existing FinMind expectation(s) -- exactly the ambiguous
     * "not found" case the spec describes (spec: Yahoo 的 404 帶有歧義), which correctly falls
     * through to FinMind, leaving every existing assertion on FinMind's data untouched. See
     * StockPriceIngestionMultiSourceIntegrationTest for the dedicated multi-source scenarios
     * (block/switch, 404-on-every-source, all-sources-blocked, etc).
     */
    private String yahooDailyUrl(String stockId, LocalDate startDate, LocalDate endDate) {
        long period1 = startDate.atStartOfDay(TAIPEI).toEpochSecond();
        long period2 = endDate.plusDays(1).atStartOfDay(TAIPEI).toEpochSecond();
        return yahooBaseUrl + "/" + stockId + ".TW?interval=1d&period1=" + period1 + "&period2=" + period2;
    }

    private void expectYahooNotFound(String stockId, LocalDate startDate, LocalDate endDate) {
        mockServer.expect(requestTo(yahooDailyUrl(stockId, startDate, endDate)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\","
                                + "\"description\":\"No data found, symbol may be delisted\"}}}"));
    }

    private String finmindFixture(String stockId, String[] dates, String[] opens, String[] maxes,
                                   String[] mins, String[] closes) {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < dates.length; i++) {
            if (i > 0) {
                data.append(",");
            }
            data.append("{\"date\":\"").append(dates[i]).append("\",\"stock_id\":\"").append(stockId)
                    .append("\",\"Trading_Volume\":1000,\"Trading_money\":100000,")
                    .append("\"open\":").append(opens[i]).append(",\"max\":").append(maxes[i])
                    .append(",\"min\":").append(mins[i]).append(",\"close\":").append(closes[i])
                    .append(",\"Trading_turnover\":10}");
        }
        return "{\"msg\":\"success\",\"status\":200,\"data\":[" + data + "]}";
    }

    private void waitUntilProgressDone(String jobType, List<String> stockIds, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allSettled = true;
            for (String id : stockIds) {
                String status = jdbc.queryForObject(
                        "SELECT status FROM stock_sync_progress WHERE stock_id = ? AND job_type = ?",
                        String.class, id, jobType);
                if (status == null || status.equals("PENDING") || status.equals("RUNNING")) {
                    allSettled = false;
                    break;
                }
            }
            if (allSettled) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError("Timed out waiting for progress to settle for " + stockIds);
    }

    private void waitUntilStatus(String stockId, String jobType, String expectedStatus, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = jdbc.queryForObject(
                    "SELECT status FROM stock_sync_progress WHERE stock_id = ? AND job_type = ?",
                    String.class, stockId, jobType);
            if (expectedStatus.equals(last)) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + stockId + " to reach " + expectedStatus
                + " (last seen: " + last + ")");
    }
}
