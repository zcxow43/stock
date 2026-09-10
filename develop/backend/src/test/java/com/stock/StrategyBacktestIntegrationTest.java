package com.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void buyPrice_equalsClosePriceAtSignalDate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(5);
        seedStock("BT001", "買進價測試", true);
        seedBaseWindow("BT001", signalDate, today);

        JsonNode item = singleItemResult("BT001", signalDate);
        assertEquals(0, new BigDecimal("100.00").compareTo(item.get("buyPrice").decimalValue()));
    }

    @Test
    void sellPrice_isMaxOpenInWindow_sellDateIsThatTradeDate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(5);
        seedStock("BT002", "賣出價測試", true);
        seedBaseWindow("BT002", signalDate, today);

        JsonNode item = singleItemResult("BT002", signalDate);
        assertEquals(0, new BigDecimal("110.00").compareTo(item.get("sellPrice").decimalValue()));
        assertEquals(signalDate.plusDays(2).toString(), item.get("sellDate").asText());
    }

    @Test
    void signalDayOwnOpen_notIncludedInSellWindow() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(5);
        seedStock("BT003", "訊號日開盤排除測試", true);
        // Signal day's own open (200.00) is far higher than every later open — must never be picked.
        insertPriceRow("BT003", signalDate, "200.00", "205.00", "95.00", "50.00", 1000);
        insertPriceRow("BT003", signalDate.plusDays(1), "60.00", "61.00", "59.00", "60.00", 1000);
        insertPriceRow("BT003", signalDate.plusDays(2), "55.00", "56.00", "54.00", "55.00", 1000);
        insertPriceRow("BT003", signalDate.plusDays(3), "58.00", "59.00", "57.00", "58.00", 1000);
        insertPriceRow("BT003", today, "59.00", "60.00", "58.00", "59.00", 1000);

        JsonNode item = singleItemResult("BT003", signalDate);
        assertNotEquals(signalDate.toString(), item.get("sellDate").asText());
        assertEquals(0, new BigDecimal("60.00").compareTo(item.get("sellPrice").decimalValue()));
        assertNotEquals(0, new BigDecimal("200.00").compareTo(item.get("sellPrice").decimalValue()));
    }

    @Test
    void tiedMaxOpen_resolvesToEarliestTradeDate() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(5);
        seedStock("BT004", "同值取最早測試", true);
        insertPriceRow("BT004", signalDate, "90.00", "91.00", "89.00", "100.00", 1000);
        insertPriceRow("BT004", signalDate.plusDays(1), "105.00", "106.00", "104.00", "105.00", 1000);
        // Two ties at 110.00 — the earlier one (day+2) must win, not day+4.
        insertPriceRow("BT004", signalDate.plusDays(2), "110.00", "111.00", "109.00", "110.00", 1000);
        insertPriceRow("BT004", signalDate.plusDays(3), "95.00", "96.00", "94.00", "95.00", 1000);
        insertPriceRow("BT004", today, "110.00", "111.00", "109.00", "110.00", 1000);

        JsonNode item = singleItemResult("BT004", signalDate);
        assertEquals(signalDate.plusDays(2).toString(), item.get("sellDate").asText());
        assertEquals(0, new BigDecimal("110.00").compareTo(item.get("sellPrice").decimalValue()));
    }

    @Test
    void returnPercent_matchesFormula_roundedToTwoDecimals() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(5);
        seedStock("BT005", "報酬率測試", true);
        seedBaseWindow("BT005", signalDate, today);

        JsonNode item = singleItemResult("BT005", signalDate);
        // (110.00 - 100.00) / 100.00 * 100 = 10.00
        assertEquals(0, new BigDecimal("10.00").compareTo(item.get("returnPercent").decimalValue()));
    }

    @Test
    void profit_matchesFormula_roundedToYuan_andLotSizeIs1000() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(5);
        seedStock("BT006", "收益測試", true);
        seedBaseWindow("BT006", signalDate, today);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT006", signalDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals(1000, root.get("lotSize").asInt());
        JsonNode item = root.get("items").get(0);
        // (110.00 - 100.00) * 1000 = 10000
        assertEquals(0, new BigDecimal("10000").compareTo(item.get("profit").decimalValue()));
    }

    @Test
    void declineAfterSignal_returnPercentAndProfitAreNegative_notClampedOrRedirected() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(5);
        seedStock("BT007", "負報酬測試", true);
        insertPriceRow("BT007", signalDate, "50.00", "51.00", "49.00", "100.00", 1000);
        insertPriceRow("BT007", signalDate.plusDays(1), "90.00", "91.00", "89.00", "89.00", 1000);
        insertPriceRow("BT007", signalDate.plusDays(2), "85.00", "86.00", "84.00", "84.00", 1000);
        insertPriceRow("BT007", signalDate.plusDays(3), "80.00", "81.00", "79.00", "79.00", 1000);
        insertPriceRow("BT007", today, "70.00", "71.00", "69.00", "69.00", 1000);

        JsonNode item = singleItemResult("BT007", signalDate);
        // Highest open after signal day is 90.00 (day+1); buy was 100.00 -> -10.00% / -10000
        assertEquals(0, new BigDecimal("90.00").compareTo(item.get("sellPrice").decimalValue()));
        assertEquals(0, new BigDecimal("-10.00").compareTo(item.get("returnPercent").decimalValue()));
        assertEquals(0, new BigDecimal("-10000").compareTo(item.get("profit").decimalValue()));
    }

    @Test
    void suspensionGap_neverBecomesSellDate_maxIsComputedFromExistingRowsOnly() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(6);
        seedStock("BT008", "停牌缺列測試", true);
        insertPriceRow("BT008", signalDate, "90.00", "91.00", "89.00", "100.00", 1000);
        insertPriceRow("BT008", signalDate.plusDays(1), "80.00", "81.00", "79.00", "80.00", 1000);
        // signalDate.plusDays(2) intentionally has no row at all — a suspended trading day.
        insertPriceRow("BT008", signalDate.plusDays(3), "130.00", "131.00", "129.00", "130.00", 1000);
        // signalDate.plusDays(4) also missing.
        insertPriceRow("BT008", today, "90.00", "91.00", "89.00", "90.00", 1000);

        JsonNode item = singleItemResult("BT008", signalDate);
        assertEquals(signalDate.plusDays(3).toString(), item.get("sellDate").asText());
        assertEquals(0, new BigDecimal("130.00").compareTo(item.get("sellPrice").decimalValue()));
    }

    // ==================== 彙總 ====================

    @Test
    void totals_costWeighted_notArithmeticMean_ofTwoWidelyDifferingPricedStocks() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT101", "高價股", true);
        seedStock("BT102", "低價股", true);
        // BT101: buy 1000.00 -> sell 1200.00 (return 20.00%, profit 200000)
        insertPriceRow("BT101", signalDate, "990.00", "1000.00", "980.00", "1000.00", 1000);
        insertPriceRow("BT101", signalDate.plusDays(1), "1200.00", "1210.00", "1190.00", "1200.00", 1000);
        insertPriceRow("BT101", today, "1100.00", "1110.00", "1090.00", "1100.00", 1000);
        // BT102: buy 20.00 -> sell 20.20 (return 1.00%, profit 200)
        insertPriceRow("BT102", signalDate, "19.80", "20.00", "19.70", "20.00", 1000);
        insertPriceRow("BT102", signalDate.plusDays(1), "20.20", "20.30", "20.10", "20.20", 1000);
        insertPriceRow("BT102", today, "20.10", "20.15", "20.05", "20.10", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT101", signalDate), item("BT102", signalDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = objectMapper.readTree(response.getBody());

        assertEquals(0, new BigDecimal("1020000").compareTo(root.get("totalCost").decimalValue()));
        assertEquals(0, new BigDecimal("200200").compareTo(root.get("totalProfit").decimalValue()));
        BigDecimal totalReturnPercent = root.get("totalReturnPercent").decimalValue();
        assertEquals(0, new BigDecimal("19.63").compareTo(totalReturnPercent));

        // Arithmetic mean of the two per-stock returns (20.00 and 1.00) is 10.50 — the weighted
        // total must not equal it.
        BigDecimal arithmeticMean = new BigDecimal("10.50");
        assertNotEquals(0, arithmeticMean.compareTo(totalReturnPercent));
        assertEquals(2, root.get("backtestedCount").asInt());
    }

    @Test
    void totalCost_equalsSumOfBuyPriceTimes1000_forBacktestableItemsOnly() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT103", "成本彙總A", true);
        seedStock("BT104", "成本彙總B", true);
        insertPriceRow("BT103", signalDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT103", today, "55.00", "56.00", "54.00", "55.00", 1000);
        insertPriceRow("BT104", signalDate, "60.00", "61.00", "59.00", "60.00", 1000);
        insertPriceRow("BT104", today, "65.00", "66.00", "64.00", "65.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT103", signalDate), item("BT104", signalDate))), String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        // 50*1000 + 60*1000
        assertEquals(0, new BigDecimal("110000").compareTo(root.get("totalCost").decimalValue()));
    }

    @Test
    void totalProfit_equalsSumOfProfit_forBacktestableItemsOnly() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT105", "收益彙總A", true);
        seedStock("BT106", "收益彙總B", true);
        insertPriceRow("BT105", signalDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT105", today, "55.00", "56.00", "54.00", "55.00", 1000);
        insertPriceRow("BT106", signalDate, "60.00", "61.00", "59.00", "60.00", 1000);
        insertPriceRow("BT106", today, "65.00", "66.00", "64.00", "65.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT105", signalDate), item("BT106", signalDate))), String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        // (55-50)*1000 + (65-60)*1000 = 5000 + 5000
        assertEquals(0, new BigDecimal("10000").compareTo(root.get("totalProfit").decimalValue()));
    }

    @Test
    void backtestedCount_countsOnlyItemsWithSellDate_lessThanItemsLengthWhenSomeUnbacktestable() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT107", "可回測", true);
        seedStock("BT108", "不可回測", true);
        insertPriceRow("BT107", signalDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT107", today, "55.00", "56.00", "54.00", "55.00", 1000);
        // BT108's only row is on today, and its "signal date" is today itself -> no day after it.
        insertPriceRow("BT108", today, "70.00", "71.00", "69.00", "70.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT107", signalDate), item("BT108", today))), String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals(1, root.get("backtestedCount").asInt());
        assertEquals(2, root.get("items").size());
        assertTrue(root.get("backtestedCount").asInt() < root.get("items").size());
    }

    // ==================== 無法回測的標的 ====================

    @Test
    void signalDayIsLatestTradeDate_buyPriceSet_restNull_returns200() throws Exception {
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
    void signalDayHasNoPriceRow_buyPriceAlsoNull_returns200() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT202", "無訊號日資料", true);
        // No price rows at all for BT202.

        JsonNode item = singleItemResult("BT202", signalDate);
        assertTrue(item.get("buyPrice").isNull());
        assertTrue(item.get("sellDate").isNull());
        assertTrue(item.get("sellPrice").isNull());
        assertTrue(item.get("returnPercent").isNull());
        assertTrue(item.get("profit").isNull());
    }

    @Test
    void unbacktestableItems_stillAppearInItems_notRemoved() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT203", "可回測", true);
        seedStock("BT204", "無後續交易日不可回測", true);
        insertPriceRow("BT203", signalDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT203", today, "55.00", "56.00", "54.00", "55.00", 1000);
        insertPriceRow("BT204", today, "70.00", "71.00", "69.00", "70.00", 1000);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT203", signalDate), item("BT204", today))), String.class);
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
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT205", "可回測", true);
        seedStock("BT206", "不可回測", true);
        insertPriceRow("BT205", signalDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT205", today, "55.00", "56.00", "54.00", "55.00", 1000);
        insertPriceRow("BT206", today, "999.00", "999.00", "999.00", "999.00", 1000);

        // Backtestable stock alone.
        ResponseEntity<String> aloneResponse = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT205", signalDate))), String.class);
        JsonNode aloneRoot = objectMapper.readTree(aloneResponse.getBody());
        BigDecimal aloneCost = aloneRoot.get("totalCost").decimalValue();

        // Combined with the unbacktestable stock.
        ResponseEntity<String> combinedResponse = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT205", signalDate), item("BT206", today))), String.class);
        JsonNode combinedRoot = objectMapper.readTree(combinedResponse.getBody());

        assertEquals(0, aloneCost.compareTo(combinedRoot.get("totalCost").decimalValue()));
    }

    @Test
    void allItemsUnbacktestable_totalsZero_totalReturnPercentNull_backtestedCountZero() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT207", "無後續交易日", true);
        seedStock("BT208", "無訊號日資料", true);
        insertPriceRow("BT207", today, "70.00", "71.00", "69.00", "70.00", 1000);
        // BT208 has no price rows at all.

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT207", today), item("BT208", signalDate))), String.class);
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
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT301", "順序C", true);
        seedStock("BT302", "順序A", true);
        seedStock("BT303", "順序B", true);
        for (String id : new String[]{"BT301", "BT302", "BT303"}) {
            insertPriceRow(id, signalDate, "50.00", "51.00", "49.00", "50.00", 1000);
            insertPriceRow(id, today, "55.00", "56.00", "54.00", "55.00", 1000);
        }

        List<BacktestItemRequestDto> requestItems =
                Arrays.asList(item("BT303", signalDate), item("BT301", signalDate), item("BT302", signalDate));
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
        List<BacktestItemRequestDto> overCap = new ArrayList<>();
        for (int i = 0; i <= StrategyBacktestService.MAX_ITEMS; i++) {
            overCap.add(item(String.format("BT8%05d", i), today));
        }
        assertEquals(StrategyBacktestService.MAX_ITEMS + 1, overCap.size());

        ResponseEntity<ErrorResponse> rejectedResponse =
                rest.postForEntity("/api/strategies/backtest", backtestRequest(overCap), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejectedResponse.getStatusCode());
        assertEquals("TOO_MANY_STOCKS", rejectedResponse.getBody().getCode());
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
    void duplicateStockId_returns400DuplicateStockId_withDuplicatedIds() {
        LocalDate today = LocalDate.now();
        seedStock("BT401", "重複代號測試", true);
        insertPriceRow("BT401", today, "50.00", "51.00", "49.00", "50.00", 1000);

        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT401", today), item("BT401", today.minusDays(1)))),
                ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("DUPLICATE_STOCK_ID", response.getBody().getCode());
        assertTrue(response.getBody().getDuplicatedIds().contains("BT401"));
    }

    @Test
    void signalDateAfterToday_returns400InvalidSignalDate_withStockId() {
        LocalDate today = LocalDate.now();
        seedStock("BT402", "未來訊號日測試", true);
        insertPriceRow("BT402", today, "50.00", "51.00", "49.00", "50.00", 1000);

        ResponseEntity<ErrorResponse> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT402", today.plusDays(1)))), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_SIGNAL_DATE", response.getBody().getCode());
        assertEquals("BT402", response.getBody().getStockId());
    }

    @Test
    void delistedStock_backtestsNormally_notRejectedOrExcluded() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT403", "已下市股票", false);
        insertPriceRow("BT403", signalDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT403", today, "55.00", "56.00", "54.00", "55.00", 1000);

        JsonNode item = singleItemResult("BT403", signalDate);
        assertEquals(0, new BigDecimal("50.00").compareTo(item.get("buyPrice").decimalValue()));
        assertEquals(0, new BigDecimal("55.00").compareTo(item.get("sellPrice").decimalValue()));
    }

    @Test
    void backtest_writesNothing_rowCountsAndContentUnchangedAcrossAllThreeTables() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT404", "無寫入測試", true);
        insertPriceRow("BT404", signalDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT404", today, "55.00", "56.00", "54.00", "55.00", 1000);

        long stockCountBefore = countAll("stock");
        long priceCountBefore = countAll("stock_daily_price");
        long progressCountBefore = countAll("stock_sync_progress");

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT404", signalDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        assertEquals(stockCountBefore, countAll("stock"));
        assertEquals(priceCountBefore, countAll("stock_daily_price"));
        assertEquals(progressCountBefore, countAll("stock_sync_progress"));

        // Content check for the rows this test itself owns: still exactly what was seeded.
        BigDecimal closeAfter = jdbc.queryForObject(
                "SELECT close_price FROM stock_daily_price WHERE stock_id = 'BT404' AND trade_date = ?",
                BigDecimal.class, signalDate);
        assertEquals(0, new BigDecimal("50.00").compareTo(closeAfter));
    }

    @Test
    void backtest_ignoresSyncProgressLock_runningPriceBackfillDoesNotBlock_returns200Not409() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(3);
        seedStock("BT405", "併發鎖無關測試", true);
        insertPriceRow("BT405", signalDate, "50.00", "51.00", "49.00", "50.00", 1000);
        insertPriceRow("BT405", today, "55.00", "56.00", "54.00", "55.00", 1000);

        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO stock_sync_progress "
                        + "(stock_id, job_type, status, target_start_date, target_end_date, last_synced_date, "
                        + "attempt_count, last_error, started_at, finished_at) "
                        + "VALUES ('BT405', 'PRICE_BACKFILL', 'RUNNING', ?, ?, NULL, 0, NULL, ?, NULL)",
                signalDate, today, now);

        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item("BT405", signalDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "backtest must not be blocked by a running "
                + "PRICE_BACKFILL job; body: " + response.getBody());
    }

    // ==================== 效能 ====================

    @Test
    void queryCount_doesNotScaleWithNumberOfStocks() {
        LocalDate today = LocalDate.now();
        LocalDate signalDate = today.minusDays(3);

        List<BacktestItemRequestDto> fewItems = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String id = "BT5" + String.format("%02d", i);
            seedStock(id, "查詢次數少量", true);
            insertPriceRow(id, signalDate, "50.00", "51.00", "49.00", "50.00", 1000);
            insertPriceRow(id, today, "55.00", "56.00", "54.00", "55.00", 1000);
            fewItems.add(item(id, signalDate));
        }

        queryCountInterceptor.reset(MAPPER_NAMESPACE);
        rest.postForEntity("/api/strategies/backtest", backtestRequest(fewItems), String.class);
        int fewCount = queryCountInterceptor.getCount();

        List<BacktestItemRequestDto> manyItems = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String id = "BT6" + String.format("%02d", i);
            seedStock(id, "查詢次數大量", true);
            insertPriceRow(id, signalDate, "50.00", "51.00", "49.00", "50.00", 1000);
            insertPriceRow(id, today, "55.00", "56.00", "54.00", "55.00", 1000);
            manyItems.add(item(id, signalDate));
        }

        queryCountInterceptor.reset(MAPPER_NAMESPACE);
        rest.postForEntity("/api/strategies/backtest", backtestRequest(manyItems), String.class);
        int manyCount = queryCountInterceptor.getCount();

        assertEquals(fewCount, manyCount, "query count must not scale with number of stocks (5 vs 50)");
        assertTrue(fewCount > 0);

        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'BT5%' OR stock_id LIKE 'BT6%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'BT5%' OR stock_id LIKE 'BT6%'");
    }

    // ==================== helpers ====================

    /** 5 close-only opening bars: signal day close=100.00; +1d open=105, +2d open=110 (max),
     *  +3d open=95, today open=98. Used by the plain-vanilla buy/sell/return/profit tests. */
    private void seedBaseWindow(String stockId, LocalDate signalDate, LocalDate today) {
        insertPriceRow(stockId, signalDate, "90.00", "91.00", "89.00", "100.00", 1000);
        insertPriceRow(stockId, signalDate.plusDays(1), "105.00", "106.00", "104.00", "105.00", 1000);
        insertPriceRow(stockId, signalDate.plusDays(2), "110.00", "111.00", "109.00", "110.00", 1000);
        insertPriceRow(stockId, signalDate.plusDays(3), "95.00", "96.00", "94.00", "95.00", 1000);
        insertPriceRow(stockId, today, "98.00", "99.00", "97.00", "98.00", 1000);
    }

    private JsonNode singleItemResult(String stockId, LocalDate signalDate) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/strategies/backtest",
                backtestRequest(Arrays.asList(item(stockId, signalDate))), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "body: " + response.getBody());
        JsonNode root = objectMapper.readTree(response.getBody());
        return root.get("items").get(0);
    }

    private BacktestItemRequestDto item(String stockId, LocalDate signalDate) {
        BacktestItemRequestDto dto = new BacktestItemRequestDto();
        dto.setStockId(stockId);
        dto.setSignalDate(signalDate);
        return dto;
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
