package com.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.dto.CreateSimulatedTradeRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for GET/POST/DELETE /api/simulated-trades — specs/backend/simulated-trade.md.
 * Uses "ST"-prefixed synthetic stock ids so this class can run alongside the other integration
 * test classes without interfering with them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulatedTradeIntegrationTest {

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
        jdbc.update("DELETE FROM simulated_trade WHERE stock_id LIKE 'ST%'");
        jdbc.update("DELETE FROM stock_sync_progress WHERE stock_id LIKE 'ST%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'ST%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'ST%'");
    }

    // ==================== 建立持股 ====================

    @Test
    void create_buyDateIsLastPositiveCloseBeforeToday_buyPriceIsThatClose_sharesIs1000() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate expectedBuyDate = today.minusDays(1);
        seedStock("ST001", "建立測試", true);
        insertPriceRow("ST001", expectedBuyDate, "100.00");
        insertPriceRow("ST001", today, "105.00");

        JsonNode item = createAndReadJson("ST001", HttpStatus.CREATED);
        assertEquals(expectedBuyDate.toString(), item.get("buyDate").asText());
        assertEquals(0, new BigDecimal("100.00").compareTo(item.get("buyPrice").decimalValue()));
        assertEquals(1000, item.get("shares").asInt());
    }

    @Test
    void create_todaysOwnClose_isNeverSelectedAsBuyDate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        seedStock("ST002", "今日收盤排除測試", true);
        insertPriceRow("ST002", yesterday, "100.00");
        insertPriceRow("ST002", today, "999.00");

        JsonNode item = createAndReadJson("ST002", HttpStatus.CREATED);
        assertEquals(yesterday.toString(), item.get("buyDate").asText());
        assertEquals(0, new BigDecimal("100.00").compareTo(item.get("buyPrice").decimalValue()));
    }

    @Test
    void create_zeroCloseDayIsSkipped_takesEarlierPositiveCloseDay() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate zeroDay = today.minusDays(1);
        LocalDate earlierPositiveDay = today.minusDays(2);
        seedStock("ST003", "零收盤跳過測試", true);
        insertPriceRow("ST003", earlierPositiveDay, "88.00");
        insertPriceRow("ST003", zeroDay, "0.00");

        JsonNode item = createAndReadJson("ST003", HttpStatus.CREATED);
        assertEquals(earlierPositiveDay.toString(), item.get("buyDate").asText());
        assertEquals(0, new BigDecimal("88.00").compareTo(item.get("buyPrice").decimalValue()));
    }

    @Test
    void create_buyPriceAndCost_areImmutableAfterSourcePriceIsCorrected() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(1);
        seedStock("ST004", "買進價不變測試", true);
        insertPriceRow("ST004", buyDate, "100.00");
        insertPriceRow("ST004", today, "100.00");

        JsonNode created = createAndReadJson("ST004", HttpStatus.CREATED);
        BigDecimal originalCost = created.get("cost").decimalValue();

        jdbc.update("UPDATE stock_daily_price SET close_price = 250.00 WHERE stock_id = 'ST004' AND trade_date = ?",
                buyDate);

        JsonNode listItem = getSingleListItem("ST004");
        assertEquals(0, new BigDecimal("100.00").compareTo(listItem.get("buyPrice").decimalValue()));
        assertEquals(0, originalCost.compareTo(listItem.get("cost").decimalValue()));
    }

    @Test
    void create_responseShape_matchesGetItemsShape() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(1);
        seedStock("ST005", "回應形狀測試", true);
        insertPriceRow("ST005", buyDate, "100.00");
        insertPriceRow("ST005", today, "105.00");

        JsonNode item = createAndReadJson("ST005", HttpStatus.CREATED);
        assertTrue(item.has("currentDate"));
        assertTrue(item.has("currentPrice"));
        assertTrue(item.has("unrealizedProfit"));
        assertTrue(item.has("returnPercent"));
        assertTrue(item.has("id"));
        assertTrue(item.has("stockName"));
    }

    // ==================== 指定買進日（本次新增） ====================

    @Test
    void create_withExplicitEarlierBuyDate_usesThatDateAndItsClose_unrealizedProfitUsesLatestClose() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate earlierBuyDate = today.minusDays(5);
        seedStock("ST100", "指定買進日測試", true);
        insertPriceRow("ST100", earlierBuyDate, "100.00");
        insertPriceRow("ST100", today.minusDays(3), "150.00");
        insertPriceRow("ST100", today, "120.00");

        JsonNode item = createAndReadJson("ST100", earlierBuyDate.toString(), HttpStatus.CREATED);
        assertEquals(earlierBuyDate.toString(), item.get("buyDate").asText());
        assertEquals(0, new BigDecimal("100.00").compareTo(item.get("buyPrice").decimalValue()));
        assertEquals(today.toString(), item.get("currentDate").asText());
        assertEquals(0, new BigDecimal("120.00").compareTo(item.get("currentPrice").decimalValue()));
    }

    @Test
    void create_sameStockTwoDifferentBuyDates_bothSucceed_bothListedNewestFirst_bothCountTowardTotals()
            throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate olderBuyDate = today.minusDays(3);
        LocalDate newerBuyDate = today.minusDays(1);
        seedStock("ST101", "多筆買進日測試", true);
        insertPriceRow("ST101", olderBuyDate, "100.00");
        insertPriceRow("ST101", newerBuyDate, "110.00");
        insertPriceRow("ST101", today, "120.00");

        // Baseline totals may already include unrelated rows from the live shared database, so the
        // effect of these two new rows is asserted as a delta, not as the response's absolute total.
        BigDecimal totalCostBefore = getListJson().get("totalCost").decimalValue();
        BigDecimal totalProfitBefore = getListJson().get("totalUnrealizedProfit").decimalValue();

        JsonNode older = createAndReadJson("ST101", olderBuyDate.toString(), HttpStatus.CREATED);
        JsonNode newer = createAndReadJson("ST101", newerBuyDate.toString(), HttpStatus.CREATED);
        BigDecimal expectedTotalCost =
                totalCostBefore.add(older.get("cost").decimalValue()).add(newer.get("cost").decimalValue());
        BigDecimal expectedTotalProfit = totalProfitBefore
                .add(older.get("unrealizedProfit").decimalValue())
                .add(newer.get("unrealizedProfit").decimalValue());

        JsonNode root = getListJson();
        JsonNode items = root.get("items");
        int newerIdx = -1;
        int olderIdx = -1;
        for (int i = 0; i < items.size(); i++) {
            JsonNode it = items.get(i);
            if (!"ST101".equals(it.get("stockId").asText())) {
                continue;
            }
            if (newerBuyDate.toString().equals(it.get("buyDate").asText())) {
                newerIdx = i;
            } else if (olderBuyDate.toString().equals(it.get("buyDate").asText())) {
                olderIdx = i;
            }
        }
        assertTrue(newerIdx >= 0 && olderIdx >= 0, "both buyDates must be present in items");
        assertTrue(newerIdx < olderIdx, "newer buyDate must be listed before the older one");

        assertEquals(0, expectedTotalCost.compareTo(root.get("totalCost").decimalValue()));
        assertEquals(0, expectedTotalProfit.compareTo(root.get("totalUnrealizedProfit").decimalValue()));

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM simulated_trade WHERE stock_id = 'ST101'", Long.class);
        assertEquals(2L, count);
    }

    @Test
    void create_buyDateIsToday_whenTodaysCloseAlreadyExists_succeeds_unrealizedProfitIsNegativeFeesOnly()
            throws Exception {
        LocalDate today = LocalDate.now();
        seedStock("ST102", "今日買進測試", true);
        insertPriceRow("ST102", today, "100.00");

        JsonNode item = createAndReadJson("ST102", today.toString(), HttpStatus.CREATED);
        assertEquals(today.toString(), item.get("buyDate").asText());
        assertEquals(0, new BigDecimal("100.00").compareTo(item.get("buyPrice").decimalValue()));
        assertEquals(today.toString(), item.get("currentDate").asText());
        assertTrue(item.get("unrealizedProfit").asInt() < 0, "unrealizedProfit must be negative (fees only)");
    }

    @Test
    void create_buyDateAfterToday_returns400InvalidBuyDate_writesNoRow() {
        seedStock("ST103", "未來日期測試", true);

        ResponseEntity<ErrorResponse> future = createRequest("ST103", "2099-01-01");
        assertEquals(HttpStatus.BAD_REQUEST, future.getStatusCode());
        assertEquals("INVALID_BUY_DATE", future.getBody().getCode());
        assertEquals("2099-01-01", future.getBody().getBuyDate());

        ResponseEntity<ErrorResponse> malformed = createRequest("ST103", "not-a-date");
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        assertEquals("INVALID_BUY_DATE", malformed.getBody().getCode());
        assertEquals("not-a-date", malformed.getBody().getBuyDate());

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM simulated_trade WHERE stock_id = 'ST103'", Long.class);
        assertEquals(0L, count);
    }

    @Test
    void create_explicitBuyDateWithNoPriceRow_returns400NoPriceOnBuyDate_neverFallsBackToNearbyDate_writesNoRow() {
        LocalDate today = LocalDate.now();
        // A Sunday strictly before today, guaranteed to have no stock_daily_price row at all.
        LocalDate sunday = today.minusDays(1);
        while (sunday.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
            sunday = sunday.minusDays(1);
        }
        LocalDate priorTradingDay = sunday.minusDays(2);
        seedStock("ST104", "無收盤價測試", true);
        insertPriceRow("ST104", priorTradingDay, "100.00");
        // sunday itself: no row at all.

        ResponseEntity<ErrorResponse> missingRow = createRequest("ST104", sunday.toString());
        assertEquals(HttpStatus.BAD_REQUEST, missingRow.getStatusCode());
        assertEquals("NO_PRICE_ON_BUY_DATE", missingRow.getBody().getCode());
        assertEquals("ST104", missingRow.getBody().getStockId());
        assertEquals(sunday.toString(), missingRow.getBody().getBuyDate());

        // A day that does have a row, but close_price = 0 — "沒有成交", not a real quote either.
        LocalDate zeroCloseDay = priorTradingDay.plusDays(1);
        insertPriceRow("ST104", zeroCloseDay, "0.00");
        ResponseEntity<ErrorResponse> zeroClose = createRequest("ST104", zeroCloseDay.toString());
        assertEquals(HttpStatus.BAD_REQUEST, zeroClose.getStatusCode());
        assertEquals("NO_PRICE_ON_BUY_DATE", zeroClose.getBody().getCode());
        assertEquals("ST104", zeroClose.getBody().getStockId());
        assertEquals(zeroCloseDay.toString(), zeroClose.getBody().getBuyDate());

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM simulated_trade WHERE stock_id = 'ST104'", Long.class);
        assertEquals(0L, count);
    }

    @Test
    void get_defaultBuyDate_isMarketWideLatestClose_independentOfAnyOneStocksOwnLastTradeDate() throws Exception {
        // Runs against the live, shared database (real stocks like 2330 already have real price
        // history), so this asserts the MARKET-WIDE property relatively rather than pinning
        // defaultBuyDate to a hardcoded absolute date computed from `today` alone.
        LocalDate today = LocalDate.now();
        String baselineDefaultBuyDate = textOrNull(getListJson().get("defaultBuyDate"));

        // A stock that stopped trading well before the current market-wide latest close date — its
        // own (much earlier) last trade date must never leak into defaultBuyDate, which is
        // market-wide, not per-stock (specs/backend/simulated-trade.md, "預設買進日（defaultBuyDate）").
        LocalDate delistedLast = today.minusDays(400);
        seedStock("ST105", "已停止交易測試", false);
        insertPriceRow("ST105", delistedLast, "50.00");

        JsonNode afterDelisted = getListJson();
        String defaultBuyDateAfterDelisted = textOrNull(afterDelisted.get("defaultBuyDate"));
        assertEquals(baselineDefaultBuyDate, defaultBuyDateAfterDelisted,
                "an older, delisted stock's own last trade date must not move defaultBuyDate");
        assertNotEquals(delistedLast.toString(), defaultBuyDateAfterDelisted,
                "defaultBuyDate must never equal the delisted stock's own last trade date");

        // Construct a still-active stock whose own last trade date is strictly later than every
        // other stock's (including the live database's real, unrelated data) — proving
        // defaultBuyDate is recomputed market-wide across ALL stocks, not scoped to any one.
        boolean baselineIsToday = today.toString().equals(baselineDefaultBuyDate);
        if (!baselineIsToday) {
            seedStock("ST106", "仍在交易測試", true);
            insertPriceRow("ST106", today, "60.00");

            JsonNode afterActive = getListJson();
            assertEquals(today.toString(), afterActive.get("defaultBuyDate").asText(),
                    "a newer close from any stock must move the market-wide defaultBuyDate forward");
            assertNotEquals(delistedLast.toString(), afterActive.get("defaultBuyDate").asText());
        }
    }

    // ==================== 未實現損益與成本 ====================

    @Test
    void unrealizedProfit_matchesFormula_sameNumbersAsBacktest() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(1);
        seedStock("ST010", "損益計算測試", true);
        insertPriceRow("ST010", buyDate, "1150.00");
        insertPriceRow("ST010", today, "1205.00");

        JsonNode item = createAndReadJson("ST010", HttpStatus.CREATED);
        assertEquals(1638, item.get("buyFee").asInt());
        assertEquals(1151638, item.get("cost").asInt());
        assertEquals(1717, item.get("sellFee").asInt());
        assertEquals(3615, item.get("sellTax").asInt());
        assertEquals(48030, item.get("unrealizedProfit").asInt());
        assertEquals(0, new BigDecimal("4.17").compareTo(item.get("returnPercent").decimalValue()));
    }

    @Test
    void unrealizedProfit_flooredNotRounded() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(1);
        seedStock("ST011", "無條件捨去測試", true);
        insertPriceRow("ST011", buyDate, "21.55");
        insertPriceRow("ST011", today, "23.10");

        JsonNode item = createAndReadJson("ST011", HttpStatus.CREATED);
        assertEquals(30, item.get("buyFee").asInt());
        assertEquals(32, item.get("sellFee").asInt());
        assertEquals(69, item.get("sellTax").asInt());
        assertEquals(21580, item.get("cost").asInt());
        assertEquals(1419, item.get("unrealizedProfit").asInt());
        assertEquals(0, new BigDecimal("6.58").compareTo(item.get("returnPercent").decimalValue()));
    }

    @Test
    void unrealizedProfit_exactDecimalArithmetic_notBinaryFloat() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(1);
        seedStock("ST012", "精確十進位測試", true);
        insertPriceRow("ST012", buyDate, "200.00");
        insertPriceRow("ST012", today, "200.00");

        JsonNode item = createAndReadJson("ST012", HttpStatus.CREATED);
        assertEquals(285, item.get("buyFee").asInt());
        assertEquals(285, item.get("sellFee").asInt());
        assertEquals(600, item.get("sellTax").asInt());
        assertEquals(-1170, item.get("unrealizedProfit").asInt());
    }

    @Test
    void unrealizedProfit_negativeWhenCurrentDateEqualsBuyDate_notZero() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(1);
        seedStock("ST013", "同日測試", true);
        insertPriceRow("ST013", buyDate, "100.00");
        // No row for today at all -> current price lookup resolves to buyDate itself.

        JsonNode item = createAndReadJson("ST013", HttpStatus.CREATED);
        assertEquals(buyDate.toString(), item.get("currentDate").asText());
        assertTrue(item.get("unrealizedProfit").asInt() < 0, "unrealizedProfit must be negative, not 0");
    }

    @Test
    void currentPrice_skipsZeroCloseDay_takesEarlierPositiveCloseDay() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(2);
        LocalDate earlierPositiveDay = today.minusDays(1);
        seedStock("ST014", "現價零收盤跳過測試", true);
        insertPriceRow("ST014", buyDate, "100.00");
        insertPriceRow("ST014", earlierPositiveDay, "110.00");
        insertPriceRow("ST014", today, "0.00");

        JsonNode item = createAndReadJson("ST014", HttpStatus.CREATED);
        assertEquals(earlierPositiveDay.toString(), item.get("currentDate").asText());
        assertEquals(0, new BigDecimal("110.00").compareTo(item.get("currentPrice").decimalValue()));
    }

    @Test
    void delistedStock_stillReported_currentDateIsItsLastTradedDay() throws Exception {
        LocalDate lastTradedDay = LocalDate.now().minusDays(30);
        seedStock("ST015", "已下市測試", false);
        insertPriceRow("ST015", lastTradedDay, "50.00");

        JsonNode item = createAndReadJson("ST015", HttpStatus.CREATED);
        assertEquals(lastTradedDay.toString(), item.get("currentDate").asText());

        JsonNode listItem = getSingleListItem("ST015");
        assertEquals(lastTradedDay.toString(), listItem.get("currentDate").asText());
    }

    // ==================== 列表與彙總 ====================

    @Test
    void get_returnsLotSizeFeeRateTaxRateAsOfDate() throws Exception {
        LocalDate today = LocalDate.now();
        seedStock("ST020", "常數測試", true);
        insertPriceRow("ST020", today.minusDays(1), "100.00");
        createAndReadJson("ST020", HttpStatus.CREATED);

        JsonNode root = getListJson();
        assertEquals(1000, root.get("lotSize").asInt());
        assertEquals(0, new BigDecimal("0.1425").compareTo(root.get("feeRatePercent").decimalValue()));
        assertEquals(0, new BigDecimal("0.3").compareTo(root.get("taxRatePercent").decimalValue()));
        assertEquals(today.toString(), root.get("asOfDate").asText());
    }

    @Test
    void totals_equalSumOfItems_costWeighted_notArithmeticMean() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(1);
        // Two wildly different price magnitudes, same shares (1000) each — a high-price stock must
        // dominate the cost-weighted total, unlike a plain arithmetic mean of the two returnPercents.
        seedStock("ST021", "高價股", true);
        insertPriceRow("ST021", buyDate, "1150.00");
        insertPriceRow("ST021", today, "1205.00");
        seedStock("ST022", "低價股", true);
        insertPriceRow("ST022", buyDate, "21.55");
        insertPriceRow("ST022", today, "23.10");

        createAndReadJson("ST021", HttpStatus.CREATED);
        createAndReadJson("ST022", HttpStatus.CREATED);

        JsonNode root = getListJson();
        JsonNode items = root.get("items");
        assertEquals(2, items.size());

        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal returnPercentSum = BigDecimal.ZERO;
        for (JsonNode item : items) {
            totalCost = totalCost.add(item.get("cost").decimalValue());
            totalProfit = totalProfit.add(item.get("unrealizedProfit").decimalValue());
            returnPercentSum = returnPercentSum.add(item.get("returnPercent").decimalValue());
        }
        assertEquals(0, totalCost.compareTo(root.get("totalCost").decimalValue()));
        assertEquals(0, totalProfit.compareTo(root.get("totalUnrealizedProfit").decimalValue()));

        BigDecimal expectedTotalReturnPercent = totalProfit
                .divide(totalCost, 10, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertEquals(0, expectedTotalReturnPercent.compareTo(root.get("totalReturnPercent").decimalValue()));

        BigDecimal arithmeticMean = returnPercentSum.divide(BigDecimal.valueOf(2), 10, java.math.RoundingMode.HALF_UP);
        assertNotEquals(0, arithmeticMean.setScale(2, java.math.RoundingMode.HALF_UP)
                .compareTo(root.get("totalReturnPercent").decimalValue()));
    }

    @Test
    void get_noHoldings_returns200EmptyItems_totalsZero_totalReturnPercentNull() throws Exception {
        JsonNode root = getListJson();
        assertEquals(0, root.get("items").size());
        assertEquals(0, root.get("totalCost").asInt());
        assertEquals(0, root.get("totalUnrealizedProfit").asInt());
        assertTrue(root.get("totalReturnPercent").isNull());
    }

    @Test
    void items_orderedByBuyDateDesc_thenStockIdAsc() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate older = today.minusDays(5);
        LocalDate newer = today.minusDays(1);
        seedStock("ST030", "排序B", true);
        seedStock("ST031", "排序A新", true);
        seedStock("ST032", "排序B新", true);
        insertPriceRow("ST030", older, "50.00");
        insertPriceRow("ST030", today, "51.00");
        insertPriceRow("ST031", newer, "60.00");
        insertPriceRow("ST031", today, "61.00");
        insertPriceRow("ST032", newer, "70.00");
        insertPriceRow("ST032", today, "71.00");

        createAndReadJson("ST030", HttpStatus.CREATED);
        createAndReadJson("ST031", HttpStatus.CREATED);
        createAndReadJson("ST032", HttpStatus.CREATED);

        JsonNode items = getListJson().get("items");
        assertEquals(3, items.size());
        // newer buyDate first; within the same (newer) buyDate, stockId ascending.
        assertEquals("ST031", items.get(0).get("stockId").asText());
        assertEquals("ST032", items.get(1).get("stockId").asText());
        assertEquals("ST030", items.get(2).get("stockId").asText());
    }

    @Test
    void priceQueries_areBatched_countDoesNotScaleWithHoldingCount() {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(1);
        for (int i = 0; i < 2; i++) {
            String id = "ST04" + i;
            seedStock(id, "查詢次數少量", true);
            insertPriceRow(id, buyDate, "50.00");
            insertPriceRow(id, today, "55.00");
            insertSimulatedTrade(id, buyDate, "50.00");
        }

        queryCountInterceptor.reset(MAPPER_NAMESPACE);
        rest.getForEntity("/api/simulated-trades", String.class);
        int fewCount = queryCountInterceptor.getCount();

        for (int i = 2; i < 20; i++) {
            String id = "ST0" + String.format("%02d", i);
            seedStock(id, "查詢次數大量", true);
            insertPriceRow(id, buyDate, "50.00");
            insertPriceRow(id, today, "55.00");
            insertSimulatedTrade(id, buyDate, "50.00");
        }

        queryCountInterceptor.reset(MAPPER_NAMESPACE);
        rest.getForEntity("/api/simulated-trades", String.class);
        int manyCount = queryCountInterceptor.getCount();

        assertEquals(fewCount, manyCount, "query count must not scale with number of holdings (2 vs 20)");
        assertTrue(fewCount > 0);
    }

    // ==================== 驗證與刪除 ====================

    @Test
    void missingOrBlankStockId_returns400InvalidStockId() {
        ResponseEntity<ErrorResponse> missing =
                rest.postForEntity("/api/simulated-trades", new CreateSimulatedTradeRequest(), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertEquals("INVALID_STOCK_ID", missing.getBody().getCode());

        CreateSimulatedTradeRequest blank = new CreateSimulatedTradeRequest();
        blank.setStockId("   ");
        ResponseEntity<ErrorResponse> blankResponse =
                rest.postForEntity("/api/simulated-trades", blank, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, blankResponse.getStatusCode());
        assertEquals("INVALID_STOCK_ID", blankResponse.getBody().getCode());
    }

    @Test
    void stockIdWithSurroundingWhitespace_isTrimmedAndAccepted() throws Exception {
        LocalDate today = LocalDate.now();
        seedStock("ST050", "去除空白測試", true);
        insertPriceRow("ST050", today.minusDays(1), "100.00");

        CreateSimulatedTradeRequest request = new CreateSimulatedTradeRequest();
        request.setStockId("  ST050  ");
        ResponseEntity<String> response =
                rest.postForEntity("/api/simulated-trades", request, String.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), "body: " + response.getBody());
        JsonNode item = objectMapper.readTree(response.getBody());
        assertEquals("ST050", item.get("stockId").asText());
    }

    @Test
    void unknownStockId_returns400UnknownStockId() {
        ResponseEntity<ErrorResponse> response = createRequest("ST_UNKNOWN_999");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("UNKNOWN_STOCK_ID", response.getBody().getCode());
        assertTrue(response.getBody().getUnknownIds().contains("ST_UNKNOWN_999"));
    }

    @Test
    void noPositiveCloseBeforeToday_returns400_andWritesNoRow() {
        seedStock("ST060", "無行情測試", true);
        // No stock_daily_price rows at all for ST060.

        ResponseEntity<ErrorResponse> response = createRequest("ST060");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("NO_PRICE_BEFORE_TODAY", response.getBody().getCode());
        assertEquals("ST060", response.getBody().getStockId());

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM simulated_trade WHERE stock_id = 'ST060'", Long.class);
        assertEquals(0L, count);
    }

    @Test
    void duplicateSameDay_returns409_dbStillHasOnlyOneRow() {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(1);
        seedStock("ST070", "重複建立測試", true);
        insertPriceRow("ST070", buyDate, "100.00");

        ResponseEntity<String> first = rest.postForEntity("/api/simulated-trades", createBody("ST070"), String.class);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());

        ResponseEntity<ErrorResponse> second = createRequest("ST070");
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertEquals("DUPLICATE_SIMULATED_TRADE", second.getBody().getCode());
        assertEquals("ST070", second.getBody().getStockId());
        assertEquals(buyDate.toString(), second.getBody().getBuyDate());

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM simulated_trade WHERE stock_id = 'ST070'", Long.class);
        assertEquals(1L, count);
    }

    @Test
    void delete_removesRow_returns204_secondDeleteReturns404() throws Exception {
        LocalDate today = LocalDate.now();
        seedStock("ST080", "刪除測試", true);
        insertPriceRow("ST080", today.minusDays(1), "100.00");

        JsonNode created = createAndReadJson("ST080", HttpStatus.CREATED);
        long id = created.get("id").asLong();

        ResponseEntity<Void> deleteResponse =
                rest.exchange("/api/simulated-trades/" + id, org.springframework.http.HttpMethod.DELETE, null, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode());

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM simulated_trade WHERE id = " + id, Long.class);
        assertEquals(0L, count);

        ResponseEntity<ErrorResponse> secondDelete = rest.exchange("/api/simulated-trades/" + id,
                org.springframework.http.HttpMethod.DELETE, null, ErrorResponse.class);
        assertEquals(HttpStatus.NOT_FOUND, secondDelete.getStatusCode());
        assertEquals("SIMULATED_TRADE_NOT_FOUND", secondDelete.getBody().getCode());
        assertEquals(Long.valueOf(id), secondDelete.getBody().getId());
    }

    @Test
    void endpoints_neverWriteToStockOrStockDailyPriceOrStockSyncProgress() throws Exception {
        LocalDate today = LocalDate.now();
        seedStock("ST090", "無副作用測試", true);
        insertPriceRow("ST090", today.minusDays(1), "100.00");

        long stockCountBefore = countAll("stock");
        long priceCountBefore = countAll("stock_daily_price");
        long syncProgressCountBefore = countAll("stock_sync_progress");

        JsonNode created = createAndReadJson("ST090", HttpStatus.CREATED);
        rest.getForEntity("/api/simulated-trades", String.class);
        rest.exchange("/api/simulated-trades/" + created.get("id").asLong(), org.springframework.http.HttpMethod.DELETE,
                null, Void.class);

        assertEquals(stockCountBefore, countAll("stock"));
        assertEquals(priceCountBefore, countAll("stock_daily_price"));
        assertEquals(syncProgressCountBefore, countAll("stock_sync_progress"));
    }

    // ==================== helpers ====================

    private JsonNode createAndReadJson(String stockId, HttpStatus expectedStatus) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/simulated-trades", createBody(stockId), String.class);
        assertEquals(expectedStatus, response.getStatusCode(), "body: " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private ResponseEntity<ErrorResponse> createRequest(String stockId) {
        return rest.postForEntity("/api/simulated-trades", createBody(stockId), ErrorResponse.class);
    }

    private CreateSimulatedTradeRequest createBody(String stockId) {
        CreateSimulatedTradeRequest request = new CreateSimulatedTradeRequest();
        request.setStockId(stockId);
        return request;
    }

    private CreateSimulatedTradeRequest createBody(String stockId, String buyDate) {
        CreateSimulatedTradeRequest request = new CreateSimulatedTradeRequest();
        request.setStockId(stockId);
        request.setBuyDate(buyDate);
        return request;
    }

    private JsonNode createAndReadJson(String stockId, String buyDate, HttpStatus expectedStatus) throws Exception {
        ResponseEntity<String> response =
                rest.postForEntity("/api/simulated-trades", createBody(stockId, buyDate), String.class);
        assertEquals(expectedStatus, response.getStatusCode(), "body: " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private ResponseEntity<ErrorResponse> createRequest(String stockId, String buyDate) {
        return rest.postForEntity("/api/simulated-trades", createBody(stockId, buyDate), ErrorResponse.class);
    }

    private JsonNode getListJson() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/api/simulated-trades", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode getSingleListItem(String stockId) throws Exception {
        JsonNode items = getListJson().get("items");
        for (JsonNode item : items) {
            if (stockId.equals(item.get("stockId").asText())) {
                return item;
            }
        }
        throw new AssertionError("No item found for stockId " + stockId);
    }

    private void seedStock(String stockId, String name, boolean active) {
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES (?, ?, 'TSE', ?)",
                stockId, name, active);
    }

    private void insertPriceRow(String stockId, LocalDate date, String close) {
        jdbc.update("INSERT INTO stock_daily_price "
                        + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, "
                        + "turnover, transaction_count, source) VALUES (?, ?, ?, ?, ?, ?, 1000, 0, 0, 'TEST')",
                stockId, date, new BigDecimal(close), new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close));
    }

    private void insertSimulatedTrade(String stockId, LocalDate buyDate, String buyPrice) {
        jdbc.update("INSERT INTO simulated_trade (stock_id, buy_date, buy_price, shares) VALUES (?, ?, ?, 1000)",
                stockId, buyDate, new BigDecimal(buyPrice));
    }

    /** {@code null} for both a missing field and an explicit JSON {@code null} value. */
    private String textOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asText();
    }

    private long countAll(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
