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
        assertEquals("左右各 5 根，需 3 段遞增，每段高過 2%",
                findPresetByCode(higherLows.get("presets"), "STRICT").get("description").asText());
        assertEquals("左右各 3 根，需 2 段遞增，每段高過 1%",
                findPresetByCode(higherLows.get("presets"), "STANDARD").get("description").asText());
        assertEquals("左右各 2 根，需 2 段遞增，高過即計",
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
                Collections.singletonList(stockId), dates.get(3), dates.get(dates.size() - 1));
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt());
        JsonNode item = result.get("items").get(0);
        assertEquals(dates.get(21).toString(), item.get("signalDate").asText());
        JsonNode lows = item.get("detail").get("lows");
        assertEquals(3, lows.size());
        assertEquals(dates.get(5).toString(), lows.get(0).get("tradeDate").asText());
        assertBigDecimalEquals("100.00", lows.get(0).get("low"));
        assertEquals(dates.get(13).toString(), lows.get(1).get("tradeDate").asText());
        assertBigDecimalEquals("102.00", lows.get(1).get("low"));
        assertEquals(dates.get(21).toString(), lows.get(2).get("tradeDate").asText());
        assertBigDecimalEquals("104.50", lows.get(2).get("low"));
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
                Collections.singletonList(stockId), dates.get(3), dates.get(dates.size() - 1));
        JsonNode standardResult = postScan(standardRequest).get("results").get(0);
        assertEquals(0, standardResult.get("matchedCount").asInt(), "0.3% rises must not satisfy STANDARD's 1% floor");

        ScanRequestDto looseRequest = scanRequest(
                Collections.singletonList(new String[]{"HIGHER_LOWS", "LOOSE"}),
                Collections.singletonList(stockId), dates.get(3), dates.get(dates.size() - 1));
        JsonNode looseResult = postScan(looseRequest).get("results").get(0);
        assertEquals(1, looseResult.get("matchedCount").asInt(), "LOOSE only requires a strictly higher low");
    }

    // ==================== AC10: swing low needs both sides; boundary candidate excluded ====================

    @Test
    void higherLows_swingLowNearEndOfWindow_missingRightSideIsExcludedNotMatched() throws Exception {
        String stockId = "SS221";
        seedStock(stockId, "邊界排除測試", true);
        LocalDate day0 = LocalDate.of(2026, 9, 1);
        // idx -2,-1: lookback (2 days, satisfies LOOSE's swingBars=2 requirement)
        // idx 0..10: window. idx2=swing low(100), idx6=swing low(102, +2% rise, only 1 rise so far).
        // idx10 = LAST day of window: would-be low(104) but has NO right-side neighbor at all -> must
        // be excluded, so only 1 rise exists overall (not the 2 LOOSE requires) -> no hit.
        String[] lows = {
                "150.00", "150.00",             // idx -2, -1 (lookback)
                "130.00", "120.00", "100.00", "120.00", "130.00", // idx0..4 (dip at idx2)
                "120.00", "102.00", "120.00", "130.00", "120.00", // idx5..9 (dip at idx6)
                "104.00"                         // idx10 (would-be dip, boundary, excluded)
        };
        LocalDate d = day0.minusDays(2);
        List<LocalDate> allDates = new ArrayList<>();
        for (String low : lows) {
            allDates.add(d);
            BigDecimal l = new BigDecimal(low);
            insertPriceRow(stockId, d, l.add(BigDecimal.ONE).toPlainString(), l.add(BigDecimal.ONE).toPlainString(),
                    low, l.add(BigDecimal.ONE).toPlainString(), 1000);
            d = d.plusDays(1);
        }
        LocalDate startDate = allDates.get(2); // idx0
        LocalDate endDate = allDates.get(allDates.size() - 1); // idx10

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"HIGHER_LOWS", "LOOSE"}),
                Collections.singletonList(stockId), startDate, endDate);
        JsonNode result = postScan(request).get("results").get(0);
        assertTrue(result.get("insufficientData").isEmpty(), "left-side lookback was sufficient");
        assertEquals(0, result.get("matchedCount").asInt(),
                "the last day's dip has no right-side neighbor at all and must be excluded, not matched");
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
                Collections.singletonList(stockId), dates.get(3), dates.get(dates.size() - 1));
        JsonNode defaultResult = postScan(defaultRequest).get("results").get(0);
        assertEquals(0, defaultResult.get("matchedCount").asInt(), "STANDARD's own 1% floor must reject ~0.3% legs");

        // 0.2%, not 0.3%: the fixture's second leg (100.60 over 100.30) is only a 0.2991% rise, so a
        // 0.30% override would still (correctly) reject it, and risePercent allows at most one
        // decimal digit anyway — 0.2% comfortably clears both legs.
        ScanRequestDto overrideRequest = scanRequestFromSelections(
                Collections.singletonList(selection("HIGHER_LOWS", "STANDARD", new BigDecimal("0.2"))),
                Collections.singletonList(stockId), dates.get(3), dates.get(dates.size() - 1));
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
                Collections.singletonList(stockId), dates.get(3), dates.get(dates.size() - 1));
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

        // HIGHER_LOWS: 25-day fixture; dates.get(3) becomes the shared scan startDate, dates.get(last) the shared endDate.
        LocalDate higherLowsDay0 = LocalDate.of(2026, 3, 22);
        List<LocalDate> higherLowsDates = seedHigherLowsFixture(higherLowsStock, higherLowsDay0,
                new String[]{"100.00", "102.00", "104.50"}); // +2%, +2.45% legs -> hits under STANDARD's own 1% floor
        LocalDate startDate = higherLowsDates.get(3);
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

        JsonNode rebound = findByCode(strategies, "REBOUND");
        assertEquals("反彈", rebound.get("name").asText());
        assertEquals(3, rebound.get("presets").size());
        assertEquals("回看 20 日，自區間最高收盤跌幅 ≥ 20% 的最低點",
                findPresetByCode(rebound.get("presets"), "STRICT").get("description").asText());
        assertEquals("回看 20 日，自區間最高收盤跌幅 ≥ 15% 的最低點",
                findPresetByCode(rebound.get("presets"), "STANDARD").get("description").asText());
        assertEquals("回看 10 日，自區間最高收盤跌幅 ≥ 10% 的最低點",
                findPresetByCode(rebound.get("presets"), "LOOSE").get("description").asText());

        JsonNode cumulativeRise = findByCode(strategies, "CUMULATIVE_RISE");
        assertEquals("累積上漲", cumulativeRise.get("name").asText());
        assertEquals(3, cumulativeRise.get("presets").size());
        assertEquals("回看 20 日，自區間最低收盤累積漲幅 ≥ 20% 的最高點",
                findPresetByCode(cumulativeRise.get("presets"), "STRICT").get("description").asText());
        assertEquals("回看 20 日，自區間最低收盤累積漲幅 ≥ 15% 的最高點",
                findPresetByCode(cumulativeRise.get("presets"), "STANDARD").get("description").asText());
        assertEquals("回看 10 日，自區間最低收盤累積漲幅 ≥ 10% 的最高點",
                findPresetByCode(cumulativeRise.get("presets"), "LOOSE").get("description").asText());
    }

    @Test
    void rebound_standard_matchesHandCalculatedPeakTroughAndDropPercent() throws Exception {
        String stockId = "SS801";
        seedStock(stockId, "反彈手算測試", true);
        LocalDate day1 = LocalDate.of(2026, 8, 6);
        seedRamp(stockId, day1, 90.00, 110.00, 4, 1000); // 08-06..08-09, below the peak
        LocalDate peakDate = day1.plusDays(4); // 08-10
        insertCloseOnlyRow(stockId, peakDate, "120.00", 1000);
        List<LocalDate> declineDates = seedRamp(stockId, peakDate.plusDays(1), 119.00, 100.00, 15, 1000); // 08-11..08-25
        LocalDate d = declineDates.get(declineDates.size() - 1); // 08-25

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"REBOUND", "STANDARD"}),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt());
        JsonNode item = result.get("items").get(0);
        assertEquals(stockId, item.get("stockId").asText());
        assertEquals(d.toString(), item.get("signalDate").asText());
        JsonNode detail = item.get("detail");
        assertEquals(peakDate.toString(), detail.get("peakDate").asText());
        assertBigDecimalEquals("120.00", detail.get("peakClose"));
        assertBigDecimalEquals("100.00", detail.get("troughClose"));
        assertBigDecimalEquals("16.67", detail.get("dropPercent"));
        assertTrue(result.get("insufficientData").isEmpty());
        assertTrue(result.get("pendingConfirm").isEmpty());
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

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"REBOUND", "STANDARD"}),
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

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"REBOUND", "STANDARD"}),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "first (90.00) and last (90.00) close are identical, but the mid-window peak means a real drop happened");
        JsonNode detail = result.get("items").get(0).get("detail");
        assertBigDecimalEquals("200.00", detail.get("peakClose"));
        assertBigDecimalEquals("90.00", detail.get("troughClose"));
        assertBigDecimalEquals("55.00", detail.get("dropPercent"));
    }

    @Test
    void cumulativeRise_standard_matchesHandCalculatedTroughPeakAndRisePercent() throws Exception {
        String stockId = "SS811";
        seedStock(stockId, "累積上漲手算測試", true);
        LocalDate troughDate = LocalDate.of(2026, 8, 5);
        insertCloseOnlyRow(stockId, troughDate, "80.00", 1000);
        seedRamp(stockId, troughDate.plusDays(1), 81.00, 99.00, 18, 1000); // 08-06..08-23
        LocalDate d = LocalDate.of(2026, 8, 28); // deliberate 4-day calendar gap from 08-23; no interpolation
        insertCloseOnlyRow(stockId, d, "100.00", 1000);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"CUMULATIVE_RISE", "STANDARD"}),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
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
    }

    @Test
    void cumulativeRise_noSingleDayRiseRequired_slowSteadyClimbStillMatches() throws Exception {
        String stockId = "SS812";
        seedStock(stockId, "緩步盤堅測試", true);
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<LocalDate> dates = seedRamp(stockId, start, 100.00, 119.00, 20, 1000); // ~1%/day, no day near 5%
        LocalDate d = dates.get(dates.size() - 1);

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"CUMULATIVE_RISE", "STANDARD"}),
                Collections.singletonList(stockId), d, d);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "19% cumulative rise across the window clears STANDARD's 15% floor even though no single day rises 5%");
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

        ScanRequestDto request = scanRequest(
                Collections.singletonList(new String[]{"CUMULATIVE_RISE", "STANDARD"}),
                Collections.singletonList(stockId), y, z);
        JsonNode result = postScan(request).get("results").get(0);
        assertEquals(1, result.get("matchedCount").asInt(),
                "only the true peak day must match, not every day of the climb");
        JsonNode item = result.get("items").get(0);
        assertEquals(y.toString(), item.get("signalDate").asText(),
                "the day after the true peak must not be reported even though it is still high");
    }

    @Test
    void rebound_lookbackVariesByPreset_strictAndLooseProduceDifferentHitResults() throws Exception {
        String stockId = "SS821";
        seedStock(stockId, "回看區間反彈測試", true);
        LocalDate d1 = LocalDate.of(2026, 5, 1);
        insertCloseOnlyRow(stockId, d1, "200.00", 1000);
        seedRamp(stockId, d1.plusDays(1), 190.00, 115.00, 9, 1000); // day2..day10
        List<LocalDate> looseRamp = seedRamp(stockId, d1.plusDays(10), 114.00, 110.10, 10, 1000); // day11..day20
        LocalDate d = looseRamp.get(looseRamp.size() - 1); // day20

        ScanRequestDto strictRequest = scanRequest(
                Collections.singletonList(new String[]{"REBOUND", "STRICT"}),
                Collections.singletonList(stockId), d, d);
        JsonNode strictResult = postScan(strictRequest).get("results").get(0);
        assertEquals(1, strictResult.get("matchedCount").asInt(),
                "STRICT's 20-day lookback reaches the far 200 peak, clearing the 20% floor");

        ScanRequestDto looseRequest = scanRequest(
                Collections.singletonList(new String[]{"REBOUND", "LOOSE"}),
                Collections.singletonList(stockId), d, d);
        JsonNode looseResult = postScan(looseRequest).get("results").get(0);
        assertEquals(0, looseResult.get("matchedCount").asInt(),
                "LOOSE's 10-day lookback only sees the local 114 peak, which fails the 10% floor");
    }

    @Test
    void cumulativeRise_lookbackVariesByPreset_strictAndLooseProduceDifferentHitResults() throws Exception {
        String stockId = "SS822";
        seedStock(stockId, "回看區間累積上漲測試", true);
        LocalDate d1 = LocalDate.of(2026, 5, 1);
        insertCloseOnlyRow(stockId, d1, "10.00", 1000);
        seedRamp(stockId, d1.plusDays(1), 20.00, 95.00, 9, 1000); // day2..day10
        List<LocalDate> looseRamp = seedRamp(stockId, d1.plusDays(10), 96.00, 98.50, 10, 1000); // day11..day20
        LocalDate d = looseRamp.get(looseRamp.size() - 1); // day20

        ScanRequestDto strictRequest = scanRequest(
                Collections.singletonList(new String[]{"CUMULATIVE_RISE", "STRICT"}),
                Collections.singletonList(stockId), d, d);
        JsonNode strictResult = postScan(strictRequest).get("results").get(0);
        assertEquals(1, strictResult.get("matchedCount").asInt(),
                "STRICT's 20-day lookback reaches the far 10.00 trough, clearing the 20% floor");

        ScanRequestDto looseRequest = scanRequest(
                Collections.singletonList(new String[]{"CUMULATIVE_RISE", "LOOSE"}),
                Collections.singletonList(stockId), d, d);
        JsonNode looseResult = postScan(looseRequest).get("results").get(0);
        assertEquals(0, looseResult.get("matchedCount").asInt(),
                "LOOSE's 10-day lookback only sees the local 96.00 trough, which fails the 10% floor");
    }

    @Test
    void rebound_risePercentOverridesDropPercentNotTreatedAsRiseThreshold() throws Exception {
        String stockId = "SS831";
        seedStock(stockId, "跌幅覆寫測試", true);
        LocalDate day1 = LocalDate.of(2026, 4, 1);
        insertCloseOnlyRow(stockId, day1, "100.00", 1000);
        List<LocalDate> declineDates = seedRamp(stockId, day1.plusDays(1), 99.00, 88.00, 19, 1000); // 12% total drop
        LocalDate d = declineDates.get(declineDates.size() - 1);

        ScanRequestDto defaultRequest = scanRequest(
                Collections.singletonList(new String[]{"REBOUND", "STANDARD"}),
                Collections.singletonList(stockId), d, d);
        JsonNode defaultResult = postScan(defaultRequest).get("results").get(0);
        assertEquals(0, defaultResult.get("matchedCount").asInt(), "12% drop must fail STANDARD's 15% floor");

        ScanRequestDto overrideRequest = scanRequestFromSelections(
                Collections.singletonList(selection("REBOUND", "STANDARD", new BigDecimal("10"))),
                Collections.singletonList(stockId), d, d);
        JsonNode overrideResult = postScan(overrideRequest).get("results").get(0);
        assertEquals(1, overrideResult.get("matchedCount").asInt(),
                "risePercent=10 must be applied as a 10% DROP floor, which the 12% drop clears");
        assertBigDecimalEquals("12.00", overrideResult.get("items").get(0).get("detail").get("dropPercent"));
    }

    @Test
    void risePercent_upperBoundRaisedTo50_30And50AreValidBut50Point1IsRejected() {
        String stockId = "SS841";
        seedStock(stockId, "上限放寬測試", true);

        ScanRequestDto thirty = new ScanRequestDto();
        thirty.setStrategies(Collections.singletonList(selection("REBOUND", "STANDARD", new BigDecimal("30"))));
        thirty.setStockIds(Collections.singletonList(stockId));
        thirty.setStartDate(LocalDate.now().minusDays(1));
        thirty.setEndDate(LocalDate.now());
        ResponseEntity<String> thirtyResponse = rest.postForEntity("/api/strategies/scan", thirty, String.class);
        assertEquals(HttpStatus.OK, thirtyResponse.getStatusCode(), "30 must be a valid risePercent override");

        ScanRequestDto fifty = new ScanRequestDto();
        fifty.setStrategies(Collections.singletonList(selection("REBOUND", "STANDARD", new BigDecimal("50"))));
        fifty.setStockIds(Collections.singletonList(stockId));
        fifty.setStartDate(LocalDate.now().minusDays(1));
        fifty.setEndDate(LocalDate.now());
        ResponseEntity<String> fiftyResponse = rest.postForEntity("/api/strategies/scan", fifty, String.class);
        assertEquals(HttpStatus.OK, fiftyResponse.getStatusCode(), "the boundary value 50 must be valid");

        ScanRequestDto fiftyOne = new ScanRequestDto();
        fiftyOne.setStrategies(Collections.singletonList(selection("REBOUND", "STANDARD", new BigDecimal("50.1"))));
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
        // Only 5 bars strictly before D; STANDARD needs lookback-1 = 19.
        seedCloseSeries(stockId, start.minusDays(5), repeat("100.00", 5), 1000);
        insertCloseOnlyRow(stockId, start, "100.00", 1000);

        ScanRequestDto request = scanRequest(
                Arrays.asList(new String[]{"REBOUND", "STANDARD"}, new String[]{"CUMULATIVE_RISE", "STANDARD"}),
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

        ScanRequestDto request = scanRequest(
                Arrays.asList(
                        new String[]{"REBOUND", "LOOSE"},
                        new String[]{"RISING_SUPPORT", "STANDARD"},
                        new String[]{"BOX_BREAKOUT", "LOOSE"},
                        new String[]{"HIGHER_LOWS", "LOOSE"},
                        new String[]{"CUMULATIVE_RISE", "LOOSE"}),
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

        ScanRequestDto request = scanRequest(
                Arrays.asList(new String[]{"REBOUND", "STANDARD"}, new String[]{"CUMULATIVE_RISE", "STANDARD"}),
                Collections.singletonList(stockId), d, d);
        ResponseEntity<String> response = rest.postForEntity("/api/strategies/scan", request, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNoAdviceWording(response.getBody());
        assertFalse(response.getBody().contains("進場"), "must not contain 進場: " + response.getBody());
        assertFalse(response.getBody().contains("出場"), "must not contain 出場: " + response.getBody());
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
     * 25-day HIGHER_LOWS fixture: 3 separated dips at index 5, 13, 21 with the given low values,
     * each shoulder-scaffolded by strictly-higher bars 3 wide on both sides (safe for
     * swingBars in {2,3}). Indices 0..2 are pre-startDate lookback (3 days, satisfies STANDARD's
     * swingBars=3); the returned list's index 3 is startDate's date.
     */
    private List<LocalDate> seedHigherLowsFixture(String stockId, LocalDate day0, String[] dipLows) {
        String[] template = {
                "150.00", "150.00", "130.00", "120.00", "110.00", // idx0-4
                dipLows[0],                                        // idx5
                "110.00", "120.00", "130.00", "150.00",           // idx6-9
                "130.00", "120.00", "110.00",                      // idx10-12
                dipLows[1],                                        // idx13
                "110.00", "120.00", "130.00", "150.00",           // idx14-17
                "130.00", "120.00", "110.00",                      // idx18-20
                dipLows[2],                                        // idx21
                "110.00", "120.00", "130.00"                       // idx22-24
        };
        List<LocalDate> dates = new ArrayList<>(template.length);
        LocalDate d = day0;
        for (String low : template) {
            dates.add(d);
            BigDecimal l = new BigDecimal(low);
            BigDecimal shoulder = l.add(BigDecimal.ONE);
            insertPriceRow(stockId, d, shoulder.toPlainString(), shoulder.toPlainString(), low,
                    shoulder.toPlainString(), 1000);
            d = d.plusDays(1);
        }
        return dates;
    }
}
