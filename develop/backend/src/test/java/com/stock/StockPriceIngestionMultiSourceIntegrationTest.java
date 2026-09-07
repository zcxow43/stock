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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * End-to-end (real DB + mocked external HTTP) coverage for the multi-source/block-switching
 * increment (spec: specs/backend/stock-price-ingestion.md, 多來源與封鎖切換（本次新增）). Complements
 * the pure-unit {@code PriceHistoryFetcherTest}/{@code YahooFinanceClientDailyHistoryTest}/
 * {@code FinMindClientTest}, which cover the orchestration/classification logic in isolation;
 * this file proves the same behavior wires correctly through the real backfill pipeline into
 * {@code stock_daily_price}/{@code stock_sync_progress}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockPriceIngestionMultiSourceIntegrationTest {

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
    private SourceAvailabilityTracker sourceAvailabilityTracker;

    @Autowired
    private DataSource dataSource;

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
        jdbc.update("DELETE FROM stock_sync_progress WHERE stock_id LIKE 'T3%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'T3%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'T3%'");
    }

    // ---------- 1. Default routing: Yahoo first, no 30-day window, source recorded ----------

    @Test
    void freshBackfill_usesYahooByDefault_evenForARangeOlderThan30Days_sourceRecordedAsYahoo() {
        seedStock("T301", "多來源-預設Yahoo", "TSE", true);

        // Deliberately > 30 days before "today" -- interval=1d has no such window (spec:
        // 逐檔歷史請求區間早於今日30天仍正常取得資料), unlike interval=1m.
        LocalDate start = LocalDate.of(2025, 1, 5);
        LocalDate end = LocalDate.of(2025, 1, 6);
        mockServer.expect(requestTo(yahooDailyUrl("T301", "TSE", start, end)))
                .andRespond(withSuccess(yahooFixture(
                        new LocalDate[]{start, end},
                        new double[]{100.0, 101.0}, new double[]{102.0, 103.0},
                        new double[]{99.0, 100.0}, new double[]{101.5, 102.5},
                        new long[]{1000, 1100}), MediaType.APPLICATION_JSON));
        // No FinMind mock registered at all: if the code fell back to FinMind unnecessarily, the
        // unmatched call itself would fail the batch (asserted below via final DONE status).

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T301"));
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        waitUntilStatus("T301", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();

        List<String> sources = jdbc.queryForList(
                "SELECT source FROM stock_daily_price WHERE stock_id = 'T301'", String.class);
        assertEquals(2, sources.size());
        assertTrue(sources.stream().allMatch("YAHOO"::equals), "expected every row's source to be YAHOO: " + sources);

        // Yahoo rows: turnover/transaction_count = 0, OHLCV valid, no validation error raised.
        jdbc.query("SELECT turnover, transaction_count, open_price, close_price, volume "
                + "FROM stock_daily_price WHERE stock_id = 'T301'", rs -> {
            assertEquals(0, new BigDecimal("0").compareTo(rs.getBigDecimal("turnover")));
            assertEquals(0, rs.getInt("transaction_count"));
            assertTrue(rs.getBigDecimal("open_price").compareTo(BigDecimal.ZERO) > 0);
            assertTrue(rs.getBigDecimal("close_price").compareTo(BigDecimal.ZERO) > 0);
            assertTrue(rs.getLong("volume") > 0);
        });
    }

    // ---------- 2. OTC suffix routing ----------

    @Test
    void otcStock_fetchesViaYahooWithTwoSuffix_writesRows() {
        seedStock("T302", "多來源-OTC", "OTC", true);
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);

        mockServer.expect(requestTo(yahooDailyUrl("T302", "OTC", start, end)))
                .andRespond(withSuccess(yahooFixture(
                        new LocalDate[]{start}, new double[]{50.0}, new double[]{51.0},
                        new double[]{49.5}, new double[]{50.5}, new long[]{500}), MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T302"));
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        waitUntilStatus("T302", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T302'", Integer.class);
        assertEquals(1, rowCount);
    }

    // ---------- 3. Primary blocked -> fallback succeeds; attempt_count/status untouched by the block ----------

    @Test
    void yahooBlocked_fallsBackToFinMind_succeeds_attemptCountUntouched_neverFailed() {
        seedStock("T303", "多來源-封鎖切換", "TSE", true);
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);

        mockServer.expect(requestTo(yahooDailyUrl("T303", "TSE", start, end)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        mockServer.expect(requestTo(finmindDailyUrl("T303", start, end)))
                .andRespond(withSuccess(finmindFixture("T303", "2025-09-01", "10.00", "10.50", "9.50", "10.20"),
                        MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T303"));
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        waitUntilStatus("T303", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();

        StockSyncProgress progress = progressMapper.findOne("T303", "PRICE_BACKFILL");
        assertEquals("DONE", progress.getStatus());
        assertEquals(0, progress.getAttemptCount()); // block never increments attempt_count
        assertNull(progress.getLastError()); // never touched by the block, and DONE clears nothing relevant

        String source = jdbc.queryForObject(
                "SELECT source FROM stock_daily_price WHERE stock_id = 'T303'", String.class);
        assertEquals("FINMIND", source);
    }

    // ---------- 4. Blocked source is skipped entirely for the REST of the batch ----------

    @Test
    void afterYahooBlocked_subsequentStockInSameBatch_neverCallsYahoo() {
        seedStock("T303", "多來源-封鎖切換", "TSE", true);
        seedStock("T304", "多來源-同批次後續標的", "TSE", true);
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);

        mockServer.expect(requestTo(yahooDailyUrl("T303", "TSE", start, end)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        mockServer.expect(requestTo(finmindDailyUrl("T303", start, end)))
                .andRespond(withSuccess(finmindFixture("T303", "2025-09-01", "10.00", "10.50", "9.50", "10.20"),
                        MediaType.APPLICATION_JSON));
        // No Yahoo mock registered for T304 at all: if the (now-blocked) source were called
        // anyway, MockRestServiceServer would reject it as an unexpected request and the stock
        // would end up FAILED instead of DONE below -- the assertion is by request count/matching,
        // never by elapsed time.
        mockServer.expect(requestTo(finmindDailyUrl("T304", start, end)))
                .andRespond(withSuccess(finmindFixture("T304", "2025-09-01", "20.00", "20.50", "19.50", "20.20"),
                        MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T303", "T304"));
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        waitUntilStatus("T304", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();

        String t304Source = jdbc.queryForObject(
                "SELECT source FROM stock_daily_price WHERE stock_id = 'T304'", String.class);
        assertEquals("FINMIND", t304Source);
    }

    // ---------- 5. Full block lifecycle: retry_after honored, default cooldown, auto-recovery,
    //              both-blocked ends the batch normally, and a later call finishes the rest ----------

    @Test
    void bothSourcesBlocked_endsBatchNormally_pendingUntouched_thenRecoversAndFinishesOnNextCall()
            throws Exception {
        seedStock("T310", "多來源-雙封鎖", "TSE", true);
        seedStock("T311", "多來源-未處理標的", "TSE", true);
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);

        // T310: Yahoo blocked with no retry_after (falls back to the configured default cooldown,
        // 1s in the test profile); FinMind blocked WITH an explicit retry_after=1 in its body.
        // Neither value is hardcoded in the fetcher -- both come from config/response.
        mockServer.expect(requestTo(yahooDailyUrl("T310", "TSE", start, end)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        mockServer.expect(requestTo(finmindDailyUrl("T310", start, end)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"msg\":\"ip banned\",\"status\":403,\"retry_after\":1,\"token_tail\":\"\"}"));
        // T311 must never be attempted this round: the batch ends the moment T310 finds both
        // sources unavailable (no mocks registered for T311 at all).

        BackfillRequest first = new BackfillRequest();
        first.setStockIds(Arrays.asList("T310", "T311"));
        first.setStartDate(start);
        first.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", first, BackfillResponse.class);

        waitUntilNotRunning(10_000);
        mockServer.verify(); // confirms exactly the two blocked calls happened, nothing for T311

        StockSyncProgress t310AfterBlock = progressMapper.findOne("T310", "PRICE_BACKFILL");
        assertEquals("PENDING", t310AfterBlock.getStatus());
        assertEquals(0, t310AfterBlock.getAttemptCount());
        StockSyncProgress t311AfterBlock = progressMapper.findOne("T311", "PRICE_BACKFILL");
        assertEquals("PENDING", t311AfterBlock.getStatus());
        assertEquals(0, t311AfterBlock.getAttemptCount());

        // Wait past both cooldowns (1s each in the test profile), then run again: both should
        // recover automatically, no restart/manual reset needed, and BOTH try Yahoo again first
        // (priority order resets on recovery -- it doesn't "remember" FinMind was used last).
        Thread.sleep(1200);
        mockServer.reset();
        mockServer.expect(requestTo(yahooDailyUrl("T310", "TSE", start, end)))
                .andRespond(withSuccess(finmindToYahooFixture(start, "30.00", "31.00", "29.50", "30.50", 300),
                        MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(yahooDailyUrl("T311", "TSE", start, end)))
                .andRespond(withSuccess(finmindToYahooFixture(start, "40.00", "41.00", "39.50", "40.50", 400),
                        MediaType.APPLICATION_JSON));

        BackfillRequest second = new BackfillRequest();
        second.setStockIds(Arrays.asList("T310", "T311"));
        second.setStartDate(start);
        second.setEndDate(end);
        second.setResume(true);
        rest.postForEntity("/api/stocks/sync/backfill", second, BackfillResponse.class);

        waitUntilStatus("T310", "PRICE_BACKFILL", "DONE", 10_000);
        waitUntilStatus("T311", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();

        String t310Source = jdbc.queryForObject(
                "SELECT source FROM stock_daily_price WHERE stock_id = 'T310'", String.class);
        String t311Source = jdbc.queryForObject(
                "SELECT source FROM stock_daily_price WHERE stock_id = 'T311'", String.class);
        assertEquals("YAHOO", t310Source);
        assertEquals("YAHOO", t311Source);
    }

    // ---------- 6. 404 ambiguity: fallback finds data (not SKIPPED); all-404 -> SKIPPED ----------

    @Test
    void yahoo404_finMindHasData_writtenNormally_neverSkipped() {
        seedStock("T320", "多來源-404後援有資料", "TSE", true);
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);

        mockServer.expect(requestTo(yahooDailyUrl("T320", "TSE", start, end)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\","
                                + "\"description\":\"No data found, symbol may be delisted\"}}}"));
        mockServer.expect(requestTo(finmindDailyUrl("T320", start, end)))
                .andRespond(withSuccess(finmindFixture("T320", "2025-09-01", "5.00", "5.50", "4.50", "5.20"),
                        MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T320"));
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        waitUntilStatus("T320", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();
    }

    @Test
    void everySourceReports404_markedSkipped() {
        seedStock("T321", "多來源-全部404", "TSE", true);
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        String notFoundBody = "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\","
                + "\"description\":\"No data found, symbol may be delisted\"}}}";

        mockServer.expect(requestTo(yahooDailyUrl("T321", "TSE", start, end)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(notFoundBody));
        mockServer.expect(requestTo(finmindDailyUrl("T321", start, end)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T321"));
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        waitUntilStatus("T321", "PRICE_BACKFILL", "SKIPPED", 10_000);
        mockServer.verify();
    }

    // ---------- 7. Cross-source idempotency: same stock/range via Yahoo then FinMind ----------

    @Test
    void sameStockAndRange_backfilledByYahooThenFinMind_rowCountUnchanged_ohlcIdentical() {
        seedStock("T330", "多來源-跨來源冪等", "TSE", true);
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);

        mockServer.expect(requestTo(yahooDailyUrl("T330", "TSE", start, end)))
                .andRespond(withSuccess(yahooFixture(new LocalDate[]{start}, new double[]{60.0},
                        new double[]{61.0}, new double[]{59.5}, new double[]{60.5}, new long[]{600}),
                        MediaType.APPLICATION_JSON));

        BackfillRequest first = new BackfillRequest();
        first.setStockIds(Arrays.asList("T330"));
        first.setStartDate(start);
        first.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", first, BackfillResponse.class);
        waitUntilStatus("T330", "PRICE_BACKFILL", "DONE", 10_000);

        Integer rowCountAfterYahoo = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T330'", Integer.class);
        BigDecimal closeAfterYahoo = jdbc.queryForObject(
                "SELECT close_price FROM stock_daily_price WHERE stock_id = 'T330'", BigDecimal.class);

        // Re-run the identical range, this time forcing FinMind (Yahoo blocked) with the SAME OHLC.
        mockServer.reset();
        mockServer.expect(requestTo(yahooDailyUrl("T330", "TSE", start, end)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        mockServer.expect(requestTo(finmindDailyUrl("T330", start, end)))
                .andRespond(withSuccess(finmindFixture("T330", "2025-09-01", "60.00", "61.00", "59.50", "60.50"),
                        MediaType.APPLICATION_JSON));

        BackfillRequest rerun = new BackfillRequest();
        rerun.setStockIds(Arrays.asList("T330"));
        rerun.setStartDate(start);
        rerun.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", rerun, BackfillResponse.class);
        waitUntilStatus("T330", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();

        Integer rowCountAfterFinMind = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'T330'", Integer.class);
        BigDecimal closeAfterFinMind = jdbc.queryForObject(
                "SELECT close_price FROM stock_daily_price WHERE stock_id = 'T330'", BigDecimal.class);
        String finalSource = jdbc.queryForObject(
                "SELECT source FROM stock_daily_price WHERE stock_id = 'T330'", String.class);

        assertEquals(rowCountAfterYahoo, rowCountAfterFinMind); // row count unchanged across sources
        assertEquals(0, closeAfterYahoo.compareTo(closeAfterFinMind)); // OHLC identical
        assertEquals("FINMIND", finalSource); // source column reflects the LAST write, as expected of UPSERT
    }

    // ---------- 8. Pre-existing FAILED rows (2026-09-06 incident shape) auto-recover via catchUp ----------

    @Test
    void preExistingFailedRow_fromBlockIncident_reopenedByCatchUp_attemptCountResetToZero_noMigrationNeeded() {
        seedStock("T340", "多來源-事故殘留FAILED", "TSE", true);
        // Exact shape of the 2026-09-06 incident: FAILED, attempt_count=1, last_error mentioning
        // HTTP 403, and never actually synced (last_synced_date NULL).
        jdbc.update("INSERT INTO stock_sync_progress "
                        + "(stock_id, job_type, status, target_start_date, target_end_date, last_synced_date, "
                        + "attempt_count, last_error, started_at, finished_at) "
                        + "VALUES ('T340', 'PRICE_BACKFILL', 'FAILED', '2025-09-01', '2025-09-01', NULL, "
                        + "1, 'HTTP 403', NOW(), NOW())");

        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        mockServer.expect(requestTo(yahooDailyUrl("T340", "TSE", start, end)))
                .andRespond(withSuccess(yahooFixture(new LocalDate[]{start}, new double[]{70.0},
                        new double[]{71.0}, new double[]{69.5}, new double[]{70.5}, new long[]{700}),
                        MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(Arrays.asList("T340"));
        request.setStartDate(start);
        request.setEndDate(end);
        request.setCatchUp(true);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        waitUntilStatus("T340", "PRICE_BACKFILL", "DONE", 10_000);
        mockServer.verify();

        StockSyncProgress progress = progressMapper.findOne("T340", "PRICE_BACKFILL");
        assertEquals("DONE", progress.getStatus());
        assertEquals(0, progress.getAttemptCount());
        assertNull(progress.getLastError());
    }

    // ---------- helpers ----------

    private void seedStock(String stockId, String name, String market, boolean active) {
        stockMapper.upsert(new Stock(stockId, name, market, active));
    }

    private String yahooDailyUrl(String stockId, String market, LocalDate start, LocalDate end) {
        long period1 = start.atStartOfDay(TAIPEI).toEpochSecond();
        long period2 = end.plusDays(1).atStartOfDay(TAIPEI).toEpochSecond();
        String suffix = "OTC".equals(market) ? ".TWO" : ".TW";
        return yahooBaseUrl + "/" + stockId + suffix + "?interval=1d&period1=" + period1 + "&period2=" + period2;
    }

    private String finmindDailyUrl(String stockId, LocalDate start, LocalDate end) {
        return finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=" + stockId
                + "&start_date=" + start + "&end_date=" + end;
    }

    private String yahooFixture(LocalDate[] dates, double[] opens, double[] highs, double[] lows,
                                 double[] closes, long[] volumes) {
        StringBuilder ts = new StringBuilder();
        StringBuilder open = new StringBuilder();
        StringBuilder high = new StringBuilder();
        StringBuilder low = new StringBuilder();
        StringBuilder close = new StringBuilder();
        StringBuilder volume = new StringBuilder();
        for (int i = 0; i < dates.length; i++) {
            if (i > 0) {
                ts.append(",");
                open.append(",");
                high.append(",");
                low.append(",");
                close.append(",");
                volume.append(",");
            }
            long epoch = dates[i].atTime(9, 0).atZone(TAIPEI).toEpochSecond();
            ts.append(epoch);
            open.append(opens[i]);
            high.append(highs[i]);
            low.append(lows[i]);
            close.append(closes[i]);
            volume.append(volumes[i]);
        }
        return "{\"chart\":{\"result\":[{\"timestamp\":[" + ts + "],\"indicators\":{\"quote\":[{"
                + "\"open\":[" + open + "],\"high\":[" + high + "],\"low\":[" + low + "],"
                + "\"close\":[" + close + "],\"volume\":[" + volume + "]}]}}],\"error\":null}}";
    }

    /** Small helper so the "recovered -> Yahoo again" fixtures read like plain OHLC literals. */
    private String finmindToYahooFixture(LocalDate date, String open, String high, String low, String close,
                                          long volume) {
        return yahooFixture(new LocalDate[]{date}, new double[]{Double.parseDouble(open)},
                new double[]{Double.parseDouble(high)}, new double[]{Double.parseDouble(low)},
                new double[]{Double.parseDouble(close)}, new long[]{volume});
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
