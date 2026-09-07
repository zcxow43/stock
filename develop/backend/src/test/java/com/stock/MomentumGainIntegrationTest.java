package com.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.dto.ErrorResponse;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for GET /api/momentum/gain — specs/backend/industry-gain-ranking.md.
 * Uses "MG"-prefixed synthetic stock ids and "MG測試"-prefixed industry names so this class can
 * run alongside the live production dataset without colliding with it. Because the endpoint's
 * population is always ALL active stocks (no stockIds filter — see the spec's "掃描範圍"), every
 * test isolates that population by temporarily deactivating every real (non-"MG") active stock,
 * restoring them in {@link #tearDown()} — same technique as
 * StrategyScanIntegrationTest#scan_omittedStockIds_scansAllActiveStocks_scannedStocksMatchesActualCount.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MomentumGainIntegrationTest {

    private static final String MAPPER_PREFIX = "com.stock.mapper.";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private QueryCountInterceptor queryCountInterceptor;

    private JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private List<String> deactivatedRealIds;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        cleanupTestData();
        isolatePopulation();
    }

    @AfterEach
    void tearDown() {
        restorePopulation();
        cleanupTestData();
    }

    private void cleanupTestData() {
        jdbc.update("DELETE FROM stock_industry WHERE stock_id LIKE 'MG%'");
        jdbc.update("DELETE FROM industry WHERE industry_name LIKE 'MG測試%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'MG%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'MG%'");
    }

    private void isolatePopulation() {
        deactivatedRealIds = jdbc.queryForList(
                "SELECT stock_id FROM stock WHERE is_active = 1 AND stock_id NOT LIKE 'MG%'", String.class);
        if (!deactivatedRealIds.isEmpty()) {
            jdbc.update("UPDATE stock SET is_active = 0 WHERE stock_id NOT LIKE 'MG%'");
        }
    }

    private void restorePopulation() {
        if (deactivatedRealIds != null && !deactivatedRealIds.isEmpty()) {
            String placeholders = String.join(",", deactivatedRealIds.stream().map(id -> "?").toArray(String[]::new));
            jdbc.update("UPDATE stock SET is_active = 1 WHERE stock_id IN (" + placeholders + ")",
                    deactivatedRealIds.toArray());
        }
    }

    // ==================== AC1: DAYS mode range = newest 20 distinct trade dates ====================

    @Test
    void days_returnsNewest20DistinctTradeDatesAsRangeWithTradingDays20() throws Exception {
        List<LocalDate> expected = jdbc.query(
                "SELECT trade_date FROM (SELECT DISTINCT trade_date FROM stock_daily_price "
                        + "ORDER BY trade_date DESC LIMIT 20) t ORDER BY trade_date ASC",
                (rs, i) -> rs.getDate("trade_date").toLocalDate());
        assertEquals(20, expected.size(), "test DB must have at least 20 distinct trade dates");

        JsonNode root = get("metric=SUM&mode=DAYS&days=20");
        assertEquals(expected.get(0).toString(), root.get("startDate").asText());
        assertEquals(expected.get(expected.size() - 1).toString(), root.get("endDate").asText());
        assertEquals(20, root.get("tradingDays").asInt());
    }

    // ==================== AC2: DAYS anchor is DB's own MAX(trade_date), not today ====================

    @Test
    void days_anchorsOnDatabaseMaxTradeDate_notTodaysCalendarDate() throws Exception {
        LocalDate dbMax = jdbc.queryForObject("SELECT MAX(trade_date) FROM stock_daily_price", LocalDate.class);
        LocalDate anchorDate = dbMax.plusDays(1); // guaranteed to differ from LocalDate.now() in this run
        insertPriceRow("MGANCHOR", anchorDate, "10.00", "10.00", "10.00", "10.00", 100);
        try {
            JsonNode root = get("metric=SUM&mode=DAYS&days=1");
            assertEquals(anchorDate.toString(), root.get("endDate").asText(),
                    "endDate must be the DB's own newest trade_date, not LocalDate.now()");
            assertEquals(anchorDate.toString(), root.get("startDate").asText());
            assertEquals(1, root.get("tradingDays").asInt());
        } finally {
            jdbc.update("DELETE FROM stock_daily_price WHERE stock_id = 'MGANCHOR'");
        }
    }

    // ==================== AC3/AC4/AC5: SUM/AVERAGE hand calculation + ranking + lookback exclusion ====================

    @Test
    void sumAndAverage_matchHandCalculatedValues_andAgreeOnRanking() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 2, 1); // lookback day (before startDate)
        LocalDate d1 = d0.plusDays(1);
        LocalDate d2 = d1.plusDays(1);
        LocalDate d3 = d2.plusDays(1);
        LocalDate d4 = d3.plusDays(1);

        seedStock("MG101A", "手算測試A", true);
        insertCloseOnlyRow("MG101A", d0, "100.00");
        insertCloseOnlyRow("MG101A", d1, "100.00");
        insertCloseOnlyRow("MG101A", d2, "105.00");
        insertCloseOnlyRow("MG101A", d3, "105.00");
        insertCloseOnlyRow("MG101A", d4, "110.25"); // 0 + 5 + 0 + 5 = 10.00 SUM; 2.50 AVERAGE

        seedStock("MG101B", "手算測試B", true);
        insertCloseOnlyRow("MG101B", d0, "100.00");
        insertCloseOnlyRow("MG101B", d1, "200.00");
        insertCloseOnlyRow("MG101B", d2, "200.00");
        insertCloseOnlyRow("MG101B", d3, "200.00");
        insertCloseOnlyRow("MG101B", d4, "200.00"); // 100 + 0 + 0 + 0 = 100.00 SUM; 25.00 AVERAGE

        JsonNode sumRoot = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d4 + "&minGain=-100");
        JsonNode sumItems = onlyIndustryItems(sumRoot);
        assertEquals(2, sumItems.size());
        assertEquals("MG101B", sumItems.get(0).get("stockId").asText(), "B's larger SUM must rank first");
        assertEquals("MG101A", sumItems.get(1).get("stockId").asText());
        assertBigDecimalEquals("100.00", sumItems.get(0).get("gain"));
        assertBigDecimalEquals("10.00", sumItems.get(1).get("gain"));
        JsonNode itemA = sumItems.get(1);
        assertEquals(4, itemA.get("tradingDays").asInt(), "lookback day must not be counted");
        assertEquals(d1.toString(), itemA.get("firstTradeDate").asText(), "lookback day must not appear as firstTradeDate");
        assertEquals(d4.toString(), itemA.get("lastTradeDate").asText());

        JsonNode avgRoot = get("metric=AVERAGE&mode=WEEKS&startDate=" + d1 + "&endDate=" + d4 + "&minGain=-100");
        JsonNode avgItems = onlyIndustryItems(avgRoot);
        assertEquals(2, avgItems.size());
        assertEquals("MG101B", avgItems.get(0).get("stockId").asText(), "ranking must agree between SUM and AVERAGE");
        assertEquals("MG101A", avgItems.get(1).get("stockId").asText());
        assertBigDecimalEquals("25.00", avgItems.get(0).get("gain"));
        assertBigDecimalEquals("2.50", avgItems.get(1).get("gain"));
    }

    // ==================== AC6: missing pre-startDate data -> insufficientDataCount, excluded ====================

    @Test
    void missingLookbackData_countsAsInsufficientData_excludedFromItems() throws Exception {
        LocalDate start = LocalDate.of(2026, 3, 1);
        LocalDate end = start.plusDays(2);
        seedStock("MG102", "資料不足測試", true);
        // No row before `start` at all -> insufficient.
        insertCloseOnlyRow("MG102", start, "100.00");
        insertCloseOnlyRow("MG102", start.plusDays(1), "101.00");
        insertCloseOnlyRow("MG102", end, "102.00");

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + start + "&endDate=" + end + "&minGain=-100");
        assertEquals(1, root.get("insufficientDataCount").asInt());
        assertEquals(0, root.get("matchedStockCount").asInt());
        assertEquals(0, root.get("industries").size());
    }

    // ==================== AC7: adjacency skips calendar gaps without interpolation ====================

    @Test
    void suspensionGap_resultsIdenticalToNoGapSequence_noInterpolation() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 4, 1);
        LocalDate d1 = d0.plusDays(1);
        LocalDate d2 = d1.plusDays(1);
        // d3 (suspension) deliberately has no row.
        LocalDate d4 = d2.plusDays(2);

        seedStock("MG103", "跳過停牌測試", true);
        insertCloseOnlyRow("MG103", d0, "100.00"); // lookback
        insertCloseOnlyRow("MG103", d1, "105.00");
        insertCloseOnlyRow("MG103", d2, "105.00");
        insertCloseOnlyRow("MG103", d4, "110.25"); // list-adjacent to d2, despite the 2-day calendar gap

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d4 + "&minGain=-100");
        JsonNode items = onlyIndustryItems(root);
        assertEquals(1, items.size());
        assertBigDecimalEquals("10.00", items.get(0).get("gain"));
        assertEquals(3, items.get(0).get("tradingDays").asInt(), "the suspension day must not be counted");
    }

    // ==================== AC8: minGain default 5; minGain=-100 lists all with sufficient data ====================

    @Test
    void minGain_defaultsToFive_andNegative100ListsEverythingWithData() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 5, 1);
        LocalDate d1 = d0.plusDays(1);

        seedStock("MG104HIGH", "門檻測試高", true);
        insertCloseOnlyRow("MG104HIGH", d0, "100.00");
        insertCloseOnlyRow("MG104HIGH", d1, "106.00"); // +6%, clears default 5

        seedStock("MG104LOW", "門檻測試低", true);
        insertCloseOnlyRow("MG104LOW", d0, "100.00");
        insertCloseOnlyRow("MG104LOW", d1, "99.00"); // -1%, below default 5

        JsonNode defaultRoot = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1);
        assertBigDecimalEquals("5.00", defaultRoot.get("minGain"));
        JsonNode defaultItems = onlyIndustryItems(defaultRoot);
        assertEquals(1, defaultItems.size());
        assertEquals("MG104HIGH", defaultItems.get(0).get("stockId").asText());

        JsonNode allRoot = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        JsonNode allItems = onlyIndustryItems(allRoot);
        assertEquals(2, allItems.size());
    }

    // ==================== AC9: threshold compares the already-rounded value ====================

    @Test
    void thresholdComparison_usesAlreadyRoundedValue_4996RoundsTo500AndIsIncluded() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 6, 1);
        LocalDate d1 = d0.plusDays(1);
        seedStock("MG105", "四捨五入測試", true);
        insertCloseOnlyRow("MG105", d0, "1000.00");
        insertCloseOnlyRow("MG105", d1, "1049.96"); // (1049.96/1000-1)*100 = 4.9960 -> rounds to 5.00

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=5");
        JsonNode items = onlyIndustryItems(root);
        assertEquals(1, items.size(), "raw 4.996 must round to 5.00 and pass minGain=5");
        assertBigDecimalEquals("5.00", items.get(0).get("gain"));
    }

    // ==================== AC10/AC11: multi-industry membership + matchedStockCount dedup ====================

    @Test
    void stockInTwoIndustries_appearsInBothGroups_matchedStockCountIsDeduped() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 7, 1);
        LocalDate d1 = d0.plusDays(1);
        seedStock("MG201", "雙產業測試", true);
        insertCloseOnlyRow("MG201", d0, "100.00");
        insertCloseOnlyRow("MG201", d1, "100.00"); // 0% gain, qualifies under minGain=-100

        int industryA = upsertIndustry("MG測試產業A");
        int industryB = upsertIndustry("MG測試產業B");
        linkStockIndustry("MG201", industryA);
        linkStockIndustry("MG201", industryB);

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        assertEquals(1, root.get("matchedStockCount").asInt());

        JsonNode groupA = findIndustryById(root, industryA);
        JsonNode groupB = findIndustryById(root, industryB);
        assertEquals(1, groupA.get("matchedCount").asInt());
        assertEquals(1, groupB.get("matchedCount").asInt());
        assertEquals("MG201", groupA.get("items").get(0).get("stockId").asText());
        assertEquals("MG201", groupB.get("items").get(0).get("stockId").asText());

        int sumOfPerIndustryMatchedCount = groupA.get("matchedCount").asInt() + groupB.get("matchedCount").asInt();
        assertTrue(root.get("matchedStockCount").asInt() < sumOfPerIndustryMatchedCount,
                "matchedStockCount must be smaller than the sum of per-industry matchedCount");
    }

    // ==================== AC12/AC13: unclassified block, always last ====================

    @Test
    void unclassifiedStocks_goToTrailingBlock_alwaysLastEvenWhenLargest() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 8, 1);
        LocalDate d1 = d0.plusDays(1);

        int namedIndustry = upsertIndustry("MG測試產業C");
        seedStock("MG202NAMED", "具產業測試", true);
        insertCloseOnlyRow("MG202NAMED", d0, "100.00");
        insertCloseOnlyRow("MG202NAMED", d1, "100.00");
        linkStockIndustry("MG202NAMED", namedIndustry);

        for (int i = 0; i < 3; i++) {
            String id = "MG202U" + i;
            seedStock(id, "未分類測試" + i, true);
            insertCloseOnlyRow(id, d0, "100.00");
            insertCloseOnlyRow(id, d1, "100.00");
        }

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        JsonNode industries = root.get("industries");
        assertTrue(industries.size() >= 2);
        JsonNode last = industries.get(industries.size() - 1);
        assertNull(nullableInt(last.get("industryId")));
        assertEquals("未分類", last.get("industryName").asText());
        assertEquals(3, last.get("matchedCount").asInt());
        assertTrue(last.get("matchedCount").asInt() > findIndustryById(root, namedIndustry).get("matchedCount").asInt(),
                "unclassified has the larger count but must still be last");
    }

    // ==================== AC14: industries sorted by matchedCount desc, then industryName asc ====================

    @Test
    void industries_sortedByMatchedCountDescendingThenNameAscending() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 9, 10);
        LocalDate d1 = d0.plusDays(1);

        int industryFew = upsertIndustry("MG測試產業Z少量"); // 1 stock
        int industryTieB = upsertIndustry("MG測試產業B同量"); // 2 stocks, tie
        int industryTieA = upsertIndustry("MG測試產業A同量"); // 2 stocks, tie, alphabetically first

        seedStock("MG203F1", "少量1", true);
        insertCloseOnlyRow("MG203F1", d0, "100.00");
        insertCloseOnlyRow("MG203F1", d1, "100.00");
        linkStockIndustry("MG203F1", industryFew);

        for (int i = 0; i < 2; i++) {
            String id = "MG203TB" + i;
            seedStock(id, "同量B" + i, true);
            insertCloseOnlyRow(id, d0, "100.00");
            insertCloseOnlyRow(id, d1, "100.00");
            linkStockIndustry(id, industryTieB);
        }
        for (int i = 0; i < 2; i++) {
            String id = "MG203TA" + i;
            seedStock(id, "同量A" + i, true);
            insertCloseOnlyRow(id, d0, "100.00");
            insertCloseOnlyRow(id, d1, "100.00");
            linkStockIndustry(id, industryTieA);
        }

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        JsonNode industries = root.get("industries");
        assertEquals(3, industries.size());
        assertEquals(industryTieA, industries.get(0).get("industryId").asInt(), "tie broken by industryName asc");
        assertEquals(industryTieB, industries.get(1).get("industryId").asInt());
        assertEquals(industryFew, industries.get(2).get("industryId").asInt());
    }

    // ==================== AC15: items sorted by gain desc, then stockId asc ====================

    @Test
    void items_sortedByGainDescendingThenStockIdAscending() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 10, 1);
        LocalDate d1 = d0.plusDays(1);
        int industry = upsertIndustry("MG測試產業排序");

        seedStock("MG204B", "排序B", true);
        insertCloseOnlyRow("MG204B", d0, "100.00");
        insertCloseOnlyRow("MG204B", d1, "120.00"); // +20%, tied with MG204A
        linkStockIndustry("MG204B", industry);

        seedStock("MG204A", "排序A", true);
        insertCloseOnlyRow("MG204A", d0, "100.00");
        insertCloseOnlyRow("MG204A", d1, "120.00"); // +20%, tied -> stockId asc breaks the tie
        linkStockIndustry("MG204A", industry);

        seedStock("MG204C", "排序C", true);
        insertCloseOnlyRow("MG204C", d0, "100.00");
        insertCloseOnlyRow("MG204C", d1, "110.00"); // +10%, lower
        linkStockIndustry("MG204C", industry);

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        JsonNode items = findIndustryById(root, industry).get("items");
        assertEquals(3, items.size());
        assertEquals("MG204A", items.get(0).get("stockId").asText());
        assertEquals("MG204B", items.get(1).get("stockId").asText());
        assertEquals("MG204C", items.get(2).get("stockId").asText());
    }

    // ==================== AC16: industries with zero hits are excluded ====================

    @Test
    void industryWithNoMatchingHits_isExcludedFromResponse() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 10, 20);
        LocalDate d1 = d0.plusDays(1);
        int emptyIndustry = upsertIndustry("MG測試產業無命中");
        seedStock("MG205", "無命中測試", true);
        insertCloseOnlyRow("MG205", d0, "100.00");
        insertCloseOnlyRow("MG205", d1, "100.50"); // +0.5%, below default minGain=5
        linkStockIndustry("MG205", emptyIndustry);

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1);
        assertNull(findIndustryByIdOrNull(root, emptyIndustry), "an industry with zero hits must not appear");
    }

    // ==================== AC17: WEEKS mode with zero trading days -> empty, not an error ====================

    @Test
    void weeksMode_noTradingDaysInRange_returnsEmptyResultNotError() throws Exception {
        // Well outside the live dataset's real trading history (2026) -> guaranteed zero rows.
        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=2020-01-01&endDate=2020-01-07&minGain=-100");
        assertTrue(root.get("startDate").isNull());
        assertTrue(root.get("endDate").isNull());
        assertEquals(0, root.get("industries").size());
    }

    // ==================== AC18: DAYS ignores startDate/endDate; WEEKS ignores days ====================

    @Test
    void daysMode_ignoresGivenDateParams_weeksMode_ignoresGivenDaysParam() throws Exception {
        JsonNode daysRoot = get("metric=SUM&mode=DAYS&days=1&startDate=2020-01-01&endDate=2020-01-02");
        assertFalse(daysRoot.get("startDate").isNull(), "DAYS mode must not adopt the ignored startDate param");
        assertFalse("2020-01-01".equals(daysRoot.get("startDate").asText()),
                "DAYS mode must ignore the given startDate, not adopt it");

        LocalDate d0 = LocalDate.of(2026, 11, 1);
        LocalDate d1 = d0.plusDays(1);
        seedStock("MG206", "忽略days測試", true);
        insertCloseOnlyRow("MG206", d0, "100.00");
        insertCloseOnlyRow("MG206", d1, "100.00");
        JsonNode weeksRoot = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&days=999&minGain=-100");
        assertEquals(d1.toString(), weeksRoot.get("startDate").asText());
        assertEquals(d1.toString(), weeksRoot.get("endDate").asText());
    }

    // ==================== AC19: items[].tradingDays reflects the stock's own count ====================

    @Test
    void itemTradingDays_reflectsThatStocksOwnCount_belowResponseLevelTradingDays() throws Exception {
        LocalDate d1 = LocalDate.of(2020, 6, 1);
        LocalDate d2 = d1.plusDays(1);
        LocalDate d3 = d1.plusDays(2);
        LocalDate d4 = d1.plusDays(3);
        LocalDate d5 = d1.plusDays(4);

        // Filler rows (no accompanying `stock` row needed — stock_daily_price has no FK) establish
        // 5 distinct global trading days, independent of the real production dataset.
        insertPriceRow("MGFILLER19", d1, "1", "1", "1", "1", 1);
        insertPriceRow("MGFILLER19", d2, "1", "1", "1", "1", 1);
        insertPriceRow("MGFILLER19", d3, "1", "1", "1", "1", 1);
        insertPriceRow("MGFILLER19", d4, "1", "1", "1", "1", 1);
        insertPriceRow("MGFILLER19", d5, "1", "1", "1", "1", 1);

        seedStock("MG207", "個股天數測試", true);
        insertCloseOnlyRow("MG207", d1.minusDays(1), "100.00"); // lookback
        insertCloseOnlyRow("MG207", d1, "100.00");
        insertCloseOnlyRow("MG207", d2, "100.00");
        // d3, d4 suspended for MG207 only
        insertCloseOnlyRow("MG207", d5, "100.00");

        try {
            JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d5 + "&minGain=-100");
            assertEquals(5, root.get("tradingDays").asInt(), "response-level tradingDays is market-wide");
            JsonNode items = onlyIndustryItems(root);
            assertEquals(1, items.size());
            assertEquals(3, items.get(0).get("tradingDays").asInt(), "this stock only traded 3 of the 5 days");
        } finally {
            jdbc.update("DELETE FROM stock_daily_price WHERE stock_id = 'MGFILLER19'");
        }
    }

    // ==================== AC20: five distinct error codes ====================

    @Test
    void missingOrInvalidMetric_rejectedWithInvalidMetric() {
        assertErrorCode("mode=DAYS&days=5", "INVALID_METRIC");
        assertErrorCode("metric=FOO&mode=DAYS&days=5", "INVALID_METRIC");
    }

    @Test
    void missingOrInvalidMode_rejectedWithInvalidMode() {
        assertErrorCode("metric=SUM&days=5", "INVALID_MODE");
        assertErrorCode("metric=SUM&mode=FOO&days=5", "INVALID_MODE");
    }

    @Test
    void daysMode_missingOrOutOfRangeDays_rejectedWithInvalidDays() {
        assertErrorCode("metric=SUM&mode=DAYS", "INVALID_DAYS");
        assertErrorCode("metric=SUM&mode=DAYS&days=0", "INVALID_DAYS");
        assertErrorCode("metric=SUM&mode=DAYS&days=121", "INVALID_DAYS");
    }

    @Test
    void weeksMode_missingOrInvertedDateRange_rejectedWithInvalidDateRange() {
        assertErrorCode("metric=SUM&mode=WEEKS", "INVALID_DATE_RANGE");
        assertErrorCode("metric=SUM&mode=WEEKS&startDate=2026-08-10&endDate=2026-08-01", "INVALID_DATE_RANGE");
    }

    @Test
    void minGain_outOfRange_rejectedWithInvalidMinGain() {
        assertErrorCode("metric=SUM&mode=DAYS&days=5&minGain=-101", "INVALID_MIN_GAIN");
        assertErrorCode("metric=SUM&mode=DAYS&days=5&minGain=1001", "INVALID_MIN_GAIN");
    }

    private void assertErrorCode(String query, String expectedCode) {
        ResponseEntity<ErrorResponse> response =
                rest.getForEntity("/api/momentum/gain?" + query, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "query: " + query);
        assertEquals(expectedCode, response.getBody().getCode(), "query: " + query);
    }

    // ==================== AC21: fixed query count regardless of population size ====================

    @Test
    void queryCount_isFixed_doesNotScaleWithPopulationSize() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 12, 1);
        LocalDate d1 = d0.plusDays(1);

        List<String> fewIds = seedQualifyingStocks("MGQ3_", 3, d0, d1);

        queryCountInterceptor.reset(MAPPER_PREFIX);
        get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        int fewCount = queryCountInterceptor.getCount();

        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'MGQ3_%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'MGQ3_%'");
        seedQualifyingStocks("MGQ300_", 300, d0, d1);

        queryCountInterceptor.reset(MAPPER_PREFIX);
        get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        int manyCount = queryCountInterceptor.getCount();

        assertEquals(fewCount, manyCount, "query count must not scale with population size (3 vs 300 stocks)");
        assertTrue(fewCount > 0);

        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'MGQ300_%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'MGQ300_%'");
    }

    private List<String> seedQualifyingStocks(String prefix, int count, LocalDate d0, LocalDate d1) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = prefix + i;
            ids.add(id);
            seedStock(id, "批次" + i, true);
            insertCloseOnlyRow(id, d0, "100.00");
            insertCloseOnlyRow(id, d1, "110.00"); // +10%, qualifies under minGain=-100
        }
        return ids;
    }

    // ==================== AC22: no advice/recommendation wording ====================

    @Test
    void response_containsNoAdviceWording() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 12, 20);
        LocalDate d1 = d0.plusDays(1);
        seedStock("MG208", "文案測試", true);
        insertCloseOnlyRow("MG208", d0, "100.00");
        insertCloseOnlyRow("MG208", d1, "110.00");

        ResponseEntity<String> response = rest.getForEntity(
                "/api/momentum/gain?metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                        + "&minGain=-100&commonStocksOnly=false",
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertFalse(body.contains("建議"), "must not contain 建議: " + body);
        assertFalse(body.contains("推薦"), "must not contain 推薦: " + body);
        assertFalse(body.contains("可進場"), "must not contain 可進場: " + body);
    }

    // ==================== commonStocksOnly ====================

    @Test
    void commonStocksOnly_omitted_defaultsToTrue_excludesNonCommonFormat() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 1, 4);
        LocalDate d1 = d0.plusDays(1);
        seedStock("9791", "普通股格式", true);
        seedStock("9791A", "特別股格式", true);
        insertCloseOnlyRow("9791", d0, "100.00");
        insertCloseOnlyRow("9791", d1, "110.00");
        insertCloseOnlyRow("9791A", d0, "100.00");
        insertCloseOnlyRow("9791A", d1, "110.00");
        try {
            // Deliberately NOT using the get() helper (it injects commonStocksOnly=false) so the
            // request genuinely omits the param and exercises the real default.
            ResponseEntity<String> response = rest.getForEntity(
                    "/api/momentum/gain?metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100",
                    String.class);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            JsonNode root = objectMapper.readTree(response.getBody());
            assertEquals(1, root.get("scannedStocks").asInt(),
                    "population must be restricted to the 4-digit non-zero-leading format by default");
            JsonNode items = onlyIndustryItems(root);
            assertEquals(1, items.size());
            assertEquals("9791", items.get(0).get("stockId").asText());
        } finally {
            jdbc.update("DELETE FROM stock_daily_price WHERE stock_id IN ('9791','9791A')");
            jdbc.update("DELETE FROM stock WHERE stock_id IN ('9791','9791A')");
        }
    }

    @Test
    void commonStocksOnly_true_excludesEtfSpecialShareAndTdrPatterns_notCountedAsInsufficientEither()
            throws Exception {
        // Synthetic stand-ins for the spec's literal examples (0050/00878/2881A/910322), one per
        // excluded shape: 4-digit-leading-zero (ETF), 5-digit-leading-zero (ETF), letter-suffixed
        // (special share), 6-digit (TDR). Using synthetic ids keeps this test deterministic and
        // independent of what happens to be in the live production universe on any given day; the
        // literal production codes are checked separately via a live curl (see spec Execution Result).
        String[] excludedIds = {"0791", "00791", "9791A", "910791"};
        LocalDate d0 = LocalDate.of(2027, 1, 11);
        LocalDate d1 = d0.plusDays(1);
        seedStock("9791", "普通股對照組", true);
        insertCloseOnlyRow("9791", d0, "100.00");
        insertCloseOnlyRow("9791", d1, "110.00");
        for (String id : excludedIds) {
            seedStock(id, "非普通股格式", true);
            insertCloseOnlyRow(id, d0, "100.00");
            insertCloseOnlyRow(id, d1, "110.00");
        }
        try {
            JsonNode root = rawGet("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                    + "&minGain=-100&commonStocksOnly=true");
            assertEquals(1, root.get("scannedStocks").asInt());
            assertEquals(0, root.get("insufficientDataCount").asInt(),
                    "excluded-by-format stocks must not be counted as insufficient data");
            JsonNode items = onlyIndustryItems(root);
            assertEquals(1, items.size());
            assertEquals("9791", items.get(0).get("stockId").asText());
        } finally {
            List<String> all = new ArrayList<>(List.of(excludedIds));
            all.add("9791");
            String inList = String.join(",", all.stream().map(id -> "'" + id + "'").toArray(String[]::new));
            jdbc.update("DELETE FROM stock_daily_price WHERE stock_id IN (" + inList + ")");
            jdbc.update("DELETE FROM stock WHERE stock_id IN (" + inList + ")");
        }
    }

    @Test
    void commonStocksOnly_false_widerPopulation_matchedCountGreaterThanTrue() throws Exception {
        String[] nonCommonIds = {"0791", "00791", "9791A", "910791"};
        LocalDate d0 = LocalDate.of(2027, 1, 18);
        LocalDate d1 = d0.plusDays(1);
        seedStock("9791", "普通股對照組", true);
        insertCloseOnlyRow("9791", d0, "100.00");
        insertCloseOnlyRow("9791", d1, "110.00");
        for (String id : nonCommonIds) {
            seedStock(id, "非普通股格式", true);
            insertCloseOnlyRow(id, d0, "100.00");
            insertCloseOnlyRow(id, d1, "110.00");
        }
        try {
            JsonNode trueRoot = rawGet("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                    + "&minGain=-100&commonStocksOnly=true");
            JsonNode falseRoot = rawGet("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                    + "&minGain=-100&commonStocksOnly=false");
            assertEquals(1, trueRoot.get("matchedStockCount").asInt());
            assertEquals(5, falseRoot.get("matchedStockCount").asInt());
            assertTrue(falseRoot.get("matchedStockCount").asInt() > trueRoot.get("matchedStockCount").asInt());
            assertEquals(1, trueRoot.get("scannedStocks").asInt());
            assertEquals(5, falseRoot.get("scannedStocks").asInt());
        } finally {
            List<String> all = new ArrayList<>(List.of(nonCommonIds));
            all.add("9791");
            String inList = String.join(",", all.stream().map(id -> "'" + id + "'").toArray(String[]::new));
            jdbc.update("DELETE FROM stock_daily_price WHERE stock_id IN (" + inList + ")");
            jdbc.update("DELETE FROM stock WHERE stock_id IN (" + inList + ")");
        }
    }

    @Test
    void commonStocksOnly_isReadOnly_neverMutatesStockOrStockIndustryTables() throws Exception {
        long stockCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM stock", Long.class);
        long linkCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM stock_industry", Long.class);
        String stockChecksumBefore = jdbc.queryForObject(
                "SELECT MD5(GROUP_CONCAT(stock_id, stock_name, market, is_active ORDER BY stock_id)) FROM stock",
                String.class);

        get("metric=SUM&mode=DAYS&days=5&minGain=-100&commonStocksOnly=true");
        get("metric=SUM&mode=DAYS&days=5&minGain=-100&commonStocksOnly=false");

        long stockCountAfter = jdbc.queryForObject("SELECT COUNT(*) FROM stock", Long.class);
        long linkCountAfter = jdbc.queryForObject("SELECT COUNT(*) FROM stock_industry", Long.class);
        String stockChecksumAfter = jdbc.queryForObject(
                "SELECT MD5(GROUP_CONCAT(stock_id, stock_name, market, is_active ORDER BY stock_id)) FROM stock",
                String.class);

        assertEquals(stockCountBefore, stockCountAfter);
        assertEquals(linkCountBefore, linkCountAfter);
        assertEquals(stockChecksumBefore, stockChecksumAfter, "stock table content must be byte-identical");
    }

    @Test
    void commonStocksOnly_combinesIndependentlyWithOtherParams() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 1, 25);
        LocalDate d1 = d0.plusDays(1);
        seedStock("9791", "組合測試", true);
        insertCloseOnlyRow("9791", d0, "100.00");
        insertCloseOnlyRow("9791", d1, "110.00");
        try {
            JsonNode root = rawGet("metric=AVERAGE&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                    + "&minGain=3&commonStocksOnly=true");
            assertEquals(1, root.get("scannedStocks").asInt());
            JsonNode items = onlyIndustryItems(root);
            assertBigDecimalEquals("10.00", items.get(0).get("gain"));
        } finally {
            jdbc.update("DELETE FROM stock_daily_price WHERE stock_id = '9791'");
            jdbc.update("DELETE FROM stock WHERE stock_id = '9791'");
        }
    }

    // ==================== avgGain / sort ====================

    @Test
    void avgGain_returnedForEveryIndustryBlock() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 2, 1);
        LocalDate d1 = d0.plusDays(1);
        int industryId = upsertIndustry("MG測試avgGain基本");
        seedStock("MG301", "avgGain基本1", true);
        insertCloseOnlyRow("MG301", d0, "100.00");
        insertCloseOnlyRow("MG301", d1, "108.00");
        linkStockIndustry("MG301", industryId);

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        JsonNode group = findIndustryById(root, industryId);
        assertBigDecimalEquals("8.00", group.get("avgGain"));
    }

    @Test
    void avgGain_computedFromRoundedDisplayValues_matchesSpecExample() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 2, 8);
        LocalDate d1 = d0.plusDays(1);
        int industryId = upsertIndustry("MG測試avgGain捨入");
        String[][] rows = {{"MG302", "105.01"}, {"MG303", "105.02"}, {"MG304", "105.04"}};
        for (String[] row : rows) {
            seedStock(row[0], "avgGain捨入", true);
            insertCloseOnlyRow(row[0], d0, "100.00");
            insertCloseOnlyRow(row[0], d1, row[1]);
            linkStockIndustry(row[0], industryId);
        }

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        JsonNode group = findIndustryById(root, industryId);
        assertEquals(3, group.get("matchedCount").asInt());
        // avgGain must be computed from the already-rounded 5.01/5.02/5.04 display values
        // ((5.01+5.02+5.04)/3 = 5.023333... -> 5.02), not from any unrounded intermediate.
        assertBigDecimalEquals("5.02", group.get("avgGain"));
    }

    @Test
    void avgGain_excludesStocksInSameIndustryThatDidNotMatch() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 2, 15);
        LocalDate d1 = d0.plusDays(1);
        int industryId = upsertIndustry("MG測試avgGain母體");
        seedStock("MG305", "命中", true);
        insertCloseOnlyRow("MG305", d0, "100.00");
        insertCloseOnlyRow("MG305", d1, "105.00");
        linkStockIndustry("MG305", industryId);
        seedStock("MG306", "未達標", true);
        insertCloseOnlyRow("MG306", d0, "100.00");
        insertCloseOnlyRow("MG306", d1, "101.00"); // gain 1.00, below minGain=5
        linkStockIndustry("MG306", industryId);

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=5");
        JsonNode group = findIndustryById(root, industryId);
        assertEquals(1, group.get("matchedCount").asInt(), "only the matched stock counts");
        assertBigDecimalEquals("5.00", group.get("avgGain"));
    }

    @Test
    void avgGain_alwaysGreaterOrEqualToMinGain() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 2, 22);
        LocalDate d1 = d0.plusDays(1);
        int industryId = upsertIndustry("MG測試avgGain下限");
        String[][] rows = {{"MG307", "106.00"}, {"MG308", "112.00"}};
        for (String[] row : rows) {
            seedStock(row[0], "下限測試", true);
            insertCloseOnlyRow(row[0], d0, "100.00");
            insertCloseOnlyRow(row[0], d1, row[1]);
            linkStockIndustry(row[0], industryId);
        }

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=5");
        JsonNode group = findIndustryById(root, industryId);
        BigDecimal minGain = root.get("minGain").decimalValue();
        assertTrue(group.get("avgGain").decimalValue().compareTo(minGain) >= 0,
                "avgGain must never be below minGain");
    }

    @Test
    void avgGain_multiIndustryStock_countsInBothBlocksAvgGain() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 3, 1);
        LocalDate d1 = d0.plusDays(1);
        int industryA = upsertIndustry("MG測試avgGain跨產業A");
        int industryB = upsertIndustry("MG測試avgGain跨產業B");
        seedStock("MG309", "跨產業", true);
        insertCloseOnlyRow("MG309", d0, "100.00");
        insertCloseOnlyRow("MG309", d1, "106.00"); // gain 6.00
        linkStockIndustry("MG309", industryA);
        linkStockIndustry("MG309", industryB);
        seedStock("MG310", "A獨有", true);
        insertCloseOnlyRow("MG310", d0, "100.00");
        insertCloseOnlyRow("MG310", d1, "105.00"); // gain 5.00
        linkStockIndustry("MG310", industryA);

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        JsonNode groupA = findIndustryById(root, industryA);
        JsonNode groupB = findIndustryById(root, industryB);
        assertBigDecimalEquals("5.50", groupA.get("avgGain")); // (6.00+5.00)/2
        assertBigDecimalEquals("6.00", groupB.get("avgGain")); // MG309 only
    }

    @Test
    void avgGain_unclassifiedBlockAlsoReturnsAvgGain() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 3, 8);
        LocalDate d1 = d0.plusDays(1);
        seedStock("MG311", "未分類avgGain", true);
        insertCloseOnlyRow("MG311", d0, "100.00");
        insertCloseOnlyRow("MG311", d1, "109.00");

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        JsonNode items = onlyIndustryItems(root); // the sole block here is the unclassified one
        assertEquals(1, items.size());
        JsonNode unclassified = root.get("industries").get(0);
        assertTrue(unclassified.get("industryId").isNull());
        assertBigDecimalEquals("9.00", unclassified.get("avgGain"));
    }

    @Test
    void avgGain_sumVsAverageMetric_differByTradingDayCountMultiple() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 3, 15);
        LocalDate d1 = d0.plusDays(1);
        LocalDate d2 = d0.plusDays(2);
        seedStock("MG312", "度量對照", true);
        insertCloseOnlyRow("MG312", d0, "100.00");
        insertCloseOnlyRow("MG312", d1, "105.00"); // day1: +5.00
        insertCloseOnlyRow("MG312", d2, "110.25"); // day2: +5.00

        JsonNode sumRoot = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d2 + "&minGain=-100");
        JsonNode avgRoot = get("metric=AVERAGE&mode=WEEKS&startDate=" + d1 + "&endDate=" + d2 + "&minGain=-100");
        int tradingDays = onlyIndustryItems(sumRoot).get(0).get("tradingDays").asInt();
        assertEquals(2, tradingDays);

        BigDecimal sumAvgGain = onlyIndustryItems(sumRoot).get(0).get("gain").decimalValue(); // single-stock block
        BigDecimal averageAvgGain = onlyIndustryItems(avgRoot).get(0).get("gain").decimalValue();
        assertEquals(0, sumAvgGain.compareTo(averageAvgGain.multiply(BigDecimal.valueOf(tradingDays))),
                "SUM-mode avgGain must equal AVERAGE-mode avgGain times the trading day count");
    }

    @Test
    void sort_omitted_defaultsToMatchCount_orderUnchanged() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 3, 22);
        LocalDate d1 = d0.plusDays(1);
        int industryFew = upsertIndustry("MG測試sort少量");
        int industryMany = upsertIndustry("MG測試sort多量");
        seedStock("MG313", "少量1", true);
        insertCloseOnlyRow("MG313", d0, "100.00");
        insertCloseOnlyRow("MG313", d1, "109.00"); // gain 9.00 (higher gain, fewer count)
        linkStockIndustry("MG313", industryFew);
        for (String id : new String[]{"MG314", "MG315"}) {
            seedStock(id, "多量", true);
            insertCloseOnlyRow(id, d0, "100.00");
            insertCloseOnlyRow(id, d1, "106.00"); // gain 6.00 each (lower gain, more count)
            linkStockIndustry(id, industryMany);
        }

        JsonNode omittedRoot = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100");
        JsonNode explicitRoot = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                + "&minGain=-100&sort=MATCH_COUNT");
        assertEquals("MATCH_COUNT", omittedRoot.get("sort").asText());
        assertEquals(industryMany, onlyRealIndustries(omittedRoot).get(0).get("industryId").asInt(),
                "matchedCount 2 must rank before matchedCount 1 by default");
        assertEquals(onlyRealIndustries(explicitRoot).get(0).get("industryId").asInt(),
                onlyRealIndustries(omittedRoot).get(0).get("industryId").asInt(),
                "omitting sort must produce the exact same order as sort=MATCH_COUNT");
    }

    @Test
    void sortAvgGain_ordersDescendingByAvgGain_thenMatchedCount_thenIndustryName() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 3, 29);
        LocalDate d1 = d0.plusDays(1);
        int industryY = upsertIndustry("MG測試sortY"); // avgGain 8.00, matchedCount 2 -> ranks 1st
        int industryX = upsertIndustry("MG測試sortX"); // avgGain 8.00, matchedCount 1, name < Z -> ranks 2nd
        int industryZ = upsertIndustry("MG測試sortZ"); // avgGain 8.00, matchedCount 1, name > X -> ranks 3rd
        for (String id : new String[]{"MG316", "MG317"}) {
            seedStock(id, "Y群組", true);
            insertCloseOnlyRow(id, d0, "100.00");
            insertCloseOnlyRow(id, d1, "108.00");
            linkStockIndustry(id, industryY);
        }
        seedStock("MG318", "X群組", true);
        insertCloseOnlyRow("MG318", d0, "100.00");
        insertCloseOnlyRow("MG318", d1, "108.00");
        linkStockIndustry("MG318", industryX);
        seedStock("MG319", "Z群組", true);
        insertCloseOnlyRow("MG319", d0, "100.00");
        insertCloseOnlyRow("MG319", d1, "108.00");
        linkStockIndustry("MG319", industryZ);

        JsonNode root = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                + "&minGain=-100&sort=AVG_GAIN");
        assertEquals("AVG_GAIN", root.get("sort").asText());
        List<JsonNode> real = onlyRealIndustries(root);
        assertEquals(3, real.size());
        assertEquals(industryY, real.get(0).get("industryId").asInt(), "matchedCount 2 breaks the avgGain tie first");
        assertEquals(industryX, real.get(1).get("industryId").asInt(), "industryName asc breaks the remaining tie");
        assertEquals(industryZ, real.get(2).get("industryId").asInt());
    }

    @Test
    void unclassifiedBlock_alwaysLast_underBothSortValues() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 4, 5);
        LocalDate d1 = d0.plusDays(1);
        int industryId = upsertIndustry("MG測試未分類排序");
        seedStock("MG320", "有產業", true);
        insertCloseOnlyRow("MG320", d0, "100.00");
        insertCloseOnlyRow("MG320", d1, "101.00"); // low gain, low matchedCount(1)
        linkStockIndustry("MG320", industryId);
        // 3 unclassified stocks with a much higher avgGain and matchedCount than the classified block.
        for (String id : new String[]{"MG321", "MG322", "MG323"}) {
            seedStock(id, "無產業", true);
            insertCloseOnlyRow(id, d0, "100.00");
            insertCloseOnlyRow(id, d1, "150.00");
        }

        JsonNode matchCountRoot = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                + "&minGain=-100&sort=MATCH_COUNT");
        JsonNode avgGainRoot = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                + "&minGain=-100&sort=AVG_GAIN");
        assertUnclassifiedIsLast(matchCountRoot);
        assertUnclassifiedIsLast(avgGainRoot);
    }

    private void assertUnclassifiedIsLast(JsonNode root) {
        JsonNode industries = root.get("industries");
        assertTrue(industries.size() >= 2);
        JsonNode last = industries.get(industries.size() - 1);
        assertTrue(last.get("industryId").isNull(), "未分類 must be last regardless of sort/avgGain/matchedCount");
    }

    @Test
    void sort_doesNotAffectItemOrderWithinBlock() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 4, 12);
        LocalDate d1 = d0.plusDays(1);
        int industryId = upsertIndustry("MG測試block內順序");
        String[][] rows = {{"MG324", "112.00"}, {"MG325", "109.00"}, {"MG326", "106.00"}};
        for (String[] row : rows) {
            seedStock(row[0], "區塊內排序", true);
            insertCloseOnlyRow(row[0], d0, "100.00");
            insertCloseOnlyRow(row[0], d1, row[1]);
            linkStockIndustry(row[0], industryId);
        }

        JsonNode matchCountRoot = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                + "&minGain=-100&sort=MATCH_COUNT");
        JsonNode avgGainRoot = get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                + "&minGain=-100&sort=AVG_GAIN");
        JsonNode itemsA = findIndustryById(matchCountRoot, industryId).get("items");
        JsonNode itemsB = findIndustryById(avgGainRoot, industryId).get("items");
        assertEquals(itemsA.size(), itemsB.size());
        for (int i = 0; i < itemsA.size(); i++) {
            assertEquals(itemsA.get(i).get("stockId").asText(), itemsB.get(i).get("stockId").asText(),
                    "item order within a block must be identical regardless of sort");
        }
    }

    @Test
    void sort_invalidValue_rejectedWithInvalidSort() {
        assertErrorCode("metric=SUM&mode=DAYS&days=5&sort=FOO", "INVALID_SORT");
    }

    @Test
    void sort_combinesIndependentlyWithOtherParams() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 4, 19);
        LocalDate d1 = d0.plusDays(1);
        int industryId = upsertIndustry("MG測試sort組合");
        seedStock("MG327", "組合", true);
        insertCloseOnlyRow("MG327", d0, "100.00");
        insertCloseOnlyRow("MG327", d1, "108.00");
        linkStockIndustry("MG327", industryId);

        JsonNode root = get("metric=AVERAGE&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                + "&minGain=3&sort=AVG_GAIN&commonStocksOnly=false");
        assertEquals("AVG_GAIN", root.get("sort").asText());
        JsonNode group = findIndustryById(root, industryId);
        assertBigDecimalEquals("8.00", group.get("avgGain"));
    }

    @Test
    void avgGainAndSort_addNoExtraDatabaseQueries() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 4, 26);
        LocalDate d1 = d0.plusDays(1);
        List<String> ids = seedQualifyingStocks("MGSORTQ_", 5, d0, d1);

        queryCountInterceptor.reset(MAPPER_PREFIX);
        get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100&sort=MATCH_COUNT");
        int matchCountQueries = queryCountInterceptor.getCount();

        queryCountInterceptor.reset(MAPPER_PREFIX);
        get("metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1 + "&minGain=-100&sort=AVG_GAIN");
        int avgGainQueries = queryCountInterceptor.getCount();

        assertEquals(matchCountQueries, avgGainQueries,
                "avgGain/sort must be computed in-memory: no extra query for sort=AVG_GAIN");
        assertTrue(matchCountQueries > 0);

        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'MGSORTQ_%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'MGSORTQ_%'");
    }

    @Test
    void avgGain_wording_noOverallPerformanceOrAdviceLanguage() throws Exception {
        LocalDate d0 = LocalDate.of(2027, 5, 3);
        LocalDate d1 = d0.plusDays(1);
        seedStock("MG328", "avgGain文案測試", true);
        insertCloseOnlyRow("MG328", d0, "100.00");
        insertCloseOnlyRow("MG328", d1, "110.00");

        ResponseEntity<String> response = rest.getForEntity(
                "/api/momentum/gain?metric=SUM&mode=WEEKS&startDate=" + d1 + "&endDate=" + d1
                        + "&minGain=-100&commonStocksOnly=false",
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertFalse(body.contains("產業整體表現"), "must not describe avgGain as overall industry performance: " + body);
        assertFalse(body.contains("建議"), "must not contain 建議: " + body);
        assertFalse(body.contains("推薦"), "must not contain 推薦: " + body);
    }

    // ==================== helpers ====================

    /**
     * All synthetic stock ids used by this test class ("MG..."-prefixed) are deliberately
     * non-numeric so they never collide with real production stock ids, but that also means none
     * of them match the commonStocksOnly=true regex (specs/backend/stock-universe-import.md's
     * "只收普通股" 4-digit definition). Tests that don't care about commonStocksOnly get
     * {@code commonStocksOnly=false} injected automatically so the default (true) doesn't silently
     * filter every synthetic stock out of the population; tests that exercise commonStocksOnly
     * itself pass it explicitly and are left untouched.
     */
    private JsonNode get(String query) throws Exception {
        if (!query.contains("commonStocksOnly")) {
            query = query + "&commonStocksOnly=false";
        }
        ResponseEntity<String> response = rest.getForEntity("/api/momentum/gain?" + query, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    /** Convenience for tests seeding exactly one industry-less (unclassified) block. */
    private JsonNode onlyIndustryItems(JsonNode root) {
        JsonNode industries = root.get("industries");
        assertEquals(1, industries.size(), "expected exactly one industry block (the unclassified one)");
        return industries.get(0).get("items");
    }

    /** Raw call with no automatic commonStocksOnly injection — for tests exercising that param itself. */
    private JsonNode rawGet(String query) throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/momentum/gain?" + query, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    /** `industries` with the trailing 未分類 (industryId == null) block, if any, excluded. */
    private List<JsonNode> onlyRealIndustries(JsonNode root) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode node : root.get("industries")) {
            if (!node.get("industryId").isNull()) {
                result.add(node);
            }
        }
        return result;
    }

    private JsonNode findIndustryById(JsonNode root, int industryId) {
        JsonNode result = findIndustryByIdOrNull(root, industryId);
        if (result == null) {
            throw new AssertionError("industryId not found: " + industryId + " in " + root);
        }
        return result;
    }

    private JsonNode findIndustryByIdOrNull(JsonNode root, int industryId) {
        for (JsonNode node : root.get("industries")) {
            JsonNode idNode = node.get("industryId");
            if (!idNode.isNull() && idNode.asInt() == industryId) {
                return node;
            }
        }
        return null;
    }

    private Integer nullableInt(JsonNode node) {
        return node.isNull() ? null : node.asInt();
    }

    private void assertBigDecimalEquals(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
                "expected " + expected + " but was " + actual.decimalValue());
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

    private void insertCloseOnlyRow(String stockId, LocalDate date, String close) {
        insertPriceRow(stockId, date, close, close, close, close, 1000);
    }

    private int upsertIndustry(String name) {
        jdbc.update("INSERT INTO industry (industry_name) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE industry_id = LAST_INSERT_ID(industry_id)", name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Integer.class);
    }

    private void linkStockIndustry(String stockId, int industryId) {
        jdbc.update("INSERT INTO stock_industry (stock_id, industry_id) VALUES (?, ?)", stockId, industryId);
    }
}
