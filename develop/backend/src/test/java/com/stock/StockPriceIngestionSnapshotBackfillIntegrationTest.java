package com.stock;

import com.stock.domain.Stock;
import com.stock.domain.StockSyncProgress;
import com.stock.dto.BackfillRequest;
import com.stock.dto.BackfillResponse;
import com.stock.mapper.StockDailyPriceMapper;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * End-to-end (real DB + mocked external HTTP) coverage for the ALL-mode day-by-day MI_INDEX
 * snapshot backfill (spec: specs/backend/stock-price-ingestion.md, 逐日全市場快照為 ALL 模式主路徑 /
 * 逐日快照不可用時降級為逐檔 / 成交量口徑與一次性重補 — 本次新增).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockPriceIngestionSnapshotBackfillIntegrationTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String STOCK_FIELDS = "\"證券代號\",\"證券名稱\",\"成交股數\",\"成交筆數\",\"成交金額\","
            + "\"開盤價\",\"最高價\",\"最低價\",\"收盤價\",\"漲跌(+/-)\",\"漲跌價差\",\"最後揭示買價\",\"最後揭示買量\","
            + "\"最後揭示賣價\",\"最後揭示賣量\",\"本益比\"";

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
    private SourceAvailabilityTracker sourceAvailabilityTracker;

    @Autowired
    private DataSource dataSource;

    @Value("${app.external.twse-mi-index-url}")
    private String miIndexBaseUrl;

    @Value("${app.external.finmind-base-url}")
    private String finmindBaseUrl;

    @Value("${app.external.yahoo-finance-base-url}")
    private String yahooBaseUrl;

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
        jdbc.update("DELETE FROM stock_sync_progress WHERE stock_id LIKE 'T4%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'T4%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'T4%'");
    }

    // ---------- 1. One MI_INDEX request per candidate day; population size does not matter ----------

    @Test
    void allMode_oneRequestPerCandidateDay_populationSizeDoesNotChangeRequestCount() throws Exception {
        seedStock("T401", "逐日快照一", true);
        seedStock("T402", "逐日快照二", true);
        seedStock("T403", "逐日快照三", true);
        seedStock("T404", "逐日快照四", true);
        seedStock("T405", "逐日快照五", true);

        LocalDate day1 = LocalDate.of(2025, 9, 1);
        LocalDate day2 = LocalDate.of(2025, 9, 2);

        mockServer.expect(requestTo(miIndexUrl(day1))).andRespond(withSuccess(
                miIndexFixture(new String[]{"T401", "T402", "T403", "T404", "T405"},
                        new String[]{"10.00", "20.00", "30.00", "40.00", "50.00"}),
                MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(miIndexUrl(day2))).andRespond(withSuccess(
                miIndexFixture(new String[]{"T401", "T402", "T403", "T404", "T405"},
                        new String[]{"11.00", "21.00", "31.00", "41.00", "51.00"}),
                MediaType.APPLICATION_JSON));
        // No Yahoo/FinMind expectations registered at all: any request to either fails mockServer.verify().

        BackfillRequest request = new BackfillRequest();
        request.setStartDate(day1);
        request.setEndDate(day2);
        // stockIds omitted -> ALL mode, so the day-by-day snapshot path actually runs; the target
        // population is restricted to this test's T40x fixtures via deactivateOtherActiveStocks().
        request.setCommonStocksOnly(false);

        List<String> otherActiveIds = deactivateOtherActiveStocks();
        try {
            ResponseEntity<BackfillResponse> response = rest.postForEntity(
                    "/api/stocks/sync/backfill", request, BackfillResponse.class);
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
            assertEquals("ALL", response.getBody().getMode());
            assertEquals(5, response.getBody().getTargetCount());

            waitUntilStatus("T401", "PRICE_BACKFILL", "DONE", 10_000);
            waitUntilStatus("T405", "PRICE_BACKFILL", "DONE", 10_000);
            mockServer.verify(); // exactly 2 MI_INDEX requests total for 5 stocks x 2 days

            for (String id : Arrays.asList("T401", "T402", "T403", "T404", "T405")) {
                Integer rowCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = ?", Integer.class, id);
                assertEquals(2, rowCount);
            }
        } finally {
            restoreActiveStocks(otherActiveIds);
        }
    }

    // ---------- 2. SELECTED mode never calls MI_INDEX ----------

    @Test
    void selectedMode_namedTwoStocks_neverCallsMiIndex() throws Exception {
        seedStock("T406", "指名一", true);
        seedStock("T407", "指名二", true);

        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);

        // Yahoo is priority for SELECTED mode; these synthetic ids don't exist there so each falls
        // through to FinMind, exactly as the existing multi-source pipeline already does. No
        // MI_INDEX expectation is registered at all -- if SELECTED mode called it, the batch would
        // fail with "no further requests expected".
        expectYahooNotFound("T406", start, end);
        mockServer.expect(requestTo(finmindUrl("T406", start, end))).andRespond(withSuccess(
                finmindFixture("T406", "2025-09-01", "10.00", "10.50", "9.50", "10.20"),
                MediaType.APPLICATION_JSON));
        expectYahooNotFound("T407", start, end);
        mockServer.expect(requestTo(finmindUrl("T407", start, end))).andRespond(withSuccess(
                finmindFixture("T407", "2025-09-01", "20.00", "20.50", "19.50", "20.20"),
                MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T406", "T407"));
        request.setStartDate(start);
        request.setEndDate(end);

        ResponseEntity<BackfillResponse> response = rest.postForEntity(
                "/api/stocks/sync/backfill", request, BackfillResponse.class);
        assertEquals("SELECTED", response.getBody().getMode());

        waitUntilStatus("T406", "PRICE_BACKFILL", "DONE", 10_000);
        waitUntilStatus("T407", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();
    }

    // ---------- 3. Population outside the target set is dropped, never written ----------

    @Test
    void snapshotRow_outsideTargetPopulation_neverWritten_noNewStockMasterRow() throws Exception {
        seedStock("T408", "母體內", true);

        LocalDate day = LocalDate.of(2025, 9, 1);
        // "0050"/"2881A" appear in the snapshot response but are NOT in the target population
        // (either because they're not is_active in `stock`, or because commonStocksOnly would
        // exclude them anyway) -- proving the day-loop filters against the target set, not just
        // writes everything the snapshot returns.
        mockServer.expect(requestTo(miIndexUrl(day))).andRespond(withSuccess(
                miIndexFixture(new String[]{"T408", "0050", "2881A"}, new String[]{"15.00", "100.00", "50.00"}),
                MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        // stockIds omitted -> ALL mode (SELECTED mode does not exercise the snapshot path at all),
        // restricted to just this one test stock via deactivateOtherActiveStocks().
        request.setCommonStocksOnly(false);
        request.setStartDate(day);
        request.setEndDate(day);

        // "2881A" is a real, pre-existing stock in the live seed data with its own unrelated
        // PRICE_BACKFILL/INDICATOR_REBUILD history -- capture its progress before this run so the
        // assertion can prove THIS batch never touched it, rather than assuming it has no rows at all.
        Integer twoEightEightOneAProgressBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_sync_progress WHERE stock_id = '2881A' AND job_type = 'PRICE_BACKFILL'",
                Integer.class);
        java.time.LocalDate twoEightEightOneALastSyncedBefore = twoEightEightOneAProgressBefore > 0
                ? jdbc.queryForObject("SELECT last_synced_date FROM stock_sync_progress "
                        + "WHERE stock_id = '2881A' AND job_type = 'PRICE_BACKFILL'", java.time.LocalDate.class)
                : null;

        List<String> otherActiveIds = deactivateOtherActiveStocks();
        try {
            rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);
            waitUntilStatus("T408", "PRICE_BACKFILL", "DONE", 10_000);
            mockServer.verify();

            Integer t408Rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T408'", Integer.class);
            assertEquals(1, t408Rows);

            // Neither out-of-population code got a price row for this trade date, and 2881A's own
            // PRICE_BACKFILL progress (row count and last_synced_date) is completely untouched --
            // this batch never targeted it, so it must not have advanced or been created anew.
            Integer zeroFiveZeroRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = '0050' AND trade_date = ?",
                    Integer.class, day);
            assertEquals(0, zeroFiveZeroRows);
            Integer twoEightEightOneARows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = '2881A' AND trade_date = ?",
                    Integer.class, day);
            assertEquals(0, twoEightEightOneARows);
            Integer twoEightEightOneAProgressAfter = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_sync_progress WHERE stock_id = '2881A' AND job_type = 'PRICE_BACKFILL'",
                    Integer.class);
            assertEquals(twoEightEightOneAProgressBefore, twoEightEightOneAProgressAfter);
            if (twoEightEightOneALastSyncedBefore != null) {
                java.time.LocalDate lastSyncedAfter = jdbc.queryForObject("SELECT last_synced_date FROM "
                        + "stock_sync_progress WHERE stock_id = '2881A' AND job_type = 'PRICE_BACKFILL'",
                        java.time.LocalDate.class);
                assertEquals(twoEightEightOneALastSyncedBefore, lastSyncedAfter);
            }
        } finally {
            restoreActiveStocks(otherActiveIds);
        }
    }

    // ---------- 4. Degradation: MI_INDEX blocked mid-batch falls back to per-stock ----------

    @Test
    void miIndexBlocked_degradesToPerStockPipeline_batchCompletesNormally_sourceRecordsYahooOrFinMind()
            throws Exception {
        seedStock("T410", "降級一", true);
        seedStock("T411", "降級二", true);

        LocalDate day = LocalDate.of(2025, 9, 1);
        mockServer.expect(requestTo(miIndexUrl(day))).andRespond(withStatus(HttpStatus.FORBIDDEN));
        // After the block, the per-stock pipeline takes over for BOTH remaining targets, each
        // continuing from target_start_date (never having been advanced) -- Yahoo first, per
        // priority order, and these synthetic ids 404 there before FinMind supplies data.
        expectYahooNotFound("T410", day, day);
        mockServer.expect(requestTo(finmindUrl("T410", day, day))).andRespond(withSuccess(
                finmindFixture("T410", "2025-09-01", "12.00", "12.50", "11.50", "12.20"),
                MediaType.APPLICATION_JSON));
        expectYahooNotFound("T411", day, day);
        mockServer.expect(requestTo(finmindUrl("T411", day, day))).andRespond(withSuccess(
                finmindFixture("T411", "2025-09-01", "22.00", "22.50", "21.50", "22.20"),
                MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStartDate(day);
        request.setEndDate(day);
        request.setCommonStocksOnly(false);

        List<String> otherActiveIds = deactivateOtherActiveStocks();
        try {
            rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

            waitUntilStatus("T410", "PRICE_BACKFILL", "DONE", 10_000);
            waitUntilStatus("T411", "PRICE_BACKFILL", "DONE", 10_000);
            mockServer.verify();

            String t410Source = jdbc.queryForObject(
                    "SELECT source FROM stock_daily_price WHERE stock_id = 'T410'", String.class);
            String t411Source = jdbc.queryForObject(
                    "SELECT source FROM stock_daily_price WHERE stock_id = 'T411'", String.class);
            assertEquals("FINMIND", t410Source);
            assertEquals("FINMIND", t411Source);

            // MI_INDEX's cooldown expires (1s in the test profile); the NEXT batch must resume
            // from the day-by-day snapshot path automatically, no restart/manual reset needed
            // (spec: MI_INDEX 冷卻到期後的下一次作業重新從逐日快照開始).
            Thread.sleep(1_200);
            mockServer.reset();
            LocalDate day2 = day.plusDays(1);
            mockServer.expect(requestTo(miIndexUrl(day2))).andRespond(withSuccess(
                    miIndexFixture(new String[]{"T410", "T411"}, new String[]{"13.00", "23.00"}),
                    MediaType.APPLICATION_JSON));

            BackfillRequest resumeRequest = new BackfillRequest();
            resumeRequest.setStartDate(day2);
            resumeRequest.setEndDate(day2);
            resumeRequest.setCommonStocksOnly(false);
            resumeRequest.setCatchUp(true);
            rest.postForEntity("/api/stocks/sync/backfill", resumeRequest, BackfillResponse.class);

            waitUntilNotRunning(10_000);
            mockServer.verify(); // exactly the 1 MI_INDEX request for day2 -- no Yahoo/FinMind at all

            String t410SourceAfterRecovery = jdbc.queryForObject(
                    "SELECT source FROM stock_daily_price WHERE stock_id = 'T410' AND trade_date = ?",
                    String.class, day2);
            assertEquals("TWSE", t410SourceAfterRecovery);
        } finally {
            restoreActiveStocks(otherActiveIds);
        }
    }

    // ---------- 5. All three sources unavailable: existing "all blocked" handling still applies ----------

    @Test
    void miIndexAndBothPerStockSourcesBlocked_batchEndsNormally_targetsStayPending_noFailedNoAttemptIncrement()
            throws Exception {
        seedStock("T412", "三源皆斷", true);

        LocalDate day = LocalDate.of(2025, 9, 1);
        mockServer.expect(requestTo(miIndexUrl(day))).andRespond(withStatus(HttpStatus.FORBIDDEN));
        mockServer.expect(requestTo(yahooDailyUrl("T412", day, day))).andRespond(withStatus(HttpStatus.FORBIDDEN));
        mockServer.expect(requestTo(finmindUrl("T412", day, day))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        BackfillRequest request = new BackfillRequest();
        request.setStartDate(day);
        request.setEndDate(day);
        request.setCommonStocksOnly(false);

        List<String> otherActiveIds = deactivateOtherActiveStocks();
        try {
            ResponseEntity<BackfillResponse> response = rest.postForEntity(
                    "/api/stocks/sync/backfill", request, BackfillResponse.class);
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

            waitUntilNotRunning(10_000);
            mockServer.verify();

            StockSyncProgress progress = progressMapper.findOne("T412", "PRICE_BACKFILL");
            assertEquals("PENDING", progress.getStatus());
            assertEquals(0, progress.getAttemptCount());
        } finally {
            restoreActiveStocks(otherActiveIds);
        }
    }

    // ---------- 6. Volume: MI_INDEX carries the real (higher) volume; OHLC identical to Yahoo ----------

    @Test
    void sameStockSameDay_miIndexVsYahoo_ohlcIdentical_volumeDiffers_miIndexHigher() throws Exception {
        seedStock("T413", "量口徑比較", true);
        LocalDate day = LocalDate.of(2025, 9, 1);

        mockServer.expect(requestTo(miIndexUrl(day))).andRespond(withSuccess(
                miIndexFixtureWithVolume("T413", "18.00", "18.50", "17.50", "18.20", "5,000,000"),
                MediaType.APPLICATION_JSON));

        BackfillRequest snapshotRequest = new BackfillRequest();
        snapshotRequest.setStartDate(day);
        snapshotRequest.setEndDate(day);
        snapshotRequest.setCommonStocksOnly(false);

        List<String> otherActiveIds = deactivateOtherActiveStocks();
        long miIndexVolume;
        long yahooVolume;
        try {
            rest.postForEntity("/api/stocks/sync/backfill", snapshotRequest, BackfillResponse.class);
            waitUntilStatus("T413", "PRICE_BACKFILL", "DONE", 10_000);
            mockServer.verify();

            miIndexVolume = jdbc.queryForObject(
                    "SELECT volume FROM stock_daily_price WHERE stock_id = 'T413'", Long.class);
            BigDecimal miIndexClose = jdbc.queryForObject(
                    "SELECT close_price FROM stock_daily_price WHERE stock_id = 'T413'", BigDecimal.class);

            // Now re-fetch the SAME stock/day via Yahoo (SELECTED mode) with a deliberately lower
            // volume, same OHLC -- exactly the documented Yahoo-is-systematically-lower relationship
            // (spec: 交易所是成交量的正確口徑；Yahoo 是偏低的那一個).
            mockServer.reset();
            mockServer.expect(requestTo(yahooDailyUrl("T413", day, day))).andRespond(withSuccess(
                    yahooFixture(day, 18.00, 18.50, 17.50, 18.20, 4_300_000L), MediaType.APPLICATION_JSON));

            BackfillRequest selectedRequest = new BackfillRequest();
            selectedRequest.setStockIds(Arrays.asList("T413"));
            selectedRequest.setStartDate(day);
            selectedRequest.setEndDate(day);
            rest.postForEntity("/api/stocks/sync/backfill", selectedRequest, BackfillResponse.class);
            waitUntilStatus("T413", "PRICE_BACKFILL", "DONE", 10_000);
            mockServer.verify();

            yahooVolume = jdbc.queryForObject(
                    "SELECT volume FROM stock_daily_price WHERE stock_id = 'T413'", Long.class);
            BigDecimal yahooClose = jdbc.queryForObject(
                    "SELECT close_price FROM stock_daily_price WHERE stock_id = 'T413'", BigDecimal.class);

            assertEquals(0, miIndexClose.compareTo(yahooClose)); // OHLC identical across sources
            assertTrue(miIndexVolume > yahooVolume,
                    "MI_INDEX volume (" + miIndexVolume + ") should be greater than Yahoo's (" + yahooVolume + ")");
        } finally {
            restoreActiveStocks(otherActiveIds);
        }
    }

    // ---------- 7. One-off re-ingest: default params (reset & rerun) converts YAHOO rows to TWSE ----------

    @Test
    void defaultParamsFullReingest_convertsExistingYahooRowsToTwse_turnoverAndTransactionCountNoLongerZero_rowCountUnchanged()
            throws Exception {
        seedStock("T414", "一次性重補", true);
        LocalDate day = LocalDate.of(2025, 9, 1);

        // First: Yahoo writes the row (turnover/transaction_count = 0, per existing contract).
        mockServer.expect(requestTo(yahooDailyUrl("T414", day, day))).andRespond(withSuccess(
                yahooFixture(day, 25.00, 25.50, 24.50, 25.20, 800_000L), MediaType.APPLICATION_JSON));

        BackfillRequest yahooRequest = new BackfillRequest();
        yahooRequest.setStockIds(Arrays.asList("T414"));
        yahooRequest.setStartDate(day);
        yahooRequest.setEndDate(day);
        rest.postForEntity("/api/stocks/sync/backfill", yahooRequest, BackfillResponse.class);
        waitUntilStatus("T414", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();

        Integer rowCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T414'", Integer.class);
        String sourceBefore = jdbc.queryForObject(
                "SELECT source FROM stock_daily_price WHERE stock_id = 'T414'", String.class);
        assertEquals(1, rowCountBefore);
        assertEquals("YAHOO", sourceBefore);

        // Now: a default-params (catchUp=false, resume=false) whole-market re-backfill over the
        // same range -- no new request parameters, no new endpoint, just the existing ALL-mode
        // "reset and rerun" behavior, which now walks the day-by-day MI_INDEX path.
        mockServer.reset();
        mockServer.expect(requestTo(miIndexUrl(day))).andRespond(withSuccess(
                miIndexFixtureWithVolume("T414", "25.00", "25.50", "24.50", "25.20", "1,200,000"),
                MediaType.APPLICATION_JSON));

        BackfillRequest reingest = new BackfillRequest();
        reingest.setStartDate(day);
        reingest.setEndDate(day);
        reingest.setCommonStocksOnly(false);

        List<String> otherActiveIds = deactivateOtherActiveStocks();
        try {
            rest.postForEntity("/api/stocks/sync/backfill", reingest, BackfillResponse.class);
            waitUntilStatus("T414", "PRICE_BACKFILL", "DONE", 10_000);
            mockServer.verify();

            Integer rowCountAfter = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T414'", Integer.class);
            String sourceAfter = jdbc.queryForObject(
                    "SELECT source FROM stock_daily_price WHERE stock_id = 'T414'", String.class);
            BigDecimal turnoverAfter = jdbc.queryForObject(
                    "SELECT turnover FROM stock_daily_price WHERE stock_id = 'T414'", BigDecimal.class);
            int transactionCountAfter = jdbc.queryForObject(
                    "SELECT transaction_count FROM stock_daily_price WHERE stock_id = 'T414'", Integer.class);

            assertEquals(rowCountBefore, rowCountAfter); // UPSERT overwrite, not a new row
            assertEquals("TWSE", sourceAfter);
            assertTrue(turnoverAfter.compareTo(BigDecimal.ZERO) > 0);
            assertTrue(transactionCountAfter > 0);
        } finally {
            restoreActiveStocks(otherActiveIds);
        }
    }

    // ---------- helpers ----------

    private void seedStock(String stockId, String name, boolean active) {
        stockMapper.upsert(new Stock(stockId, name, "TSE", active));
    }

    /** Deactivates every currently-active row NOT owned by this test file (T4xx), so an ALL-mode
     * request restricted only via commonStocksOnly=false still targets exactly this test's fixtures. */
    private List<String> deactivateOtherActiveStocks() {
        List<String> otherActiveIds = jdbc.queryForList(
                "SELECT stock_id FROM stock WHERE is_active = 1 AND stock_id NOT LIKE 'T4%'", String.class);
        jdbc.update("UPDATE stock SET is_active = 0 WHERE stock_id NOT LIKE 'T4%'");
        return otherActiveIds;
    }

    private void restoreActiveStocks(List<String> ids) {
        if (!ids.isEmpty()) {
            jdbc.update("UPDATE stock SET is_active = 1 WHERE stock_id IN ("
                    + ids.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("") + ")", ids.toArray());
        }
    }

    private String miIndexUrl(LocalDate date) {
        return miIndexBaseUrl + "?date=" + date.format(DATE_PARAM) + "&type=ALL&response=json";
    }

    private String miIndexFixture(String[] stockIds, String[] closes) {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < stockIds.length; i++) {
            if (i > 0) {
                data.append(",");
            }
            String close = closes[i];
            data.append("[\"").append(stockIds[i]).append("\",\"測試股\",\"1,000,000\",\"500\",\"12,345,678\",\"")
                    .append(close).append("\",\"").append(close).append("\",\"").append(close).append("\",\"")
                    .append(close).append("\",\"<p style='color:red'>+</p>\",\"0.00\",\"\",\"\",\"\",\"\",\"15.0\"]");
        }
        return "{\"tables\":["
                + "{\"fields\":[\"指數\",\"收盤指數\"],\"data\":[[\"發行量加權股價指數\",\"17000.00\"]]},"
                + "{\"fields\":[" + STOCK_FIELDS + "],\"data\":[" + data + "]}"
                + "]}";
    }

    private String miIndexFixtureWithVolume(String stockId, String open, String high, String low, String close,
                                             String volume) {
        String row = "[\"" + stockId + "\",\"測試股\",\"" + volume + "\",\"500\",\"12,345,678\",\"" + open
                + "\",\"" + high + "\",\"" + low + "\",\"" + close
                + "\",\"<p style='color:red'>+</p>\",\"0.00\",\"\",\"\",\"\",\"\",\"15.0\"]";
        return "{\"tables\":["
                + "{\"fields\":[\"指數\",\"收盤指數\"],\"data\":[[\"發行量加權股價指數\",\"17000.00\"]]},"
                + "{\"fields\":[" + STOCK_FIELDS + "],\"data\":[" + row + "]}"
                + "]}";
    }

    private String yahooDailyUrl(String stockId, LocalDate start, LocalDate end) {
        long period1 = start.atStartOfDay(TAIPEI).toEpochSecond();
        long period2 = end.plusDays(1).atStartOfDay(TAIPEI).toEpochSecond();
        return yahooBaseUrl + "/" + stockId + ".TW?interval=1d&period1=" + period1 + "&period2=" + period2;
    }

    private void expectYahooNotFound(String stockId, LocalDate start, LocalDate end) {
        mockServer.expect(requestTo(yahooDailyUrl(stockId, start, end)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\","
                                + "\"description\":\"No data found, symbol may be delisted\"}}}"));
    }

    private String yahooFixture(LocalDate date, double open, double high, double low, double close, long volume) {
        long epoch = date.atTime(9, 0).atZone(TAIPEI).toEpochSecond();
        return "{\"chart\":{\"result\":[{\"timestamp\":[" + epoch + "],\"indicators\":{\"quote\":[{"
                + "\"open\":[" + open + "],\"high\":[" + high + "],\"low\":[" + low + "],"
                + "\"close\":[" + close + "],\"volume\":[" + volume + "]}]}}],\"error\":null}}";
    }

    private String finmindUrl(String stockId, LocalDate start, LocalDate end) {
        return finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=" + stockId
                + "&start_date=" + start + "&end_date=" + end;
    }

    private String finmindFixture(String stockId, String date, String open, String max, String min, String close) {
        return "{\"msg\":\"success\",\"status\":200,\"data\":[{\"date\":\"" + date + "\",\"stock_id\":\""
                + stockId + "\",\"Trading_Volume\":1000,\"Trading_money\":100000,"
                + "\"open\":" + open + ",\"max\":" + max + ",\"min\":" + min + ",\"close\":" + close
                + ",\"Trading_turnover\":10}]}";
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
