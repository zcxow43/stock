package com.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.dto.BacktestDuplicateItemDto;
import com.stock.dto.BacktestItemRequestDto;
import com.stock.dto.BacktestRequestDto;
import com.stock.dto.ErrorResponse;
import com.stock.service.StrategyBacktestService;
import com.stock.service.StrategyScanService;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for POST /api/strategies/backtest — specs/backend/strategy-backtest.md.
 * Uses "BT"-prefixed synthetic stock ids so this class can run alongside the other integration
 * test classes without interfering with them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StrategyBacktestIntegrationTest {

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
        jdbc.update("DELETE FROM stock_sync_progress WHERE stock_id LIKE 'BT%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'BT%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'BT%'");
    }

    // ==================== 回測規則 ====================

    @Test
    void buyPrice_equalsClosePriceAtBuyDate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(5);
        seedStock("BT001", "買進價測試", true);
        seedBaseWindow("BT001", buyDate, today);

        JsonNode item = singleItemResult("BT001", buyDate);
        assertEquals(0, new BigDecimal("100.00").compareTo(item.get("buyPrice").decimalValue()));
    }

    @Test
    void sellPrice_isMaxOpenInWindow_sellDateIsThatTradeDate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(5);
        seedStock("BT002", "賣出價測試", true);
        seedBaseWindow("BT002", buyDate, today);

        JsonNode item = singleItemResult("BT002", buyDate);
        assertEquals(0, new BigDecimal("110.00").compareTo(item.get("sellPrice").decimalValue()));
        assertEquals(buyDate.plusDays(2).toString(), item.get("sellDate").asText());
    }

    @Test
    void buyDateOwnOpen_notIncludedInSellWindow() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(5);
        seedStock("BT003", "買進日開盤排除測試", true);
        // Buy day's own open (200.00) is far higher than every later open — must never be picked.
        insertPriceRow("BT003", buyDate, "200.00", "205.00", "95.00", "50.00", 1000);
        insertPriceRow("BT003", buyDate.plusDays(1), "60.00", "61.00", "59.00", "60.00", 1000);
        insertPriceRow("BT003", buyDate.plusDays(2), "55.00", "56.00", "54.00", "55.00", 1000);
        insertPriceRow("BT003", buyDate.plusDays(3), "58.00", "59.00", "57.00", "58.00", 1000);
        insertPriceRow("BT003", today, "59.00", "60.00", "58.00", "59.00", 1000);

        JsonNode item = singleItemResult("BT003", buyDate);
        assertNotEquals(buyDate.toString(), item.get("sellDate").asText());
        assertEquals(0, new BigDecimal("60.00").compareTo(item.get("sellPrice").decimalValue()));
        assertNotEquals(0, new BigDecimal("200.00").compareTo(item.get("sellPrice").decimalValue()));
    }

    @Test
    void tiedMaxOpen_resolvesToEarliestTradeDate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(5);
        seedStock("BT004", "同值取最早測試", true);
        insertPriceRow("BT004", buyDate, "90.00", "91.00", "89.00", "100.00", 1000);
        insertPriceRow("BT004", buyDate.plusDays(1), "105.00", "106.00", "104.00", "105.00", 1000);
        // Two ties at 110.00 — the earlier one (day+2) must win, not day+4.
        insertPriceRow("BT004", buyDate.plusDays(2), "110.00", "111.00", "109.00", "110.00", 1000);
        insertPriceRow("BT004", buyDate.plusDays(3), "95.00", "96.00", "94.00", "95.00", 1000);
        insertPriceRow("BT004", today, "110.00", "111.00", "109.00", "110.00", 1000);

        JsonNode item = singleItemResult("BT004", buyDate);
        assertEquals(buyDate.plusDays(2).toString(), item.get("sellDate").asText());
        assertEquals(0, new BigDecimal("110.00").compareTo(item.get("sellPrice").decimalValue()));
    }

    @Test
    void returnPercent_matchesFormula_roundedToTwoDecimals() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(5);
        seedStock("BT005", "報酬率測試", true);
        seedBaseWindow("BT005", buyDate, today);

        JsonNode item = singleItemResult("BT005", buyDate);
        // buy 100.00 -> sell 110.00: buyFee=142 (100000*0.001425=142.5, floor), cost=100142;
        // sellFee=156 (110000*0.001425=156.75, floor), sellTax=330 (110000*0.003=330);
        // profit = 110000-156-330-100142 = 9372; returnPercent = 9372/100142*100 = 9.3608...-> 9.36
        assertEquals(0, new BigDecimal("9.36").compareTo(item.get("returnPercent").decimalValue()));
    }

    @Test
    void profit_matchesFormula_roundedToYuan_andLotSizeIs1000() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(5);
        seedStock("BT006", "收益測試", true);
        seedBaseWindow("BT006", buyDate, today);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT006", buyDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals(1000, root.get("lotSize").asInt());
        JsonNode item = root.get("items").get(0);
        // profit = sellPrice*1000 - sellFee - sellTax - cost = 110000-156-330-100142 = 9372
        assertEquals(0, new BigDecimal("9372").compareTo(item.get("profit").decimalValue()));
    }

    @Test
    void declineAfterBuyDate_returnPercentAndProfitAreNegative_notClampedOrRedirected() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(5);
        seedStock("BT007", "負報酬測試", true);
        insertPriceRow("BT007", buyDate, "50.00", "51.00", "49.00", "100.00", 1000);
        insertPriceRow("BT007", buyDate.plusDays(1), "90.00", "91.00", "89.00", "89.00", 1000);
        insertPriceRow("BT007", buyDate.plusDays(2), "85.00", "86.00", "84.00", "84.00", 1000);
        insertPriceRow("BT007", buyDate.plusDays(3), "80.00", "81.00", "79.00", "79.00", 1000);
        insertPriceRow("BT007", today, "70.00", "71.00", "69.00", "69.00", 1000);

        JsonNode item = singleItemResult("BT007", buyDate);
        // Highest open after buy day is 90.00 (day+1); buy 100.00 -> cost=100142 (buyFee=142.5 floor 142)
        // sellFee=floor(90000*0.001425)=128, sellTax=floor(90000*0.003)=270
        // profit = 90000-128-270-100142 = -10540; returnPercent = -10540/100142*100 = -10.5276 -> -10.53
        assertEquals(0, new BigDecimal("90.00").compareTo(item.get("sellPrice").decimalValue()));
        assertEquals(0, new BigDecimal("-10.53").compareTo(item.get("returnPercent").decimalValue()));
        assertEquals(0, new BigDecimal("-10540").compareTo(item.get("profit").decimalValue()));
    }

    @Test
    void suspensionGap_neverBecomesSellDate_maxIsComputedFromExistingRowsOnly() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(6);
        seedStock("BT008", "停牌缺列測試", true);
        insertPriceRow("BT008", buyDate, "90.00", "91.00", "89.00", "100.00", 1000);
        insertPriceRow("BT008", buyDate.plusDays(1), "80.00", "81.00", "79.00", "80.00", 1000);
        // buyDate.plusDays(2) intentionally has no row at all — a suspended trading day.
        insertPriceRow("BT008", buyDate.plusDays(3), "130.00", "131.00", "129.00", "130.00", 1000);
        // buyDate.plusDays(4) also missing.
        insertPriceRow("BT008", today, "90.00", "91.00", "89.00", "90.00", 1000);

        JsonNode item = singleItemResult("BT008", buyDate);
        assertEquals(buyDate.plusDays(3).toString(), item.get("sellDate").asText());
        assertEquals(0, new BigDecimal("130.00").compareTo(item.get("sellPrice").decimalValue()));
    }

    // ==================== 彙總 ====================

    @Test
    void totals_costWeighted_notArithmeticMean_ofTwoWidelyDifferingPricedStocks() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT101", "高價股", true);
        seedStock("BT102", "低價股", true);
        // BT101: buy 1000.00 -> sell 1200.00 (return 20.00%, profit 200000)
        insertPriceRow("BT101", buyDate, "990.00", "1000.00", "980.00", "1000.00", 1000);
        insertPriceRow("BT101", buyDate.plusDays(1), "1200.00", "1210.00", "1190.00", "1200.00", 1000);
        insertPriceRow("BT101", today, "1100.00", "1110.00", "1090.00", "1100.00", 1000);
        // BT102: buy 20.00 -> sell 20.20 (return 1.00%, profit 200)
        insertPriceRow("BT102", buyDate, "19.80", "20.00", "19.70", "20.00", 1000);
        insertPriceRow("BT102", buyDate.plusDays(1), "20.20", "20.30", "20.10", "20.20", 1000);
        insertPriceRow("BT102", today, "20.10", "20.15", "20.05", "20.10", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT101", buyDate), item("BT102", buyDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());

        // BT101: cost=1001425 (buyFee=1425), profit=193265 (sellFee=1710, sellTax=3600) -> 19.30%
        // BT102: cost=20028 (buyFee=28), profit=84 (sellFee=28, sellTax=60) -> 0.42%
        assertEquals(0, new BigDecimal("1021453").compareTo(root.get("totalCost").decimalValue()));
        assertEquals(0, new BigDecimal("193349").compareTo(root.get("totalProfit").decimalValue()));
        BigDecimal totalReturnPercent = root.get("totalReturnPercent").decimalValue();
        assertEquals(0, new BigDecimal("18.93").compareTo(totalReturnPercent));

        // Arithmetic mean of the two per-stock returns (19.30 and 0.42) is 9.86 — the weighted
        // total must not equal it.
        BigDecimal arithmeticMean = new BigDecimal("9.86");
        assertNotEquals(0, arithmeticMean.compareTo(totalReturnPercent));
        assertEquals(2, root.get("backtestedCount").asInt());
    }

    @Test
    void totalCost_equalsSumOfBuyPriceTimes1000_forBacktestableItemsOnly() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT103", "成本彙總A", true);
        seedStock("BT104", "成本彙總B", true);
        insertPriceRow("BT103", buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT103", today, "55.00", "56.00", "54.00", "55.00", 1000);
        insertPriceRow("BT104", buyDate, "60.00", "61.00", "59.00", "60.00", 1000);
        insertPriceRow("BT104", today, "65.00", "66.00", "64.00", "65.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT103", buyDate), item("BT104", buyDate))), String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        // cost(50.00)=50000+71=50071 (buyFee=floor(50000*0.001425)=71)
        // cost(60.00)=60000+85=60085 (buyFee=floor(60000*0.001425)=85)
        assertEquals(0, new BigDecimal("110156").compareTo(root.get("totalCost").decimalValue()));
    }

    @Test
    void totalProfit_equalsSumOfProfit_forBacktestableItemsOnly() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT105", "收益彙總A", true);
        seedStock("BT106", "收益彙總B", true);
        insertPriceRow("BT105", buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT105", today, "55.00", "56.00", "54.00", "55.00", 1000);
        insertPriceRow("BT106", buyDate, "60.00", "61.00", "59.00", "60.00", 1000);
        insertPriceRow("BT106", today, "65.00", "66.00", "64.00", "65.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT105", buyDate), item("BT106", buyDate))), String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        // buy50->sell55: cost=50071, sellFee=78, sellTax=165, profit=55000-78-165-50071=4686
        // buy60->sell65: cost=60085, sellFee=92, sellTax=195, profit=65000-92-195-60085=4628
        assertEquals(0, new BigDecimal("9314").compareTo(root.get("totalProfit").decimalValue()));
    }

    @Test
    void backtestedCount_countsOnlyItemsWithSellDate_lessThanItemsLengthWhenSomeUnbacktestable() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT107", "可回測", true);
        seedStock("BT108", "不可回測", true);
        insertPriceRow("BT107", buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT107", today, "55.00", "56.00", "54.00", "55.00", 1000);
        // BT108's only row is on today, and its "buy date" is today itself -> no day after it.
        insertPriceRow("BT108", today, "70.00", "71.00", "69.00", "70.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT107", buyDate), item("BT108", today))), String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals(1, root.get("backtestedCount").asInt());
        assertEquals(2, root.get("items").size());
        assertTrue(root.get("backtestedCount").asInt() < root.get("items").size());
    }

    // ==================== 無法回測的標的 ====================

    @Test
    void buyDateIsLatestTradeDate_buyPriceSet_restNull_returns200() throws Exception {
        LocalDate today = LocalDate.now();
        seedStock("BT201", "無後續交易日", true);
        insertPriceRow("BT201", today, "88.00", "89.00", "87.00", "88.00", 1000);

        JsonNode item = singleItemResult("BT201", today);
        assertEquals(0, new BigDecimal("88.00").compareTo(item.get("buyPrice").decimalValue()));
        assertTrue(item.get("sellDate").isNull());
        assertTrue(item.get("sellPrice").isNull());
        assertTrue(item.get("returnPercent").isNull());
        assertTrue(item.get("profit").isNull());
    }

    @Test
    void buyDateHasNoPriceRow_buyPriceAlsoNull_returns200() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT202", "無買進日資料", true);
        // No price rows at all for BT202.

        JsonNode item = singleItemResult("BT202", buyDate);
        assertTrue(item.get("buyPrice").isNull());
        assertTrue(item.get("sellDate").isNull());
        assertTrue(item.get("sellPrice").isNull());
        assertTrue(item.get("returnPercent").isNull());
        assertTrue(item.get("profit").isNull());
    }

    @Test
    void unbacktestableItems_stillAppearInItems_notRemoved() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT203", "可回測", true);
        seedStock("BT204", "無後續交易日不可回測", true);
        insertPriceRow("BT203", buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT203", today, "55.00", "56.00", "54.00", "55.00", 1000);
        insertPriceRow("BT204", today, "70.00", "71.00", "69.00", "70.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT203", buyDate), item("BT204", today))), String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        List<String> ids = new ArrayList<>();
        for (JsonNode item : root.get("items")) {
            ids.add(item.get("stockId").asText());
        }
        assertTrue(ids.contains("BT203"));
        assertTrue(ids.contains("BT204"));
        assertEquals(2, ids.size());
    }

    @Test
    void unbacktestableItems_excludedFromTotals_totalCostEqualsBacktestableAlone() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT205", "可回測", true);
        seedStock("BT206", "不可回測", true);
        insertPriceRow("BT205", buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT205", today, "55.00", "56.00", "54.00", "55.00", 1000);
        insertPriceRow("BT206", today, "999.00", "999.00", "999.00", "999.00", 1000);

        // Backtestable stock alone.
        ResponseEntity<String> aloneResponse = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT205", buyDate))), String.class);
        JsonNode aloneRoot = objectMapper.readTree(aloneResponse.getBody());
        BigDecimal aloneCost = aloneRoot.get("totalCost").decimalValue();

        // Combined with the unbacktestable stock.
        ResponseEntity<String> combinedResponse = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT205", buyDate), item("BT206", today))), String.class);
        JsonNode combinedRoot = objectMapper.readTree(combinedResponse.getBody());

        assertEquals(0, aloneCost.compareTo(combinedRoot.get("totalCost").decimalValue()));
    }

    @Test
    void allItemsUnbacktestable_totalsZero_totalReturnPercentNull_backtestedCountZero() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT207", "無後續交易日", true);
        seedStock("BT208", "無買進日資料", true);
        insertPriceRow("BT207", today, "70.00", "71.00", "69.00", "70.00", 1000);
        // BT208 has no price rows at all.

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT207", today), item("BT208", buyDate))), String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals(0, BigDecimal.ZERO.compareTo(root.get("totalCost").decimalValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(root.get("totalProfit").decimalValue()));
        assertTrue(root.get("totalReturnPercent").isNull(), "totalReturnPercent must be null, not 0");
        assertEquals(0, root.get("backtestedCount").asInt());
    }

    // ==================== 契約與驗證 ====================

    @Test
    void items_orderMatchesRequestOrder_notReordered() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT301", "順序C", true);
        seedStock("BT302", "順序A", true);
        seedStock("BT303", "順序B", true);
        for (String id : new String[]{"BT301", "BT302", "BT303"}) {
            insertPriceRow(id, buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
            insertPriceRow(id, today, "55.00", "56.00", "54.00", "55.00", 1000);
        }

        List<BacktestItemRequestDto> requestItems =
                Arrays.asList(item("BT303", buyDate), item("BT301", buyDate), item("BT302", buyDate));
        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(requestItems), String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals("BT303", root.get("items").get(0).get("stockId").asText());
        assertEquals("BT301", root.get("items").get(1).get("stockId").asText());
        assertEquals("BT302", root.get("items").get(2).get("stockId").asText());
    }

    @Test
    void emptyItems_returns400NoBacktestItems_noPriceQueriesIssued() {
        queryCountInterceptor.reset(MAPPER_NAMESPACE);
        ResponseEntity<ErrorResponse> response =
                rest.postForEntity("/api/strategies/backtest", backtestRequest(new ArrayList<>()), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("NO_BACKTEST_ITEMS", response.getBody().getCode());
        assertEquals(0, queryCountInterceptor.getCount(), "no price query must be issued when validation fails");
    }

    @Test
    void missingItems_returns400NoBacktestItems() {
        BacktestRequestDto request = new BacktestRequestDto();
        request.setItems(null);
        ResponseEntity<ErrorResponse> response =
                rest.postForEntity("/api/strategies/backtest", request, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("NO_BACKTEST_ITEMS", response.getBody().getCode());
    }

    /**
     * The regression this endpoint actually shipped with: a full-market scan hits 500+ stocks and
     * the frontend must send every one of them (specs/frontend/strategy.md「送出全部命中標的」), so a
     * 200-item cap made 回測 unconditionally fail with TOO_MANY_STOCKS on a full-market scan.
     * See specs/backend/strategy-backtest.md「上限為什麼不是 200」.
     */
    @Test
    void marketSizedHitList_of532Items_returns200() {
        LocalDate today = LocalDate.now();
        List<BacktestItemRequestDto> hits = new ArrayList<>();
        for (int i = 0; i < 532; i++) {
            String id = String.format("BT7%03d", i);
            seedStock(id, "全市場命中", true);
            hits.add(item(id, today));
        }

        ResponseEntity<String> response =
                rest.postForEntity("/api/strategies/backtest", backtestRequest(hits), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "a 532-item full-market hit list must be accepted; body: " + response.getBody());
    }

    @Test
    void itemsOverCap_returns400TooManyStocks() {
        LocalDate today = LocalDate.now();
        // The size check runs before the existence check, so these ids need no seeding.
        // MAX_ITEMS is now 20000 and counts ITEMS (stockId, buyDate pairs) — use distinct
        // buyDates on the same synthetic id to build the oversized list cheaply, without
        // needing MAX_ITEMS+1 distinct stock ids.
        List<BacktestItemRequestDto> overCap = new ArrayList<>();
        for (int i = 0; i <= StrategyBacktestService.MAX_ITEMS; i++) {
            overCap.add(item("BT800000", today.minusDays(i)));
        }
        assertEquals(StrategyBacktestService.MAX_ITEMS + 1, overCap.size());

        ResponseEntity<ErrorResponse> rejectedResponse =
                rest.postForEntity("/api/strategies/backtest", backtestRequest(overCap), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejectedResponse.getStatusCode());
        assertEquals("TOO_MANY_STOCKS", rejectedResponse.getBody().getCode());
    }

    /** 2000 items must remain legal now that the cap is 20000 — this is a plain sanity check that
     *  the old 2000-item ceiling did not silently survive as a second, lower bound. */
    @Test
    void items2000_isLegal_doesNotTriggerTooManyStocks() {
        LocalDate today = LocalDate.now();
        List<BacktestItemRequestDto> items = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            items.add(item("BT800001", today.minusDays(i)));
        }
        assertEquals(2000, items.size());
        seedStock("BT800001", "2000筆合法性測試", true);

        ResponseEntity<String> response =
                rest.postForEntity("/api/strategies/backtest", backtestRequest(items), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "2000 items must be legal; body: " + response.getBody());

        jdbc.update("DELETE FROM stock WHERE stock_id = 'BT800001'");
    }

    /** The cap must stay decoupled from strategy-scan's typed-list limit — re-aliasing them is
     *  exactly what caused the failure above (same spec section). */
    @Test
    void backtestCap_isNotScanStockIdsCap() {
        assertTrue(StrategyBacktestService.MAX_ITEMS > StrategyScanService.MAX_STOCK_IDS,
                "回測上限必須大於掃描的 stockIds 上限，否則全市場命中清單無法回測");
    }

    @Test
    void unknownStockId_returns400UnknownStockId_withUnknownIds() {
        LocalDate today = LocalDate.now();
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT_NOPE", today))), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("UNKNOWN_STOCK_ID", response.getBody().getCode());
        assertTrue(response.getBody().getUnknownIds().contains("BT_NOPE"));
    }

    @Test
    void sameStockId_sameBuyDate_twice_returns400DuplicateBacktestItem_withDuplicatedItems() {
        LocalDate today = LocalDate.now();
        seedStock("BT401", "重複組合測試", true);
        insertPriceRow("BT401", today, "50.00", "51.00", "49.00", "50.00", 1000);

        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT401", today), item("BT401", today))),
                ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("DUPLICATE_BACKTEST_ITEM", response.getBody().getCode());
        List<BacktestDuplicateItemDto> duplicatedItems = response.getBody().getDuplicatedItems();
        assertEquals(1, duplicatedItems.size());
        assertEquals("BT401", duplicatedItems.get(0).getStockId());
        assertEquals(today, duplicatedItems.get(0).getBuyDate());
    }

    @Test
    void sameStockId_differentBuyDates_doesNotTriggerDuplicateError() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate earlierBuyDate = today.minusDays(5);
        seedStock("BT409", "同代號不同買進日測試", true);
        insertPriceRow("BT409", earlierBuyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT409", earlierBuyDate.plusDays(1), "52.00", "53.00", "51.00", "52.00", 1000);
        insertPriceRow("BT409", today.minusDays(1), "54.00", "55.00", "53.00", "54.00", 1000);
        insertPriceRow("BT409", today, "56.00", "57.00", "55.00", "56.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT409", earlierBuyDate), item("BT409", today.minusDays(1)))),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals(2, root.get("items").size());
    }

    // ==================== 一檔多買進日 ====================

    @Test
    void sameStockId_twoDifferentBuyDates_returns200WithTwoIndependentItems() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate firstBuyDate = today.minusDays(6);
        LocalDate secondBuyDate = today.minusDays(3);
        seedStock("BT410", "一檔多買進日測試", true);
        // First buy-date window (firstBuyDate, secondBuyDate] area: max open right after firstBuyDate.
        insertPriceRow("BT410", firstBuyDate, "100.00", "101.00", "99.00", "100.00", 1000);
        insertPriceRow("BT410", firstBuyDate.plusDays(1), "130.00", "131.00", "129.00", "125.00", 1000);
        insertPriceRow("BT410", secondBuyDate, "120.00", "121.00", "119.00", "120.00", 1000);
        insertPriceRow("BT410", secondBuyDate.plusDays(1), "150.00", "151.00", "149.00", "145.00", 1000);
        insertPriceRow("BT410", today, "140.00", "141.00", "139.00", "140.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT410", firstBuyDate), item("BT410", secondBuyDate))),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals(2, root.get("items").size());

        JsonNode first = root.get("items").get(0);
        assertEquals("BT410", first.get("stockId").asText());
        assertEquals(firstBuyDate.toString(), first.get("buyDate").asText());
        assertEquals(0, new BigDecimal("100.00").compareTo(first.get("buyPrice").decimalValue()));

        JsonNode second = root.get("items").get(1);
        assertEquals("BT410", second.get("stockId").asText());
        assertEquals(secondBuyDate.toString(), second.get("buyDate").asText());
        assertEquals(0, new BigDecimal("120.00").compareTo(second.get("buyPrice").decimalValue()));

        // Each item must have its own independently-computed outcome.
        assertTrue(first.get("sellPrice").decimalValue().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(second.get("sellPrice").decimalValue().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void sameStockId_twoBuyDates_eachSellDateOnlyPicksFromItsOwnWindow() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate firstBuyDate = today.minusDays(6);
        LocalDate secondBuyDate = today.minusDays(3);
        seedStock("BT411", "獨立視窗測試", true);
        // A very high open right after firstBuyDate but BEFORE secondBuyDate — only the first
        // item's window (firstBuyDate, asOfDate] should ever see it; but since the query window is
        // shared, what matters is that the SECOND item's own buyDate boundary excludes it from
        // consideration as a "later" occurrence, and that the second item picks from strictly
        // after its OWN buy date.
        insertPriceRow("BT411", firstBuyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT411", firstBuyDate.plusDays(1), "999.00", "999.00", "998.00", "300.00", 1000);
        insertPriceRow("BT411", secondBuyDate, "60.00", "61.00", "59.00", "60.00", 1000);
        insertPriceRow("BT411", secondBuyDate.plusDays(1), "70.00", "71.00", "69.00", "70.00", 1000);
        insertPriceRow("BT411", today, "80.00", "81.00", "79.00", "80.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT411", firstBuyDate), item("BT411", secondBuyDate))),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        JsonNode root = objectMapper.readTree(response.getBody());

        JsonNode first = root.get("items").get(0);
        // First item's window (firstBuyDate, asOfDate] includes the 999.00 open the day after —
        // that is the max in its own window.
        assertEquals(0, new BigDecimal("999.00").compareTo(first.get("sellPrice").decimalValue()));
        assertEquals(firstBuyDate.plusDays(1).toString(), first.get("sellDate").asText());

        JsonNode second = root.get("items").get(1);
        // Second item's window is (secondBuyDate, asOfDate] — the 999.00 open is BEFORE
        // secondBuyDate and must never be a candidate for it; its max is 80.00 (today).
        assertEquals(0, new BigDecimal("80.00").compareTo(second.get("sellPrice").decimalValue()));
        assertEquals(today.toString(), second.get("sellDate").asText());
        assertNotEquals(0, new BigDecimal("999.00").compareTo(second.get("sellPrice").decimalValue()));
    }

    @Test
    void sameStockId_twoBuyDates_eachCountsOnceInTotals() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate firstBuyDate = today.minusDays(6);
        LocalDate secondBuyDate = today.minusDays(3);
        seedStock("BT412", "彙總各計一次測試", true);
        insertPriceRow("BT412", firstBuyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT412", firstBuyDate.plusDays(1), "60.00", "61.00", "59.00", "55.00", 1000);
        insertPriceRow("BT412", secondBuyDate, "62.00", "63.00", "61.00", "62.00", 1000);
        insertPriceRow("BT412", secondBuyDate.plusDays(1), "70.00", "71.00", "69.00", "65.00", 1000);
        insertPriceRow("BT412", today, "75.00", "76.00", "74.00", "70.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT412", firstBuyDate), item("BT412", secondBuyDate))),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());
        // totalCost = cost(50.00) + cost(62.00) = 50071 + 62088 = 112159
        // (buyFee(50.00)=floor(50000*0.001425)=71, buyFee(62.00)=floor(62000*0.001425)=88)
        assertEquals(0, new BigDecimal("112159").compareTo(root.get("totalCost").decimalValue()));
        assertEquals(2, root.get("backtestedCount").asInt());
    }

    @Test
    void sameStockId_multipleItems_orderPreservedAsSentInRequest() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate firstBuyDate = today.minusDays(6);
        LocalDate secondBuyDate = today.minusDays(3);
        seedStock("BT413", "多筆順序測試", true);
        insertPriceRow("BT413", firstBuyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT413", firstBuyDate.plusDays(1), "60.00", "61.00", "59.00", "55.00", 1000);
        insertPriceRow("BT413", secondBuyDate, "62.00", "63.00", "61.00", "62.00", 1000);
        insertPriceRow("BT413", secondBuyDate.plusDays(1), "70.00", "71.00", "69.00", "65.00", 1000);
        insertPriceRow("BT413", today, "75.00", "76.00", "74.00", "70.00", 1000);

        // Send the LATER buyDate first — the response must preserve that same order, not sort
        // by date.
        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT413", secondBuyDate), item("BT413", firstBuyDate))),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals(secondBuyDate.toString(), root.get("items").get(0).get("buyDate").asText());
        assertEquals(firstBuyDate.toString(), root.get("items").get(1).get("buyDate").asText());
    }

    @Test
    void responseItems_containBuyDateField_neverSignalDate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT414", "buyDate欄位測試", true);
        insertPriceRow("BT414", buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT414", today, "55.00", "56.00", "54.00", "55.00", 1000);

        JsonNode item = singleItemResult("BT414", buyDate);
        assertEquals(buyDate.toString(), item.get("buyDate").asText(), "response items must carry buyDate");
        assertTrue(item.get("signalDate") == null, "response items must never carry a signalDate field");
    }

    @Test
    void buyDateAfterToday_returns400InvalidBuyDate_withStockId() {
        LocalDate today = LocalDate.now();
        seedStock("BT402", "未來買進日測試", true);
        insertPriceRow("BT402", today, "50.00", "51.00", "49.00", "50.00", 1000);

        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT402", today.plusDays(1)))), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_BUY_DATE", response.getBody().getCode());
        assertEquals("BT402", response.getBody().getStockId());
    }

    @Test
    void delistedStock_backtestsNormally_notRejectedOrExcluded() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT403", "已下市股票", false);
        insertPriceRow("BT403", buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT403", today, "55.00", "56.00", "54.00", "55.00", 1000);

        JsonNode item = singleItemResult("BT403", buyDate);
        assertEquals(0, new BigDecimal("50.00").compareTo(item.get("buyPrice").decimalValue()));
        assertEquals(0, new BigDecimal("55.00").compareTo(item.get("sellPrice").decimalValue()));
    }

    @Test
    void backtest_writesNothing_rowCountsAndContentUnchangedAcrossAllThreeTables() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT404", "無寫入測試", true);
        insertPriceRow("BT404", buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT404", today, "55.00", "56.00", "54.00", "55.00", 1000);

        long stockCountBefore = countAll("stock");
        long priceCountBefore = countAll("stock_daily_price");
        long progressCountBefore = countAll("stock_sync_progress");

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT404", buyDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        assertEquals(stockCountBefore, countAll("stock"));
        assertEquals(priceCountBefore, countAll("stock_daily_price"));
        assertEquals(progressCountBefore, countAll("stock_sync_progress"));

        // Content check for the rows this test itself owns: still exactly what was seeded.
        BigDecimal closeAfter = jdbc.queryForObject(
                "SELECT close_price FROM stock_daily_price WHERE stock_id = 'BT404' AND trade_date = ?",
                BigDecimal.class, buyDate);
        assertEquals(0, new BigDecimal("50.00").compareTo(closeAfter));
    }

    @Test
    void backtest_ignoresSyncProgressLock_runningPriceBackfillDoesNotBlock_returns200Not409() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT405", "併發鎖無關測試", true);
        insertPriceRow("BT405", buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT405", today, "55.00", "56.00", "54.00", "55.00", 1000);

        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO stock_sync_progress "
                        + "(stock_id, job_type, status, target_start_date, target_end_date, last_synced_date, "
                        + "attempt_count, last_error, started_at, finished_at) "
                        + "VALUES ('BT405', 'PRICE_BACKFILL', 'RUNNING', ?, ?, NULL, 0, NULL, ?, NULL)",
                buyDate, today, now);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT405", buyDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "backtest must not be blocked by a running "
                + "PRICE_BACKFILL job; body: " + response.getBody());
    }

    // ==================== 效能 ====================

    @Test
    void queryCount_doesNotScaleWithNumberOfStocks() {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);

        List<BacktestItemRequestDto> fewItems = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String id = "BT5" + String.format("%02d", i);
            seedStock(id, "查詢次數少量", true);
            insertPriceRow(id, buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
            insertPriceRow(id, today, "55.00", "56.00", "54.00", "55.00", 1000);
            fewItems.add(item(id, buyDate));
        }

        queryCountInterceptor.reset(MAPPER_NAMESPACE);
        rest.postForEntity("/api/strategies/backtest", backtestRequest(fewItems), String.class);
        int fewCount = queryCountInterceptor.getCount();

        List<BacktestItemRequestDto> manyItems = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String id = "BT6" + String.format("%02d", i);
            seedStock(id, "查詢次數大量", true);
            insertPriceRow(id, buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
            insertPriceRow(id, today, "55.00", "56.00", "54.00", "55.00", 1000);
            manyItems.add(item(id, buyDate));
        }

        queryCountInterceptor.reset(MAPPER_NAMESPACE);
        rest.postForEntity("/api/strategies/backtest", backtestRequest(manyItems), String.class);
        int manyCount = queryCountInterceptor.getCount();

        assertEquals(fewCount, manyCount, "query count must not scale with number of stocks (5 vs 50)");
        assertTrue(fewCount > 0);

        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'BT5%' OR stock_id LIKE 'BT6%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'BT5%' OR stock_id LIKE 'BT6%'");
    }

    // ---------- 價格為 0 的日子不是行情 (specs/backend/strategy-backtest.md) ----------

    /**
     * Regression: 1,044 rows across 63 stocks carry a 0.00 close (a no-trade day recorded as zero).
     * A buy landing on one used to divide by zero in computeReturnPercent and 500 the whole
     * batch — observed live on 2026-09-12 from the 策略 page's 回測 button.
     */
    @Test
    void zeroClosePriceOnBuyDate_isUnbacktestable_returns200Not500() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(5);
        seedStock("BT6ZC", "買進日未成交", true);
        insertPriceRow("BT6ZC", buyDate, "0.00", "0.00", "0.00", "0.00", 0);
        insertPriceRow("BT6ZC", buyDate.plusDays(1), "120.00", "121.00", "119.00", "120.00", 1000);

        JsonNode item = singleItemResult("BT6ZC", buyDate);
        assertTrue(item.get("buyPrice").isNull(), "0 close is not a buy price");
        assertTrue(item.get("sellDate").isNull());
        assertTrue(item.get("sellPrice").isNull());
        assertTrue(item.get("returnPercent").isNull());
        assertTrue(item.get("profit").isNull());
    }

    @Test
    void zeroOpenPrice_isNotASellCandidate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(5);
        seedStock("BT6ZO", "最高開盤那天未成交", true);
        insertPriceRow("BT6ZO", buyDate, "90.00", "91.00", "89.00", "100.00", 1000);
        // The only "highest" open in the window is a 0 no-trade day — it must not win.
        insertPriceRow("BT6ZO", buyDate.plusDays(1), "0.00", "0.00", "0.00", "0.00", 0);
        insertPriceRow("BT6ZO", buyDate.plusDays(2), "105.00", "106.00", "104.00", "105.00", 1000);

        JsonNode item = singleItemResult("BT6ZO", buyDate);
        assertEquals(buyDate.plusDays(2).toString(), item.get("sellDate").asText());
        assertEquals(0, new BigDecimal("105.00").compareTo(item.get("sellPrice").decimalValue()));
    }

    @Test
    void allZeroOpensInWindow_isTreatedAsNoSellableTradingDay() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(5);
        seedStock("BT6ZW", "窗口內全未成交", true);
        insertPriceRow("BT6ZW", buyDate, "90.00", "91.00", "89.00", "100.00", 1000);
        insertPriceRow("BT6ZW", buyDate.plusDays(1), "0.00", "0.00", "0.00", "0.00", 0);
        insertPriceRow("BT6ZW", buyDate.plusDays(2), "0.00", "0.00", "0.00", "0.00", 0);

        JsonNode item = singleItemResult("BT6ZW", buyDate);
        assertEquals(0, new BigDecimal("100.00").compareTo(item.get("buyPrice").decimalValue()),
                "buyPrice stays — only the sell side is missing");
        assertTrue(item.get("sellDate").isNull());
        assertTrue(item.get("sellPrice").isNull());
        assertTrue(item.get("returnPercent").isNull());
        assertTrue(item.get("profit").isNull());
    }

    @Test
    void zeroPriceItems_stayInItemsButAreExcludedFromTotals() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(5);
        seedStock("BT6ZA", "正常可回測", true);
        seedBaseWindow("BT6ZA", buyDate, today);
        seedStock("BT6ZB", "買進日未成交", true);
        insertPriceRow("BT6ZB", buyDate, "0.00", "0.00", "0.00", "0.00", 0);
        insertPriceRow("BT6ZB", buyDate.plusDays(1), "120.00", "121.00", "119.00", "120.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT6ZA", buyDate), item("BT6ZB", buyDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        JsonNode root = objectMapper.readTree(response.getBody());

        assertEquals(2, root.get("items").size(), "the zero-price item must stay in items");
        assertEquals(1, root.get("backtestedCount").asInt());
        // Only BT6ZA's cost is counted: buyPrice 100.00 -> cost = 100000 + buyFee(142) = 100142.
        assertEquals(0, new BigDecimal("100142").compareTo(root.get("totalCost").decimalValue()));
    }

    // ==================== 以進場日買進（上漲支撐 D+2） ====================

    @Test
    void signalDateOnlyRequest_missingBuyDate_returns400InvalidBuyDate() {
        LocalDate today = LocalDate.now();
        seedStock("BT415", "只帶signalDate測試", true);
        insertPriceRow("BT415", today, "50.00", "51.00", "49.00", "50.00", 1000);

        Map<String, Object> rawItem = new LinkedHashMap<>();
        rawItem.put("stockId", "BT415");
        rawItem.put("signalDate", today.toString());
        // Deliberately no "buyDate" key at all — this request shape is what the OLD contract sent.

        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/backtest",
                rawBody(Arrays.asList(rawItem)), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_BUY_DATE", response.getBody().getCode());
        assertEquals("BT415", response.getBody().getStockId());
    }

    /**
     * D close 100 / D+1 close 102 / D+2 close 104 / D+3 open 110 — buyDate is D+2 (the day
     * RISING_SUPPORT's confirmation completes, specs/backend/strategy-scan.md), so the buy price
     * must be D+2's close (104), never D's close (100), and the sell window must start at D+3, not
     * at D+1 (specs/backend/strategy-backtest.md, "以進場日買進（上漲支撐 D+2）").
     */
    @Test
    void buyAnchoredOnBuyDate_notEarlierSignalDay_risingSupportD2Fixture() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3); // D+2
        LocalDate d = buyDate.minusDays(2);
        LocalDate d1 = buyDate.minusDays(1);
        LocalDate d3 = buyDate.plusDays(1);
        seedStock("BT420", "上漲支撐D2買進測試", true);
        insertPriceRow("BT420", d, "99.00", "101.00", "98.00", "100.00", 1000);
        insertPriceRow("BT420", d1, "101.00", "103.00", "100.00", "102.00", 1000);
        insertPriceRow("BT420", buyDate, "90.00", "105.00", "89.00", "104.00", 1000);
        insertPriceRow("BT420", d3, "110.00", "112.00", "109.00", "111.00", 1000);
        insertPriceRow("BT420", today, "95.00", "96.00", "94.00", "95.00", 1000);

        JsonNode item = singleItemResult("BT420", buyDate);
        assertEquals(0, new BigDecimal("104.00").compareTo(item.get("buyPrice").decimalValue()),
                "buyPrice must be the buyDate (D+2) close (104), not D's close (100)");
        LocalDate sellDate = LocalDate.parse(item.get("sellDate").asText());
        assertFalse(sellDate.isBefore(d3), "sellDate must not be earlier than D+3");
        assertEquals(d3, sellDate);
        assertEquals(0, new BigDecimal("110.00").compareTo(item.get("sellPrice").decimalValue()));
    }

    @Test
    void duplicateBuyDate_ignoresOriginatingStrategyOrSignalDate() {
        LocalDate today = LocalDate.now();
        seedStock("BT421", "重複買進日無關策略測試", true);
        insertPriceRow("BT421", today, "50.00", "51.00", "49.00", "50.00", 1000);

        // Same (stockId, buyDate) reported by two different (hypothetical) origins — one carrying
        // a RISING_SUPPORT-style signalDate two days earlier, the other a same-day signal from a
        // different pattern. Neither field exists on the request DTO; both must be ignored and the
        // pair must still collide as ONE duplicate.
        Map<String, Object> fromRisingSupport = new LinkedHashMap<>();
        fromRisingSupport.put("stockId", "BT421");
        fromRisingSupport.put("buyDate", today.toString());
        fromRisingSupport.put("signalDate", today.minusDays(2).toString());
        fromRisingSupport.put("strategyCode", "RISING_SUPPORT");

        Map<String, Object> fromRebound = new LinkedHashMap<>();
        fromRebound.put("stockId", "BT421");
        fromRebound.put("buyDate", today.toString());
        fromRebound.put("signalDate", today.toString());
        fromRebound.put("strategyCode", "REBOUND");

        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/backtest",
                rawBody(Arrays.asList(fromRisingSupport, fromRebound)), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("DUPLICATE_BACKTEST_ITEM", response.getBody().getCode());
        List<BacktestDuplicateItemDto> duplicatedItems = response.getBody().getDuplicatedItems();
        assertEquals(1, duplicatedItems.size());
        assertEquals("BT421", duplicatedItems.get(0).getStockId());
        assertEquals(today, duplicatedItems.get(0).getBuyDate());
    }

    @Test
    void sameStockAndBuyDate_identicalResponse_regardlessOfOriginatingStrategyOrSignalDate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT422", "來源無關回應一致測試", true);
        insertPriceRow("BT422", buyDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT422", buyDate.plusDays(1), "60.00", "61.00", "59.00", "55.00", 1000);
        insertPriceRow("BT422", today, "58.00", "59.00", "57.00", "58.00", 1000);

        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("stockId", "BT422");
        plain.put("buyDate", buyDate.toString());

        Map<String, Object> withOrigin = new LinkedHashMap<>();
        withOrigin.put("stockId", "BT422");
        withOrigin.put("buyDate", buyDate.toString());
        withOrigin.put("signalDate", buyDate.minusDays(2).toString());
        withOrigin.put("strategyCode", "RISING_SUPPORT");

        ResponseEntity<String> responseA = rest.postForEntity("/api/strategies/backtest",
                rawBody(Arrays.asList(plain)), String.class);
        ResponseEntity<String> responseB = rest.postForEntity("/api/strategies/backtest",
                rawBody(Arrays.asList(withOrigin)), String.class);

        assertEquals(HttpStatus.OK, responseA.getStatusCode(), "body: " + responseA.getBody());
        assertEquals(HttpStatus.OK, responseB.getStatusCode(), "body: " + responseB.getBody());
        JsonNode itemA = objectMapper.readTree(responseA.getBody()).get("items").get(0);
        JsonNode itemB = objectMapper.readTree(responseB.getBody()).get("items").get(0);
        assertEquals(itemA, itemB,
                "identical (stockId, buyDate) must produce an identical item regardless of origin");
    }

    // ==================== 交易成本 ====================

    @Test
    void tradingCost_buy1150Sell1205_matchesSpecExactNumbers() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT501", "交易成本1150→1205", true);
        seedSellWindow("BT501", buyDate, today, "1150.00", "1205.00");

        JsonNode item = singleItemResult("BT501", buyDate);
        assertEquals(0, new BigDecimal("1638").compareTo(item.get("buyFee").decimalValue()));
        assertEquals(0, new BigDecimal("1717").compareTo(item.get("sellFee").decimalValue()));
        assertEquals(0, new BigDecimal("3615").compareTo(item.get("sellTax").decimalValue()));
        assertEquals(0, new BigDecimal("1151638").compareTo(item.get("cost").decimalValue()));
        assertEquals(0, new BigDecimal("48030").compareTo(item.get("profit").decimalValue()));
        assertEquals(0, new BigDecimal("4.17").compareTo(item.get("returnPercent").decimalValue()));
    }

    @Test
    void tradingCost_fractionalYuan_flooredNotRounded_buy2155Sell2310() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT502", "尾數無條件捨去", true);
        seedSellWindow("BT502", buyDate, today, "21.55", "23.10");

        JsonNode item = singleItemResult("BT502", buyDate);
        // buyFee = floor(21550*0.001425) = floor(30.70875) = 30 (not rounded to 31)
        assertEquals(0, new BigDecimal("30").compareTo(item.get("buyFee").decimalValue()));
        // sellFee = floor(23100*0.001425) = floor(32.9175) = 32
        assertEquals(0, new BigDecimal("32").compareTo(item.get("sellFee").decimalValue()));
        // sellTax = floor(23100*0.003) = floor(69.3) = 69
        assertEquals(0, new BigDecimal("69").compareTo(item.get("sellTax").decimalValue()));
        assertEquals(0, new BigDecimal("21580").compareTo(item.get("cost").decimalValue()));
        assertEquals(0, new BigDecimal("1419").compareTo(item.get("profit").decimalValue()));
        assertEquals(0, new BigDecimal("6.58").compareTo(item.get("returnPercent").decimalValue()));
    }

    /**
     * Regression the DBA rule for this spec warns about explicitly: {@code 200.00 × 1000 × 0.1425%}
     * is exactly {@code 285}; a binary-float route to that number can land on {@code 284.999...}
     * and floor to {@code 284} instead. This asserts the exact decimal result.
     */
    @Test
    void tradingCost_exactDecimalArithmetic_buySell200_feeIsExactly285NotDouble284() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT503", "精確十進位測試", true);
        seedSellWindow("BT503", buyDate, today, "200.00", "200.00");

        JsonNode item = singleItemResult("BT503", buyDate);
        assertEquals(0, new BigDecimal("285").compareTo(item.get("buyFee").decimalValue()),
                "200.00 * 1000 * 0.1425% must be exactly 285, never 284");
        assertEquals(0, new BigDecimal("285").compareTo(item.get("sellFee").decimalValue()));
        assertEquals(0, new BigDecimal("600").compareTo(item.get("sellTax").decimalValue()));
        assertEquals(0, new BigDecimal("-1170").compareTo(item.get("profit").decimalValue()));
    }

    @Test
    void tradingCost_noMinimumFee_buySell10_feeNotPaddedTo20() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT504", "無最低手續費測試", true);
        seedSellWindow("BT504", buyDate, today, "10.00", "10.00");

        JsonNode item = singleItemResult("BT504", buyDate);
        assertEquals(0, new BigDecimal("14").compareTo(item.get("buyFee").decimalValue()),
                "fee below 20 yuan must not be padded up to a minimum");
        assertEquals(0, new BigDecimal("14").compareTo(item.get("sellFee").decimalValue()));
        assertEquals(0, new BigDecimal("-58").compareTo(item.get("profit").decimalValue()));
        assertEquals(0, new BigDecimal("-0.58").compareTo(item.get("returnPercent").decimalValue()));
    }

    @Test
    void tradingCost_sellPriceEqualsBuyPrice_profitIsNegativeNotZero() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT505", "平盤出場測試", true);
        seedSellWindow("BT505", buyDate, today, "1150.00", "1150.00");

        JsonNode item = singleItemResult("BT505", buyDate);
        assertEquals(0, new BigDecimal("-6726").compareTo(item.get("profit").decimalValue()),
                "a flat exit still pays two fees and one tax, so profit must be negative, not 0");
        assertEquals(0, new BigDecimal("-0.58").compareTo(item.get("returnPercent").decimalValue()));
    }

    @Test
    void tradingCost_responseCarriesFeeAndTaxRates_andEveryItemCarriesFeeFields() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT506", "費率回傳測試", true);
        seedSellWindow("BT506", buyDate, today, "100.00", "105.00");

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT506", buyDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals(0, new BigDecimal("0.1425").compareTo(root.get("feeRatePercent").decimalValue()));
        assertEquals(0, new BigDecimal("0.3").compareTo(root.get("taxRatePercent").decimalValue()));

        JsonNode item = root.get("items").get(0);
        assertFalse(item.get("buyFee").isNull());
        assertFalse(item.get("sellFee").isNull());
        assertFalse(item.get("sellTax").isNull());
        assertFalse(item.get("cost").isNull());
    }

    @Test
    void tradingCost_noSellDay_buyFeeAndCostPresent_sellFieldsNull_costExcludedFromTotal() throws Exception {
        LocalDate today = LocalDate.now();
        seedStock("BT507", "無可賣出日費用測試", true);
        insertPriceRow("BT507", today, "88.00", "89.00", "87.00", "88.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT507", today))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode item = root.get("items").get(0);

        // buyFee = floor(88000*0.001425) = floor(125.4) = 125; cost = 88000+125 = 88125.
        assertEquals(0, new BigDecimal("125").compareTo(item.get("buyFee").decimalValue()));
        assertEquals(0, new BigDecimal("88125").compareTo(item.get("cost").decimalValue()));
        assertTrue(item.get("sellFee").isNull());
        assertTrue(item.get("sellTax").isNull());
        assertTrue(item.get("profit").isNull());
        assertTrue(item.get("returnPercent").isNull());
        // This item has no sellDate, so its cost must not be counted in totalCost.
        assertEquals(0, BigDecimal.ZERO.compareTo(root.get("totalCost").decimalValue()));
        assertEquals(0, root.get("backtestedCount").asInt());
    }

    @Test
    void tradingCost_buyPriceNull_allFeeFieldsNull() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT508", "買進價為null費用測試", true);
        // No price rows at all for BT508 -> buyPrice stays null.

        JsonNode item = singleItemResult("BT508", buyDate);
        assertTrue(item.get("buyPrice").isNull());
        assertTrue(item.get("buyFee").isNull());
        assertTrue(item.get("sellFee").isNull());
        assertTrue(item.get("sellTax").isNull());
        assertTrue(item.get("cost").isNull());
    }

    @Test
    void tradingCost_twoItems_totalCostTotalProfitTotalReturnPercent_matchSpecExactNumbers() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate buyDate = today.minusDays(3);
        seedStock("BT509", "交易成本彙總A", true);
        seedStock("BT510", "交易成本彙總B", true);
        seedSellWindow("BT509", buyDate, today, "1150.00", "1205.00");
        seedSellWindow("BT510", buyDate, today, "1180.00", "1210.00");

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT509", buyDate), item("BT510", buyDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals(0, new BigDecimal("2333319").compareTo(root.get("totalCost").decimalValue()));
        assertEquals(0, new BigDecimal("70995").compareTo(root.get("totalProfit").decimalValue()));
        assertEquals(0, new BigDecimal("3.04").compareTo(root.get("totalReturnPercent").decimalValue()));
    }

    // ==================== helpers ====================

    /** Buy-day close + one later day whose open is the (only) sell candidate — the minimal fixture
     *  the 交易成本 tests need: a buy price and exactly one sell price, nothing else contending for
     *  "highest open". */
    private void seedSellWindow(String stockId, LocalDate buyDate, LocalDate today, String buyClose,
                                 String sellOpen) {
        insertPriceRow(stockId, buyDate, buyClose, buyClose, buyClose, buyClose, 1000);
        insertPriceRow(stockId, buyDate.plusDays(1), sellOpen, sellOpen, sellOpen, sellOpen, 1000);
        if (buyDate.plusDays(1).isBefore(today)) {
            // Keep every later day's open below sellOpen so it never wins the max.
            insertPriceRow(stockId, today, "0.01", "0.01", "0.01", "0.01", 1000);
        }
    }

    /** 5 close-only opening bars: buy day close=100.00; +1d open=105, +2d open=110 (max),
     *  +3d open=95, today open=98. Used by the plain-vanilla buy/sell/return/profit tests. */
    private void seedBaseWindow(String stockId, LocalDate buyDate, LocalDate today) {
        insertPriceRow(stockId, buyDate, "90.00", "91.00", "89.00", "100.00", 1000);
        insertPriceRow(stockId, buyDate.plusDays(1), "105.00", "106.00", "104.00", "105.00", 1000);
        insertPriceRow(stockId, buyDate.plusDays(2), "110.00", "111.00", "109.00", "110.00", 1000);
        insertPriceRow(stockId, buyDate.plusDays(3), "95.00", "96.00", "94.00", "95.00", 1000);
        insertPriceRow(stockId, today, "98.00", "99.00", "97.00", "98.00", 1000);
    }

    private JsonNode singleItemResult(String stockId, LocalDate buyDate) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item(stockId, buyDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        JsonNode root = objectMapper.readTree(response.getBody());
        return root.get("items").get(0);
    }

    private BacktestItemRequestDto item(String stockId, LocalDate buyDate) {
        BacktestItemRequestDto dto = new BacktestItemRequestDto();
        dto.setStockId(stockId);
        dto.setBuyDate(buyDate);
        return dto;
    }

    /**
     * A raw, untyped request body — used only by the "以進場日買進" tests that must send fields
     * (`signalDate`, `strategyCode`) which {@link BacktestItemRequestDto} deliberately has no
     * property for, to prove the endpoint ignores them rather than 400ing on an unknown field.
     */
    private Map<String, Object> rawBody(List<Map<String, Object>> rawItems) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rawItems);
        return body;
    }

    private BacktestRequestDto backtestRequest(List<BacktestItemRequestDto> items) {
        BacktestRequestDto request = new BacktestRequestDto();
        request.setItems(items);
        return request;
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

    private long countAll(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
