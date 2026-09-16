package com.stock;

import com.stock.config.BackfillProperties;
import com.stock.domain.Stock;
import com.stock.domain.StockInstitutionalTrade;
import com.stock.dto.BackfillRequest;
import com.stock.dto.BackfillResponse;
import com.stock.dto.DailySyncRequest;
import com.stock.dto.DailySyncResponse;
import com.stock.mapper.StockInstitutionalTradeMapper;
import com.stock.mapper.StockMapper;
import com.stock.service.InstitutionalTradeCatchUpRunner;
import com.stock.service.InstitutionalTradeIngestionService;
import com.stock.service.JobRunningRegistry;
import com.stock.service.external.SourceAvailabilityTracker;
import com.stock.service.external.dto.NormalizedInstitutionalTradeRow;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * End-to-end (real DB + mocked external HTTP) coverage for the institutional-trade background
 * catch-up (spec: specs/backend/institutional-trade-ingestion.md). Unlike every other backfill
 * path, "which dates are missing" is a GLOBAL query across the whole {@code stock_daily_price} /
 * {@code stock_institutional_trade} tables (spec: 缺少日期的認定), not scoped to any one test's
 * stock ids -- so every test that drives the real {@link InstitutionalTradeCatchUpRunner} must
 * first neutralize whichever real trading days the rest of this dev database has already
 * accumulated in {@code stock_daily_price} but not yet in {@code stock_institutional_trade} (which
 * is every real trading day today, since this is a brand new table). {@link #maskOtherMissingDates}
 * does this by inserting a harmless placeholder row (stock_id {@code __MASK__}, never a real
 * master row, no FK to violate) for every currently-missing date this test does NOT care about,
 * so {@code findMissingTradeDates} returns exactly the date(s) under test. This is cleaned up in
 * {@link #tearDown()} on every run, real dev data is otherwise never modified.
 *
 * <p>Needs its own Spring context (the shared test profile default is
 * {@code app.institutional-trade.catch-up.enabled=false}, per the spec's own requirement that CI
 * must be able to turn this off) -- overridden to {@code true} here via {@link TestPropertySource}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.institutional-trade.catch-up.enabled=true")
class StockInstitutionalTradeIngestionIntegrationTest {

    private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MMdd");
    private static final String T86_FIELDS = "\"證券代號\",\"證券名稱\",\"外陸資買進股數(不含外資自營商)\","
            + "\"外陸資賣出股數(不含外資自營商)\",\"外陸資買賣超股數(不含外資自營商)\",\"外資自營商買進股數\",\"外資自營商賣出股數\","
            + "\"外資自營商買賣超股數\",\"投信買進股數\",\"投信賣出股數\",\"投信買賣超股數\",\"自營商買賣超股數\","
            + "\"自營商買進股數(自行買賣)\",\"自營商賣出股數(自行買賣)\",\"自營商買賣超股數(自行買賣)\",\"自營商買進股數(避險)\","
            + "\"自營商賣出股數(避險)\",\"自營商買賣超股數(避險)\",\"三大法人買賣超股數\"";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("externalApiRestTemplate")
    private RestTemplate externalApiRestTemplate;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockInstitutionalTradeMapper tradeMapper;

    @Autowired
    private InstitutionalTradeIngestionService ingestionService;

    @Autowired
    private InstitutionalTradeCatchUpRunner institutionalTradeCatchUpRunner;

    @Autowired
    private JobRunningRegistry jobRunningRegistry;

    @Autowired
    private SourceAvailabilityTracker sourceAvailabilityTracker;

    @Autowired
    private DataSource dataSource;

    @Value("${app.external.twse-t86-url}")
    private String t86Url;

    @Value("${app.external.twse-mi-index-url}")
    private String miIndexUrl;

    @Value("${app.external.twse-daily-all-url}")
    private String dailyAllUrl;

    @Autowired
    private BackfillProperties backfillProperties;

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
        jdbc.update("DELETE FROM stock_institutional_trade WHERE stock_id = '__MASK__'");
        jdbc.update("DELETE FROM stock_institutional_trade WHERE stock_id LIKE 'T7%'");
        jdbc.update("DELETE FROM stock_institutional_trade WHERE stock_id IN ('2609','00632R') AND trade_date = '2026-09-11'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id = '2609' AND trade_date = '2026-09-11'");
        jdbc.update("DELETE FROM stock_sync_progress WHERE stock_id LIKE 'T7%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'T7%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'T7%'");
    }

    // ---------- test isolation helper: mask every OTHER real missing date ----------

    /**
     * Inserts a harmless placeholder row for every date {@link StockInstitutionalTradeMapper#findMissingTradeDates}
     * currently reports missing, EXCEPT the ones passed in, so a real run of
     * {@link InstitutionalTradeCatchUpRunner} only ever sees the date(s) this test cares about
     * (see class Javadoc for why this is necessary).
     */
    private void maskOtherMissingDates(LocalDate... datesToLeaveOpen) {
        Set<LocalDate> keepOpen = new HashSet<>(Arrays.asList(datesToLeaveOpen));
        List<LocalDate> missing = tradeMapper.findMissingTradeDates(backfillProperties.getStartupCatchUp().getStartDate(), LocalDate.now());
        for (LocalDate date : missing) {
            if (!keepOpen.contains(date)) {
                jdbc.update("INSERT IGNORE INTO stock_institutional_trade "
                        + "(stock_id, trade_date, foreign_buy_shares, foreign_sell_shares, foreign_net_shares, "
                        + "foreign_dealer_buy_shares, foreign_dealer_sell_shares, foreign_dealer_net_shares, "
                        + "trust_buy_shares, trust_sell_shares, trust_net_shares, dealer_net_shares, "
                        + "dealer_self_buy_shares, dealer_self_sell_shares, dealer_self_net_shares, "
                        + "dealer_hedge_buy_shares, dealer_hedge_sell_shares, dealer_hedge_net_shares, "
                        + "total_net_shares, source) "
                        + "VALUES ('__MASK__', ?, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,'TEST')", date);
            }
        }
    }

    // ---------- 1. Real T86 data spot check (values captured live during this task) ----------

    @Test
    void realT86Fixture_2609And00632R_matchDocumentedValues_sourceIsTwse() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 11);
        // Marks 2026-09-11 as a trading day for stock_daily_price's purposes -- 2609 already exists
        // in the real stock master, this is the only row this test adds to stock_daily_price.
        jdbc.update("INSERT INTO stock_daily_price (stock_id, trade_date, open_price, high_price, low_price, "
                + "close_price, volume, turnover, transaction_count, source) VALUES "
                + "('2609', ?, 65.00, 66.00, 64.00, 65.50, 30195141, 1000000000, 1000, 'TWSE')", date);
        maskOtherMissingDates(date);

        // Real T86 data for date=20260911, captured live during this task.
        String body = "{\"stat\":\"OK\",\"hints\":\"單位：股\",\"fields\":[" + T86_FIELDS + "],\"data\":["
                + "[\"00632R\",\"元大台灣50反1   \",\"4,795,000\",\"74,787,000\",\"-69,992,000\",\"0\",\"0\",\"0\","
                + "\"0\",\"0\",\"0\",\"147,788,978\",\"600,000\",\"0\",\"600,000\",\"156,534,149\",\"9,345,171\","
                + "\"147,188,978\",\"77,796,978\"],"
                + "[\"2609\",\"陽明            \",\"46,546,290\",\"23,548,005\",\"22,998,285\",\"0\",\"0\",\"0\","
                + "\"105,000\",\"2,894\",\"102,106\",\"647,311\",\"363,568\",\"350,420\",\"13,148\",\"708,754\","
                + "\"74,591\",\"634,163\",\"23,747,702\"]"
                + "]}";
        mockServer.expect(requestTo(t86Url(date))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        institutionalTradeCatchUpRunner.triggerAsync();
        waitUntilInstitutionalCatchUpFinished(10_000);
        mockServer.verify();

        StockInstitutionalTrade row2609 = tradeMapper.findOne("2609", date);
        assertEquals(46546290L, row2609.getForeignBuyShares());
        assertEquals(23548005L, row2609.getForeignSellShares());
        assertEquals(22998285L, row2609.getForeignNetShares());
        assertEquals(102106L, row2609.getTrustNetShares());
        assertEquals(23747702L, row2609.getTotalNetShares());
        assertEquals("TWSE", row2609.getSource());

        StockInstitutionalTrade rowEtf = tradeMapper.findOne("00632R", date);
        assertEquals(-69992000L, rowEtf.getForeignNetShares());
        assertEquals("TWSE", rowEtf.getSource());
    }

    // ---------- 2. Master-only filtering: unknown id dropped, ETF/inactive written normally ----------

    @Test
    void masterFilter_unknownIdNeverWritten_stockRowCountUnchanged_inactiveAndEtfLikeIdsWrittenNormally()
            throws Exception {
        seedStock("T750", "母體內普通股", true);
        seedStock("T751", "母體內但停用", false);
        LocalDate date = LocalDate.of(2026, 3, 3);
        seedDailyPrice("T750", date);
        maskOtherMissingDates(date);

        long stockCountBefore = countStockTable();

        String body = t86Fixture(date, new String[]{"T750", "T751", "T7UNKNOWN"});
        mockServer.expect(requestTo(t86Url(date))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        institutionalTradeCatchUpRunner.triggerAsync();
        waitUntilInstitutionalCatchUpFinished(10_000);
        mockServer.verify();

        assertEquals(1, countRows("T750", date));
        assertEquals(1, countRows("T751", date)); // inactive but present in master -> still written
        assertEquals(0, countRows("T7UNKNOWN", date)); // not in master -> dropped

        assertEquals(stockCountBefore, countStockTable()); // no new `stock` row for the unknown id
        Integer unknownStockRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock WHERE stock_id = 'T7UNKNOWN'", Integer.class);
        assertEquals(0, unknownStockRow);
    }

    // ---------- 3. Idempotent upsert: same (stock_id, trade_date) rerun overwrites, not duplicates ----------

    @Test
    void applyDay_sameStockAndDateTwice_rowCountUnchanged_secondValueOverwritesFirst() {
        seedStock("T752", "重跑覆蓋", true);
        LocalDate date = LocalDate.of(2025, 11, 4);

        ingestionService.applyDay(List.of(row("T752", date, 1000)));
        assertEquals(1, countRows("T752", date));
        assertEquals(1000L, tradeMapper.findOne("T752", date).getForeignBuyShares());

        ingestionService.applyDay(List.of(row("T752", date, 2000)));
        assertEquals(1, countRows("T752", date));
        assertEquals(2000L, tradeMapper.findOne("T752", date).getForeignBuyShares());
    }

    // ---------- 4. Atomic write: a mid-batch failure rolls back the WHOLE day, not just the bad row ----------

    @Test
    void applyDay_midBatchFailure_rollsBackWholeDay_dayStaysMissingForNextTrigger() {
        seedStock("T753", "交易邊界一", true);
        seedStock("T754", "交易邊界二", true);
        LocalDate date = LocalDate.of(2026, 3, 5);
        seedDailyPrice("T753", date);

        // A row whose trade_date is null cannot be inserted (trade_date is part of the composite
        // PRIMARY KEY, NOT NULL) -- a genuine, protocol-independent SQL failure, not a fragile
        // simulation. It is deliberately the THIRD row so the first two (T753, T754) really do
        // reach the database before the failure, proving the transaction rolls back writes that
        // already happened, not merely that it "did nothing".
        NormalizedInstitutionalTradeRow poison = new NormalizedInstitutionalTradeRow(
                "T753", null, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertThrows(RuntimeException.class, () -> ingestionService.applyDay(
                List.of(row("T753", date, 100), row("T754", date, 200), poison)));

        assertEquals(0, countRows("T753", date));
        assertEquals(0, countRows("T754", date));

        // The day must still be reported as missing -- "有列＝已抓" never became true for it.
        maskOtherMissingDates(date);
        List<LocalDate> missing = tradeMapper.findMissingTradeDates(backfillProperties.getStartupCatchUp().getStartDate(), LocalDate.now());
        assertTrue(missing.contains(date));
    }

    // ---------- 5. Missing-date detection: single query, exact set, ascending, weekends excluded ----------

    @Test
    void findMissingTradeDates_singleQuery_returnsExactSetAscending_datesWithoutDailyPriceNeverIncluded() {
        LocalDate tradingDay1 = LocalDate.of(2025, 11, 10);
        LocalDate tradingDay2 = LocalDate.of(2025, 11, 11);
        LocalDate alreadyCaughtUp = LocalDate.of(2025, 11, 12);
        LocalDate weekendNeverSeeded = LocalDate.of(2025, 11, 9); // deliberately has no stock_daily_price row

        seedStock("T755", "缺少日期測試", true);
        seedDailyPrice("T755", tradingDay1);
        seedDailyPrice("T755", tradingDay2);
        seedDailyPrice("T755", alreadyCaughtUp);
        ingestionService.applyDay(List.of(row("T755", alreadyCaughtUp, 10)));

        List<LocalDate> missing = tradeMapper.findMissingTradeDates(tradingDay1.minusDays(5), alreadyCaughtUp);

        assertEquals(Arrays.asList(tradingDay1, tradingDay2), missing); // ascending, exact set
        assertFalse(missing.contains(alreadyCaughtUp));
        assertFalse(missing.contains(weekendNeverSeeded));
    }

    // ---------- 6. Runner-level: exactly one T86 request per missing date, zero for already-covered ----------

    @Test
    void catchUpRun_oneRequestPerMissingDate_zeroRequestsWhenAllAlreadyCoveredOrMasked() throws Exception {
        seedStock("T756", "請求數測試", true);
        LocalDate missingDay = LocalDate.of(2026, 3, 7);
        LocalDate alreadyCoveredDay = LocalDate.of(2026, 3, 8);
        seedDailyPrice("T756", missingDay);
        seedDailyPrice("T756", alreadyCoveredDay);
        ingestionService.applyDay(List.of(row("T756", alreadyCoveredDay, 10)));
        maskOtherMissingDates(missingDay);

        mockServer.expect(requestTo(t86Url(missingDay)))
                .andRespond(withSuccess(t86Fixture(missingDay, new String[]{"T756"}), MediaType.APPLICATION_JSON));
        // No expectation registered for alreadyCoveredDay -- any request to it fails mockServer.verify().

        institutionalTradeCatchUpRunner.triggerAsync();
        waitUntilInstitutionalCatchUpFinished(10_000);
        mockServer.verify();

        assertEquals(1, countRows("T756", missingDay));
    }

    @Test
    void catchUpRun_allDatesAlreadyCoveredOrMasked_zeroExternalRequests() throws Exception {
        maskOtherMissingDates(); // leave nothing open

        institutionalTradeCatchUpRunner.triggerAsync();
        waitUntilInstitutionalCatchUpFinished(10_000);
        mockServer.verify(); // zero registered expectations -- any request at all fails this
    }

    // ---------- 7. Failure handling: non-trading-day shape and format error both skip, continue ----------

    @Test
    void nonTradingDayAndMalformedDates_bothSkippedWithoutFailure_validDateStillWritten() throws Exception {
        seedStock("T757", "失敗處置測試", true);
        LocalDate nonTradingLike = LocalDate.of(2026, 3, 10);
        LocalDate malformed = LocalDate.of(2026, 3, 11);
        LocalDate valid = LocalDate.of(2026, 3, 12);
        seedDailyPrice("T757", nonTradingLike);
        seedDailyPrice("T757", malformed);
        seedDailyPrice("T757", valid);
        maskOtherMissingDates(nonTradingLike, malformed, valid);

        // Real non-trading-day shape, captured live from date=20260913 (Sunday).
        mockServer.expect(requestTo(t86Url(nonTradingLike))).andRespond(
                withSuccess("{\"stat\":\"很抱歉，沒有符合條件的資料!\",\"total\":0}", MediaType.APPLICATION_JSON));
        // Missing a required field name ("三大法人買賣超股數") -> format error.
        String missingFieldFixture = "{\"stat\":\"OK\",\"fields\":[\"證券代號\",\"證券名稱\"],"
                + "\"data\":[[\"T757\",\"測試\"]]}";
        mockServer.expect(requestTo(t86Url(malformed)))
                .andRespond(withSuccess(missingFieldFixture, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(t86Url(valid)))
                .andRespond(withSuccess(t86Fixture(valid, new String[]{"T757"}), MediaType.APPLICATION_JSON));

        institutionalTradeCatchUpRunner.triggerAsync();
        waitUntilInstitutionalCatchUpFinished(10_000);
        mockServer.verify();

        assertEquals(0, countRows("T757", nonTradingLike));
        assertEquals(0, countRows("T757", malformed));
        assertEquals(1, countRows("T757", valid));
    }

    // ---------- 8. Block: ends the run immediately without retry; recovers automatically after cooldown ----------

    @Test
    void blockedResponse_endsRunImmediately_recoversAutomaticallyOnNextTriggerAfterCooldown() throws Exception {
        seedStock("T758", "封鎖測試", true);
        LocalDate day = LocalDate.of(2026, 3, 14);
        seedDailyPrice("T758", day);
        maskOtherMissingDates(day);

        mockServer.expect(requestTo(t86Url(day))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertTrue(sourceAvailabilityTracker.isAvailable("TWSE"));
        institutionalTradeCatchUpRunner.triggerAsync();
        waitUntilInstitutionalCatchUpFinished(10_000);
        mockServer.verify();

        assertEquals(0, countRows("T758", day));
        assertFalse(sourceAvailabilityTracker.isAvailable("TWSE"));

        // Cooldown is 1s in the test profile (app.backfill.rate-limit.default-block-cooldown-seconds).
        Thread.sleep(1_200);
        mockServer.reset();
        mockServer.expect(requestTo(t86Url(day)))
                .andRespond(withSuccess(t86Fixture(day, new String[]{"T758"}), MediaType.APPLICATION_JSON));

        institutionalTradeCatchUpRunner.triggerAsync();
        waitUntilInstitutionalCatchUpFinished(10_000);
        mockServer.verify();

        assertEquals(1, countRows("T758", day));
    }

    // ---------- 9. Timeout: retried with backoff, then skipped; continues to next date ----------

    @Test
    void timeout_retriedThenSkipped_continuesToNextDate() throws Exception {
        seedStock("T759", "逾時重試測試", true);
        LocalDate timeoutDay = LocalDate.of(2026, 3, 16);
        LocalDate validDay = LocalDate.of(2026, 3, 17);
        seedDailyPrice("T759", timeoutDay);
        seedDailyPrice("T759", validDay);
        maskOtherMissingDates(timeoutDay, validDay);

        // Test profile's app.backfill.rate-limit.max-retries=3 -> 1 initial + 3 retries = 4 attempts.
        for (int i = 0; i < 4; i++) {
            mockServer.expect(requestTo(t86Url(timeoutDay))).andRespond(request -> {
                throw new java.io.IOException("simulated timeout");
            });
        }
        mockServer.expect(requestTo(t86Url(validDay)))
                .andRespond(withSuccess(t86Fixture(validDay, new String[]{"T759"}), MediaType.APPLICATION_JSON));

        institutionalTradeCatchUpRunner.triggerAsync();
        waitUntilInstitutionalCatchUpFinished(15_000);
        mockServer.verify();

        assertEquals(0, countRows("T759", timeoutDay));
        assertEquals(1, countRows("T759", validDay));
    }

    // ---------- 10. Trigger wiring: fires after a PRICE_BACKFILL batch completes ----------

    @Test
    void priceBackfillCompletion_triggersInstitutionalCatchUp_writesTheNewlyBackfilledDate() throws Exception {
        seedStock("T760", "回補觸發測試", true);
        LocalDate day = LocalDate.of(2026, 3, 19);
        // day might already be a real missing date (other stocks' stock_daily_price rows already
        // cover most of 2026) even before T760 has a row of its own -- explicitly keep it open.
        maskOtherMissingDates(day);

        mockServer.expect(requestTo(miIndexUrl(day)))
                .andRespond(withSuccess(miIndexFixture(day, "T760"), MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(t86Url(day)))
                .andRespond(withSuccess(t86Fixture(day, new String[]{"T760"}), MediaType.APPLICATION_JSON));

        BackfillRequest request = new BackfillRequest();
        request.setStartDate(day);
        request.setEndDate(day);
        request.setCommonStocksOnly(false);

        List<String> otherActiveIds = deactivateOtherActiveStocks();
        try {
            ResponseEntity<BackfillResponse> response = rest.postForEntity(
                    "/api/stocks/sync/backfill", request, BackfillResponse.class);
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

            waitUntilNotRunning(10_000); // PRICE_BACKFILL batch itself
            waitUntilInstitutionalCatchUpFinished(10_000); // the catch-up it triggers
            mockServer.verify();

            assertEquals(1, countRows("T760", day));
        } finally {
            restoreActiveStocks(otherActiveIds);
        }
    }

    // ---------- 11. Trigger wiring: fires after a successful daily increment ----------

    @Test
    void dailySyncSuccess_triggersInstitutionalCatchUp_writesTheNewlySyncedDate() throws Exception {
        LocalDate day = LocalDate.of(2026, 3, 21); // arbitrary date not otherwise used by this suite
        // day might already be a real missing date (other stocks' stock_daily_price rows already
        // cover most of 2026) even before T761 has a row of its own -- explicitly keep it open.
        maskOtherMissingDates(day);
        String twseFixture = "[{\"Date\":\"" + rocDate(day) + "\",\"Code\":\"T761\",\"Name\":\"每日觸發測試\","
                + "\"TradeVolume\":\"1000\",\"TradeValue\":\"88000\",\"OpeningPrice\":\"88.00\","
                + "\"HighestPrice\":\"89.00\",\"LowestPrice\":\"87.50\",\"ClosingPrice\":\"88.50\","
                + "\"Transaction\":\"12\"}]";
        mockServer.expect(requestTo(dailyAllUrl))
                .andRespond(withSuccess(twseFixture, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(t86Url(day)))
                .andRespond(withSuccess(t86Fixture(day, new String[]{"T761"}), MediaType.APPLICATION_JSON));

        ResponseEntity<DailySyncResponse> response = rest.postForEntity(
                "/api/stocks/sync/daily", new DailySyncRequest(), DailySyncResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(day, response.getBody().getTradeDate());

        waitUntilInstitutionalCatchUpFinished(10_000);
        mockServer.verify();

        assertEquals(1, countRows("T761", day));
    }

    // ---------- helpers ----------

    private void seedStock(String stockId, String name, boolean active) {
        stockMapper.upsert(new Stock(stockId, name, "TSE", active));
    }

    private void seedDailyPrice(String stockId, LocalDate date) {
        jdbc.update("INSERT INTO stock_daily_price (stock_id, trade_date, open_price, high_price, low_price, "
                + "close_price, volume, turnover, transaction_count, source) VALUES "
                + "(?, ?, 10.00, 10.50, 9.50, 10.20, 1000, 10000, 5, 'TWSE')", stockId, date);
    }

    private NormalizedInstitutionalTradeRow row(String stockId, LocalDate date, long foreignBuy) {
        return new NormalizedInstitutionalTradeRow(stockId, date, foreignBuy, 0, foreignBuy,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, foreignBuy);
    }

    private int countRows(String stockId, LocalDate date) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_institutional_trade WHERE stock_id = ? AND trade_date = ?",
                Integer.class, stockId, date);
        return count == null ? 0 : count;
    }

    private long countStockTable() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM stock", Long.class);
        return count == null ? 0 : count;
    }

    /** Formats a western LocalDate as a compact ROC (Minguo) date string, e.g. 2026-03-21 -> "1150321". */
    private String rocDate(LocalDate date) {
        return (date.getYear() - 1911) + date.format(MMDD);
    }

    private String t86Url(LocalDate date) {
        return t86Url + "?date=" + date.format(DATE_PARAM) + "&selectType=ALLBUT0999&response=json";
    }

    private String t86Fixture(LocalDate date, String[] stockIds) {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < stockIds.length; i++) {
            if (i > 0) {
                data.append(",");
            }
            data.append("[\"").append(stockIds[i]).append("\",\"測試股\",\"1,000\",\"200\",\"800\","
                    + "\"0\",\"0\",\"0\",\"100\",\"50\",\"50\",\"10\",\"5\",\"3\",\"2\",\"5\",\"3\",\"2\",\"62\"]");
        }
        return "{\"stat\":\"OK\",\"hints\":\"單位：股\",\"fields\":[" + T86_FIELDS + "],\"data\":[" + data + "]}";
    }

    private String miIndexUrl(LocalDate date) {
        return miIndexUrl + "?date=" + date.format(DATE_PARAM) + "&type=ALL&response=json";
    }

    private String miIndexFixture(LocalDate date, String stockId) {
        String stockFields = "\"證券代號\",\"證券名稱\",\"成交股數\",\"成交筆數\",\"成交金額\","
                + "\"開盤價\",\"最高價\",\"最低價\",\"收盤價\",\"漲跌(+/-)\",\"漲跌價差\",\"最後揭示買價\",\"最後揭示買量\","
                + "\"最後揭示賣價\",\"最後揭示賣量\",\"本益比\"";
        String row = "[\"" + stockId + "\",\"測試股\",\"1,000,000\",\"500\",\"12,345,678\",\"15.00\",\"15.50\","
                + "\"14.50\",\"15.20\",\"<p style='color:red'>+</p>\",\"0.10\",\"\",\"\",\"\",\"\",\"15.0\"]";
        return "{\"tables\":[{\"fields\":[" + stockFields + "],\"data\":[" + row + "]}]}";
    }

    private List<String> deactivateOtherActiveStocks() {
        List<String> otherActiveIds = jdbc.queryForList(
                "SELECT stock_id FROM stock WHERE is_active = 1 AND stock_id NOT LIKE 'T7%'", String.class);
        jdbc.update("UPDATE stock SET is_active = 0 WHERE stock_id NOT LIKE 'T7%'");
        return otherActiveIds;
    }

    private void restoreActiveStocks(List<String> ids) {
        if (!ids.isEmpty()) {
            jdbc.update("UPDATE stock SET is_active = 1 WHERE stock_id IN ("
                    + ids.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("") + ")", ids.toArray());
        }
    }

    private void waitUntilNotRunning(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (jobRunningRegistry.isRunning("PRICE_BACKFILL") && System.currentTimeMillis() < deadline) {
            sleep(50);
        }
    }

    private void waitUntilInstitutionalCatchUpFinished(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        // Give the @Async dispatch a moment to actually start before polling for completion, so a
        // very fast run isn't mistaken for "never started".
        sleep(50);
        while (institutionalTradeCatchUpRunner.isRunning() && System.currentTimeMillis() < deadline) {
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
