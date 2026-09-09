package com.stock;

import com.stock.domain.Stock;
import com.stock.domain.StockSyncProgress;
import com.stock.dto.BackfillRequest;
import com.stock.dto.BackfillResponse;
import com.stock.dto.DailySyncRequest;
import com.stock.dto.DailySyncResponse;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockSyncProgressMapper;
import com.stock.service.JobRunningRegistry;
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
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * End-to-end coverage for "every path that writes a trading day's prices must advance
 * last_synced_date" (spec: specs/backend/stock-price-ingestion.md, 寫入行情的路徑都必須推進進度（本次新增）) —
 * specifically the daily-increment path, which previously wrote stock_daily_price without ever
 * touching stock_sync_progress, causing startup catch-up to reopen the whole population every
 * trading day.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockDailyIncrementProgressAdvanceIntegrationTest {

    private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("externalApiRestTemplate")
    private RestTemplate externalApiRestTemplate;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockSyncProgressMapper progressMapper;

    @Autowired
    private JobRunningRegistry jobRunningRegistry;

    @Autowired
    private SourceAvailabilityTracker sourceAvailabilityTracker;

    @Autowired
    private DataSource dataSource;

    @Value("${app.external.twse-daily-all-url}")
    private String twseUrl;

    @Value("${app.external.twse-mi-index-url}")
    private String miIndexBaseUrl;

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
        jdbc.update("DELETE FROM stock_sync_progress WHERE stock_id LIKE 'T5%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'T5%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'T5%'");
    }

    // ---------- 1. Daily sync advances an EXISTING progress row to the snapshot's actual trade date ----------

    @Test
    void dailySync_advancesExistingProgressRow_toSnapshotsActualTradeDate_notSystemToday() throws Exception {
        seedStock("T501", "每日增量推進一", true);
        seedProgress("T501", "DONE", LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 29),
                LocalDate.of(2025, 8, 29), 0, null);

        // ROC 1140901 -> western 2025-09-01, deliberately not "today" -- proves the date used comes
        // from the snapshot response, never the system clock.
        String fixture = twseFixture("T501", "1140901", "10.00", "10.50", "9.50", "10.20");
        mockServer.expect(requestTo(twseUrl)).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        ResponseEntity<DailySyncResponse> response = rest.postForEntity(
                "/api/stocks/sync/daily", new DailySyncRequest(), DailySyncResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(LocalDate.of(2025, 9, 1), response.getBody().getTradeDate());

        StockSyncProgress progress = progressMapper.findOne("T501", "PRICE_BACKFILL");
        assertEquals(LocalDate.of(2025, 9, 1), progress.getLastSyncedDate());
        assertEquals("DONE", progress.getStatus()); // status itself is untouched, only the date moves
    }

    // ---------- 2. Daily sync never creates a NEW progress row ----------

    @Test
    void dailySync_neverCreatesNewProgressRow_forStockWithNoExistingRow() throws Exception {
        seedStock("T502", "每日增量不新建", true);
        // No pre-existing stock_sync_progress row for T502 at all.

        String fixture = twseFixture("T502", "1140901", "20.00", "20.50", "19.50", "20.20");
        mockServer.expect(requestTo(twseUrl)).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        rest.postForEntity("/api/stocks/sync/daily", new DailySyncRequest(), DailySyncResponse.class);

        Integer progressRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_sync_progress WHERE stock_id = 'T502' AND job_type = 'PRICE_BACKFILL'",
                Integer.class);
        assertEquals(0, progressRows);
        // The price row itself IS written (daily sync's existing, unchanged responsibility).
        Integer priceRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T502'", Integer.class);
        assertEquals(1, priceRows);
    }

    // ---------- 3. Daily sync never regresses an already-ahead progress row ----------

    @Test
    void dailySync_neverRegressesProgressRow_thatIsAlreadyAheadOfTheSnapshotDate() throws Exception {
        seedStock("T503", "每日增量不倒退", true);
        LocalDate ahead = LocalDate.of(2025, 9, 10);
        seedProgress("T503", "DONE", LocalDate.of(2025, 8, 1), ahead, ahead, 0, null);

        String fixture = twseFixture("T503", "1140901", "30.00", "30.50", "29.50", "30.20"); // 2025-09-01, EARLIER
        mockServer.expect(requestTo(twseUrl)).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        rest.postForEntity("/api/stocks/sync/daily", new DailySyncRequest(), DailySyncResponse.class);

        StockSyncProgress progress = progressMapper.findOne("T503", "PRICE_BACKFILL");
        assertEquals(ahead, progress.getLastSyncedDate()); // unchanged, not rolled back to 2025-09-01
    }

    // ---------- 4. End-to-end: daily sync then catchUp makes zero external requests ----------

    @Test
    void endToEnd_dailySyncThenCatchUp_caughtUpCountEqualsTargetCount_zeroExternalRequests() throws Exception {
        seedStock("T504", "端到端推進", true);
        seedProgress("T504", "DONE", LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 29),
                LocalDate.of(2025, 8, 29), 0, null);

        String fixture = twseFixture("T504", "1140901", "40.00", "40.50", "39.50", "40.20"); // 2025-09-01
        mockServer.expect(requestTo(twseUrl)).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));
        rest.postForEntity("/api/stocks/sync/daily", new DailySyncRequest(), DailySyncResponse.class);

        StockSyncProgress afterDaily = progressMapper.findOne("T504", "PRICE_BACKFILL");
        assertEquals(LocalDate.of(2025, 9, 1), afterDaily.getLastSyncedDate());

        // No MI_INDEX (or Yahoo/FinMind) expectation registered at all: catchUp must make zero
        // external requests since T504 is now caught up through 2025-09-01.
        mockServer.reset();

        BackfillRequest catchUpRequest = new BackfillRequest();
        catchUpRequest.setStartDate(LocalDate.of(2025, 8, 1));
        catchUpRequest.setEndDate(LocalDate.of(2025, 9, 1));
        catchUpRequest.setCatchUp(true);
        catchUpRequest.setCommonStocksOnly(false);

        List<String> otherActiveIds = deactivateOtherActiveStocks();
        try {
            ResponseEntity<BackfillResponse> response = rest.postForEntity(
                    "/api/stocks/sync/backfill", catchUpRequest, BackfillResponse.class);
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
            assertEquals(1, response.getBody().getTargetCount());
            assertEquals(1, response.getBody().getCaughtUpCount());

            waitUntilNotRunning(5_000);
            mockServer.verify(); // no expectations registered at all -- any request fails this
        } finally {
            restoreActiveStocks(otherActiveIds);
        }
    }

    // ---------- 5. Weekend restart: population reopens, but costs exactly 1 request ----------

    @Test
    void weekendRestart_lastSyncedFriday_endDateSaturday_populationReopens_butOnlyOneRequest_lastSyncedAdvancesToSaturday()
            throws Exception {
        seedStock("T505", "週末重啟", true);
        LocalDate friday = LocalDate.of(2025, 9, 5);
        LocalDate saturday = LocalDate.of(2025, 9, 6);
        seedProgress("T505", "DONE", LocalDate.of(2025, 8, 1), friday, friday, 0, null);

        // Exactly 1 MI_INDEX request for Saturday, empty (non-trading day) response.
        mockServer.expect(requestTo(miIndexUrl(saturday)))
                .andRespond(withSuccess(miIndexNonTradingDayFixture(), MediaType.APPLICATION_JSON));

        BackfillRequest catchUpRequest = new BackfillRequest();
        catchUpRequest.setStartDate(LocalDate.of(2025, 8, 1));
        catchUpRequest.setEndDate(saturday);
        catchUpRequest.setCatchUp(true);
        catchUpRequest.setCommonStocksOnly(false);

        List<String> otherActiveIds = deactivateOtherActiveStocks();
        try {
            ResponseEntity<BackfillResponse> response = rest.postForEntity(
                    "/api/stocks/sync/backfill", catchUpRequest, BackfillResponse.class);
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
            assertEquals(1, response.getBody().getTargetCount());
            assertEquals(0, response.getBody().getCaughtUpCount()); // Friday < Saturday -> reopened

            waitUntilStatus("T505", "PRICE_BACKFILL", "DONE", 10_000);
            mockServer.verify(); // exactly the 1 Saturday MI_INDEX request happened

            StockSyncProgress after = progressMapper.findOne("T505", "PRICE_BACKFILL");
            assertEquals(saturday, after.getLastSyncedDate());
        } finally {
            restoreActiveStocks(otherActiveIds);
        }
    }

    // ---------- helpers ----------

    private void seedStock(String stockId, String name, boolean active) {
        stockMapper.upsert(new Stock(stockId, name, "TSE", active));
    }

    private void seedProgress(String stockId, String status, LocalDate targetStartDate, LocalDate targetEndDate,
                               LocalDate lastSyncedDate, int attemptCount, String lastError) {
        jdbc.update("INSERT INTO stock_sync_progress "
                        + "(stock_id, job_type, status, target_start_date, target_end_date, last_synced_date, "
                        + "attempt_count, last_error, started_at, finished_at) "
                        + "VALUES (?, 'PRICE_BACKFILL', ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                stockId, status, targetStartDate, targetEndDate, lastSyncedDate, attemptCount, lastError);
    }

    private List<String> deactivateOtherActiveStocks() {
        List<String> otherActiveIds = jdbc.queryForList(
                "SELECT stock_id FROM stock WHERE is_active = 1 AND stock_id NOT LIKE 'T5%'", String.class);
        jdbc.update("UPDATE stock SET is_active = 0 WHERE stock_id NOT LIKE 'T5%'");
        return otherActiveIds;
    }

    private void restoreActiveStocks(List<String> ids) {
        if (!ids.isEmpty()) {
            jdbc.update("UPDATE stock SET is_active = 1 WHERE stock_id IN ("
                    + ids.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("") + ")", ids.toArray());
        }
    }

    private String twseFixture(String stockId, String rocDate, String open, String high, String low, String close) {
        return "[{\"Date\":\"" + rocDate + "\",\"Code\":\"" + stockId + "\",\"Name\":\"測試股\","
                + "\"TradeVolume\":\"1000\",\"TradeValue\":\"100000\",\"OpeningPrice\":\"" + open
                + "\",\"HighestPrice\":\"" + high + "\",\"LowestPrice\":\"" + low + "\",\"ClosingPrice\":\"" + close
                + "\",\"Transaction\":\"10\"}]";
    }

    private String miIndexUrl(LocalDate date) {
        return miIndexBaseUrl + "?date=" + date.format(DATE_PARAM) + "&type=ALL&response=json";
    }

    private String miIndexNonTradingDayFixture() {
        return "{\"tables\":[{\"fields\":[\"指數\",\"收盤指數\"],\"data\":[]}]}";
    }

    private void waitUntilNotRunning(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (jobRunningRegistry.isRunning("PRICE_BACKFILL") && System.currentTimeMillis() < deadline) {
            sleep(50);
        }
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

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
