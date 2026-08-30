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
    void catalog_returnsTwoStrategiesWithThreePresetsEachMatchingSpecWording() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/strategies", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode strategies = root.get("strategies");
        assertEquals(2, strategies.size());

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
            // stockIds omitted -> ALL active

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

    private StrategySelectionDto selection(String code, String preset) {
        StrategySelectionDto dto = new StrategySelectionDto();
        dto.setCode(code);
        dto.setPreset(preset);
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
