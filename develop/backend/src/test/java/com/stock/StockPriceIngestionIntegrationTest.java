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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

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
    private DataSource dataSource;

    @Value("${app.external.twse-daily-all-url}")
    private String twseUrl;

    @Value("${app.external.finmind-base-url}")
    private String finmindBaseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        waitUntilNotRunning(10_000);
        mockServer = MockRestServiceServer.bindTo(externalApiRestTemplate).ignoreExpectOrder(false).build();
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        waitUntilNotRunning(15_000);
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
    void backfill_selectedMode_onlyProcessesGivenIds_retriesOn429_andIsIdempotent() throws Exception {
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

        // T201: first attempt rate-limited (429), retried once and succeeds
        mockServer.expect(requestTo(finmindUrlT201)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        mockServer.expect(requestTo(finmindUrlT201)).andRespond(withSuccess(t201Body, MediaType.APPLICATION_JSON));
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
        mockServer.expect(requestTo(finmindUrlT201)).andRespond(withSuccess(t201Body, MediaType.APPLICATION_JSON));
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

        mockServer.expect(requestTo(finmindUrl211)).andRespond(withSuccess(body211, MediaType.APPLICATION_JSON));
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

        mockServer.expect(requestTo(url231)).andRespond(withSuccess(body231, MediaType.APPLICATION_JSON));
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

        mockServer.expect(requestTo(url241)).andRespond(withSuccess(body241, MediaType.APPLICATION_JSON));
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

    // ---------- helpers ----------

    private void seedStock(String stockId, String name, boolean active) {
        Stock s = new Stock(stockId, name, "TSE", active);
        stockMapper.upsert(s);
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
