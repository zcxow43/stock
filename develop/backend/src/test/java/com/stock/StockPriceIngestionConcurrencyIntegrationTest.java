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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * End-to-end (real DB + mocked external HTTP) coverage for concurrent per-stock backfill
 * fetching (spec: specs/backend/stock-price-ingestion.md, 母體預設普通股與並行抓取's concurrency bullets).
 * Runs against a DEDICATED Spring context with a small, deterministic
 * {@code app.backfill.executor-pool-size} / {@code app.backfill.rate-limit.interval-ms} override
 * (distinct from the shared test context's sequential defaults) so genuine concurrency is
 * observable without depending on real network latency or elapsed-time-only assertions.
 *
 * Every assertion here is by an observed count (RUNNING rows, request arrivals) or by which
 * requests were/weren't made — never by "the whole batch took roughly N seconds" alone, per the
 * spec's own insistence (以進度列的 RUNNING 檔數或請求時間重疊斷言，非以總耗時推測).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.backfill.executor-pool-size=4", "app.backfill.rate-limit.interval-ms=150"})
class StockPriceIngestionConcurrencyIntegrationTest {

    private static final String JOB_TYPE = StockSyncProgress.JOB_PRICE_BACKFILL;

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
        // Concurrent requests arrive in an unpredictable relative order -- unlike the sequential
        // multi-source tests, this suite must match by URL regardless of arrival order.
        mockServer = MockRestServiceServer.bindTo(externalApiRestTemplate).ignoreExpectOrder(true).build();
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

    // ---------- 1. Concurrency actually reaches the configured cap (RUNNING-count assertion) ----------

    @Test
    void concurrentWorkers_reachConfiguredCapOfFour_neverExceedIt() throws Exception {
        int poolSize = 4;
        List<String> stockIds = new ArrayList<>();
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);

        for (int i = 1; i <= 8; i++) {
            String id = "T50" + i;
            stockIds.add(id);
            seedStock(id, "並行測試" + i, "TSE", true);
            // Deliberately slow (300ms) so every worker is still mid-request when polled, long
            // enough relative to the 150ms per-source interval that up to `poolSize` requests
            // genuinely overlap in time rather than merely being scheduled back-to-back.
            mockServer.expect(requestTo(yahooDailyUrl(id, "TSE", start, end)))
                    .andRespond(slowSuccess(yahooFixture(id, start, "10.00"), 300));
        }

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(stockIds);
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        int maxObservedRunning = 0;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && jobRunningRegistry.isRunning(JOB_TYPE)) {
            Integer running = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_sync_progress WHERE job_type = ? AND status = 'RUNNING' "
                            + "AND stock_id LIKE 'T5%'", Integer.class, JOB_TYPE);
            maxObservedRunning = Math.max(maxObservedRunning, running);
            assertTrue(running <= poolSize,
                    "observed " + running + " concurrently RUNNING stocks, exceeding the configured pool size "
                            + poolSize);
            Thread.sleep(15);
        }
        waitUntilNotRunning(10_000);
        mockServer.verify();

        assertEquals(poolSize, maxObservedRunning,
                "expected concurrency to actually reach the configured pool size (" + poolSize + "); only "
                        + "observed " + maxObservedRunning + " at once");

        for (String id : stockIds) {
            assertEquals("DONE", progressMapper.findOne(id, JOB_TYPE).getStatus());
        }
    }

    // ---------- 2. Block discovered by one worker stops new work batch-wide; in-flight stocks finish ----------

    /**
     * Every worker races the shared cursor concurrently, so which exact stocks are "already
     * claimed" when the blocked one is discovered is inherently timing-dependent (best-effort,
     * lock-free coordination -- spec: 已在處理中的標的照常跑完 accepts that several stocks may be
     * in flight at once, it never promises a hard real-time cutover the instant a block is
     * found). This test therefore mocks EVERY candidate stock with a real (delayed) response
     * rather than asserting which specific ids were "never touched" -- an assertion that would
     * flake under system load, since a stock racing in a few milliseconds early is not a spec
     * violation. What the spec actually guarantees, and what this asserts instead:
     *   - the blocked stock itself reverts to PENDING with attempt_count untouched;
     *   - NO stock in the whole batch ever ends up FAILED or with an incremented attempt_count
     *     as a result of the block (spec: 無任何標的被標記FAILED、無任何attempt_count被累加);
     *   - the batch does not simply drain the entire list once both sources are blocked -- at
     *     least one stock stays PENDING, proving new work genuinely stopped being claimed.
     */
    @Test
    void blockDiscoveredByOneWorker_stopsClaimingNewWork_noneFailedOrAttemptCounted() throws Exception {
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);

        String blockedStock = "T510"; // both sources blocked for this one
        List<String> others = Arrays.asList("T511", "T512", "T513", "T514", "T515");

        List<String> allIds = new ArrayList<>();
        allIds.add(blockedStock);
        allIds.addAll(others);
        for (String id : allIds) {
            seedStock(id, "封鎖並行測試-" + id, "TSE", true);
        }

        mockServer.expect(requestTo(yahooDailyUrl(blockedStock, "TSE", start, end)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        mockServer.expect(requestTo(finmindDailyUrl(blockedStock, start, end)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        for (String id : others) {
            // Slow enough that whichever ones DO get claimed are still genuinely in flight when
            // the blocked stock (processed by a sibling worker) discovers both sources are
            // unavailable. ExpectedCount.between(0, 1): each of these may or may not actually be
            // claimed before the batch stops taking new work -- either outcome is spec-compliant,
            // only an outright unmocked request (or being requested more than once) would not be.
            mockServer.expect(org.springframework.test.web.client.ExpectedCount.between(0, 1),
                            requestTo(yahooDailyUrl(id, "TSE", start, end)))
                    .andRespond(slowSuccess(yahooFixture(id, start, "20.00"), 400));
        }

        BackfillRequest request = new BackfillRequest();
        request.setStockIds(allIds);
        request.setStartDate(start);
        request.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", request, BackfillResponse.class);

        waitUntilNotRunning(10_000);
        mockServer.verify();

        StockSyncProgress blocked = progressMapper.findOne(blockedStock, JOB_TYPE);
        assertEquals("PENDING", blocked.getStatus());
        assertEquals(0, blocked.getAttemptCount());

        int failedCount = 0;
        int pendingCount = 0;
        for (String id : others) {
            StockSyncProgress p = progressMapper.findOne(id, JOB_TYPE);
            if ("FAILED".equals(p.getStatus())) {
                failedCount++;
            }
            if ("PENDING".equals(p.getStatus())) {
                pendingCount++;
            }
            assertEquals(0, p.getAttemptCount(), id + ": attempt_count must never be incremented by a block");
        }
        assertEquals(0, failedCount, "no stock may end up FAILED once both sources are blocked");
        assertTrue(pendingCount >= 1,
                "the batch must stop claiming new work well before draining the whole list -- "
                        + "expected at least one of " + others + " to remain PENDING");
    }

    // ---------- 3. Concurrency doesn't change idempotency ----------

    @Test
    void concurrentBatch_runTwice_rowCountUnchanged() throws Exception {
        LocalDate start = LocalDate.of(2025, 9, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        List<String> stockIds = Arrays.asList("T521", "T522", "T523", "T524");
        for (String id : stockIds) {
            seedStock(id, "並行冪等測試-" + id, "TSE", true);
        }

        for (String id : stockIds) {
            mockServer.expect(requestTo(yahooDailyUrl(id, "TSE", start, end)))
                    .andRespond(withSuccess(yahooFixture(id, start, "15.00"), MediaType.APPLICATION_JSON));
        }
        BackfillRequest first = new BackfillRequest();
        first.setStockIds(stockIds);
        first.setStartDate(start);
        first.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", first, BackfillResponse.class);
        waitUntilNotRunning(10_000);
        mockServer.verify();

        Integer rowCountAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id LIKE 'T52%'", Integer.class);

        mockServer.reset();
        for (String id : stockIds) {
            mockServer.expect(requestTo(yahooDailyUrl(id, "TSE", start, end)))
                    .andRespond(withSuccess(yahooFixture(id, start, "15.00"), MediaType.APPLICATION_JSON));
        }
        BackfillRequest second = new BackfillRequest();
        second.setStockIds(stockIds);
        second.setStartDate(start);
        second.setEndDate(end);
        rest.postForEntity("/api/stocks/sync/backfill", second, BackfillResponse.class);
        waitUntilNotRunning(10_000);
        mockServer.verify();

        Integer rowCountAfterSecond = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id LIKE 'T52%'", Integer.class);
        assertEquals(rowCountAfterFirst, rowCountAfterSecond);
    }

    // ---------- helpers ----------

    private void seedStock(String stockId, String name, String market, boolean active) {
        stockMapper.upsert(new Stock(stockId, name, market, active));
    }

    /** Wraps a normal success response with an artificial delay, so several requests genuinely overlap in time. */
    private ResponseCreator slowSuccess(String body, long delayMs) {
        ResponseCreator delegate = withSuccess(body, MediaType.APPLICATION_JSON);
        return request -> {
            sleep(delayMs);
            return delegate.createResponse(request);
        };
    }

    private String yahooDailyUrl(String stockId, String market, LocalDate start, LocalDate end) {
        java.time.ZoneId taipei = java.time.ZoneId.of("Asia/Taipei");
        long period1 = start.atStartOfDay(taipei).toEpochSecond();
        long period2 = end.plusDays(1).atStartOfDay(taipei).toEpochSecond();
        String suffix = "OTC".equals(market) ? ".TWO" : ".TW";
        return yahooBaseUrl + "/" + stockId + suffix + "?interval=1d&period1=" + period1 + "&period2=" + period2;
    }

    private String finmindDailyUrl(String stockId, LocalDate start, LocalDate end) {
        return finmindBaseUrl + "?dataset=TaiwanStockPrice&data_id=" + stockId
                + "&start_date=" + start + "&end_date=" + end;
    }

    private String yahooFixture(String stockId, LocalDate date, String close) {
        long epoch = date.atTime(9, 0).atZone(java.time.ZoneId.of("Asia/Taipei")).toEpochSecond();
        return "{\"chart\":{\"result\":[{\"timestamp\":[" + epoch + "],\"indicators\":{\"quote\":[{"
                + "\"open\":[" + close + "],\"high\":[" + close + "],\"low\":[" + close + "],"
                + "\"close\":[" + close + "],\"volume\":[1000]}]}}],\"error\":null}}";
    }

    private void waitUntilNotRunning(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (jobRunningRegistry.isRunning(JOB_TYPE) && System.currentTimeMillis() < deadline) {
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
}
