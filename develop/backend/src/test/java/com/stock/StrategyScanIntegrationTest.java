package com.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.dto.ErrorResponse;
import com.stock.dto.ScanRequestDto;
import com.stock.dto.StrategySelectionDto;
import com.stock.support.QueryCountInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for GET /api/strategies and POST /api/strategies/scan —
 * specs/backend/strategy-scan.md. Uses "SS"-prefixed synthetic stock ids so this class can run
 * alongside the other integration test classes without interfering with them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StrategyScanIntegrationTest {

    private static final String MAPPER_NAMESPACE = "com.stock.mapper.StockDailyPriceMapper.";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private QueryCountInterceptor queryCountInterceptor;

    private JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    private void cleanupTestData() {
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'SS%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'SS%'");
    }

    // ==================== AC1: catalogue ====================

    @Test
    void catalog_containsBoxBreakoutAndHigherLowsWithThreePresetsEachMatchingSpecWording() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/strategies", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode strategies = root.get("strategies");
        // 5 total since increment 2 added RISING_SUPPORT and increment 3 added REBOUND/CUMULATIVE_RISE
        // — see catalog_returnsThreeStrategiesIncludingRisingSupportMatchingSpecWording and
        // catalog_returnsFiveStrategiesIncludingReboundAndCumulativeRiseMatchingSpecWording below.
        assertEquals(5, strategies.size());

        JsonNode box = findByCode(strategies, "BOX_BREAKOUT");
        assertEquals("箱型突破", box.get("name").asText());
        assertEquals(3, box.get("presets").size());
        assertEquals("回看 60 根，箱高 < 5%，突破 2% 且量增 2 倍，需連 2 根確認",
                findPresetByCode(box.get("presets"), "STRICT").get("description").asText());
        assertEquals("回看 20 根，箱高 < 8%，突破 1.5% 且量增 1.5 倍",
                findPresetByCode(box.get("presets"), "STANDARD").get("description").asText());
        assertEquals("回看 20 根，不驗證盤整，收盤突破上緣即計",
                findPresetByCode(box.get("presets"), "LOOSE").get("description").asText());

        JsonNode higherLows = findByCode(strategies, "HIGHER_LOWS");
        assertEquals("底底高", higherLows.get("name").asText());
        assertEquals(3, higherLows.get("presets").size());
        assertEquals("以 5 日均線為基準，左右各 5 根，需 3 段遞增，每段高過 2%",
                findPresetByCode(higherLows.get("presets"), "STRICT").get("description").asText());
        assertEquals("以 5 日均線為基準，左右各 3 根，需 2 段遞增，每段高過 1%",
                findPresetByCode(higherLows.get("presets"), "STANDARD").get("description").asText());
        assertEquals("以 5 日均線為基準，左右各 2 根，需 2 段遞增，高過即計",
                findPresetByCode(higherLows.get("presets"), "LOOSE").get("description").asText());
    }

    // ==================== AC17: no advice/recommendation wording ====================

    @Test
    void catalogAndScanResponses_containNoAdviceWording() throws Exception {
        ResponseEntity<String> catalogResponse = rest.getForEntity("/api/strategies", String.class);
        assertNoAdviceWording(catalogResponse.getBody());

        seedStock("SS901", "文案語氣測試", true);
        List<LocalDate> dates = seedTightBox("SS901", LocalDate.of(2026, 1, 1), 20, "100.00", "103.00", "99.00", 1000);
        LocalDate breakoutDate = dates.get(dates.size() - 1).plusDays(1);
        insertPriceRow("SS901", breakoutDate, "104.00", "106.00", "104.00", "106.09", 2500);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"BOX_BREAKOUT", "STANDARD"}),
                Collections.singletonList("SS901"), breakoutDate, breakoutDate);
        ResponseEntity<String> scanResponse = rest.postForEntity("/api/strategies/scan", request, String.class);
        assertEquals(HttpStatus.OK, scanResponse.getStatusCode());
        assertNoAdviceWording(scanResponse.getBody());
    }

    private void assertNoAdviceWording(String body) {
        assertFalse(body.contains("建議"), "must not contain 建議: " + body);
        assertFalse(body.contains("推薦"), "must not contain 推薦: " + body);
    }

    // ==================== AC2: default date range ====================

    @Test
    void scan_omittedDates_defaultsToTodayMinusOneCalendarMonthThroughToday() throws Exception {
        seedStock("SS001", "預設區間測試", true);

        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("HIGHER_LOWS", "LOOSE")));
        request.setStockIds(Collections.singletonList("SS001"));

        JsonNode root = postScan(request);
        LocalDate today = LocalDate.now();
        assertEquals(today.minusMonths(1).toString(), root.get("startDate").asText());
        assertEquals(today.toString(), root.get("endDate").asText());
    }

    // ==================== AC3: omitted stockIds scans all active ====================

    @Test
    void scan_omittedStockIds_scansAllActiveStocks_scannedStocksMatchesActualCount() throws Exception {
        seedStock("SS011", "全市場一", true);
        seedStock("SS012", "全市場二", true);
        seedStock("SS013", "全市場已下市", false); // must be excluded from the ALL-active scan

        List<String> otherActiveIds = jdbc.queryForList(
                "SELECT stock_id FROM stock WHERE is_active = 1 AND stock_id NOT LIKE 'SS%'", String.class);
        jdbc.update("UPDATE stock SET is_active = 0 WHERE stock_id NOT LIKE 'SS%'");
        try {
            ScanRequestDto request = new ScanRequestDto();
            request.setStrategies(Collections.singletonList(selection("HIGHER_LOWS", "LOOSE")));
            request.setStartDate(LocalDate.of(2026, 1, 1));
            request.setEndDate(LocalDate.of(2026, 1, 10));
            // stockIds omitted -> ALL active. This AC is about is_active filtering, not
            // commonStocksOnly (increment 3) — the synthetic "SS..." ids used here are not
            // 4-digit codes, so they must not be filtered out by the default commonStocksOnly=true.
            request.setCommonStocksOnly(false);

            JsonNode root = postScan(request);
            assertEquals(2, root.get("scannedStocks").asInt());
        } finally {
            if (!otherActiveIds.isEmpty()) {
                jdbc.update("UPDATE stock SET is_active = 1 WHERE stock_id IN ("
                        + otherActiveIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("") + ")",
                        otherActiveIds.toArray());
            }
        }
    }

    // ==================== AC4: specified stockIds allow delisted ====================

    @Test
    void scan_specifiedStockIds_onlyScansThoseIncludingDelisted() throws Exception {
        seedStock("SS021", "指定-活躍", true);
        seedStock("SS022", "指定-已下市", false);
        seedStock("SS023", "未指定", true); // must be excluded even though active

        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("HIGHER_LOWS", "LOOSE")));
        request.setStockIds(Arrays.asList("SS021", "SS022"));
        request.setStartDate(LocalDate.of(2026, 1, 1));
        request.setEndDate(LocalDate.of(2026, 1, 10));

        JsonNode root = postScan(request);
        assertEquals(2, root.get("scannedStocks").asInt());
    }

    // ==================== AC5: BOX_BREAKOUT STANDARD hand-calculated hit ====================

    @Test
    void boxBreakout_standard_matchesHandCalculatedBoxHeightBreakoutAndVolume() throws Exception {
        String stockId = "SS101";
        seedStock(stockId, "箱高6趴測試", true);
        LocalDate start = LocalDate.of(2026, 2, 1);
        // 20 lookback bars: high=103, low=97 -> boxHigh=103, boxLow=97, range = 6/100 = 6% (< 8% OK)
        List<LocalDate> lookbackDates = seedTightBox(stockId, start, 20, "100.00", "103.00", "97.00", 1000);
        LocalDate breakoutDate = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        // close = 103 * 1.02 = 105.06 -> breakoutPercent exactly 2.00%; volume = 1000*1.8=1800 -> ratio 1.80
        insertPriceRow(stockId, breakoutDate, "104.00", "106.00", "104.00", "105.06", 1800);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"BOX_BREAKOUT", "STANDARD"}),
                Collections.singletonList(stockId), breakoutDate, breakoutDate);
        JsonNode root = postScan(request);
        JsonNode result = root.get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt());
        JsonNode item = result.get("items").get(0);
        assertEquals(stockId, item.get("stockId").asText());
        assertEquals(breakoutDate.toString(), item.get("signalDate").asText());
        JsonNode detail = item.get("detail");
        assertBigDecimalEquals("103.00", detail.get("boxHigh"));
        assertBigDecimalEquals("97.00", detail.get("boxLow"));
        assertBigDecimalEquals("105.06", detail.get("breakoutClose"));
        assertBigDecimalEquals("2.00", detail.get("breakoutPercent"));
        assertBigDecimalEquals("1.80", detail.get("volumeRatio"));
        assertTrue(result.get("insufficientData").isEmpty());
        assertTrue(result.get("pendingConfirm").isEmpty());
    }

    // ==================== AC6: consolidation precondition ====================

    @Test
    void boxBreakout_consolidationPrecondition_standardRejectsButLooseAccepts15PercentRange() throws Exception {
        String stockId = "SS111";
        seedStock(stockId, "盤整前提測試", true);
        LocalDate start = LocalDate.of(2026, 3, 1);
        List<LocalDate> dates = new ArrayList<>();
        LocalDate d = start;
        for (int i = 0; i < 20; i++) {
            dates.add(d);
            if (i == 0) {
                insertPriceRow(stockId, d, "93.00", "93.50", "92.50", "93.00", 1000); // global min low = 92.50
            } else if (i == 19) {
                insertPriceRow(stockId, d, "107.00", "107.50", "106.50", "107.00", 1000); // global max high = 107.50
            } else {
                insertPriceRow(stockId, d, "100.00", "100.00", "95.00", "100.00", 1000);
            }
            d = d.plusDays(1);
        }
        // boxHigh=107.50, boxLow=92.50 -> mid=100, range = 15/100 = 15% (>= 8% STANDARD cap, not validated by LOOSE)
        LocalDate breakoutDate = d;
        insertPriceRow(stockId, breakoutDate, "109.00", "111.00", "109.00", "110.00", 1000);

        ScanRequestDto standardRequest = scanRequest(
                Collections.singletonList(new String[]{"BOX_BREAKOUT", "STANDARD"}),
                Collections.singletonList(stockId), breakoutDate, breakoutDate);
        JsonNode standardResult = postScan(standardRequest).get("results").get(0);
        assertEquals(0, standardResult.get("matchedCount").asInt(), "15% box range must fail STANDARD's 8% cap");

        ScanRequestDto looseRequest = scanRequest(
                Collections.singletonList(new String[]{"BOX_BREAKOUT", "LOOSE"}),
                Collections.singletonList(stockId), breakoutDate, breakoutDate);
        JsonNode looseResult = postScan(looseRequest).get("results").get(0);
        assertEquals(1, looseResult.get("matchedCount").asInt(), "LOOSE must not validate the consolidation range");
        assertEquals(breakoutDate.toString(), looseResult.get("items").get(0).get("signalDate").asText());
    }

    // ==================== AC7: STRICT confirmBars=2 pendingConfirm ====================

    @Test
    void boxBreakout_strict_lastDayBreakoutWithNoNextDayData_isPendingConfirmNotItem() throws Exception {
        String stockId = "SS121";
        seedStock(stockId, "待確認測試", true);
        LocalDate start = LocalDate.of(2026, 4, 1);
        // 60 lookback bars: high=103, low=99 -> boxHigh=103, boxLow=99, range = 4/101 = 3.96% (< 5% STRICT cap)
        List<LocalDate> lookbackDates = seedTightBox(stockId, start, 60, "100.00", "103.00", "99.00", 1000);
        LocalDate breakoutDate = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        // close = 103 * 1.03 = 106.09 (breakout 3% > 2%); volume = 1000*2.5=2500 (ratio 2.5 > 2.0)
        insertPriceRow(stockId, breakoutDate, "104.00", "108.00", "104.00", "106.09", 2500);
        // deliberately no next-day row -> "無次日資料"

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"BOX_BREAKOUT", "STRICT"}),
                Collections.singletonList(stockId), breakoutDate, breakoutDate);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(0, result.get("matchedCount").asInt());
        assertTrue(result.get("items").isEmpty());
        List<String> pendingConfirm = toStringList(result.get("pendingConfirm"));
        assertTrue(pendingConfirm.contains(stockId));
    }

    // ==================== AC14: adjacency across a suspension gap (same fixture as AC7,
    //                       but the next trading day exists 2 calendar days later) ====================

    @Test
    void boxBreakout_strict_confirmationSkipsCalendarGapWithoutInterpolation() throws Exception {
        String stockId = "SS122";
        seedStock(stockId, "跳過停牌確認測試", true);
        LocalDate start = LocalDate.of(2026, 4, 1);
        List<LocalDate> lookbackDates = seedTightBox(stockId, start, 60, "100.00", "103.00", "99.00", 1000);
        LocalDate breakoutDate = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertPriceRow(stockId, breakoutDate, "104.00", "108.00", "104.00", "106.09", 2500);
        // breakoutDate + 1 calendar day is a suspension (no row at all)
        LocalDate confirmDate = breakoutDate.plusDays(2); // next *trading* day, list-adjacent to breakoutDate
        insertPriceRow(stockId, confirmDate, "106.00", "112.00", "106.00", "110.00", 1500);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"BOX_BREAKOUT", "STRICT"}),
                Collections.singletonList(stockId), breakoutDate, confirmDate);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "confirmation must use the next *trading* day (list-adjacent), not require the very next calendar day");
        assertEquals(breakoutDate.toString(), result.get("items").get(0).get("signalDate").asText());
        assertTrue(result.get("pendingConfirm").isEmpty());
    }

    // ==================== AC12: most recent hit is reported when multiple occur ====================

    @Test
    void boxBreakout_multipleHitsInRange_signalDateIsMostRecent() throws Exception {
        String stockId = "SS131";
        seedStock(stockId, "多次命中測試", true);
        LocalDate start = LocalDate.of(2026, 5, 1);
        List<LocalDate> lookback1 = seedTightBox(stockId, start, 20, "100.00", "103.00", "99.00", 1000);
        LocalDate firstBreakout = lookback1.get(lookback1.size() - 1).plusDays(1);
        insertPriceRow(stockId, firstBreakout, "104.00", "106.00", "104.00", "105.00", 1000); // LOOSE: close>103

        LocalDate secondBoxStart = firstBreakout.plusDays(1);
        List<LocalDate> lookback2 = seedTightBox(stockId, secondBoxStart, 20, "100.00", "103.00", "99.00", 1000);
        LocalDate secondBreakout = lookback2.get(lookback2.size() - 1).plusDays(1);
        insertPriceRow(stockId, secondBreakout, "104.00", "112.00", "104.00", "110.00", 1000);

        // startDate = firstBreakout: lookback1 (20 days) is fetched as pre-startDate lookback data,
        // exactly satisfying LOOSE's 20-day requirement; the window [firstBreakout, secondBreakout]
        // then contains both breakouts for this one stock.
        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"BOX_BREAKOUT", "LOOSE"}),
                Collections.singletonList(stockId), firstBreakout, secondBreakout);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(), "same stock hit twice must collapse to one item");
        JsonNode item = result.get("items").get(0);
        assertEquals(secondBreakout.toString(), item.get("signalDate").asText());
        assertBigDecimalEquals("110.00", item.get("detail").get("breakoutClose"));
    }

    // ==================== AC13: sort by signalDate desc, then stockId asc ====================

    @Test
    void items_sortedBySignalDateDescendingThenStockIdAscending() throws Exception {
        LocalDate x = LocalDate.of(2026, 6, 21); // shared window start for all three stocks

        // SSC hits on day X itself (the earliest day in the shared window).
        seedStock("SSC", "排序C", true);
        seedTightBox("SSC", x.minusDays(20), 20, "100.00", "103.00", "99.00", 1000); // pre-X lookback
        insertPriceRow("SSC", x, "104.00", "106.00", "104.00", "105.00", 1000);

        // SSA and SSB hit later, on the same day X+10 -> tied signalDate, broken by stockId asc.
        LocalDate lateDate = x.plusDays(10);
        for (String id : new String[]{"SSA", "SSB"}) {
            seedStock(id, "排序" + id, true);
            // Covers X-20..X+9 (30 consecutive days) with a consistent box so the rolling 20-day
            // lookback for the X+10 breakout is tight regardless of where it falls.
            seedTightBox(id, x.minusDays(20), 30, "100.00", "103.00", "99.00", 1000);
            insertPriceRow(id, lateDate, "104.00", "106.00", "104.00", "105.00", 1000);
        }

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"BOX_BREAKOUT", "LOOSE"}),
                Arrays.asList("SSA", "SSB", "SSC"), x, lateDate);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(3, result.get("matchedCount").asInt());
        JsonNode items = result.get("items");
        assertEquals("SSA", items.get(0).get("stockId").asText());
        assertEquals("SSB", items.get(1).get("stockId").asText());
        assertEquals("SSC", items.get(2).get("stockId").asText());
    }

    // ==================== AC8: HIGHER_LOWS STANDARD hit ====================

    @Test
    void higherLows_standard_threeRisingSwingLowsEachOver1Percent_matchesWithThreeLows() throws Exception {
        String stockId = "SS201";
        seedStock(stockId, "底底高標準測試", true);
        LocalDate day0 = LocalDate.of(2026, 7, 1);
        List<LocalDate> dates = seedHigherLowsFixture(stockId, day0,
                new String[]{"100.00", "102.00", "104.50"}); // +2%, +2.45%

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"HIGHER_LOWS", "STANDARD"}),
                Collections.singletonList(stockId), dates.get(10), dates.get(dates.size() - 1));
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt());
        JsonNode item = result.get("items").get(0);
        assertEquals(dates.get(higherLowsSignalIndex(2)).toString(), item.get("signalDate").asText());
        JsonNode lows = item.get("detail").get("lows");
        assertEquals(3, lows.size());
        assertEquals(dates.get(higherLowsSignalIndex(0)).toString(), lows.get(0).get("tradeDate").asText());
        assertBigDecimalEquals("100.00", lows.get(0).get("ma5"));
        assertBigDecimalEquals("100.00", lows.get(0).get("low"));
        assertEquals(dates.get(higherLowsSignalIndex(1)).toString(), lows.get(1).get("tradeDate").asText());
        assertBigDecimalEquals("102.00", lows.get(1).get("ma5"));
        assertBigDecimalEquals("102.00", lows.get(1).get("low"));
        assertEquals(dates.get(higherLowsSignalIndex(2)).toString(), lows.get(2).get("tradeDate").asText());
        assertBigDecimalEquals("104.50", lows.get(2).get("ma5"));
        assertBigDecimalEquals("104.50", lows.get(2).get("low"));
    }

    // ==================== 底底高改以 MA5 平滑線為判定基準 ====================

    @Test
    void higherLows_ma5UsesCloseNotLow_longLowerShadowDayIsNotASwingLow() throws Exception {
        String stockId = "SS202";
        seedStock(stockId, "長下影線雜訊測試", true);
        LocalDate day0 = LocalDate.of(2026, 7, 20);
        List<LocalDate> dates = seedHigherLowsFixture(stockId, day0,
                new String[]{"100.00", "102.00", "104.50"});
        // A single day deep inside a shoulder plateau (well away from any real dip's ±swingBars
        // window) with a long lower shadow: its LOW plunges to 10.00 but its CLOSE stays at the
        // shoulder's 200.00, so MA5 there is unaffected and it must NOT become a swing low. A
        // raw-low-based algorithm would have made this the deepest "low" of the whole series.
        LocalDate shadowDate = dates.get(17); // mid-shoulder between dip 0 and dip 1 (indices 14/25)
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id = ? AND trade_date = ?", stockId, shadowDate);
        insertPriceRow(stockId, shadowDate, "200.00", "201.00", "10.00", "200.00", 1000);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"HIGHER_LOWS", "STANDARD"}),
                Collections.singletonList(stockId), dates.get(10), dates.get(dates.size() - 1));
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "the long-lower-shadow day must not disturb the MA5-based swing-low pattern");
        JsonNode lows = result.get("items").get(0).get("detail").get("lows");
        assertEquals(3, lows.size());
        for (JsonNode low : lows) {
            assertNotEquals(shadowDate.toString(), low.get("tradeDate").asText(),
                    "the long-lower-shadow day (low=10.00, close=200.00) must not appear as a swing low");
        }
        assertEquals(dates.get(higherLowsSignalIndex(0)).toString(), lows.get(0).get("tradeDate").asText());
        assertEquals(dates.get(higherLowsSignalIndex(1)).toString(), lows.get(1).get("tradeDate").asText());
        assertEquals(dates.get(higherLowsSignalIndex(2)).toString(), lows.get(2).get("tradeDate").asText());
    }

    // ==================== AC9: LOOSE tolerance ====================

    @Test
    void higherLows_tolerance_pointThreePercentRisesFailStandardButPassLoose() throws Exception {
        String stockId = "SS211";
        seedStock(stockId, "容忍度測試", true);
        LocalDate day0 = LocalDate.of(2026, 8, 1);
        List<LocalDate> dates = seedHigherLowsFixture(stockId, day0,
                new String[]{"100.00", "100.30", "100.60"}); // ~0.3% each rise

        ScanRequestDto standardRequest = scanRequest(
                Collections.singletonList(new String[]{"HIGHER_LOWS", "STANDARD"}),
                Collections.singletonList(stockId), dates.get(10), dates.get(dates.size() - 1));
        JsonNode standardResult = postScan(standardRequest).get("results").get(0);
        assertEquals(0, standardResult.get("matchedCount").asInt(), "0.3% rises must not satisfy STANDARD's 1% floor");

        ScanRequestDto looseRequest = scanRequest(
                Collections.singletonList(new String[]{"HIGHER_LOWS", "LOOSE"}),
                Collections.singletonList(stockId), dates.get(10), dates.get(dates.size() - 1));
        JsonNode looseResult = postScan(looseRequest).get("results").get(0);
        assertEquals(1, looseResult.get("matchedCount").asInt(), "LOOSE only requires a strictly higher low");
    }

    // ==================== AC10: swing low needs both sides; boundary candidate excluded ====================

    @Test
    void higherLows_swingLowNearEndOfWindow_missingRightSideIsExcludedNotMatched() throws Exception {
        String stockId = "SS221";
        seedStock(stockId, "邊界排除測試", true);
        LocalDate day0 = LocalDate.of(2026, 9, 1);
        // 10 lead-in days (ample lookback for LOOSE's swingBars+4=6), then dip1 (ma5=100, signal at
        // its 5th day) and dip2 (ma5=102, +2% rise -> only 1 leg so far, LOOSE's requiredRises=2
        // needs 2). A third, would-be dip (104) is appended but the series is cut off immediately
        // after its 5-day flat run — no shoulder at all follows — so its signal day has no
        // right-side neighbor within LOOSE's swingBars=2 and must be excluded. Without it there are
        // only 2 swing lows (1 leg), not the 3 (2 legs) LOOSE requires -> no hit. If the boundary
        // exclusion were broken, this would wrongly become 3 swing lows (100 -> 102 -> 104) and hit.
        List<LocalDate> dates = seedHigherLowsFixture(stockId, day0, new String[]{"100.00", "102.00"});
        LocalDate wouldBeDipStart = dates.get(dates.size() - 1).plusDays(1);
        LocalDate d = wouldBeDipStart;
        for (int i = 0; i < 5; i++) {
            insertCloseOnlyRow(stockId, d, "104.00", 1000);
            dates.add(d);
            d = d.plusDays(1);
        }
        LocalDate startDate = dates.get(10);
        LocalDate endDate = dates.get(dates.size() - 1);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"HIGHER_LOWS", "LOOSE"}),
                Collections.singletonList(stockId), startDate, endDate);
        JsonNode result = postScan(request).get("results").get(0);
        assertTrue(result.get("insufficientData").isEmpty(), "left-side lookback was sufficient");
        assertEquals(0, result.get("matchedCount").asInt(),
                "the would-be third dip has no right-side neighbor at all and must be excluded, not matched");
    }

    // ==================== AC11: insufficient lookback -> insufficientData, excluded from items ====================

    @Test
    void boxBreakout_insufficientLookback_isReportedSeparatelyFromNoMatch() throws Exception {
        String stockId = "SS301";
        seedStock(stockId, "資料不足測試", true);
        LocalDate start = LocalDate.of(2026, 10, 1);
        // Only 10 lookback bars seeded, but STRICT requires 60 -> insufficientData.
        List<LocalDate> lookback = seedTightBox(stockId, start, 10, "100.00", "103.00", "99.00", 1000);
        LocalDate breakoutDate = lookback.get(lookback.size() - 1).plusDays(1);
        insertPriceRow(stockId, breakoutDate, "104.00", "108.00", "104.00", "106.09", 2500);

        // startDate = breakoutDate: the 10 lookback bars are fetched as pre-startDate lookback data
        // (an actual, counted shortfall of 10 < 60), not folded into the window itself.
        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"BOX_BREAKOUT", "STRICT"}),
                Collections.singletonList(stockId), breakoutDate, breakoutDate);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(0, result.get("matchedCount").asInt());
        assertTrue(result.get("items").isEmpty());
        List<String> insufficientData = toStringList(result.get("insufficientData"));
        assertTrue(insufficientData.contains(stockId));
    }

    // ==================== AC15: batched query count does not scale with stock count ====================

    @Test
    void scan_priceQueriesAreBatched_queryCountDoesNotScaleWithStockCount() throws Exception {
        List<String> fewIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = "SS40" + i;
            seedStock(id, "批次測試" + i, true);
            fewIds.add(id);
        }
        List<String> manyIds = new ArrayList<>(fewIds);
        for (int i = 3; i < 12; i++) {
            String id = "SS41" + i;
            seedStock(id, "批次測試" + i, true);
            manyIds.add(id);
        }

        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 10);

        queryCountInterceptor.reset(MAPPER_NAMESPACE);
        ScanRequestDto fewRequest = scanRequest(
                Collections.singletonList(new String[]{"HIGHER_LOWS", "LOOSE"}), fewIds, start, end);
        postScan(fewRequest);
        int fewCount = queryCountInterceptor.getCount();

        queryCountInterceptor.reset(MAPPER_NAMESPACE);
        ScanRequestDto manyRequest = scanRequest(
                Collections.singletonList(new String[]{"HIGHER_LOWS", "LOOSE"}), manyIds, start, end);
        postScan(manyRequest);
        int manyCount = queryCountInterceptor.getCount();

        assertEquals(2, fewCount, "expected exactly one lookback query + one window query");
        assertEquals(fewCount, manyCount, "price query count must not scale with the number of scanned stocks");
    }

    // ==================== AC16: six error codes ====================

    @Test
    void scan_emptyStrategies_rejectedWithNoStrategySelected() {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.emptyList());
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("NO_STRATEGY_SELECTED", response.getBody().getCode());
    }

    @Test
    void scan_unknownStrategyCode_rejectedWithUnknownStrategy() {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("FOO", "STANDARD")));
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("UNKNOWN_STRATEGY", response.getBody().getCode());
        assertTrue(response.getBody().getUnknown().contains("FOO"));
    }

    @Test
    void scan_duplicateStrategyCode_rejectedWithDuplicateStrategy() {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Arrays.asList(
                selection("BOX_BREAKOUT", "STANDARD"), selection("BOX_BREAKOUT", "STRICT")));
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("DUPLICATE_STRATEGY", response.getBody().getCode());
        assertTrue(response.getBody().getDuplicated().contains("BOX_BREAKOUT"));
    }

    @Test
    void scan_unknownStockId_rejectedWithUnknownStockId() {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("BOX_BREAKOUT", "STANDARD")));
        request.setStockIds(Collections.singletonList("SS9999"));
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("UNKNOWN_STOCK_ID", response.getBody().getCode());
        assertTrue(response.getBody().getUnknownIds().contains("SS9999"));
    }

    @Test
    void scan_tooManyStockIds_rejectedWithTooManyStocks() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            ids.add("X" + i);
        }
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("BOX_BREAKOUT", "STANDARD")));
        request.setStockIds(ids);
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("TOO_MANY_STOCKS", response.getBody().getCode());
    }

    @Test
    void scan_invalidDateRange_rejectedWithInvalidDateRange() {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("BOX_BREAKOUT", "STANDARD")));
        request.setStartDate(LocalDate.of(2026, 8, 10));
        request.setEndDate(LocalDate.of(2026, 8, 1));
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_DATE_RANGE", response.getBody().getCode());
    }

    // ==================== Increment 2 (RISING_SUPPORT) — AC1: catalogue now has 3 strategies ====================

    @Test
    void catalog_returnsThreeStrategiesIncludingRisingSupportMatchingSpecWording() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/strategies", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode strategies = root.get("strategies");
        // 5 total since increment 3 added REBOUND/CUMULATIVE_RISE — see
        // catalog_returnsFiveStrategiesIncludingReboundAndCumulativeRiseMatchingSpecWording below.
        assertEquals(5, strategies.size());

        JsonNode risingSupport = findByCode(strategies, "RISING_SUPPORT");
        assertEquals("上漲支撐", risingSupport.get("name").asText());
        assertEquals(3, risingSupport.get("presets").size());
        assertEquals("收盤突破前 20 日收盤高點且單日漲幅 ≥ 5%，其後 2 日不跌破起漲收盤",
                findPresetByCode(risingSupport.get("presets"), "STRICT").get("description").asText());
        assertEquals("收盤突破前 10 日收盤高點且單日漲幅 ≥ 3%，其後 2 日不跌破起漲收盤",
                findPresetByCode(risingSupport.get("presets"), "STANDARD").get("description").asText());
        assertEquals("收盤突破前 5 日收盤高點且單日漲幅 ≥ 2%，其後 2 日不跌破起漲收盤",
                findPresetByCode(risingSupport.get("presets"), "LOOSE").get("description").asText());
    }

    // ==================== AC2: STANDARD hand-calculated hit ====================

    @Test
    void risingSupport_standard_matchesHandCalculatedSupportRiseAndConfirm() throws Exception {
        String stockId = "SS501";
        seedStock(stockId, "上漲支撐手算測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 1, 1);
        String[] lookbackCloses = {
                "1100.00", "1100.00", "1100.00", "1100.00", "1100.00",
                "1236.00", "1100.00", "1100.00", "1100.00", "1200.00"
        };
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, lookbackCloses, 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1296.00", 1000);
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(stockId, d1, "1272.00", 1000);
        LocalDate d2 = d1.plusDays(1);
        insertCloseOnlyRow(stockId, d2, "1248.00", 1000);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STANDARD"}),
                Collections.singletonList(stockId), d, d2);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt());
        JsonNode item = result.get("items").get(0);
        assertEquals(stockId, item.get("stockId").asText());
        assertEquals(d.toString(), item.get("signalDate").asText());
        JsonNode detail = item.get("detail");
        assertBigDecimalEquals("1200.00", detail.get("supportClose"));
        assertBigDecimalEquals("1296.00", detail.get("riseClose"));
        assertBigDecimalEquals("8.00", detail.get("risePercent"));
        assertBigDecimalEquals("1236.00", detail.get("priorHighClose"));
        JsonNode confirmCloses = detail.get("confirmCloses");
        assertEquals(2, confirmCloses.size());
        assertEquals(d1.toString(), confirmCloses.get(0).get("tradeDate").asText());
        assertBigDecimalEquals("1272.00", confirmCloses.get(0).get("close"));
        assertEquals(d2.toString(), confirmCloses.get(1).get("tradeDate").asText());
        assertBigDecimalEquals("1248.00", confirmCloses.get(1).get("close"));
        assertTrue(result.get("insufficientData").isEmpty());
        assertTrue(result.get("pendingConfirm").isEmpty());
    }

    // ==================== AC3: support line is D-1 close; equal does not count ====================

    @Test
    void risingSupport_confirmEqualToSupportClose_doesNotCount() throws Exception {
        String stockId = "SS502";
        seedStock(stockId, "支撐相等不命中測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 2, 1);
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, repeat("1000.00", 10), 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1050.00", 1000); // 5% rise, breaks the flat 1000 high
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(stockId, d1, "1000.00", 1000); // exactly == support close -> must not count as holding
        LocalDate d2 = d1.plusDays(1);
        // Kept below D's own close (1050, the recomputed lookback high as of d2) so d2 itself does not
        // become a second RISING_SUPPORT candidate day.
        insertCloseOnlyRow(stockId, d2, "1010.00", 1000);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STANDARD"}),
                Collections.singletonList(stockId), d, d2);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(0, result.get("matchedCount").asInt(),
                "D+1 closing exactly at the support line must not count as holding it");
        assertTrue(result.get("items").isEmpty());
        assertTrue(result.get("pendingConfirm").isEmpty());
    }

    // ==================== AC4: breakout-above-lookback-high condition ====================

    @Test
    void risingSupport_riseMeetsThresholdButNotAboveLookbackHigh_doesNotMatch() throws Exception {
        String stockId = "SS503";
        seedStock(stockId, "未突破前高測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 3, 1);
        String[] lookbackCloses = new String[10];
        lookbackCloses[0] = "1300.00"; // peak within the lookback window
        for (int i = 1; i < 10; i++) {
            lookbackCloses[i] = "1000.00";
        }
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, lookbackCloses, 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        // D-1 close = 1000.00; D close = 1000*1.05 = 1050.00 (5% rise, above STANDARD's 3% floor) but
        // still below the lookback window's own peak of 1300 -> must not count as a breakout.
        insertCloseOnlyRow(stockId, d, "1050.00", 1000);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STANDARD"}),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(0, result.get("matchedCount").asInt(),
                "close must exceed the entire prior lookback window's closes, not just rise day-over-day");
        assertTrue(result.get("pendingConfirm").isEmpty(),
                "must fail on the breakout check, before confirmation is even considered");
    }

    // ==================== AC5: risePercent threshold varies by preset ====================

    @Test
    void risingSupport_risePercentThreshold_standardRejectsButLooseAccepts2point5Percent() throws Exception {
        String stockId = "SS504";
        seedStock(stockId, "漲幅門檻測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 4, 1);
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, repeat("1000.00", 20), 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1025.00", 1000); // 2.5% rise over support 1000
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(stockId, d1, "1010.00", 1000);
        LocalDate d2 = d1.plusDays(1);
        insertCloseOnlyRow(stockId, d2, "1010.00", 1000);

        ScanRequestDto standardRequest = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STANDARD"}),
                Collections.singletonList(stockId), d, d2);
        JsonNode standardResult = postScan(standardRequest).get("results").get(0);
        assertEquals(0, standardResult.get("matchedCount").asInt(), "2.5% rise must fail STANDARD's 3% floor");

        ScanRequestDto looseRequest = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "LOOSE"}),
                Collections.singletonList(stockId), d, d2);
        JsonNode looseResult = postScan(looseRequest).get("results").get(0);
        assertEquals(1, looseResult.get("matchedCount").asInt(), "2.5% rise must pass LOOSE's 2% floor");
        assertEquals(d.toString(), looseResult.get("items").get(0).get("signalDate").asText());
    }

    // ==================== AC6: lookback length varies by preset ====================

    @Test
    void risingSupport_lookback_looseMatchesButStrictRejectsDueToLongerLookbackHigh() throws Exception {
        String stockId = "SS505";
        seedStock(stockId, "回看區間測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 5, 1);
        String[] lookbackCloses = new String[20];
        for (int i = 0; i < 20; i++) {
            lookbackCloses[i] = "1000.00";
        }
        // Spike 10 trading days before D: inside STRICT's 20-day lookback, outside LOOSE's 5-day one.
        lookbackCloses[9] = "2000.00";
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, lookbackCloses, 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1100.00", 1000); // 10% rise over support 1000
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(stockId, d1, "1150.00", 1000);
        LocalDate d2 = d1.plusDays(1);
        insertCloseOnlyRow(stockId, d2, "1150.00", 1000);

        ScanRequestDto looseRequest = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "LOOSE"}),
                Collections.singletonList(stockId), d, d2);
        JsonNode looseResult = postScan(looseRequest).get("results").get(0);
        assertEquals(1, looseResult.get("matchedCount").asInt(), "LOOSE's 5-day lookback does not reach the earlier spike");
        assertEquals(d.toString(), looseResult.get("items").get(0).get("signalDate").asText());

        ScanRequestDto strictRequest = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STRICT"}),
                Collections.singletonList(stockId), d, d2);
        JsonNode strictResult = postScan(strictRequest).get("results").get(0);
        assertEquals(0, strictResult.get("matchedCount").asInt(),
                "STRICT's 20-day lookback reaches the 2000 spike, blocking the breakout");
        assertTrue(strictResult.get("insufficientData").isEmpty(),
                "exactly 20 lookback bars were seeded, satisfying STRICT's requirement");
    }

    // ==================== AC7: confirmation length fixed at 2, never shrinks with sensitivity ====================

    @Test
    void risingSupport_confirmLength_fixedAtTwoRegardlessOfPreset() throws Exception {
        String stockId = "SS506";
        seedStock(stockId, "確認固定兩日測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 6, 1);
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, repeat("1000.00", 20), 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1060.00", 1000); // 6% rise, clears all three presets' thresholds
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(stockId, d1, "990.00", 1000); // D+1 fails to hold the support line
        LocalDate d2 = d1.plusDays(1);
        insertCloseOnlyRow(stockId, d2, "1100.00", 1000); // D+2 holds fine on its own

        for (String preset : new String[]{"STRICT", "STANDARD", "LOOSE"}) {
            ScanRequestDto request = scanRequest(
                    Collections.singletonList(new String[]{"RISING_SUPPORT", preset}),
                    Collections.singletonList(stockId), d, d2);
            JsonNode result = postScan(request).get("results").get(0);
            assertEquals(0, result.get("matchedCount").asInt(),
                    preset + " must still require BOTH D+1 and D+2 above the support line");
        }
    }

    // ==================== AC8: missing confirm data -> pendingConfirm ====================

    @Test
    void risingSupport_missingConfirmData_isPendingConfirmNotItem() throws Exception {
        String stockId = "SS507";
        seedStock(stockId, "待確認測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 7, 1);
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, repeat("1000.00", 10), 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1050.00", 1000); // 5% rise, breaks out; STANDARD only needs 3%
        // deliberately no rows after d at all -> "D 落在可用行情的尾端"

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STANDARD"}),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(0, result.get("matchedCount").asInt());
        assertTrue(result.get("items").isEmpty());
        List<String> pendingConfirm = toStringList(result.get("pendingConfirm"));
        assertTrue(pendingConfirm.contains(stockId));
    }

    // ==================== AC9: confirmation data may come from after endDate ====================

    @Test
    void risingSupport_confirmDataAfterEndDate_matchesNormallyNotPendingConfirm() throws Exception {
        String stockId = "SS508";
        seedStock(stockId, "確認取自endDate之後測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 8, 1);
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, repeat("1000.00", 10), 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1050.00", 1000);
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(stockId, d1, "1060.00", 1000); // stored in the DB after endDate
        LocalDate d2 = d1.plusDays(1);
        insertCloseOnlyRow(stockId, d2, "1070.00", 1000); // stored in the DB after endDate

        // endDate = d itself: D+1/D+2 already exist in the DB even though they fall after endDate.
        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STANDARD"}),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "D+1/D+2 already exist in the DB after endDate, so this must be a normal hit, not pendingConfirm");
        assertEquals(d.toString(), result.get("items").get(0).get("signalDate").asText());
        assertTrue(result.get("pendingConfirm").isEmpty());
    }

    // ==================== AC10: insufficient lookback -> insufficientData ====================

    @Test
    void risingSupport_insufficientLookback_isReportedSeparatelyFromNoMatch() throws Exception {
        String stockId = "SS509";
        seedStock(stockId, "回看資料不足測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 9, 1);
        // STANDARD requires 10 lookback days; only 5 are seeded.
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, repeat("1000.00", 5), 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1050.00", 1000);
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(stockId, d1, "1060.00", 1000);
        LocalDate d2 = d1.plusDays(1);
        insertCloseOnlyRow(stockId, d2, "1070.00", 1000);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STANDARD"}),
                Collections.singletonList(stockId), d, d2);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(0, result.get("matchedCount").asInt());
        assertTrue(result.get("items").isEmpty());
        List<String> insufficientData = toStringList(result.get("insufficientData"));
        assertTrue(insufficientData.contains(stockId));
    }

    // ==================== AC11: no volume check at all ====================

    @Test
    void risingSupport_noVolumeCheck_resultsIdenticalRegardlessOfVolume() throws Exception {
        String lowVolStock = "SS510";
        String highVolStock = "SS511";
        seedStock(lowVolStock, "量能不驗證-低量", true);
        seedStock(highVolStock, "量能不驗證-高量", true);
        LocalDate lookbackStart = LocalDate.of(2026, 10, 1);
        List<LocalDate> lowLookback = seedCloseSeries(lowVolStock, lookbackStart, repeat("1000.00", 10), 100);
        seedCloseSeries(highVolStock, lookbackStart, repeat("1000.00", 10), 999999);
        LocalDate d = lowLookback.get(lowLookback.size() - 1).plusDays(1);
        insertCloseOnlyRow(lowVolStock, d, "1050.00", 100);
        insertCloseOnlyRow(highVolStock, d, "1050.00", 999999);
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(lowVolStock, d1, "1060.00", 100);
        insertCloseOnlyRow(highVolStock, d1, "1060.00", 999999);
        LocalDate d2 = d1.plusDays(1);
        insertCloseOnlyRow(lowVolStock, d2, "1070.00", 100);
        insertCloseOnlyRow(highVolStock, d2, "1070.00", 999999);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STANDARD"}),
                Arrays.asList(lowVolStock, highVolStock), d, d2);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(2, result.get("matchedCount").asInt(), "volume must not affect the RISING_SUPPORT outcome at all");
    }

    // ==================== AC12: three strategies together, results in submitted order ====================

    @Test
    void scan_threeStrategiesTogether_resultsReturnedInSubmittedOrder() throws Exception {
        String stockId = "SS512";
        seedStock(stockId, "三策略合併測試", true);
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 10);
        // Ample flat lookback/window bars so every detector runs without hitting insufficientData.
        seedCloseSeries(stockId, start.minusDays(60), repeat("100.00", 80), 1000);

        ScanRequestDto request = scanRequest(
                Arrays.asList(
                        new String[]{"RISING_SUPPORT", "STANDARD"},
                        new String[]{"BOX_BREAKOUT", "LOOSE"},
                        new String[]{"HIGHER_LOWS", "LOOSE"}),
                Collections.singletonList(stockId), start, end);
        JsonNode results = postScan(request).get("results");
        assertEquals(3, results.size());
        assertEquals("RISING_SUPPORT", results.get(0).get("strategy").asText());
        assertEquals("BOX_BREAKOUT", results.get(1).get("strategy").asText());
        assertEquals("HIGHER_LOWS", results.get(2).get("strategy").asText());
    }

    // ==================== AC13: signalDate is the rise day D itself, not D+2 ====================

    @Test
    void risingSupport_signalDateIsRiseDayItselfNotConfirmationCompletionDay() throws Exception {
        String stockId = "SS513";
        seedStock(stockId, "訊號日期測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 12, 1);
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, repeat("1000.00", 10), 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1050.00", 1000);
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(stockId, d1, "1060.00", 1000);
        LocalDate d2 = d1.plusDays(1);
        insertCloseOnlyRow(stockId, d2, "1070.00", 1000);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STANDARD"}),
                Collections.singletonList(stockId), d, d2);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt());
        JsonNode item = result.get("items").get(0);
        assertEquals(d.toString(), item.get("signalDate").asText(), "signalDate must be the rise day D, not D+2");
        assertNotEquals(d2.toString(), item.get("signalDate").asText());
    }

    // ==================== AC14: no advice/recommendation wording for RISING_SUPPORT ====================

    @Test
    void risingSupportScanResponse_containsNoAdviceWording() throws Exception {
        String stockId = "SS514";
        seedStock(stockId, "上漲支撐文案測試", true);
        LocalDate lookbackStart = LocalDate.of(2027, 1, 1);
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, repeat("1000.00", 10), 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1050.00", 1000);
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(stockId, d1, "1060.00", 1000);
        LocalDate d2 = d1.plusDays(1);
        insertCloseOnlyRow(stockId, d2, "1070.00", 1000);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STANDARD"}),
                Collections.singletonList(stockId), d, d2);
        ResponseEntity<String> response = rest.postForEntity("/api/strategies/scan", request, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNoAdviceWording(response.getBody());
    }

    // ==================== Increment 3: risePercent override ====================

    @Test
    void boxBreakout_risePercentOverridesBreakoutPercentOnly_defaultAcceptsButHigherOverrideRejects() throws Exception {
        String stockId = "SS601";
        seedStock(stockId, "突破覆寫測試", true);
        LocalDate start = LocalDate.of(2026, 3, 1);
        List<LocalDate> lookbackDates = seedTightBox(stockId, start, 20, "100.00", "103.00", "97.00", 1000);
        LocalDate breakoutDate = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertPriceRow(stockId, breakoutDate, "104.00", "106.00", "104.00", "105.00", 1600);

        ScanRequestDto defaultRequest = scanRequest(
                Collections.singletonList(new String[]{"BOX_BREAKOUT", "STANDARD"}),
                Collections.singletonList(stockId), breakoutDate, breakoutDate);
        JsonNode defaultResult = postScan(defaultRequest).get("results").get(0);
        assertEquals(1, defaultResult.get("matchedCount").asInt(),
                "STANDARD's own 1.5% breakoutPercent must accept a 105.00 close over a 103 box top");

        ScanRequestDto overrideRequest = scanRequestFromSelections(
                Collections.singletonList(selection("BOX_BREAKOUT", "STANDARD", new BigDecimal("2.5"))),
                Collections.singletonList(stockId), breakoutDate, breakoutDate);
        JsonNode overrideResult = postScan(overrideRequest).get("results").get(0);
        assertEquals(0, overrideResult.get("matchedCount").asInt(),
                "risePercent=2.5 overrides breakoutPercent to 2.5% (needs >=105.575), which 105.00 must fail");
    }

    @Test
    void risingSupport_risePercentOverridesSingleDayRiseOnly_defaultAcceptsButHigherOverrideRejects() throws Exception {
        String stockId = "SS602";
        seedStock(stockId, "漲幅覆寫測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 3, 1);
        String[] lookbackCloses = {"1000.00", "1000.00", "1000.00", "1000.00", "1000.00",
                "1010.00", "1000.00", "1000.00", "1000.00", "1000.00"};
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, lookbackCloses, 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1035.00", 1000); // 3.5% rise over D-1=1000, breaks above priorHigh 1010
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(stockId, d1, "1020.00", 1000);
        LocalDate d2 = d1.plusDays(1);
        insertCloseOnlyRow(stockId, d2, "1010.00", 1000);

        ScanRequestDto defaultRequest = scanRequest(
                Collections.singletonList(new String[]{"RISING_SUPPORT", "STANDARD"}),
                Collections.singletonList(stockId), d, d2);
        JsonNode defaultResult = postScan(defaultRequest).get("results").get(0);
        assertEquals(1, defaultResult.get("matchedCount").asInt(), "STANDARD's own 3% floor must accept a 3.5% rise");

        ScanRequestDto overrideRequest = scanRequestFromSelections(
                Collections.singletonList(selection("RISING_SUPPORT", "STANDARD", new BigDecimal("4"))),
                Collections.singletonList(stockId), d, d2);
        JsonNode overrideResult = postScan(overrideRequest).get("results").get(0);
        assertEquals(0, overrideResult.get("matchedCount").asInt(), "risePercent=4 must reject a 3.5% rise");
    }

    @Test
    void higherLows_risePercentOverridesPerLegRiseOnly_standardDefaultRejectsButLowerOverrideAccepts() throws Exception {
        String stockId = "SS603";
        seedStock(stockId, "底底高覆寫測試", true);
        LocalDate day0 = LocalDate.of(2026, 3, 1);
        List<LocalDate> dates = seedHigherLowsFixture(stockId, day0,
                new String[]{"100.00", "100.30", "100.60"}); // ~0.3% each rise

        ScanRequestDto defaultRequest = scanRequest(
                Collections.singletonList(new String[]{"HIGHER_LOWS", "STANDARD"}),
                Collections.singletonList(stockId), dates.get(10), dates.get(dates.size() - 1));
        JsonNode defaultResult = postScan(defaultRequest).get("results").get(0);
        assertEquals(0, defaultResult.get("matchedCount").asInt(), "STANDARD's own 1% floor must reject ~0.3% legs");

        // 0.2%, not 0.3%: the fixture's second leg (100.60 over 100.30) is only a 0.2991% rise, so a
        // 0.30% override would still (correctly) reject it, and risePercent allows at most one
        // decimal digit anyway — 0.2% comfortably clears both legs.
        ScanRequestDto overrideRequest = scanRequestFromSelections(
                Collections.singletonList(selection("HIGHER_LOWS", "STANDARD", new BigDecimal("0.2"))),
                Collections.singletonList(stockId), dates.get(10), dates.get(dates.size() - 1));
        JsonNode overrideResult = postScan(overrideRequest).get("results").get(0);
        assertEquals(1, overrideResult.get("matchedCount").asInt(), "risePercent=0.2 must accept both ~0.3% legs");
    }

    @Test
    void risePercent_zero_disablesThresholdEntirely_evenAHairsWidthRiseCounts() throws Exception {
        String stockId = "SS604";
        seedStock(stockId, "門檻歸零測試", true);
        LocalDate day0 = LocalDate.of(2026, 3, 20);
        List<LocalDate> dates = seedHigherLowsFixture(stockId, day0,
                new String[]{"100.00", "100.01", "100.02"}); // a hair's width above the previous low each time

        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(selection("HIGHER_LOWS", "STANDARD", BigDecimal.ZERO)),
                Collections.singletonList(stockId), dates.get(10), dates.get(dates.size() - 1));
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "risePercent=0 must behave like 'strictly higher is enough', not zero matches or a disabled pattern");
    }

    @Test
    void risePercent_doesNotAffectOtherParameters_sameOverrideStillDiffersByLookback() throws Exception {
        String stockId = "SS605";
        seedStock(stockId, "覆寫不影響其餘參數測試", true);
        LocalDate lookbackStart = LocalDate.of(2026, 3, 1);
        String[] lookbackCloses = new String[20];
        for (int i = 0; i < 20; i++) {
            lookbackCloses[i] = "1000.00";
        }
        // 10 trading days before D: inside STRICT's 20-day lookback, outside LOOSE's 5-day one.
        lookbackCloses[9] = "2000.00";
        List<LocalDate> lookbackDates = seedCloseSeries(stockId, lookbackStart, lookbackCloses, 1000);
        LocalDate d = lookbackDates.get(lookbackDates.size() - 1).plusDays(1);
        insertCloseOnlyRow(stockId, d, "1100.00", 1000); // 10% rise over support 1000
        LocalDate d1 = d.plusDays(1);
        insertCloseOnlyRow(stockId, d1, "1150.00", 1000);
        LocalDate d2 = d1.plusDays(1);
        insertCloseOnlyRow(stockId, d2, "1150.00", 1000);

        BigDecimal sameOverride = new BigDecimal("8"); // below the raw 10% rise regardless of preset

        ScanRequestDto looseRequest = scanRequestFromSelections(
                Collections.singletonList(selection("RISING_SUPPORT", "LOOSE", sameOverride)),
                Collections.singletonList(stockId), d, d2);
        JsonNode looseResult = postScan(looseRequest).get("results").get(0);
        assertEquals(1, looseResult.get("matchedCount").asInt(),
                "LOOSE's 5-day lookback still applies with the override -> the 2000 spike stays out of view");

        ScanRequestDto strictRequest = scanRequestFromSelections(
                Collections.singletonList(selection("RISING_SUPPORT", "STRICT", sameOverride)),
                Collections.singletonList(stockId), d, d2);
        JsonNode strictResult = postScan(strictRequest).get("results").get(0);
        assertEquals(0, strictResult.get("matchedCount").asInt(),
                "STRICT's 20-day lookback still applies with the SAME override -> the 2000 spike still blocks it");
    }

    @Test
    void risePercent_appliesIndependentlyAcrossStrategiesInOneRequest_resultsInSubmittedOrder() throws Exception {
        String boxStock = "SS701";
        String risingStock = "SS702";
        String higherLowsStock = "SS703";
        seedStock(boxStock, "獨立覆寫-箱型", true);
        seedStock(risingStock, "獨立覆寫-上漲支撐", true);
        seedStock(higherLowsStock, "獨立覆寫-底底高", true);

        // HIGHER_LOWS: MA5 fixture; dates.get(10) becomes the shared scan startDate, dates.get(last) the shared endDate.
        LocalDate higherLowsDay0 = LocalDate.of(2026, 3, 22);
        List<LocalDate> higherLowsDates = seedHigherLowsFixture(higherLowsStock, higherLowsDay0,
                new String[]{"100.00", "102.00", "104.50"}); // +2%, +2.45% legs -> hits under STANDARD's own 1% floor
        LocalDate startDate = higherLowsDates.get(10);
        LocalDate endDate = higherLowsDates.get(higherLowsDates.size() - 1);

        // BOX_BREAKOUT: 20 lookback bars ending the day before startDate; breakout day = startDate itself.
        seedTightBox(boxStock, startDate.minusDays(20), 20, "100.00", "103.00", "97.00", 1000);
        insertPriceRow(boxStock, startDate, "104.00", "106.00", "104.00", "105.00", 1600);

        // RISING_SUPPORT: 10 lookback bars ending the day before startDate; D = startDate.
        String[] risingLookback = {"1000.00", "1000.00", "1000.00", "1000.00", "1000.00",
                "1010.00", "1000.00", "1000.00", "1000.00", "1000.00"};
        seedCloseSeries(risingStock, startDate.minusDays(10), risingLookback, 1000);
        insertCloseOnlyRow(risingStock, startDate, "1035.00", 1000);
        insertCloseOnlyRow(risingStock, startDate.plusDays(1), "1020.00", 1000);
        insertCloseOnlyRow(risingStock, startDate.plusDays(2), "1010.00", 1000);

        List<StrategySelectionDto> selections = Arrays.asList(
                selection("BOX_BREAKOUT", "STANDARD", new BigDecimal("2.5")),
                selection("RISING_SUPPORT", "STANDARD", new BigDecimal("4")),
                selection("HIGHER_LOWS", "STANDARD")); // no override -> STANDARD's own 1% floor
        ScanRequestDto request = scanRequestFromSelections(selections,
                Arrays.asList(boxStock, risingStock, higherLowsStock), startDate, endDate);
        request.setCommonStocksOnly(true);

        JsonNode results = postScan(request).get("results");
        assertEquals(3, results.size());
        assertEquals("BOX_BREAKOUT", results.get(0).get("strategy").asText());
        assertEquals("RISING_SUPPORT", results.get(1).get("strategy").asText());
        assertEquals("HIGHER_LOWS", results.get(2).get("strategy").asText());

        assertEquals(0, results.get(0).get("matchedCount").asInt(),
                "2.5% override must reject a 105.00 close against a 103 box top (needs >=105.575)");
        assertEquals(0, results.get(1).get("matchedCount").asInt(),
                "4% override must reject a 3.5% single-day rise");
        assertEquals(1, results.get(2).get("matchedCount").asInt(),
                "no override -> STANDARD's own 1% per-leg floor still applies and the 2%/2.45% legs clear it");
    }

    @Test
    void risePercent_negative_rejectedWithInvalidRisePercentNamingStrategy() {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("HIGHER_LOWS", "STANDARD", new BigDecimal("-1"))));
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", response.getBody().getCode());
        assertEquals("HIGHER_LOWS", response.getBody().getStrategy());
    }

    @Test
    void risePercent_tooHigh_rejectedWithInvalidRisePercentNamingCorrectStrategyAmongMultiple() {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Arrays.asList(
                selection("HIGHER_LOWS", "STANDARD"), // valid, no override
                selection("RISING_SUPPORT", "STANDARD", new BigDecimal("100"))));
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", response.getBody().getCode());
        assertEquals("RISING_SUPPORT", response.getBody().getStrategy());
    }

    @Test
    void risePercent_moreThanOneDecimalDigit_rejectedWithInvalidRisePercent() {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("BOX_BREAKOUT", "STANDARD", new BigDecimal("2.55"))));
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", response.getBody().getCode());
        assertEquals("BOX_BREAKOUT", response.getBody().getStrategy());
    }

    @Test
    void risePercent_trailingZeroDecimal_isNotRejectedAsTooManyDecimalDigits() {
        String stockId = "SS606";
        seedStock(stockId, "小數尾零測試", true);
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("BOX_BREAKOUT", "STANDARD", new BigDecimal("2.50"))));
        request.setStockIds(Collections.singletonList(stockId));
        request.setStartDate(LocalDate.now().minusDays(1));
        request.setEndDate(LocalDate.now());
        ResponseEntity<String> response = rest.postForEntity("/api/strategies/scan", request, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "\"2.50\" carries only one real decimal digit; body: " + response.getBody());
    }

    // ==================== Increment 6: risePercent's upper bound is per-strategy ====================
    // specs/backend/strategy-scan.md, "risePercent 的上限逐型態認定，不是全系統一個值": BOX_BREAKOUT /
    // HIGHER_LOWS / RISING_SUPPORT are tightened to 0~20; REBOUND / CUMULATIVE_RISE keep 0~50.

    @Test
    void boxBreakout_risePercentUpperBoundTightenedTo20_20ValidBut20Point1Rejected() {
        String stockId = "SS910";
        seedStock(stockId, "箱型突破上限收緊測試", true);
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now();

        ScanRequestDto twenty = scanRequestFromSelections(
                Collections.singletonList(selection("BOX_BREAKOUT", "STANDARD", new BigDecimal("20"))),
                Collections.singletonList(stockId), start, end);
        ResponseEntity<String> twentyResponse = rest.postForEntity("/api/strategies/scan", twenty, String.class);
        assertEquals(HttpStatus.OK, twentyResponse.getStatusCode(), "20 must remain a valid risePercent override");

        ScanRequestDto twentyPointOne = scanRequestFromSelections(
                Collections.singletonList(selection("BOX_BREAKOUT", "STANDARD", new BigDecimal("20.1"))),
                Collections.singletonList(stockId), start, end);
        ResponseEntity<ErrorResponse> rejectedResponse =
                rest.postForEntity("/api/strategies/scan", twentyPointOne, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejectedResponse.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", rejectedResponse.getBody().getCode());
        assertEquals("BOX_BREAKOUT", rejectedResponse.getBody().getStrategy());
    }

    @Test
    void higherLows_risePercentUpperBoundTightenedTo20_20ValidBut20Point1Rejected() {
        String stockId = "SS911";
        seedStock(stockId, "底底高上限收緊測試", true);
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now();

        ScanRequestDto twenty = scanRequestFromSelections(
                Collections.singletonList(selection("HIGHER_LOWS", "STANDARD", new BigDecimal("20"))),
                Collections.singletonList(stockId), start, end);
        ResponseEntity<String> twentyResponse = rest.postForEntity("/api/strategies/scan", twenty, String.class);
        assertEquals(HttpStatus.OK, twentyResponse.getStatusCode(), "20 must remain a valid risePercent override");

        ScanRequestDto twentyPointOne = scanRequestFromSelections(
                Collections.singletonList(selection("HIGHER_LOWS", "STANDARD", new BigDecimal("20.1"))),
                Collections.singletonList(stockId), start, end);
        ResponseEntity<ErrorResponse> rejectedResponse =
                rest.postForEntity("/api/strategies/scan", twentyPointOne, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejectedResponse.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", rejectedResponse.getBody().getCode());
        assertEquals("HIGHER_LOWS", rejectedResponse.getBody().getStrategy());
    }

    @Test
    void risingSupport_risePercentUpperBoundTightenedTo20_20ValidBut20Point1Rejected() {
        String stockId = "SS912";
        seedStock(stockId, "上漲支撐上限收緊測試", true);
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now();

        ScanRequestDto twenty = scanRequestFromSelections(
                Collections.singletonList(selection("RISING_SUPPORT", "STANDARD", new BigDecimal("20"))),
                Collections.singletonList(stockId), start, end);
        ResponseEntity<String> twentyResponse = rest.postForEntity("/api/strategies/scan", twenty, String.class);
        assertEquals(HttpStatus.OK, twentyResponse.getStatusCode(), "20 must remain a valid risePercent override");

        ScanRequestDto twentyPointOne = scanRequestFromSelections(
                Collections.singletonList(selection("RISING_SUPPORT", "STANDARD", new BigDecimal("20.1"))),
                Collections.singletonList(stockId), start, end);
        ResponseEntity<ErrorResponse> rejectedResponse =
                rest.postForEntity("/api/strategies/scan", twentyPointOne, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejectedResponse.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", rejectedResponse.getBody().getCode());
        assertEquals("RISING_SUPPORT", rejectedResponse.getBody().getStrategy());
    }

    @Test
    void risingSupport_risePercent30Rejected_notSilentlyAcceptedThenZeroHits() {
        // Daily price-limit alone makes a 30% single-day rise structurally impossible for
        // RISING_SUPPORT — this must be a 400, not an accepted request that quietly returns zero
        // matches (specs/backend/strategy-scan.md, "一個永遠不可能命中的請求應該被拒絕，而不是被受理後回零命中").
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("RISING_SUPPORT", "STANDARD", new BigDecimal("30"))));
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", response.getBody().getCode());
        assertEquals("RISING_SUPPORT", response.getBody().getStrategy());
    }

    @Test
    void rebound_and_cumulativeRise_risePercent30StillValid_upperBoundNotTightenedAlongside() {
        String reboundStock = "SS913";
        String cumulativeStock = "SS914";
        seedStock(reboundStock, "反彈上限未收緊測試", true);
        seedStock(cumulativeStock, "累積上漲上限未收緊測試", true);
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now();

        ScanRequestDto reboundRequest = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, null, new BigDecimal("30"))),
                Collections.singletonList(reboundStock), start, end);
        ResponseEntity<String> reboundResponse = rest.postForEntity("/api/strategies/scan", reboundRequest, String.class);
        assertEquals(HttpStatus.OK, reboundResponse.getStatusCode(), "REBOUND's risePercent=30 must remain valid");

        ScanRequestDto cumulativeRequest = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", null, new BigDecimal("30"))),
                Collections.singletonList(cumulativeStock), start, end);
        ResponseEntity<String> cumulativeResponse =
                rest.postForEntity("/api/strategies/scan", cumulativeRequest, String.class);
        assertEquals(HttpStatus.OK, cumulativeResponse.getStatusCode(),
                "CUMULATIVE_RISE's risePercent=30 must remain valid");
    }

    @Test
    void risePercent_perStrategyBound_mixedRequest_namesTheActuallyOffendingStrategy() {
        // 25 is invalid for the tightened BOX_BREAKOUT (>20) but would have been valid under the old,
        // system-wide 50 bound — this is the value that actually distinguishes the new per-strategy
        // enforcement from the old one. HIGHER_LOWS' 15 stays within its own (also tightened, but not
        // exceeded) 0~20 bound, and is listed first so the response can't merely be reporting
        // whichever selection happens to be checked first.
        List<StrategySelectionDto> selections = Arrays.asList(
                selection("HIGHER_LOWS", "STANDARD", new BigDecimal("15")),
                selection("BOX_BREAKOUT", "STANDARD", new BigDecimal("25")));
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(selections);
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", response.getBody().getCode());
        assertEquals("BOX_BREAKOUT", response.getBody().getStrategy(),
                "must name the strategy that actually exceeds its own bound, not the first selection submitted");
    }

    @Test
    void rebound_dropPercentUpperBoundUnchanged_50ValidRemains() {
        String stockId = "SS915";
        seedStock(stockId, "反彈跌幅上限未變測試", true);
        StrategySelectionDto dto = reboundSelection(null, new BigDecimal("50"), null, null, null);
        ScanRequestDto request = scanRequestFromSelections(Collections.singletonList(dto),
                Collections.singletonList(stockId), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<String> response = rest.postForEntity("/api/strategies/scan", request, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "REBOUND's dropPercent=50 must remain valid");
    }

    @Test
    void risingSupport_risePercentZero_andSingleDecimalDigitRule_unchanged() {
        String stockId = "SS916";
        seedStock(stockId, "上漲支撐零值與小數規則測試", true);

        ScanRequestDto zeroRequest = scanRequestFromSelections(
                Collections.singletonList(selection("RISING_SUPPORT", "STANDARD", BigDecimal.ZERO)),
                Collections.singletonList(stockId), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<String> zeroResponse = rest.postForEntity("/api/strategies/scan", zeroRequest, String.class);
        assertEquals(HttpStatus.OK, zeroResponse.getStatusCode(), "risePercent=0 must remain a valid override");

        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("RISING_SUPPORT", "STANDARD", new BigDecimal("2.55"))));
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", response.getBody().getCode());
        assertEquals("RISING_SUPPORT", response.getBody().getStrategy());
    }

    @Test
    void cumulativeRise_risePercentMoreThanOneDecimalDigit_stillRejected() {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(
                selectionDays("CUMULATIVE_RISE", null, new BigDecimal("12.55"))));
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", response.getBody().getCode());
        assertEquals("CUMULATIVE_RISE", response.getBody().getStrategy());
    }

    @Test
    void getStrategies_cumulativeRiseAndReboundParamMax_stillFifty() throws Exception {
        // GET /api/strategies must keep advertising 50 for the two strategies whose enforced bound
        // did not change (specs/backend/strategy-scan.md's acceptance criteria pin this explicitly) —
        // it must not have drifted while BOX_BREAKOUT/HIGHER_LOWS/RISING_SUPPORT's enforced bound was
        // tightened elsewhere in this increment.
        ResponseEntity<String> response = rest.getForEntity("/api/strategies", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode strategies = objectMapper.readTree(response.getBody()).get("strategies");

        for (String code : new String[]{"CUMULATIVE_RISE", "REBOUND"}) {
            JsonNode strategy = findByCode(strategies, code);
            JsonNode params = strategy.get("params");
            JsonNode risePercent = findByCode(params, "risePercent");
            assertBigDecimalEquals("50", risePercent.get("max"));
        }
    }

    // ==================== Increment 3: commonStocksOnly ====================
    // Exercised against the live dataset's real ETF (0050/00878), special share (2881A) and TDR
    // (910322) rows, plus real ordinary shares — these already exist from stock-universe-import and
    // are never written to by these tests, only read.

    @Test
    void commonStocksOnly_omittedDefaultsToTrue_scannedStocksCountsOnlyCommonCodes() throws Exception {
        Long expectedCommonActive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock WHERE is_active = 1 AND stock_id REGEXP '^[1-9][0-9]{3}$'", Long.class);

        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("HIGHER_LOWS", "LOOSE")));
        request.setStartDate(LocalDate.now().minusDays(3));
        request.setEndDate(LocalDate.now());
        // stockIds and commonStocksOnly both omitted -> ALL active, filtered to common stocks only.

        JsonNode root = postScan(request);
        assertEquals(expectedCommonActive.intValue(), root.get("scannedStocks").asInt());
    }

    @Test
    void commonStocksOnly_false_scannedStocksSubstantiallyLargerThanDefaultTrue() throws Exception {
        Long expectedAllActive = jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE is_active = 1", Long.class);

        ScanRequestDto trueRequest = new ScanRequestDto();
        trueRequest.setStrategies(Collections.singletonList(selection("HIGHER_LOWS", "LOOSE")));
        trueRequest.setStartDate(LocalDate.now().minusDays(3));
        trueRequest.setEndDate(LocalDate.now());
        int scannedCommonOnly = postScan(trueRequest).get("scannedStocks").asInt();

        ScanRequestDto falseRequest = new ScanRequestDto();
        falseRequest.setStrategies(Collections.singletonList(selection("HIGHER_LOWS", "LOOSE")));
        falseRequest.setCommonStocksOnly(false);
        falseRequest.setStartDate(LocalDate.now().minusDays(3));
        falseRequest.setEndDate(LocalDate.now());
        int scannedAll = postScan(falseRequest).get("scannedStocks").asInt();

        assertEquals(expectedAllActive.intValue(), scannedAll);
        assertTrue(scannedAll > scannedCommonOnly,
                "commonStocksOnly=false (" + scannedAll + ") should exceed the default true (" + scannedCommonOnly + ")");
    }

    @Test
    void commonStocksOnly_true_excludesKnownEtfSpecialShareAndTdrFromAllResultLists() throws Exception {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("HIGHER_LOWS", "LOOSE")));
        request.setStartDate(LocalDate.now().minusDays(5));
        request.setEndDate(LocalDate.now());
        // commonStocksOnly omitted -> true

        JsonNode result = postScan(request).get("results").get(0);
        for (String code : new String[]{"0050", "00878", "2881A", "910322"}) {
            assertFalse(containsStockId(result.get("items"), code), code + " must not appear in items");
            assertFalse(toStringList(result.get("insufficientData")).contains(code),
                    code + " must not appear in insufficientData");
            assertFalse(toStringList(result.get("pendingConfirm")).contains(code),
                    code + " must not appear in pendingConfirm");
        }
    }

    @Test
    void commonStocksOnly_ignoredWhenStockIdsGiven_bothCommonAndNonCommonAreScanned() throws Exception {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("HIGHER_LOWS", "LOOSE")));
        request.setStockIds(Arrays.asList("0050", "2330"));
        request.setCommonStocksOnly(true);
        request.setStartDate(LocalDate.now().minusDays(5));
        request.setEndDate(LocalDate.now());

        JsonNode root = postScan(request);
        assertEquals(2, root.get("scannedStocks").asInt(),
                "commonStocksOnly must be ignored entirely once stockIds names an explicit list");
    }

    @Test
    void commonStocksOnly_neverWritesToStockTable_rowCountAndActiveFlagsUnchanged() throws Exception {
        Long countBefore = jdbc.queryForObject("SELECT COUNT(*) FROM stock", Long.class);
        Long activeCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE is_active = 1", Long.class);

        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(Collections.singletonList(selection("HIGHER_LOWS", "LOOSE")));
        request.setCommonStocksOnly(true);
        request.setStartDate(LocalDate.now().minusDays(3));
        request.setEndDate(LocalDate.now());
        postScan(request);

        Long countAfter = jdbc.queryForObject("SELECT COUNT(*) FROM stock", Long.class);
        Long activeCountAfter = jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE is_active = 1", Long.class);
        assertEquals(countBefore, countAfter, "commonStocksOnly must never write to the stock table");
        assertEquals(activeCountBefore, activeCountAfter);
    }

    // ==================== Increment 3: REBOUND + CUMULATIVE_RISE ====================

    @Test
    void catalog_returnsFiveStrategiesIncludingReboundAndCumulativeRiseMatchingSpecWording() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/strategies", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode strategies = root.get("strategies");
        assertEquals(5, strategies.size());

        // REBOUND's catalogue shape (empty presets + description + paramGroups + params) moved to
        // catalog_rebound_hasEmptyPresetsPlusDescriptionParamGroupsAndParams below (REBOUND dropped
        // its three sensitivity presets in favor of typable dropDays/dropPercent/riseDays/
        // risePercent — see specs/backend/strategy-scan.md, "反彈改為跌段＋選用漲段的雙段參數、移除靈敏度"). CUMULATIVE_RISE's
        // catalogue wording lives in catalog_cumulativeRise_hasEmptyPresetsPlusDescriptionAndParams below.
    }

    // ==================== 反彈改為跌段＋選用漲段的雙段參數、移除靈敏度 ====================

    @Test
    void catalog_rebound_hasEmptyPresetsPlusDescriptionParamGroupsAndParams() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/strategies", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode strategies = root.get("strategies");

        JsonNode rebound = findByCode(strategies, "REBOUND");
        assertEquals("反彈", rebound.get("name").asText());
        assertEquals(0, rebound.get("presets").size());
        assertEquals("先在回看窗口內自最高收盤跌幅達門檻築出谷底，其後指定天數內自谷底反彈幅度達門檻",
                rebound.get("description").asText());

        JsonNode paramGroups = rebound.get("paramGroups");
        assertEquals(1, paramGroups.size());
        JsonNode riseGroup = paramGroups.get(0);
        assertEquals("rise", riseGroup.get("code").asText());
        assertEquals("另外要求反彈漲幅", riseGroup.get("name").asText());
        assertTrue(riseGroup.get("default").asBoolean());

        JsonNode params = rebound.get("params");
        assertEquals(4, params.size());

        JsonNode dropDays = findByCode(params, "dropDays");
        assertEquals("下跌天數", dropDays.get("name").asText());
        assertEquals("日", dropDays.get("unit").asText());
        assertBigDecimalEquals("3", dropDays.get("default"));
        assertBigDecimalEquals("1", dropDays.get("min"));
        assertBigDecimalEquals("90", dropDays.get("max"));
        assertBigDecimalEquals("1", dropDays.get("step"));
        assertFalse(dropDays.has("group"), "dropDays is not optional and must carry no group");

        JsonNode dropPercent = findByCode(params, "dropPercent");
        assertEquals("跌幅門檻", dropPercent.get("name").asText());
        assertEquals("%", dropPercent.get("unit").asText());
        assertBigDecimalEquals("10", dropPercent.get("default"));
        assertBigDecimalEquals("0", dropPercent.get("min"));
        assertBigDecimalEquals("50", dropPercent.get("max"));
        assertBigDecimalEquals("0.1", dropPercent.get("step"));
        assertFalse(dropPercent.has("group"), "dropPercent is not optional and must carry no group");

        JsonNode riseDays = findByCode(params, "riseDays");
        assertEquals("反彈天數", riseDays.get("name").asText());
        assertEquals("日", riseDays.get("unit").asText());
        assertBigDecimalEquals("1", riseDays.get("default"));
        assertBigDecimalEquals("1", riseDays.get("min"));
        assertBigDecimalEquals("90", riseDays.get("max"));
        assertBigDecimalEquals("1", riseDays.get("step"));
        assertEquals("rise", riseDays.get("group").asText());

        JsonNode risePercent = findByCode(params, "risePercent");
        assertEquals("反彈幅度", risePercent.get("name").asText());
        assertEquals("%", risePercent.get("unit").asText());
        assertBigDecimalEquals("5", risePercent.get("default"));
        assertBigDecimalEquals("0", risePercent.get("min"));
        assertBigDecimalEquals("50", risePercent.get("max"));
        assertBigDecimalEquals("0.1", risePercent.get("step"));
        assertEquals("rise", risePercent.get("group").asText());
    }

    @Test
    void catalog_otherThreeStrategies_untouchedByReboundChange() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/strategies", String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode strategies = root.get("strategies");

        for (String code : new String[]{"BOX_BREAKOUT", "HIGHER_LOWS", "RISING_SUPPORT"}) {
            JsonNode strategy = findByCode(strategies, code);
            assertEquals(3, strategy.get("presets").size(), code + " must keep its three presets");
            assertFalse(strategy.has("paramGroups"), code + " must not carry paramGroups (only REBOUND does)");
        }
        JsonNode cumulativeRise = findByCode(strategies, "CUMULATIVE_RISE");
        assertEquals(0, cumulativeRise.get("presets").size());
        assertEquals("回看指定天數，自窗口內最低收盤累積漲幅達門檻的最高點", cumulativeRise.get("description").asText());
        assertFalse(cumulativeRise.has("paramGroups"), "CUMULATIVE_RISE must not carry paramGroups (only REBOUND does)");
    }

    // ==================== Increment 4: CUMULATIVE_RISE drops presets for days/risePercent ====================

    @Test
    void catalog_cumulativeRise_hasEmptyPresetsPlusDescriptionAndParams() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/strategies", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode strategies = root.get("strategies");
        assertEquals(5, strategies.size());

        JsonNode cumulativeRise = findByCode(strategies, "CUMULATIVE_RISE");
        assertEquals("累積上漲", cumulativeRise.get("name").asText());
        assertEquals(0, cumulativeRise.get("presets").size());
        assertEquals("回看指定天數，自窗口內最低收盤累積漲幅達門檻的最高點", cumulativeRise.get("description").asText());

        JsonNode params = cumulativeRise.get("params");
        assertEquals(2, params.size());
        JsonNode daysParam = findByCode(params, "days");
        assertEquals("天數", daysParam.get("name").asText());
        assertEquals("日", daysParam.get("unit").asText());
        assertBigDecimalEquals("20", daysParam.get("default"));
        assertBigDecimalEquals("1", daysParam.get("min"));
        assertBigDecimalEquals("90", daysParam.get("max"));
        assertBigDecimalEquals("1", daysParam.get("step"));

        JsonNode risePercentParam = findByCode(params, "risePercent");
        assertEquals("漲幅門檻", risePercentParam.get("name").asText());
        assertEquals("%", risePercentParam.get("unit").asText());
        assertBigDecimalEquals("15", risePercentParam.get("default"));
        assertBigDecimalEquals("0", risePercentParam.get("min"));
        assertBigDecimalEquals("50", risePercentParam.get("max"));
        assertBigDecimalEquals("0.1", risePercentParam.get("step"));
    }

    @Test
    void catalog_otherFourStrategies_unchangedByCumulativeRiseChange() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/strategies", String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode strategies = root.get("strategies");

        for (String code : new String[]{"BOX_BREAKOUT", "HIGHER_LOWS", "RISING_SUPPORT"}) {
            JsonNode strategy = findByCode(strategies, code);
            assertEquals(3, strategy.get("presets").size(), code + " must keep its three presets");
            assertTrue(strategy.get("description") == null || strategy.get("description").isNull(),
                    code + " must not carry a strategy-level description (only the no-preset strategies do)");
            assertTrue(strategy.get("params") == null || strategy.get("params").isEmpty(),
                    code + " must not carry params (only the no-preset strategies do)");
        }
        // REBOUND's own preset-vs-params shape is covered by
        // catalog_rebound_hasEmptyPresetsPlusDescriptionParamGroupsAndParams — it dropped its three
        // presets in a later increment (see "反彈改為跌段＋選用漲段的雙段參數、移除靈敏度"), so it is no longer part of
        // this "preset strategies unchanged" assertion.
    }

    @Test
    void rebound_defaultParams_signalDateIsReboundDayWithHandCalculatedDetail() throws Exception {
        String stockId = "SS801";
        seedStock(stockId, "反彈手算測試", true);
        // Filler so the earliest T candidate (dropDays-1+riseDays = 2+1 = 3 trading days back from
        // startDate) has enough history behind it.
        LocalDate filler = LocalDate.of(2026, 8, 15);
        seedRamp(stockId, filler, 130.00, 130.00, 3, 1000); // 08-15..08-17
        LocalDate peakDate = filler.plusDays(3); // 08-18: H, the dropDays=3 window's highest close
        insertCloseOnlyRow(stockId, peakDate, "120.00", 1000);
        LocalDate mid = peakDate.plusDays(1); // 08-19
        insertCloseOnlyRow(stockId, mid, "110.00", 1000);
        LocalDate troughDate = mid.plusDays(1); // 08-20: T, the window's low -> drop = 16.67%
        insertCloseOnlyRow(stockId, troughDate, "100.00", 1000);
        LocalDate reboundDate = troughDate.plusDays(1); // 08-21: S, +6% over T's close -> clears the 5% default
        insertCloseOnlyRow(stockId, reboundDate, "106.00", 1000);

        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, null, null)),
                Collections.singletonList(stockId), peakDate, reboundDate);
        JsonNode result = postScan(request).get("results").get(0);
        assertTrue(result.get("requireRise").asBoolean(), "requireRise must default to true");
        assertEquals(3, result.get("dropDays").asInt());
        assertBigDecimalEquals("10", result.get("dropPercent"));
        assertEquals(1, result.get("riseDays").asInt());
        assertBigDecimalEquals("5", result.get("risePercent"));
        assertFalse(result.has("preset"), "REBOUND must never echo preset");
        assertEquals(1, result.get("matchedCount").asInt());
        JsonNode item = result.get("items").get(0);
        assertEquals(stockId, item.get("stockId").asText());
        assertEquals(reboundDate.toString(), item.get("signalDate").asText(),
                "signalDate must be S (the rebound day), not T (the trough)");
        JsonNode detail = item.get("detail");
        assertEquals(peakDate.toString(), detail.get("peakDate").asText());
        assertBigDecimalEquals("120.00", detail.get("peakClose"));
        assertEquals(troughDate.toString(), detail.get("troughDate").asText());
        assertBigDecimalEquals("100.00", detail.get("troughClose"));
        assertBigDecimalEquals("16.67", detail.get("dropPercent"));
        assertBigDecimalEquals("6.00", detail.get("risePercent"));
        assertTrue(result.get("insufficientData").isEmpty());
        assertTrue(result.get("pendingConfirm").isEmpty());
    }

    @Test
    void rebound_riseDaysWindow_firstDayMeetingThresholdWinsEvenIfLaterDayFallsBack() throws Exception {
        String stockId = "SS900A";
        seedStock(stockId, "反彈視窗回落測試", true);
        LocalDate filler = LocalDate.of(2026, 9, 1);
        seedRamp(stockId, filler, 130.00, 130.00, 4, 1000); // dropDays-1+riseDays = 2+2 = 4 bars needed
        LocalDate peakDate = filler.plusDays(4);
        insertCloseOnlyRow(stockId, peakDate, "120.00", 1000);
        LocalDate mid = peakDate.plusDays(1);
        insertCloseOnlyRow(stockId, mid, "110.00", 1000);
        LocalDate troughDate = mid.plusDays(1);
        insertCloseOnlyRow(stockId, troughDate, "100.00", 1000); // 16.67% drop, clears the 10% default
        LocalDate reboundDay1 = troughDate.plusDays(1);
        insertCloseOnlyRow(stockId, reboundDay1, "106.00", 1000); // +6%, clears the 5% default
        LocalDate reboundDay2 = reboundDay1.plusDays(1);
        insertCloseOnlyRow(stockId, reboundDay2, "101.00", 1000); // falls back to +1%, below 5%

        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, 2, null)),
                Collections.singletonList(stockId), peakDate, reboundDay2);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "riseDays=2 must still hit even though day 2 falls back below the threshold");
        assertEquals(reboundDay1.toString(), result.get("items").get(0).get("signalDate").asText(),
                "signalDate must be the first day meeting the threshold, not the last day of the window");
    }

    @Test
    void rebound_riseWindowNotYetElapsed_hitsWhenAlreadyMet_missesWhenNotWithoutPendingConfirm() throws Exception {
        String stockId = "SS900B";
        seedStock(stockId, "反彈視窗未跑滿測試", true);
        LocalDate filler = LocalDate.of(2026, 9, 10);
        seedRamp(stockId, filler, 130.00, 130.00, 5, 1000); // dropDays-1+riseDays = 2+3 = 5 bars needed
        LocalDate peakDate = filler.plusDays(5);
        insertCloseOnlyRow(stockId, peakDate, "120.00", 1000);
        LocalDate mid = peakDate.plusDays(1);
        insertCloseOnlyRow(stockId, mid, "110.00", 1000);
        LocalDate troughDate = mid.plusDays(1); // the trough is endDate itself -> only 0 of riseDays=3 elapsed
        insertCloseOnlyRow(stockId, troughDate, "100.00", 1000); // 16.67% drop, clears the 10% default

        // Not yet met: the rebound window has not elapsed at all yet -> a miss, not pendingConfirm.
        ScanRequestDto notYetMetRequest = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, 3, null)),
                Collections.singletonList(stockId), peakDate, troughDate);
        JsonNode notYetMetResult = postScan(notYetMetRequest).get("results").get(0);
        assertEquals(0, notYetMetResult.get("matchedCount").asInt(), "the rebound window has not elapsed yet");
        assertTrue(notYetMetResult.get("pendingConfirm").isEmpty(),
                "REBOUND must never produce pendingConfirm, even when its rebound window has not elapsed");

        // Already met: a rebound day immediately after the trough clears the threshold before the
        // 3-day window would even finish -> a hit, even though the window is still incomplete.
        LocalDate reboundDate = troughDate.plusDays(1);
        insertCloseOnlyRow(stockId, reboundDate, "106.00", 1000);
        ScanRequestDto alreadyMetRequest = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, 3, null)),
                Collections.singletonList(stockId), peakDate, reboundDate);
        JsonNode alreadyMetResult = postScan(alreadyMetRequest).get("results").get(0);
        assertEquals(1, alreadyMetResult.get("matchedCount").asInt(),
                "already met within the unelapsed window must still hit");
        assertTrue(alreadyMetResult.get("pendingConfirm").isEmpty());
    }

    @Test
    void rebound_dropDaysAndRiseDaysCountTradingDaysNotCalendarDays_weekendDoesNotConsumeWindowLength()
            throws Exception {
        String stockId = "SS900C";
        seedStock(stockId, "反彈交易日週末測試", true);
        LocalDate filler = LocalDate.of(2026, 9, 1);
        seedRamp(stockId, filler, 130.00, 130.00, 3, 1000); // dropDays-1+riseDays = 2+1 = 3 bars needed
        LocalDate mon = filler.plusDays(3); // peak, 3 trading days back from the trough
        insertCloseOnlyRow(stockId, mon, "120.00", 1000);
        insertCloseOnlyRow(stockId, mon.plusDays(1), "110.00", 1000); // Tue
        LocalDate followingMon = mon.plusDays(4); // real weekend gap, still the 3rd trading day
        insertCloseOnlyRow(stockId, followingMon, "100.00", 1000); // trough, 16.67% drop
        LocalDate reboundDate = followingMon.plusDays(1);
        insertCloseOnlyRow(stockId, reboundDate, "106.00", 1000); // +6%, clears the 5% default

        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, null, null)),
                Collections.singletonList(stockId), mon, reboundDate);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "the 3-trading-day dropDays window must reach the Monday peak despite the weekend gap");
        JsonNode detail = result.get("items").get(0).get("detail");
        assertEquals(mon.toString(), detail.get("peakDate").asText());
        assertBigDecimalEquals("16.67", detail.get("dropPercent"));
    }

    @Test
    void rebound_multipleHitsInRange_reportsOnlyTheMostRecentSignalDate() throws Exception {
        String stockId = "SS900D";
        seedStock(stockId, "反彈多次命中測試", true);
        LocalDate filler = LocalDate.of(2026, 9, 1);
        seedRamp(stockId, filler, 130.00, 130.00, 3, 1000);
        LocalDate peak1 = filler.plusDays(3);
        insertCloseOnlyRow(stockId, peak1, "120.00", 1000);
        insertCloseOnlyRow(stockId, peak1.plusDays(1), "110.00", 1000);
        LocalDate trough1 = peak1.plusDays(2);
        insertCloseOnlyRow(stockId, trough1, "100.00", 1000); // first hit: 16.67% drop
        LocalDate rebound1 = trough1.plusDays(1);
        insertCloseOnlyRow(stockId, rebound1, "106.00", 1000); // first rebound, +6%

        LocalDate peak2 = rebound1.plusDays(1);
        insertCloseOnlyRow(stockId, peak2, "140.00", 1000);
        insertCloseOnlyRow(stockId, peak2.plusDays(1), "125.00", 1000);
        LocalDate trough2 = peak2.plusDays(2);
        insertCloseOnlyRow(stockId, trough2, "110.00", 1000); // second hit: (140-110)/140 = 21.43% drop
        LocalDate rebound2 = trough2.plusDays(1);
        insertCloseOnlyRow(stockId, rebound2, "120.00", 1000); // second rebound, +9.09%

        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, null, null)),
                Collections.singletonList(stockId), peak1, rebound2);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(), "only the most recent signalDate is reported");
        assertEquals(rebound2.toString(), result.get("items").get(0).get("signalDate").asText());
    }

    @Test
    void rebound_dMustBeLow_dayAfterTrueTroughDoesNotMatchEvenThoughStillNearLow() throws Exception {
        String stockId = "SS802";
        seedStock(stockId, "反彈低點限定測試", true);
        LocalDate fillerStart = LocalDate.of(2026, 8, 22);
        seedRamp(stockId, fillerStart, 80.00, 80.00, 10, 1000); // 08-22..08-31, flat filler below the peak
        LocalDate peakDate = fillerStart.plusDays(10); // 09-01
        insertCloseOnlyRow(stockId, peakDate, "120.00", 1000);
        seedRamp(stockId, peakDate.plusDays(1), 117.00, 96.00, 8, 1000); // 09-02..09-09
        LocalDate y = peakDate.plusDays(9); // 09-10: the true, deepest trough
        insertCloseOnlyRow(stockId, y, "93.00", 1000);
        LocalDate z = y.plusDays(1); // 09-11: an uptick, still well below the peak but no longer the low
        insertCloseOnlyRow(stockId, z, "95.00", 1000);

        // requireRise=false isolates stage 1 (trough-finding) so this AC is not entangled with
        // whether a rebound is also found — signalDate then equals T directly. dropDays=10 reaches
        // back to the 09-01 peak (120) so the true trough clears the 10% default dropPercent.
        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(10, null, false, null, null)),
                Collections.singletonList(stockId), y, z);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "only the true trough day must match, not every day of the decline");
        JsonNode item = result.get("items").get(0);
        assertEquals(y.toString(), item.get("signalDate").asText(),
                "the day after the true trough must not be reported even though it is still deep");
    }

    @Test
    void rebound_measuresWindowMaxToPostMin_notFirstMinusLastClose() throws Exception {
        String stockId = "SS803";
        seedStock(stockId, "首尾相減會漏失測試", true);
        LocalDate day1 = LocalDate.of(2026, 7, 1);
        insertCloseOnlyRow(stockId, day1, "90.00", 1000);
        seedRamp(stockId, day1.plusDays(1), 110.00, 190.00, 5, 1000); // 07-02..07-06, rising toward the peak
        LocalDate peakDate = day1.plusDays(6); // 07-07
        insertCloseOnlyRow(stockId, peakDate, "200.00", 1000);
        List<LocalDate> declineDates = seedRamp(stockId, peakDate.plusDays(1), 180.00, 90.00, 13, 1000); // 07-08..07-20
        LocalDate d = declineDates.get(declineDates.size() - 1); // 07-20, close == day1's close

        // requireRise=false isolates the drop measurement itself from the (unrelated) rebound
        // stage; dropDays=20 reaches all the way back to day1 so the mid-window 200 peak is in view.
        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(20, null, false, null, null)),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "first (90.00) and last (90.00) close are identical, but the mid-window peak means a real drop happened");
        JsonNode detail = result.get("items").get(0).get("detail");
        assertBigDecimalEquals("200.00", detail.get("peakClose"));
        assertBigDecimalEquals("90.00", detail.get("troughClose"));
        assertBigDecimalEquals("55.00", detail.get("dropPercent"));
        assertFalse(detail.has("risePercent"), "requireRise=false must omit risePercent from detail");
    }

    @Test
    void cumulativeRise_omittedDaysAndRisePercent_matchesHandCalculatedTroughPeakAndRisePercent() throws Exception {
        // Omitting both days and risePercent must reproduce exactly what the old STANDARD preset
        // (lookback 20, risePercent 15%) produced — see specs/backend/strategy-scan.md, "兩個預設值
        // 沿用本型態原本「標準」那一段的值".
        String stockId = "SS811";
        seedStock(stockId, "累積上漲手算測試", true);
        LocalDate troughDate = LocalDate.of(2026, 8, 5);
        insertCloseOnlyRow(stockId, troughDate, "80.00", 1000);
        seedRamp(stockId, troughDate.plusDays(1), 81.00, 99.00, 18, 1000); // 08-06..08-23
        LocalDate d = LocalDate.of(2026, 8, 28); // deliberate 4-day calendar gap from 08-23; no interpolation
        insertCloseOnlyRow(stockId, d, "100.00", 1000);

        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", null, null)),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(20, result.get("days").asInt(), "omitted days must default to 20");
        assertFalse(result.has("preset"), "CUMULATIVE_RISE must never echo preset");
        assertEquals(1, result.get("matchedCount").asInt());
        JsonNode item = result.get("items").get(0);
        assertEquals(d.toString(), item.get("signalDate").asText());
        JsonNode detail = item.get("detail");
        assertEquals(troughDate.toString(), detail.get("troughDate").asText());
        assertBigDecimalEquals("80.00", detail.get("troughClose"));
        assertBigDecimalEquals("100.00", detail.get("peakClose"));
        assertBigDecimalEquals("25.00", detail.get("risePercent"));
        assertTrue(result.get("insufficientData").isEmpty());
        assertTrue(result.get("pendingConfirm").isEmpty());

        // Explicitly passing days=20/risePercent=15 must produce the identical result.
        ScanRequestDto explicit = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", 20, new BigDecimal("15"))),
                Collections.singletonList(stockId), d, d);
        JsonNode explicitResult = postScan(explicit).get("results").get(0);
        assertEquals(result.get("matchedCount").asInt(), explicitResult.get("matchedCount").asInt());
        assertBigDecimalEquals("25.00", explicitResult.get("items").get(0).get("detail").get("risePercent"));
    }

    @Test
    void cumulativeRise_noSingleDayRiseRequired_slowSteadyClimbStillMatches() throws Exception {
        String stockId = "SS812";
        seedStock(stockId, "緩步盤堅測試", true);
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<LocalDate> dates = seedRamp(stockId, start, 100.00, 119.00, 20, 1000); // ~1%/day, no day near 5%
        LocalDate d = dates.get(dates.size() - 1);

        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", null, null)),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "19% cumulative rise across the window clears the 15% default floor even though no single day rises 5%");
    }

    @Test
    void cumulativeRise_dMustBeHigh_dayAfterTruePeakDoesNotMatchEvenThoughStillHigh() throws Exception {
        String stockId = "SS813";
        seedStock(stockId, "累積上漲高點限定測試", true);
        LocalDate fillerStart = LocalDate.of(2026, 8, 22);
        seedRamp(stockId, fillerStart, 90.00, 90.00, 10, 1000); // 08-22..08-31, flat filler above the trough
        LocalDate troughDate = fillerStart.plusDays(10); // 09-01
        insertCloseOnlyRow(stockId, troughDate, "80.00", 1000);
        seedRamp(stockId, troughDate.plusDays(1), 83.00, 104.00, 8, 1000); // 09-02..09-09
        LocalDate y = troughDate.plusDays(9); // 09-10: the true, highest peak
        insertCloseOnlyRow(stockId, y, "107.00", 1000);
        LocalDate z = y.plusDays(1); // 09-11: a downtick, still well above the trough but no longer the high
        insertCloseOnlyRow(stockId, z, "105.00", 1000);

        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", null, null)),
                Collections.singletonList(stockId), y, z);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "only the true peak day must match, not every day of the climb");
        JsonNode item = result.get("items").get(0);
        assertEquals(y.toString(), item.get("signalDate").asText(),
                "the day after the true peak must not be reported even though it is still high");
    }

    @Test
    void rebound_dropDaysVaries_producesDifferentHitResults() throws Exception {
        String stockId = "SS821";
        seedStock(stockId, "回看區間反彈測試", true);
        LocalDate d1 = LocalDate.of(2026, 5, 1);
        insertCloseOnlyRow(stockId, d1, "200.00", 1000);
        seedRamp(stockId, d1.plusDays(1), 190.00, 115.00, 9, 1000); // day2..day10
        List<LocalDate> narrowRamp = seedRamp(stockId, d1.plusDays(10), 114.00, 110.10, 10, 1000); // day11..day20
        LocalDate d = narrowRamp.get(narrowRamp.size() - 1); // day20

        // requireRise=false isolates the drop measurement so only dropDays' effect is exercised.
        ScanRequestDto wideRequest = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(20, null, false, null, null)),
                Collections.singletonList(stockId), d, d);
        JsonNode wideResult = postScan(wideRequest).get("results").get(0);
        assertEquals(1, wideResult.get("matchedCount").asInt(),
                "dropDays=20 reaches the far 200 peak, clearing the 10% default dropPercent");

        ScanRequestDto narrowRequest = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(10, null, false, null, null)),
                Collections.singletonList(stockId), d, d);
        JsonNode narrowResult = postScan(narrowRequest).get("results").get(0);
        assertEquals(0, narrowResult.get("matchedCount").asInt(),
                "dropDays=10 only sees the local 114 peak, which fails the 10% default dropPercent");
    }

    @Test
    void cumulativeRise_daysVaries_days30And10ProduceDifferentHitResults() throws Exception {
        String stockId = "SS822";
        seedStock(stockId, "回看天數累積上漲測試", true);
        LocalDate d1 = LocalDate.of(2026, 5, 1);
        insertCloseOnlyRow(stockId, d1, "10.00", 1000);
        seedRamp(stockId, d1.plusDays(1), 20.00, 95.00, 9, 1000); // day2..day10
        List<LocalDate> looseRamp = seedRamp(stockId, d1.plusDays(10), 96.00, 98.50, 20, 1000); // day11..day30
        LocalDate d = looseRamp.get(looseRamp.size() - 1); // day30

        // days=30 reaches all the way back to day1's 10.00 trough — 20% floor clears easily.
        ScanRequestDto thirtyDaysRequest = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", 30, new BigDecimal("20"))),
                Collections.singletonList(stockId), d, d);
        JsonNode thirtyDaysResult = postScan(thirtyDaysRequest).get("results").get(0);
        assertEquals(30, thirtyDaysResult.get("days").asInt());
        assertEquals(1, thirtyDaysResult.get("matchedCount").asInt(),
                "days=30 reaches the far 10.00 trough, clearing the 20% floor");

        // days=10 only sees the local ~96.00..98.50 climb, which fails the same 20% floor.
        ScanRequestDto tenDaysRequest = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", 10, new BigDecimal("20"))),
                Collections.singletonList(stockId), d, d);
        JsonNode tenDaysResult = postScan(tenDaysRequest).get("results").get(0);
        assertEquals(10, tenDaysResult.get("days").asInt());
        assertEquals(0, tenDaysResult.get("matchedCount").asInt(),
                "days=10 only sees the local climb, which fails the 20% floor");
    }

    @Test
    void rebound_risePercentGatesReboundLegNotDropPercent_breakingSemanticChange() throws Exception {
        String stockId = "SS831";
        seedStock(stockId, "反彈幅度覆寫測試", true);
        LocalDate filler = LocalDate.of(2026, 4, 1);
        seedRamp(stockId, filler, 130.00, 130.00, 3, 1000);
        LocalDate peakDate = filler.plusDays(3);
        insertCloseOnlyRow(stockId, peakDate, "120.00", 1000);
        LocalDate mid = peakDate.plusDays(1);
        insertCloseOnlyRow(stockId, mid, "110.00", 1000);
        LocalDate troughDate = mid.plusDays(1); // 16.67% drop, clears the 10% default dropPercent
        insertCloseOnlyRow(stockId, troughDate, "100.00", 1000);
        LocalDate reboundDate = troughDate.plusDays(1); // 6% rebound
        insertCloseOnlyRow(stockId, reboundDate, "106.00", 1000);

        ScanRequestDto defaultRequest = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, null, null)),
                Collections.singletonList(stockId), peakDate, reboundDate);
        JsonNode defaultResult = postScan(defaultRequest).get("results").get(0);
        assertEquals(1, defaultResult.get("matchedCount").asInt(), "the default 5% risePercent must accept a 6% rebound");

        // BREAKING (specs/backend/strategy-scan.md, "語意變更（破壞性）"): risePercent used to override
        // REBOUND's drop threshold; it now gates the rebound leg instead. If it still meant "drop
        // override" a 10% value would have no bearing on the already-clearing 16.67% drop and this
        // would still hit; instead the 6% rebound must now fail a 10% REBOUND floor.
        ScanRequestDto overrideRequest = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, null, new BigDecimal("10"))),
                Collections.singletonList(stockId), peakDate, reboundDate);
        JsonNode overrideResult = postScan(overrideRequest).get("results").get(0);
        assertEquals(0, overrideResult.get("matchedCount").asInt(),
                "risePercent=10 must be applied as a 10% REBOUND floor (not a drop override); the 6% rebound must fail it");
    }

    @Test
    void risePercent_upperBoundRaisedTo50_30And50AreValidBut50Point1IsRejected() {
        String stockId = "SS841";
        seedStock(stockId, "上限放寬測試", true);
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now();

        ScanRequestDto thirty = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, null, new BigDecimal("30"))),
                Collections.singletonList(stockId), start, end);
        ResponseEntity<String> thirtyResponse = rest.postForEntity("/api/strategies/scan", thirty, String.class);
        assertEquals(HttpStatus.OK, thirtyResponse.getStatusCode(), "30 must be a valid risePercent override");

        ScanRequestDto fifty = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, null, new BigDecimal("50"))),
                Collections.singletonList(stockId), start, end);
        ResponseEntity<String> fiftyResponse = rest.postForEntity("/api/strategies/scan", fifty, String.class);
        assertEquals(HttpStatus.OK, fiftyResponse.getStatusCode(), "the boundary value 50 must be valid");

        ScanRequestDto fiftyOne = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, null, null, new BigDecimal("50.1"))),
                Collections.singletonList(stockId), start, end);
        ResponseEntity<ErrorResponse> fiftyOneResponse =
                rest.postForEntity("/api/strategies/scan", fiftyOne, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, fiftyOneResponse.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", fiftyOneResponse.getBody().getCode());
        assertEquals("REBOUND", fiftyOneResponse.getBody().getStrategy());
    }

    @Test
    void reboundAndCumulativeRise_insufficientLookback_reportedSeparatelyFromNoMatch() throws Exception {
        String stockId = "SS851";
        seedStock(stockId, "反彈與累積上漲資料不足測試", true);
        LocalDate start = LocalDate.of(2026, 2, 1);
        // Only 2 bars strictly before D: below both REBOUND's default dropDays-1+riseDays=3 and
        // CUMULATIVE_RISE's default days-1=19.
        seedCloseSeries(stockId, start.minusDays(2), repeat("100.00", 2), 1000);
        insertCloseOnlyRow(stockId, start, "100.00", 1000);

        ScanRequestDto request = scanRequestFromSelections(
                Arrays.asList(reboundSelection(null, null, null, null, null),
                        selectionDays("CUMULATIVE_RISE", null, null)),
                Collections.singletonList(stockId), start, start);
        JsonNode results = postScan(request).get("results");
        for (JsonNode result : results) {
            assertEquals(0, result.get("matchedCount").asInt());
            assertTrue(result.get("items").isEmpty());
            assertTrue(toStringList(result.get("insufficientData")).contains(stockId));
        }
    }

    @Test
    void scan_fiveStrategiesTogether_resultsReturnedInSubmittedOrder() throws Exception {
        String stockId = "SS861";
        seedStock(stockId, "五策略合併測試", true);
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 10);
        seedCloseSeries(stockId, start.minusDays(60), repeat("100.00", 80), 1000);

        ScanRequestDto request = scanRequestFromSelections(
                Arrays.asList(
                        reboundSelection(null, null, null, null, null),
                        selection("RISING_SUPPORT", "STANDARD"),
                        selection("BOX_BREAKOUT", "LOOSE"),
                        selection("HIGHER_LOWS", "LOOSE"),
                        selectionDays("CUMULATIVE_RISE", null, null)),
                Collections.singletonList(stockId), start, end);
        JsonNode results = postScan(request).get("results");
        assertEquals(5, results.size());
        assertEquals("REBOUND", results.get(0).get("strategy").asText());
        assertEquals("RISING_SUPPORT", results.get(1).get("strategy").asText());
        assertEquals("BOX_BREAKOUT", results.get(2).get("strategy").asText());
        assertEquals("HIGHER_LOWS", results.get(3).get("strategy").asText());
        assertEquals("CUMULATIVE_RISE", results.get(4).get("strategy").asText());
    }

    @Test
    void reboundAndCumulativeRiseScanResponse_containsNoAdviceWording() throws Exception {
        String stockId = "SS871";
        seedStock(stockId, "反彈累積上漲文案測試", true);
        LocalDate day1 = LocalDate.of(2026, 8, 6);
        seedRamp(stockId, day1, 90.00, 110.00, 4, 1000);
        LocalDate peakDate = day1.plusDays(4);
        insertCloseOnlyRow(stockId, peakDate, "120.00", 1000);
        List<LocalDate> declineDates = seedRamp(stockId, peakDate.plusDays(1), 119.00, 100.00, 15, 1000);
        LocalDate d = declineDates.get(declineDates.size() - 1);

        ScanRequestDto request = scanRequestFromSelections(
                Arrays.asList(reboundSelection(null, null, null, null, null),
                        selectionDays("CUMULATIVE_RISE", null, null)),
                Collections.singletonList(stockId), d, d);
        ResponseEntity<String> response = rest.postForEntity("/api/strategies/scan", request, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNoAdviceWording(response.getBody());
        assertFalse(response.getBody().contains("進場"), "must not contain 進場: " + response.getBody());
        assertFalse(response.getBody().contains("出場"), "must not contain 出場: " + response.getBody());
    }

    @Test
    void cumulativeRise_daysCountsTradingDaysNotCalendarDays_weekendDoesNotConsumeWindowLength() throws Exception {
        String stockId = "SS881";
        seedStock(stockId, "交易日週末測試", true);
        // Mon..Fri, then the next trading day is the following Monday — a real weekend gap.
        LocalDate mon = LocalDate.of(2026, 8, 24);
        insertCloseOnlyRow(stockId, mon, "80.00", 1000); // trough, 6 trading days back from D
        insertCloseOnlyRow(stockId, mon.plusDays(1), "101.00", 1000); // Tue
        insertCloseOnlyRow(stockId, mon.plusDays(2), "102.00", 1000); // Wed
        insertCloseOnlyRow(stockId, mon.plusDays(3), "103.00", 1000); // Thu
        insertCloseOnlyRow(stockId, mon.plusDays(4), "104.00", 1000); // Fri
        LocalDate d = mon.plusDays(7); // following Monday: 5 trading days + a real weekend gap
        insertCloseOnlyRow(stockId, d, "120.00", 1000); // D, the 6th trading day of the window

        // days=6 must reach all the way back to the Monday trough (80.00) despite the calendar
        // gap — if the weekend wrongly consumed window length, the window would instead start at
        // Wed/Thu and miss the 80.00 trough, producing a much smaller (or no) rise.
        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", 6, new BigDecimal("45"))),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "the 6-trading-day window must reach the Monday trough despite the weekend gap");
        JsonNode detail = result.get("items").get(0).get("detail");
        assertEquals(mon.toString(), detail.get("troughDate").asText());
        assertBigDecimalEquals("80.00", detail.get("troughClose"));
        assertBigDecimalEquals("50.00", detail.get("risePercent"));
    }

    @Test
    void cumulativeRise_days1_isLegalRequest_onlyHitsWhenRisePercentIsZero() throws Exception {
        String stockId = "SS882";
        seedStock(stockId, "單日窗口測試", true);
        LocalDate d = LocalDate.of(2026, 8, 10);
        insertCloseOnlyRow(stockId, d, "100.00", 1000);

        // Default risePercent (15) must yield zero hits, never a 400.
        ScanRequestDto defaultRisePercent = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", 1, null)),
                Collections.singletonList(stockId), d, d);
        ResponseEntity<String> defaultResponse = rest.postForEntity("/api/strategies/scan", defaultRisePercent, String.class);
        assertEquals(HttpStatus.OK, defaultResponse.getStatusCode(), "days=1 must be a legal request");
        JsonNode defaultResult = objectMapper.readTree(defaultResponse.getBody()).get("results").get(0);
        assertEquals(1, defaultResult.get("days").asInt());
        assertEquals(0, defaultResult.get("matchedCount").asInt(),
                "days=1's rise is always 0%, which fails any positive risePercent floor");

        // risePercent=0 must hit, since 0% rise clears a 0% floor.
        ScanRequestDto zeroRisePercent = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", 1, BigDecimal.ZERO)),
                Collections.singletonList(stockId), d, d);
        JsonNode zeroResult = postScan(zeroRisePercent).get("results").get(0);
        assertEquals(1, zeroResult.get("matchedCount").asInt(),
                "days=1 with risePercent=0 must hit — the window's only bar is trivially its own trough and peak");
    }

    @Test
    void cumulativeRise_days0_rejectedWithInvalidDays() {
        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", 0, null)),
                Collections.singletonList("SS883"), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_DAYS", response.getBody().getCode());
        assertEquals("CUMULATIVE_RISE", response.getBody().getStrategy());
    }

    @Test
    void cumulativeRise_days91_rejectedWithInvalidDays() {
        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", 91, null)),
                Collections.singletonList("SS884"), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_DAYS", response.getBody().getCode());
        assertEquals("CUMULATIVE_RISE", response.getBody().getStrategy());
    }

    @Test
    void cumulativeRise_days20Point5_rejectedWithInvalidDays() {
        StrategySelectionDto dto = new StrategySelectionDto();
        dto.setCode("CUMULATIVE_RISE");
        dto.setDays(new BigDecimal("20.5"));
        ScanRequestDto request = scanRequestFromSelections(Collections.singletonList(dto),
                Collections.singletonList("SS885"), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_DAYS", response.getBody().getCode());
        assertEquals("CUMULATIVE_RISE", response.getBody().getStrategy());
    }

    @Test
    void cumulativeRise_presetSent_rejectedWithPresetNotApplicable() {
        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(selection("CUMULATIVE_RISE", "STANDARD")),
                Collections.singletonList("SS886"), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("PRESET_NOT_APPLICABLE", response.getBody().getCode());
        assertEquals("CUMULATIVE_RISE", response.getBody().getStrategy());
    }

    @Test
    void rebound_daysSent_rejectedWithDaysNotApplicable() {
        StrategySelectionDto dto = reboundSelection(null, null, null, null, null);
        dto.setDays(BigDecimal.TEN);
        ScanRequestDto request = scanRequestFromSelections(Collections.singletonList(dto),
                Collections.singletonList("SS887"), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("DAYS_NOT_APPLICABLE", response.getBody().getCode());
        assertEquals("REBOUND", response.getBody().getStrategy());
    }

    @Test
    void rebound_presetSent_rejectedWithPresetNotApplicable() {
        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(selection("REBOUND", "STANDARD")),
                Collections.singletonList("SS892"), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("PRESET_NOT_APPLICABLE", response.getBody().getCode());
        assertEquals("REBOUND", response.getBody().getStrategy());
    }

    @Test
    void rebound_reboundOnlyParamsSentToOtherStrategies_rejectedWithParamNotApplicable() {
        for (String param : new String[]{"requireRise", "dropDays", "dropPercent", "riseDays"}) {
            StrategySelectionDto dto = selection("BOX_BREAKOUT", "STANDARD");
            if ("requireRise".equals(param)) {
                dto.setRequireRise(true);
            } else if ("dropDays".equals(param)) {
                dto.setDropDays(BigDecimal.TEN);
            } else if ("dropPercent".equals(param)) {
                dto.setDropPercent(BigDecimal.TEN);
            } else {
                dto.setRiseDays(BigDecimal.ONE);
            }
            ScanRequestDto request = scanRequestFromSelections(Collections.singletonList(dto),
                    Collections.singletonList("SS893"), LocalDate.now().minusDays(1), LocalDate.now());
            ResponseEntity<ErrorResponse> response =
                    rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), param + " must be rejected");
            assertEquals("PARAM_NOT_APPLICABLE", response.getBody().getCode());
            assertEquals("BOX_BREAKOUT", response.getBody().getStrategy());
            assertEquals(param, response.getBody().getParam());
        }
    }

    @Test
    void rebound_cumulativeRiseSentReboundOnlyParams_alsoRejectedWithParamNotApplicable() {
        StrategySelectionDto dto = selectionDays("CUMULATIVE_RISE", null, null);
        dto.setDropDays(BigDecimal.TEN);
        ScanRequestDto request = scanRequestFromSelections(Collections.singletonList(dto),
                Collections.singletonList("SS894"), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("PARAM_NOT_APPLICABLE", response.getBody().getCode());
        assertEquals("CUMULATIVE_RISE", response.getBody().getStrategy());
        assertEquals("dropDays", response.getBody().getParam());
    }

    @Test
    void rebound_requireRiseFalseWithRiseDaysOrRisePercent_rejectedWithParamNotApplicable() {
        StrategySelectionDto withRiseDays = reboundSelection(null, null, false, 2, null);
        ScanRequestDto riseDaysRequest = scanRequestFromSelections(Collections.singletonList(withRiseDays),
                Collections.singletonList("SS895"), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<ErrorResponse> riseDaysResponse =
                rest.postForEntity("/api/strategies/scan", riseDaysRequest, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, riseDaysResponse.getStatusCode());
        assertEquals("PARAM_NOT_APPLICABLE", riseDaysResponse.getBody().getCode());
        assertEquals("REBOUND", riseDaysResponse.getBody().getStrategy());
        assertEquals("riseDays", riseDaysResponse.getBody().getParam());

        StrategySelectionDto withRisePercent = reboundSelection(null, null, false, null, new BigDecimal("5"));
        ScanRequestDto risePercentRequest = scanRequestFromSelections(Collections.singletonList(withRisePercent),
                Collections.singletonList("SS896"), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<ErrorResponse> risePercentResponse =
                rest.postForEntity("/api/strategies/scan", risePercentRequest, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, risePercentResponse.getStatusCode());
        assertEquals("PARAM_NOT_APPLICABLE", risePercentResponse.getBody().getCode());
        assertEquals("REBOUND", risePercentResponse.getBody().getStrategy());
        assertEquals("risePercent", risePercentResponse.getBody().getParam());
    }

    @Test
    void rebound_requireRiseFalse_onlyUsesDropLeg_signalDateEqualsTroughAndDetailOmitsRisePercent() throws Exception {
        String stockId = "SS897";
        seedStock(stockId, "反彈關閉漲段測試", true);
        LocalDate filler = LocalDate.of(2026, 5, 30);
        seedRamp(stockId, filler, 130.00, 130.00, 2, 1000); // dropDays-1 = 2 bars needed (requireRise=false)
        LocalDate peakDate = filler.plusDays(2);
        insertCloseOnlyRow(stockId, peakDate, "120.00", 1000);
        LocalDate troughDate = peakDate.plusDays(2); // dropDays=3 default window = [peakDate, mid, troughDate]
        insertCloseOnlyRow(stockId, peakDate.plusDays(1), "110.00", 1000);
        insertCloseOnlyRow(stockId, troughDate, "100.00", 1000); // 16.67% drop, clears the 10% default

        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(null, null, false, null, null)),
                Collections.singletonList(stockId), peakDate, troughDate);
        JsonNode result = postScan(request).get("results").get(0);
        assertFalse(result.get("requireRise").asBoolean());
        assertFalse(result.has("riseDays"), "requireRise=false must omit riseDays from the response header");
        assertFalse(result.has("risePercent"), "requireRise=false must omit risePercent from the response header");
        assertEquals(1, result.get("matchedCount").asInt());
        JsonNode item = result.get("items").get(0);
        assertEquals(troughDate.toString(), item.get("signalDate").asText(), "signalDate must equal T itself");
        assertFalse(item.get("detail").has("risePercent"), "detail must omit risePercent when requireRise is false");
    }

    @Test
    void rebound_dropDays1_isLegalRequest_onlyHitsWhenDropPercentIsZero() throws Exception {
        String stockId = "SS898";
        seedStock(stockId, "單日跌段窗口測試", true);
        LocalDate d = LocalDate.of(2026, 6, 10);
        insertCloseOnlyRow(stockId, d, "100.00", 1000);

        ScanRequestDto defaultDropPercent = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(1, null, false, null, null)),
                Collections.singletonList(stockId), d, d);
        JsonNode defaultResult = postScan(defaultDropPercent).get("results").get(0);
        assertEquals(1, defaultResult.get("dropDays").asInt());
        assertEquals(0, defaultResult.get("matchedCount").asInt(),
                "dropDays=1's drop is always 0%, which fails the positive default dropPercent floor");

        ScanRequestDto zeroDropPercent = scanRequestFromSelections(
                Collections.singletonList(reboundSelection(1, BigDecimal.ZERO, false, null, null)),
                Collections.singletonList(stockId), d, d);
        JsonNode zeroResult = postScan(zeroDropPercent).get("results").get(0);
        assertEquals(1, zeroResult.get("matchedCount").asInt(),
                "dropDays=1 with dropPercent=0 must hit — the window's only bar is trivially its own peak and trough");
    }

    @Test
    void rebound_invalidDropDays_rejected() {
        for (String value : new String[]{"0", "91", "3.5"}) {
            StrategySelectionDto dto = reboundSelection(null, null, null, null, null);
            dto.setDropDays(new BigDecimal(value));
            ScanRequestDto request = scanRequestFromSelections(Collections.singletonList(dto),
                    Collections.singletonList("SS899"), LocalDate.now().minusDays(1), LocalDate.now());
            ResponseEntity<ErrorResponse> response =
                    rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "dropDays=" + value + " must be rejected");
            assertEquals("INVALID_DROP_DAYS", response.getBody().getCode());
            assertEquals("REBOUND", response.getBody().getStrategy());
        }
    }

    @Test
    void rebound_invalidRiseDays_rejected() {
        for (String value : new String[]{"0", "91", "1.5"}) {
            StrategySelectionDto dto = reboundSelection(null, null, null, null, null);
            dto.setRiseDays(new BigDecimal(value));
            ScanRequestDto request = scanRequestFromSelections(Collections.singletonList(dto),
                    Collections.singletonList("SS900"), LocalDate.now().minusDays(1), LocalDate.now());
            ResponseEntity<ErrorResponse> response =
                    rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "riseDays=" + value + " must be rejected");
            assertEquals("INVALID_RISE_DAYS", response.getBody().getCode());
            assertEquals("REBOUND", response.getBody().getStrategy());
        }
    }

    @Test
    void rebound_invalidDropPercent_rejected() {
        for (String value : new String[]{"-1", "50.1", "10.55"}) {
            StrategySelectionDto dto = reboundSelection(null, null, null, null, null);
            dto.setDropPercent(new BigDecimal(value));
            ScanRequestDto request = scanRequestFromSelections(Collections.singletonList(dto),
                    Collections.singletonList("SS901A"), LocalDate.now().minusDays(1), LocalDate.now());
            ResponseEntity<ErrorResponse> response =
                    rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "dropPercent=" + value + " must be rejected");
            assertEquals("INVALID_DROP_PERCENT", response.getBody().getCode());
            assertEquals("REBOUND", response.getBody().getStrategy());
        }
    }

    @Test
    void otherThreeStrategies_daysSent_alsoRejectedWithDaysNotApplicable() {
        for (String[] codeAndPreset : new String[][]{
                {"BOX_BREAKOUT", "STANDARD"}, {"HIGHER_LOWS", "STANDARD"}, {"RISING_SUPPORT", "STANDARD"}}) {
            StrategySelectionDto dto = selection(codeAndPreset[0], codeAndPreset[1]);
            dto.setDays(BigDecimal.TEN);
            ScanRequestDto request = scanRequestFromSelections(Collections.singletonList(dto),
                    Collections.singletonList("SS888"), LocalDate.now().minusDays(1), LocalDate.now());
            ResponseEntity<ErrorResponse> response =
                    rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals("DAYS_NOT_APPLICABLE", response.getBody().getCode());
            assertEquals(codeAndPreset[0], response.getBody().getStrategy());
        }
    }

    @Test
    void presetBasedStrategiesMissingPreset_stillRejectedWithUnknownStrategy_notRelaxed() {
        // REBOUND is excluded here since it dropped presets entirely (see "反彈改為跌段＋選用漲段的雙段參數、移除靈敏度")
        // — omitting `preset` for it is now the *correct* request shape, not an error.
        for (String code : new String[]{"BOX_BREAKOUT", "HIGHER_LOWS", "RISING_SUPPORT"}) {
            ScanRequestDto request = new ScanRequestDto();
            request.setStrategies(Collections.singletonList(selection(code, null)));
            ResponseEntity<ErrorResponse> response =
                    rest.postForEntity("/api/strategies/scan", request, ErrorResponse.class);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                    code + " missing preset must still be rejected");
            assertEquals("UNKNOWN_STRATEGY", response.getBody().getCode());
        }
    }

    @Test
    void scanResponseShape_cumulativeRiseReturnsDaysNotPreset_othersReturnPresetNotDays() throws Exception {
        String stockId = "SS889";
        seedStock(stockId, "回應欄位形狀測試", true);
        seedCloseSeries(stockId, LocalDate.of(2026, 1, 1), repeat("100.00", 40), 1000);
        LocalDate d = LocalDate.of(2026, 2, 9);

        ScanRequestDto request = scanRequestFromSelections(
                Arrays.asList(
                        selection("BOX_BREAKOUT", "LOOSE"),
                        selection("HIGHER_LOWS", "LOOSE"),
                        selection("RISING_SUPPORT", "LOOSE"),
                        reboundSelection(null, null, null, null, null),
                        selectionDays("CUMULATIVE_RISE", 15, null)),
                Collections.singletonList(stockId), d, d);
        JsonNode results = postScan(request).get("results");
        for (int i = 0; i < 3; i++) {
            JsonNode result = results.get(i);
            assertTrue(result.has("preset"), result.get("strategy").asText() + " must return preset");
            assertFalse(result.has("days"), result.get("strategy").asText() + " must not return days");
        }
        JsonNode reboundResult = results.get(3);
        assertEquals("REBOUND", reboundResult.get("strategy").asText());
        assertFalse(reboundResult.has("preset"), "REBOUND must not return preset");
        assertFalse(reboundResult.has("days"), "REBOUND must not return days");
        assertTrue(reboundResult.has("requireRise"), "REBOUND must return requireRise");
        assertTrue(reboundResult.has("dropDays"), "REBOUND must return dropDays");
        assertTrue(reboundResult.has("dropPercent"), "REBOUND must return dropPercent");

        JsonNode cumulativeRiseResult = results.get(4);
        assertEquals("CUMULATIVE_RISE", cumulativeRiseResult.get("strategy").asText());
        assertFalse(cumulativeRiseResult.has("preset"), "CUMULATIVE_RISE must not return preset");
        assertTrue(cumulativeRiseResult.has("days"), "CUMULATIVE_RISE must return days");
        assertEquals(15, cumulativeRiseResult.get("days").asInt());
    }

    @Test
    void cumulativeRise_daysExceedsAvailableHistory_stockGoesToInsufficientData() throws Exception {
        String stockId = "SS890";
        seedStock(stockId, "天數超過可用行情測試", true);
        LocalDate d = LocalDate.of(2026, 3, 1);
        // Only 5 bars strictly before D; days=90 needs 89.
        seedCloseSeries(stockId, d.minusDays(5), repeat("100.00", 5), 1000);
        insertCloseOnlyRow(stockId, d, "100.00", 1000);

        ScanRequestDto request = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", 90, null)),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(0, result.get("matchedCount").asInt());
        assertTrue(result.get("items").isEmpty());
        assertTrue(toStringList(result.get("insufficientData")).contains(stockId));
    }

    @Test
    void cumulativeRise_risePercentValidationUnchanged_boundaryValidUpperExceededInvalid() {
        String stockId = "SS891";
        seedStock(stockId, "漲幅門檻邊界測試", true);
        ScanRequestDto boundaryRequest = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", null, new BigDecimal("50"))),
                Collections.singletonList(stockId), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<String> boundaryResponse =
                rest.postForEntity("/api/strategies/scan", boundaryRequest, String.class);
        assertEquals(HttpStatus.OK, boundaryResponse.getStatusCode(), "risePercent=50 must remain valid");

        ScanRequestDto tooHighRequest = scanRequestFromSelections(
                Collections.singletonList(selectionDays("CUMULATIVE_RISE", null, new BigDecimal("50.1"))),
                Collections.singletonList(stockId), LocalDate.now().minusDays(1), LocalDate.now());
        ResponseEntity<ErrorResponse> tooHighResponse =
                rest.postForEntity("/api/strategies/scan", tooHighRequest, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, tooHighResponse.getStatusCode());
        assertEquals("INVALID_RISE_PERCENT", tooHighResponse.getBody().getCode(),
                "out-of-range risePercent must still be INVALID_RISE_PERCENT, not INVALID_DAYS");
        assertEquals("CUMULATIVE_RISE", tooHighResponse.getBody().getStrategy());
    }

    // ==================== helpers ====================

    private JsonNode findByCode(JsonNode array, String code) {
        for (JsonNode node : array) {
            if (code.equals(node.get("code").asText())) {
                return node;
            }
        }
        throw new AssertionError("code not found: " + code);
    }

    private JsonNode findPresetByCode(JsonNode presets, String code) {
        return findByCode(presets, code);
    }

    private void assertBigDecimalEquals(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
                "expected " + expected + " but was " + actual.decimalValue());
    }

    private List<String> toStringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(node.asText());
        }
        return result;
    }

    private boolean containsStockId(JsonNode items, String stockId) {
        for (JsonNode item : items) {
            if (stockId.equals(item.get("stockId").asText())) {
                return true;
            }
        }
        return false;
    }

    private StrategySelectionDto selection(String code, String preset) {
        StrategySelectionDto dto = new StrategySelectionDto();
        dto.setCode(code);
        dto.setPreset(preset);
        return dto;
    }

    private StrategySelectionDto selection(String code, String preset, BigDecimal risePercent) {
        StrategySelectionDto dto = selection(code, preset);
        dto.setRisePercent(risePercent);
        return dto;
    }

    /** Increment 4 helper: builds a CUMULATIVE_RISE-shaped selection — `days`/`risePercent`, no
     *  `preset`. Either argument may be null to mean "omitted" (defaults to 20 / 15% respectively). */
    private StrategySelectionDto selectionDays(String code, Integer days, BigDecimal risePercent) {
        StrategySelectionDto dto = new StrategySelectionDto();
        dto.setCode(code);
        dto.setDays(days != null ? BigDecimal.valueOf(days) : null);
        dto.setRisePercent(risePercent);
        return dto;
    }

    /**
     * Builds a REBOUND-shaped selection — no `preset`, and each of `dropDays`/`dropPercent`/
     * `requireRise`/`riseDays`/`risePercent` set only when the corresponding argument is non-null
     * (null means "omitted", i.e. use that field's own default) — see
     * specs/backend/strategy-scan.md, "反彈改為跌段＋選用漲段的雙段參數、移除靈敏度".
     */
    private StrategySelectionDto reboundSelection(Integer dropDays, BigDecimal dropPercent, Boolean requireRise,
                                                   Integer riseDays, BigDecimal risePercent) {
        StrategySelectionDto dto = new StrategySelectionDto();
        dto.setCode("REBOUND");
        if (dropDays != null) {
            dto.setDropDays(BigDecimal.valueOf(dropDays));
        }
        dto.setDropPercent(dropPercent);
        dto.setRequireRise(requireRise);
        if (riseDays != null) {
            dto.setRiseDays(BigDecimal.valueOf(riseDays));
        }
        dto.setRisePercent(risePercent);
        return dto;
    }

    private ScanRequestDto scanRequest(List<String[]> strategies, List<String> stockIds,
                                        LocalDate startDate, LocalDate endDate) {
        ScanRequestDto request = new ScanRequestDto();
        List<StrategySelectionDto> selections = new ArrayList<>();
        for (String[] s : strategies) {
            selections.add(selection(s[0], s[1]));
        }
        request.setStrategies(selections);
        request.setStockIds(stockIds);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        return request;
    }

    /** Increment 3 helper: builds the request directly from fully-formed selections (needed once
     *  risePercent overrides are involved, since the String[] shorthand above has no room for them). */
    private ScanRequestDto scanRequestFromSelections(List<StrategySelectionDto> selections, List<String> stockIds,
                                                       LocalDate startDate, LocalDate endDate) {
        ScanRequestDto request = new ScanRequestDto();
        request.setStrategies(selections);
        request.setStockIds(stockIds);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        return request;
    }

    /**
     * `count` close-only bars linearly interpolated from startValue (day 0) to endValue (day
     * count-1) inclusive, 2-decimal precision, consecutive calendar days ascending from start.
     * Used for REBOUND/CUMULATIVE_RISE fixtures, where only the two endpoints of a leg need an
     * exact value and the days in between just need to be monotonic.
     */
    private List<LocalDate> seedRamp(String stockId, LocalDate start, double startValue, double endValue,
                                      int count, long volume) {
        List<LocalDate> dates = new ArrayList<>(count);
        LocalDate d = start;
        for (int i = 0; i < count; i++) {
            double v = count == 1 ? startValue : startValue + (endValue - startValue) * i / (count - 1);
            insertCloseOnlyRow(stockId, d, String.format(java.util.Locale.ROOT, "%.2f", v), volume);
            dates.add(d);
            d = d.plusDays(1);
        }
        return dates;
    }

    private JsonNode postScan(ScanRequestDto request) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/strategies/scan", request, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private void seedStock(String stockId, String name, boolean active) {
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES (?, ?, 'TSE', ?)",
                stockId, name, active);
    }

    private void insertPriceRow(String stockId, LocalDate date, String open, String high, String low,
                                 String close, long volume) {
        jdbc.update("INSERT INTO stock_daily_price "
                        + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, "
                        + "turnover, transaction_count, source) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'TEST')",
                stockId, date, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), volume);
    }

    /** Flat OHLC bar: open = high = low = close = the given value. Used for RISING_SUPPORT fixtures,
     *  which only look at close prices. */
    private void insertCloseOnlyRow(String stockId, LocalDate date, String close, long volume) {
        insertPriceRow(stockId, date, close, close, close, close, volume);
    }

    /** One close-only bar per element of `closes`, consecutive calendar days ascending from start. */
    private List<LocalDate> seedCloseSeries(String stockId, LocalDate start, String[] closes, long volume) {
        List<LocalDate> dates = new ArrayList<>(closes.length);
        LocalDate d = start;
        for (String close : closes) {
            insertCloseOnlyRow(stockId, d, close, volume);
            dates.add(d);
            d = d.plusDays(1);
        }
        return dates;
    }

    private String[] repeat(String value, int count) {
        String[] result = new String[count];
        java.util.Arrays.fill(result, value);
        return result;
    }

    /** `count` consecutive calendar-day bars, each with the given open/high/low, ascending from start. */
    private List<LocalDate> seedTightBox(String stockId, LocalDate start, int count, String open, String high,
                                          String low, long volume) {
        List<LocalDate> dates = new ArrayList<>(count);
        LocalDate d = start;
        for (int i = 0; i < count; i++) {
            insertPriceRow(stockId, d, open, high, low, open, volume);
            dates.add(d);
            d = d.plusDays(1);
        }
        return dates;
    }

    /**
     * HIGHER_LOWS/MA5 fixture: 3 separated dips, each a 5-consecutive-day flat run at the given
     * close value surrounded by a 200.00 shoulder plateau. Because MA5 is a trailing 5-day close
     * average, the LAST day of each 5-day flat run is the one whose MA5 exactly equals that dip
     * value (every day of the run before it still partly averages in the higher shoulder) — so it
     * becomes the swing-low "signal" day, shifted 4 days later than the run's first day. This keeps
     * every rise-percentage relationship between dipLows[] identical to a raw-low fixture's, while
     * being genuinely MA5-based (flat, low=close=ma5 for every bar; see
     * specs/backend/strategy-scan.md, "底底高改以 MA5 平滑線為判定基準").
     *
     * <p>Layout: 10 lead-in days at 200.00 (indices 0-9, ample pre-startDate lookback for every
     * preset), then per dip: 5 flat days at dipLows[k] followed by 6 shoulder days at 200.00.
     * Returned dates: index 10 is the conventional scan startDate; the signal day for dip k
     * (0-based) is at index {@code 10 + k*11 + 4}.
     */
    private List<LocalDate> seedHigherLowsFixture(String stockId, LocalDate day0, String[] dipLows) {
        List<String> template = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            template.add("200.00");
        }
        for (String dip : dipLows) {
            for (int i = 0; i < 5; i++) {
                template.add(dip);
            }
            for (int i = 0; i < 6; i++) {
                template.add("200.00");
            }
        }
        List<LocalDate> dates = new ArrayList<>(template.size());
        LocalDate d = day0;
        for (String close : template) {
            insertCloseOnlyRow(stockId, d, close, 1000);
            dates.add(d);
            d = d.plusDays(1);
        }
        return dates;
    }

    /** The signal (swing-low) index within {@link #seedHigherLowsFixture}'s returned dates for the
     *  0-based dip number {@code dipIndex}. */
    private int higherLowsSignalIndex(int dipIndex) {
        return 10 + dipIndex * 11 + 4;
    }
}
