package com.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.domain.StockDailyIndicator;
import com.stock.dto.ErrorResponse;
import com.stock.dto.IndicatorRebuildRequest;
import com.stock.dto.IndicatorRebuildResponse;
import com.stock.dto.StatisticsItemDto;
import com.stock.dto.StatisticsResponseDto;
import com.stock.dto.StatisticsSeriesRowDto;
import com.stock.mapper.StockDailyIndicatorMapper;
import com.stock.service.JobRunningRegistry;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for POST /api/stocks/indicators/rebuild and GET /api/stocks/statistics —
 * specs/backend/stock-indicator-statistics.md. Uses "TI"-prefixed synthetic stock ids so this
 * class can run alongside the other integration test classes without interfering with them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockIndicatorStatisticsIntegrationTest {

    private static final String JOB_TYPE = "INDICATOR_REBUILD";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobRunningRegistry jobRunningRegistry;

    @Autowired
    private StockDailyIndicatorMapper indicatorMapper;

    private JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        waitUntilNotRunning(10_000);
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        jobRunningRegistry.finish(JOB_TYPE); // in case a test seeded a fake "running" state
        waitUntilNotRunning(15_000);
        cleanupTestData();
    }

    private void cleanupTestData() {
        jdbc.update("DELETE FROM stock_daily_indicator WHERE stock_id LIKE 'TI%'");
        jdbc.update("DELETE FROM stock_sync_progress WHERE stock_id LIKE 'TI%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'TI%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'TI%'");
    }

    // ==================== 1. Rebuild endpoint validation ====================

    @Test
    void rebuild_unknownStockId_rejectedWith400() {
        IndicatorRebuildRequest request = new IndicatorRebuildRequest();
        request.setStockIds(Arrays.asList("TI9999"));

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/indicators/rebuild", request, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("UNKNOWN_STOCK_ID", response.getBody().getCode());
        assertTrue(response.getBody().getUnknownIds().contains("TI9999"));
    }

    @Test
    void rebuild_invalidDateRange_rejectedWith400() {
        IndicatorRebuildRequest request = new IndicatorRebuildRequest();
        request.setStartDate(LocalDate.of(2026, 8, 10));
        request.setEndDate(LocalDate.of(2026, 8, 1));

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/indicators/rebuild", request, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_DATE_RANGE", response.getBody().getCode());
    }

    @Test
    void rebuild_jobAlreadyRunning_rejectedWith409() {
        assertTrue(jobRunningRegistry.tryStart(JOB_TYPE), "test setup: registry should have been free");

        IndicatorRebuildRequest request = new IndicatorRebuildRequest();
        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/indicators/rebuild", request, ErrorResponse.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("JOB_ALREADY_RUNNING", response.getBody().getCode());
    }

    // ==================== 2. Incremental vs full, warmup, gap handling ====================

    @Test
    void rebuild_incrementalStep_matchesSameDayComputedByFull() throws Exception {
        String stockId = "TI11";
        seedStock(stockId, "增量對照測試", true);
        LocalDate start = LocalDate.of(2026, 1, 1);
        int days = 40;
        List<LocalDate> dates = seedPriceSeries(stockId, start, days);
        LocalDate lastDate = dates.get(dates.size() - 1);

        // FULL rebuild over the whole available history (no startDate -> no warmup boundary)
        IndicatorRebuildRequest fullRequest = new IndicatorRebuildRequest();
        fullRequest.setStockIds(Arrays.asList(stockId));
        fullRequest.setMode("FULL");
        ResponseEntity<IndicatorRebuildResponse> fullResponse = rest.postForEntity(
                "/api/stocks/indicators/rebuild", fullRequest, IndicatorRebuildResponse.class);
        assertEquals(HttpStatus.ACCEPTED, fullResponse.getStatusCode());
        waitUntilProgressSettled(stockId, 10_000);
        assertEquals("DONE", progressStatus(stockId));

        StockDailyIndicator expected = indicatorMapper.findOne(stockId, lastDate, StockDailyIndicator.PARAM_KEY);
        assertNotNull(expected, "FULL rebuild must have produced a row for the last trading day");

        // Simulate "the last day hasn't been advanced yet": drop just that one row.
        jdbc.update("DELETE FROM stock_daily_indicator WHERE stock_id = ? AND trade_date = ?", stockId, lastDate);

        // INCREMENTAL rebuild must read the previous day's persisted state and advance exactly one step.
        IndicatorRebuildRequest incrementalRequest = new IndicatorRebuildRequest();
        incrementalRequest.setStockIds(Arrays.asList(stockId));
        incrementalRequest.setMode("INCREMENTAL");
        ResponseEntity<IndicatorRebuildResponse> incResponse = rest.postForEntity(
                "/api/stocks/indicators/rebuild", incrementalRequest, IndicatorRebuildResponse.class);
        assertEquals(HttpStatus.ACCEPTED, incResponse.getStatusCode());
        assertEquals("INCREMENTAL", incResponse.getBody().getComputeMode());
        waitUntilProgressSettled(stockId, 10_000);
        assertEquals("DONE", progressStatus(stockId));

        StockDailyIndicator actual = indicatorMapper.findOne(stockId, lastDate, StockDailyIndicator.PARAM_KEY);
        assertNotNull(actual, "INCREMENTAL rebuild must have re-produced the row for the last trading day");

        assertEquals(0, expected.getEmaFast().compareTo(actual.getEmaFast()));
        assertEquals(0, expected.getEmaSlow().compareTo(actual.getEmaSlow()));
        assertEquals(0, expected.getDif().compareTo(actual.getDif()));
        assertEquals(0, expected.getDea().compareTo(actual.getDea()));
        assertEquals(0, expected.getOsc().compareTo(actual.getOsc()));
        assertEquals(0, expected.getKValue().compareTo(actual.getKValue()));
        assertEquals(0, expected.getDValue().compareTo(actual.getDValue()));
        assertEquals(0, expected.getJValue().compareTo(actual.getJValue()));
    }

    @Test
    void rebuild_incrementalWithNoPreviousIndicatorRow_marksFailedInsteadOfSeedingDefault() throws Exception {
        String stockId = "TI12";
        seedStock(stockId, "缺前日狀態測試", true);
        seedPriceSeries(stockId, LocalDate.of(2026, 2, 1), 20);
        // No indicator rows exist at all for this stock -> INCREMENTAL has no previous state to read.

        IndicatorRebuildRequest request = new IndicatorRebuildRequest();
        request.setStockIds(Arrays.asList(stockId));
        request.setMode("INCREMENTAL");
        ResponseEntity<IndicatorRebuildResponse> response = rest.postForEntity(
                "/api/stocks/indicators/rebuild", request, IndicatorRebuildResponse.class);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

        waitUntilProgressSettled(stockId, 10_000);
        assertEquals("FAILED", progressStatus(stockId));

        String lastError = jdbc.queryForObject(
                "SELECT last_error FROM stock_sync_progress WHERE stock_id = ? AND job_type = ?",
                String.class, stockId, JOB_TYPE);
        assertTrue(lastError != null && lastError.contains("NEEDS_FULL_REBUILD"));

        Integer indicatorRowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_indicator WHERE stock_id = ?", Integer.class, stockId);
        assertEquals(0, indicatorRowCount, "must never seed a default (K=D=50) value to paper over the gap");
    }

    @Test
    void rebuild_warmupRows_areWrittenAsWarmupAndExcludedFromStatisticsSeries() throws Exception {
        String stockId = "TI13";
        seedStock(stockId, "暖身測試", true);
        LocalDate start = LocalDate.of(2026, 1, 1);
        List<LocalDate> dates = seedPriceSeries(stockId, start, 300); // 250 warmup + 50 output
        LocalDate outputStart = dates.get(250);
        LocalDate outputEnd = dates.get(dates.size() - 1);

        IndicatorRebuildRequest request = new IndicatorRebuildRequest();
        request.setStockIds(Arrays.asList(stockId));
        request.setStartDate(outputStart);
        request.setEndDate(outputEnd);
        request.setMode("FULL");
        ResponseEntity<IndicatorRebuildResponse> response = rest.postForEntity(
                "/api/stocks/indicators/rebuild", request, IndicatorRebuildResponse.class);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(250, response.getBody().getWarmupTradingDays());
        waitUntilProgressSettled(stockId, 15_000);
        assertEquals("DONE", progressStatus(stockId));

        Integer warmupRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_indicator WHERE stock_id = ? AND is_warmup = 1",
                Integer.class, stockId);
        assertEquals(250, warmupRows);
        Integer nonWarmupRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_indicator WHERE stock_id = ? AND is_warmup = 0",
                Integer.class, stockId);
        assertEquals(50, nonWarmupRows);

        StatisticsResponseDto stats = getStatistics(
                "stockIds=" + stockId + "&startDate=" + outputStart + "&endDate=" + outputEnd + "&includeSeries=true");
        StatisticsItemDto item = stats.getItems().get(0);
        assertEquals(50, item.getTradingDays());
        assertEquals(50, item.getSeries().size());
        for (StatisticsSeriesRowDto row : item.getSeries()) {
            assertFalse(row.getTradeDate().isBefore(outputStart), "warmup dates must never leak into the series");
            assertNotNull(row.getDif(), "non-warmup rows must have a computed indicator value");
        }
    }

    @Test
    void rebuild_insufficientHistory_stillReturnsIndicatorsButWarmupSufficientIsFalse() throws Exception {
        String stockId = "TI14";
        seedStock(stockId, "新股測試", true);
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<LocalDate> dates = seedPriceSeries(stockId, start, 30); // far fewer than 250 trading days

        IndicatorRebuildRequest request = new IndicatorRebuildRequest();
        request.setStockIds(Arrays.asList(stockId));
        request.setMode("FULL");
        rest.postForEntity("/api/stocks/indicators/rebuild", request, IndicatorRebuildResponse.class);
        waitUntilProgressSettled(stockId, 10_000);
        assertEquals("DONE", progressStatus(stockId));

        LocalDate first = dates.get(0);
        LocalDate last = dates.get(dates.size() - 1);
        StatisticsResponseDto stats = getStatistics(
                "stockIds=" + stockId + "&startDate=" + first + "&endDate=" + last);
        StatisticsItemDto item = stats.getItems().get(0);
        assertEquals(30, item.getTradingDays());
        assertNotNull(item.getSummary());
        assertNotNull(item.getSummary().getLatestDif(), "must still output indicators even though warmup is short");
        assertFalse(item.isWarmupSufficient());
    }

    // ==================== 3. Statistics query contract ====================

    @Test
    void statistics_defaultDateRange_isLatestTradeDateMinusTwoCalendarMonths() {
        String stockId = "TI15";
        seedStock(stockId, "預設區間測試", true);
        LocalDate start = LocalDate.of(2026, 1, 15);
        LocalDate end = LocalDate.of(2026, 3, 15);
        seedPriceRangeDaily(stockId, start, end);

        StatisticsResponseDto stats = getStatistics("stockIds=" + stockId);
        assertEquals("SELECTED", stats.getScope());
        assertEquals(end, stats.getEndDate());
        assertEquals(start, stats.getStartDate()); // end.minusMonths(2) == start exactly, both seeded dates
        assertEquals(1, stats.getStockCount());
        assertNotNull(stats.getItems().get(0).getSeries());
    }

    @Test
    void statistics_selectedScope_multipleStocks_returnsSeriesForEach() {
        seedStock("TI21", "多選一", true);
        seedStock("TI22", "多選二", true);
        LocalDate start = LocalDate.of(2026, 4, 1);
        LocalDate end = LocalDate.of(2026, 4, 10);
        seedPriceRangeDaily("TI21", start, end);
        seedPriceRangeDaily("TI22", start, end);

        StatisticsResponseDto stats = getStatistics("stockIds=TI21,TI22&startDate=" + start + "&endDate=" + end);
        assertEquals("SELECTED", stats.getScope());
        assertEquals(2, stats.getStockCount());
        for (StatisticsItemDto item : stats.getItems()) {
            assertNotNull(item.getSeries());
            assertFalse(item.getSeries().isEmpty());
        }
    }

    @Test
    void statistics_allScope_omitsSeriesFieldEntirely() throws Exception {
        seedStock("TI23", "全市場一", true);
        LocalDate start = LocalDate.of(2026, 4, 1);
        LocalDate end = LocalDate.of(2026, 4, 5);
        seedPriceRangeDaily("TI23", start, end);

        String url = "/api/stocks/statistics?startDate=" + start + "&endDate=" + end;
        ResponseEntity<String> raw = rest.getForEntity(url, String.class);
        assertEquals(HttpStatus.OK, raw.getStatusCode());

        JsonNode root = objectMapper.readTree(raw.getBody());
        assertEquals("ALL", root.get("scope").asText());
        boolean foundOurStock = false;
        for (JsonNode item : root.get("items")) {
            if ("TI23".equals(item.get("stockId").asText())) {
                foundOurStock = true;
                assertFalse(item.has("series"), "ALL scope items must not include a series field at all");
            }
        }
        assertTrue(foundOurStock);
    }

    @Test
    void statistics_allScopeWithIncludeSeriesTrue_rejectedWith400() {
        ResponseEntity<ErrorResponse> response = rest.getForEntity(
                "/api/stocks/statistics?includeSeries=true", ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("SERIES_NOT_ALLOWED_FOR_ALL_SCOPE", response.getBody().getCode());
    }

    @Test
    void statistics_moreThan50StockIds_rejectedWith400() {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            if (i > 0) ids.append(',');
            ids.append("TIX").append(String.format("%02d", i));
        }
        ResponseEntity<ErrorResponse> response = rest.getForEntity(
                "/api/stocks/statistics?stockIds=" + ids, ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("TOO_MANY_STOCK_IDS", response.getBody().getCode());
        assertEquals(50, response.getBody().getLimit());
    }

    @Test
    void statistics_noPriceDataInRange_returns200WithZeroTradingDaysAndNullSummary() {
        seedStock("TI16", "無行情測試", true);
        // no price rows seeded at all

        StatisticsResponseDto stats = getStatistics(
                "stockIds=TI16&startDate=2026-01-01&endDate=2026-01-31");
        StatisticsItemDto item = stats.getItems().get(0);
        assertEquals(0, item.getTradingDays());
        assertNull(item.getSummary());
        assertNotNull(item.getSeries());
        assertTrue(item.getSeries().isEmpty());
    }

    @Test
    void statistics_priceWithoutAnyIndicator_seriesHasOhlcButNullIndicators_summaryLatestAndCrossesNull() {
        String stockId = "TI17";
        seedStock(stockId, "尚未算指標測試", true);
        LocalDate start = LocalDate.of(2026, 5, 1);
        LocalDate end = LocalDate.of(2026, 5, 10);
        seedPriceRangeDaily(stockId, start, end);
        // deliberately no indicator rows -> LEFT JOIN must still surface the price rows

        StatisticsResponseDto stats = getStatistics(
                "stockIds=" + stockId + "&startDate=" + start + "&endDate=" + end + "&includeSeries=true");
        StatisticsItemDto item = stats.getItems().get(0);
        assertTrue(item.getTradingDays() > 0);
        assertFalse(item.getSeries().isEmpty(), "series must not be empty just because indicators aren't computed yet");
        for (StatisticsSeriesRowDto row : item.getSeries()) {
            assertNotNull(row.getOpen());
            assertNotNull(row.getHigh());
            assertNotNull(row.getLow());
            assertNotNull(row.getClose());
            assertNull(row.getDif());
            assertNull(row.getDea());
            assertNull(row.getOsc());
            assertNull(row.getK());
            assertNull(row.getD());
            assertNull(row.getJ());
        }
        assertNotNull(item.getSummary(), "price/volume summary must still be computed");
        assertNotNull(item.getSummary().getFirstOpen());
        assertNull(item.getSummary().getLatestDif());
        assertNull(item.getSummary().getLatestK());
        // the critical null-vs-zero distinction: "not computed yet" must never be reported as "0 crossings"
        assertNull(item.getSummary().getMacdGoldenCross());
        assertNull(item.getSummary().getMacdDeathCross());
        assertNull(item.getSummary().getKdGoldenCross());
        assertNull(item.getSummary().getKdDeathCross());
    }

    /**
     * Cross counting and "gap in the middle doesn't break subsequent comparisons" (spec's
     * "亦不中斷其後的比較" / AC "區間中間有數日缺指標列時...仍正常參與交叉判定"), verified against
     * hand-crafted, independently-known-correct osc/k/d sequences — deliberately decoupled from
     * IndicatorCalculationService's own formula (covered separately by
     * IndicatorCalculationServiceTest) so this test only exercises the statistics query's
     * cross-detection logic itself.
     */
    @Test
    void statistics_crossCounts_matchHandCraftedSequenceAndSkipTheGapWithoutBreakingLaterComparisons() {
        String stockId = "TI31";
        seedStock(stockId, "交叉測試", true);
        LocalDate start = LocalDate.of(2026, 3, 2);
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            dates.add(start.plusDays(i));
        }
        for (LocalDate d : dates) {
            insertPriceRow(stockId, d, "100.00", "105.00", "95.00", "100.00", 1000);
        }

        // index: 0    1    2    3(gap) 4    5    6    7    8    9    10   11
        double[] osc = {-1, -1, 1, /*gap*/ -1, -1, 1, 1, -1, -1, 1, 1};
        double[][] kd = {
                {40, 50}, {45, 50}, {60, 50}, /*gap*/
                {55, 60}, {50, 60}, {70, 60}, {75, 65}, {60, 68}, {58, 68}, {80, 70}, {82, 71}
        };
        int idx = 0;
        for (int i = 0; i < dates.size(); i++) {
            if (i == 3) {
                continue; // deliberately no indicator row for this trading day
            }
            double o = osc[idx];
            double k = kd[idx][0];
            double d = kd[idx][1];
            idx++;
            insertIndicatorRow(stockId, dates.get(i), o, k, d);
        }

        StatisticsResponseDto stats = getStatistics(
                "stockIds=" + stockId + "&startDate=" + dates.get(0) + "&endDate=" + dates.get(dates.size() - 1)
                        + "&includeSeries=true");
        StatisticsItemDto item = stats.getItems().get(0);
        assertEquals(12, item.getTradingDays());
        assertNull(item.getSeries().get(3).getDif(), "gap day must surface as null, not be skipped from the series");
        assertNotNull(item.getSeries().get(3).getOpen(), "price must still be present on the gap day");

        assertEquals(3, item.getSummary().getMacdGoldenCross());
        assertEquals(1, item.getSummary().getMacdDeathCross());
        assertEquals(3, item.getSummary().getKdGoldenCross());
        assertEquals(1, item.getSummary().getKdDeathCross());

        // latest* must reflect the last day (index 11), which does have an indicator row
        assertEquals(0, new BigDecimal("1.0000").compareTo(item.getSummary().getLatestOsc()));
        assertEquals(0, new BigDecimal("82.0000").compareTo(item.getSummary().getLatestK()));
        assertEquals(0, new BigDecimal("71.0000").compareTo(item.getSummary().getLatestD()));
    }

    @Test
    void statistics_seriesIndicatorValues_areRoundedToFourDecimalPlaces() {
        String stockId = "TI41";
        seedStock(stockId, "精度測試", true);
        LocalDate date = LocalDate.of(2026, 6, 1);
        insertPriceRow(stockId, date, "100.00", "105.00", "95.00", "100.00", 1000);
        indicatorMapper.upsertBatch(Arrays.asList(buildIndicatorRow(stockId, date,
                new BigDecimal("1.123456789"), new BigDecimal("60.123456789"), new BigDecimal("50.123456789"))));

        StatisticsResponseDto stats = getStatistics(
                "stockIds=" + stockId + "&startDate=" + date + "&endDate=" + date + "&includeSeries=true");
        StatisticsSeriesRowDto row = stats.getItems().get(0).getSeries().get(0);
        assertEquals(4, row.getDif().scale());
        assertEquals(4, row.getK().scale());
        assertEquals(4, row.getD().scale());
        // HALF_UP rounding of the raw 8-decimal stored value, not of some already-rounded intermediate.
        assertEquals(0, new BigDecimal("1.1235").compareTo(row.getDif()));
    }

    // ==================== helpers ====================

    private StatisticsResponseDto getStatistics(String query) {
        ResponseEntity<StatisticsResponseDto> response = rest.getForEntity(
                "/api/stocks/statistics?" + query, StatisticsResponseDto.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    private void seedStock(String stockId, String name, boolean active) {
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES (?, ?, 'TSE', ?)",
                stockId, name, active);
    }

    /** Deterministic pseudo-random OHLC walk, one row per calendar day (weekends included as trading days
     *  for simplicity — this test suite treats every seeded date as a trading day). */
    private List<LocalDate> seedPriceSeries(String stockId, LocalDate start, int count) {
        java.util.Random random = new java.util.Random(stockId.hashCode());
        List<LocalDate> dates = new ArrayList<>(count);
        BigDecimal price = new BigDecimal("100.00");
        LocalDate date = start;
        for (int i = 0; i < count; i++) {
            BigDecimal delta = BigDecimal.valueOf((random.nextDouble() - 0.48) * 4)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal open = price;
            BigDecimal close = open.add(delta);
            if (close.compareTo(new BigDecimal("1.00")) < 0) {
                close = new BigDecimal("1.00");
            }
            BigDecimal high = open.max(close).add(new BigDecimal("1.00"));
            BigDecimal low = open.min(close).subtract(new BigDecimal("1.00"));
            if (low.compareTo(new BigDecimal("0.50")) < 0) {
                low = new BigDecimal("0.50");
            }
            insertPriceRow(stockId, date, open.toPlainString(), high.toPlainString(), low.toPlainString(),
                    close.toPlainString(), 1000 + i);
            dates.add(date);
            price = close;
            date = date.plusDays(1);
        }
        return dates;
    }

    private void seedPriceRangeDaily(String stockId, LocalDate start, LocalDate end) {
        LocalDate date = start;
        int i = 0;
        while (!date.isAfter(end)) {
            BigDecimal base = new BigDecimal(100 + i);
            insertPriceRow(stockId, date, base.toPlainString(), base.add(BigDecimal.ONE).toPlainString(),
                    base.subtract(BigDecimal.ONE).toPlainString(), base.toPlainString(), 1000 + i);
            date = date.plusDays(1);
            i++;
        }
    }

    private void insertPriceRow(String stockId, LocalDate date, String open, String high, String low,
                                 String close, long volume) {
        jdbc.update("INSERT INTO stock_daily_price "
                        + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, "
                        + "turnover, transaction_count, source) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'TEST')",
                stockId, date, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), volume);
    }

    private void insertIndicatorRow(String stockId, LocalDate date, double osc, double k, double d) {
        BigDecimal oscBd = BigDecimal.valueOf(osc);
        BigDecimal kBd = BigDecimal.valueOf(k);
        BigDecimal dBd = BigDecimal.valueOf(d);
        indicatorMapper.upsertBatch(Arrays.asList(buildIndicatorRow(stockId, date, oscBd, kBd, dBd)));
    }

    private StockDailyIndicator buildIndicatorRow(String stockId, LocalDate date, BigDecimal osc, BigDecimal k,
                                                    BigDecimal d) {
        StockDailyIndicator row = new StockDailyIndicator();
        row.setStockId(stockId);
        row.setTradeDate(date);
        row.setParamKey(StockDailyIndicator.PARAM_KEY);
        row.setEmaFast(new BigDecimal("100.00000000"));
        row.setEmaSlow(new BigDecimal("100.00000000"));
        row.setDif(osc); // dif - dea = osc; dea fixed at 0 below so dif == osc keeps the identity trivially true
        row.setDea(BigDecimal.ZERO);
        row.setOsc(osc);
        row.setRsv(new BigDecimal("50.00000000"));
        row.setKValue(k);
        row.setDValue(d);
        row.setJValue(k.multiply(BigDecimal.valueOf(3)).subtract(d.multiply(BigDecimal.valueOf(2))));
        row.setWarmup(false);
        return row;
    }

    private String progressStatus(String stockId) {
        return jdbc.queryForObject(
                "SELECT status FROM stock_sync_progress WHERE stock_id = ? AND job_type = ?",
                String.class, stockId, JOB_TYPE);
    }

    private void waitUntilProgressSettled(String stockId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM stock_sync_progress WHERE stock_id = ? AND job_type = ?",
                    String.class, stockId, JOB_TYPE);
            if (status != null && !status.equals("PENDING") && !status.equals("RUNNING")) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + stockId + " indicator rebuild to settle");
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
