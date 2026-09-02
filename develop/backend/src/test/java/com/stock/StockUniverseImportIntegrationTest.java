package com.stock;

import com.stock.dto.CreateStockRequest;
import com.stock.dto.ErrorResponse;
import com.stock.dto.StockDetailDto;
import com.stock.dto.UniverseImportResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies specs/backend/stock-universe-import.md: POST /api/stocks/universe/import.
 *
 * <p>Every successful call now fires exactly two external requests in order — source 1
 * ({@code twseUrl}, the stock list) then source 2 ({@code industryUrl}, the industry profile) —
 * so every test that reaches the write path must declare both mock expectations in that order
 * ({@code mockServer.ignoreExpectOrder(false)}); a test that only declares source 1 and the
 * implementation still tries to call source 2 (or vice versa) fails loudly rather than silently.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockUniverseImportIntegrationTest {

    /** Every stock id this test class creates, for cleanup. stock_industry rows cascade-delete
     * with their stock (fk_si_stock ON DELETE CASCADE); industry rows are deliberately never
     * deleted here, matching specs/dba/industry.md's "本表不做刪除". */
    private static final String[] TEST_STOCK_IDS = {
            "9871", "9872", "9873", "9874", "9875", "9876", "9877", "9878",
            "9879", "9880", "9881", "9882", "9883", "9884", "9885", "9886"
    };

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("externalApiRestTemplate")
    private RestTemplate externalApiRestTemplate;

    @Autowired
    private DataSource dataSource;

    @Value("${app.external.twse-daily-all-url}")
    private String twseUrl;

    @Value("${app.external.twse-industry-url}")
    private String industryUrl;

    private MockRestServiceServer mockServer;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        mockServer = MockRestServiceServer.bindTo(externalApiRestTemplate).ignoreExpectOrder(false).build();
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    private void cleanupTestData() {
        StringBuilder ids = new StringBuilder();
        for (String id : TEST_STOCK_IDS) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append('\'').append(id).append('\'');
        }
        jdbc.update("DELETE FROM stock_industry WHERE stock_id IN (" + ids + ")");
        jdbc.update("DELETE FROM stock WHERE stock_id IN (" + ids + ")");
    }

    // ---------- Happy path: single external request per source, filtering, upsert semantics ----------

    @Test
    void import_singleRequest_filtersToOrdinaryShares_upsertsStockOnly_noPriceRowsWritten() {
        long priceCountBefore = countAll("stock_daily_price");
        long indicatorCountBefore = countAll("stock_daily_indicator");

        // 0050 / 2881A / 910322 are real, already-seeded rows in the live database (from the
        // real-market daily/master sync, which does NOT apply this endpoint's ordinary-share
        // filter). Capture their pre-existing names so we can assert they are left byte-for-byte
        // untouched by this import, rather than wrongly asserting their absence.
        String namesBefore = namesOf("0050", "2881A", "910322");

        expectSource1(jsonArray(
                stockDayRow("9871", "測試普通股一"),
                stockDayRow("0050", "這個名字不應被寫入"),
                stockDayRow("2881A", "這個名字不應被寫入"),
                stockDayRow("910322", "這個名字不應被寫入")));
        expectSource2("[]"); // industry source empty this run; irrelevant to this test's scope

        ResponseEntity<UniverseImportResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        UniverseImportResponse body = response.getBody();
        assertEquals(4, body.getFetchedCount());
        assertEquals(1, body.getEligibleCount());
        assertEquals(3, body.getSkippedCount());
        assertEquals(body.getFetchedCount() - body.getEligibleCount(), body.getSkippedCount());
        assertEquals(1, body.getInsertedCount());
        assertEquals(0, body.getUpdatedCount());

        mockServer.verify(); // exactly source1 then source2, no more

        // eligible ordinary share written
        String name = jdbc.queryForObject(
                "SELECT stock_name FROM stock WHERE stock_id = '9871'", String.class);
        assertEquals("測試普通股一", name);
        String market = jdbc.queryForObject(
                "SELECT market FROM stock WHERE stock_id = '9871'", String.class);
        assertEquals("TSE", market);

        // skipped codes' existing rows are completely untouched (not "absent" -- they are real
        // production rows written by the unrelated daily-sync path, which applies no such filter)
        assertEquals(namesBefore, namesOf("0050", "2881A", "910322"));

        Integer totalActiveFromDb = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock WHERE is_active = 1", Integer.class);
        assertEquals(totalActiveFromDb.intValue(), body.getTotalActiveCount());

        // no price/indicator rows written at all
        assertEquals(priceCountBefore, countAll("stock_daily_price"));
        assertEquals(indicatorCountBefore, countAll("stock_daily_indicator"));

        // no stock_sync_progress row created for this import
        Integer progressRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_sync_progress WHERE stock_id = '9871'", Integer.class);
        assertEquals(0, progressRows);

        // industry source reported empty; no industry writes happened
        assertEquals(UniverseImportResponse.INDUSTRY_STATUS_EMPTY, body.getIndustrySourceStatus());
    }

    // ---------- Re-running is idempotent: second run inserts 0, updates eligibleCount ----------

    @Test
    void import_runTwice_secondRun_insertsZero_updatesEqualsFirstEligibleCount_rowCountUnchanged() {
        String fixture = jsonArray(
                stockDayRow("9872", "測試重跑一"),
                stockDayRow("9873", "測試重跑二"));

        expectSource1(fixture);
        expectSource2("[]");
        ResponseEntity<UniverseImportResponse> first = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(2, first.getBody().getInsertedCount());
        assertEquals(0, first.getBody().getUpdatedCount());

        long stockCountAfterFirst = countAll("stock");

        mockServer.reset();
        expectSource1(fixture);
        expectSource2("[]");
        ResponseEntity<UniverseImportResponse> second = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertEquals(0, second.getBody().getInsertedCount());
        assertEquals(first.getBody().getEligibleCount(), second.getBody().getUpdatedCount());

        assertEquals(stockCountAfterFirst, countAll("stock"));
    }

    // ---------- is_active never revived by import; never touched on update ----------

    @Test
    void import_existingDeactivatedStock_staysDeactivated_afterImport() {
        // Seed as active, then deactivate via the real catalog endpoint (spec's own deactivation
        // path), matching the acceptance criterion's exact scenario.
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES "
                + "('9871', '停用前名稱', 'TSE', 1) "
                + "ON DUPLICATE KEY UPDATE stock_name = VALUES(stock_name), is_active = 1");
        ResponseEntity<Void> deleteResponse = rest.exchange(
                "/api/stocks/9871", HttpMethod.DELETE, null, Void.class);
        assertEquals(HttpStatus.OK, deleteResponse.getStatusCode());

        Boolean activeBefore = jdbc.queryForObject(
                "SELECT is_active FROM stock WHERE stock_id = '9871'", Boolean.class);
        assertEquals(Boolean.FALSE, activeBefore);

        expectSource1(jsonArray(stockDayRow("9871", "匯入後最新名稱")));
        expectSource2("[]");

        ResponseEntity<UniverseImportResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getUpdatedCount());

        Boolean activeAfter = jdbc.queryForObject(
                "SELECT is_active FROM stock WHERE stock_id = '9871'", Boolean.class);
        assertEquals(Boolean.FALSE, activeAfter);
        // name still refreshed even though is_active untouched
        String nameAfter = jdbc.queryForObject(
                "SELECT stock_name FROM stock WHERE stock_id = '9871'", String.class);
        assertEquals("匯入後最新名稱", nameAfter);
    }

    // ---------- Error: upstream empty array -> 502 UPSTREAM_EMPTY, no writes to any of the 3 tables ----------

    @Test
    void import_upstreamReturnsEmptyArray_returns502UpstreamEmpty_noWrites() {
        long stockCountBefore = countAll("stock");
        long industryCountBefore = countAll("industry");
        long stockIndustryCountBefore = countAll("stock_industry");

        expectSource1("[]");
        // source 1 fails before source 2 is ever requested (spec 服務流程 step 3 precedes step 7);
        // no expectSource2(...) declared here — an unexpected extra request would fail this test.

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("UPSTREAM_EMPTY", response.getBody().getCode());
        assertEquals(stockCountBefore, countAll("stock"));
        assertEquals(industryCountBefore, countAll("industry"));
        assertEquals(stockIndustryCountBefore, countAll("stock_industry"));
    }

    // ---------- Error: upstream response has rows but none are eligible -> 502 UPSTREAM_EMPTY ----------

    @Test
    void import_upstreamReturnsOnlyIneligibleRows_returns502UpstreamEmpty_noWrites() {
        long stockCountBefore = countAll("stock");

        expectSource1(jsonArray(stockDayRow("0050", "元大台灣50")));

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("UPSTREAM_EMPTY", response.getBody().getCode());
        assertEquals(stockCountBefore, countAll("stock"));
    }

    // ---------- Error: upstream unreachable -> 502 UPSTREAM_UNAVAILABLE, no writes to any of the 3 tables ----------

    @Test
    void import_upstreamConnectionFails_returns502UpstreamUnavailable_noWrites() {
        long stockCountBefore = countAll("stock");
        long industryCountBefore = countAll("industry");
        long stockIndustryCountBefore = countAll("stock_industry");

        mockServer.expect(requestTo(twseUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new IOException("simulated connection failure");
                });

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("UPSTREAM_UNAVAILABLE", response.getBody().getCode());
        assertEquals(stockCountBefore, countAll("stock"));
        assertEquals(industryCountBefore, countAll("industry"));
        assertEquals(stockIndustryCountBefore, countAll("stock_industry"));
    }

    // ---------- Error: upstream response malformed (not the expected shape) -> 502 UPSTREAM_MALFORMED ----------

    @Test
    void import_upstreamReturnsMalformedJson_returns502UpstreamMalformed_noWrites() {
        long stockCountBefore = countAll("stock");

        expectSource1("{\"not\":\"an-array\"}");

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("UPSTREAM_MALFORMED", response.getBody().getCode());
        assertEquals(stockCountBefore, countAll("stock"));
    }

    // ======================================================================================
    // Increment: 交易所官方產業別 (industry / stock_industry)
    // ======================================================================================

    // ---------- industry gets populated with real Chinese names, not codes/garbage ----------

    @Test
    void import_industry_populatesChineseIndustryNames_notNumericCodes() {
        expectSource1(jsonArray(
                stockDayRow("9874", "測試半導體股"),
                stockDayRow("9875", "測試光電股")));
        // "24"/"26" are TWSE's real numeric industry classification codes for 半導體業/光電業 —
        // the actual shape t187ap03_L returns (see TwseIndustryCodeCatalog's javadoc).
        expectSource2(jsonArray(
                industryRow("9874", "24"),
                industryRow("9875", "26")));

        ResponseEntity<UniverseImportResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(UniverseImportResponse.INDUSTRY_STATUS_OK, response.getBody().getIndustrySourceStatus());

        String semiName = jdbc.queryForObject(
                "SELECT i.industry_name FROM stock_industry si JOIN industry i ON i.industry_id = si.industry_id "
                        + "WHERE si.stock_id = '9874'", String.class);
        assertEquals("半導體業", semiName);
        String semiHex = jdbc.queryForObject(
                "SELECT HEX(industry_name) FROM industry WHERE industry_name = '半導體業'", String.class);
        assertEquals("E58D8AE5B08EE9AB94E6A5AD", semiHex); // not garbled, not "24"

        String opticName = jdbc.queryForObject(
                "SELECT i.industry_name FROM stock_industry si JOIN industry i ON i.industry_id = si.industry_id "
                        + "WHERE si.stock_id = '9875'", String.class);
        assertEquals("光電業", opticName);

        // neither value is a bare digit string
        assertTrue(semiName.chars().noneMatch(Character::isDigit));
        assertTrue(opticName.chars().noneMatch(Character::isDigit));
    }

    // ---------- real code 2330 links to 半導體業 (HEX-confirmed UTF-8) ----------

    @Test
    void import_realStock2330_linksToSemiconductorIndustry() {
        Integer existsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock WHERE stock_id = '2330'", Integer.class);
        assertEquals(1, existsBefore, "2330 (TSMC) is expected to already exist in the live dev database");
        String stockNameBefore = jdbc.queryForObject(
                "SELECT stock_name FROM stock WHERE stock_id = '2330'", String.class);

        // 2330 appears only in source 2 here, never in source 1 -- proving the link is written
        // purely because 2330 already exists in `stock` (spec: 存在於 stock 中), without this test
        // touching (or needing to touch) its real stock_name.
        expectSource1(jsonArray(stockDayRow("9876", "測試普通股填充")));
        expectSource2(jsonArray(industryRow("2330", "24")));

        ResponseEntity<UniverseImportResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        String industryName = jdbc.queryForObject(
                "SELECT i.industry_name FROM stock_industry si JOIN industry i ON i.industry_id = si.industry_id "
                        + "WHERE si.stock_id = '2330'", String.class);
        assertEquals("半導體業", industryName);
        String hex = jdbc.queryForObject(
                "SELECT HEX(i.industry_name) FROM stock_industry si JOIN industry i ON i.industry_id = si.industry_id "
                        + "WHERE si.stock_id = '2330'", String.class);
        assertEquals("E58D8AE5B08EE9AB94E6A5AD", hex);

        // 2330's own stock_name was never touched by this import (it wasn't in source 1 this run)
        String stockNameAfter = jdbc.queryForObject(
                "SELECT stock_name FROM stock WHERE stock_id = '2330'", String.class);
        assertEquals(stockNameBefore, stockNameAfter);
    }

    // ---------- response carries the 4 industry fields; uncategorized = totalActive - linked ----------

    @Test
    void import_response_includesIndustryFields_andUncategorizedArithmeticHolds() {
        expectSource1(jsonArray(stockDayRow("9878", "測試回應欄位股")));
        expectSource2(jsonArray(industryRow("9878", "24")));

        ResponseEntity<UniverseImportResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        UniverseImportResponse body = response.getBody();

        assertEquals(UniverseImportResponse.INDUSTRY_STATUS_OK, body.getIndustrySourceStatus());
        assertTrue(body.getIndustryCount() > 0);
        assertTrue(body.getIndustryLinkedStockCount() > 0);
        assertEquals(body.getTotalActiveCount() - body.getIndustryLinkedStockCount(), body.getUncategorizedStockCount());

        Integer linkedFromDb = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT si.stock_id) FROM stock_industry si JOIN stock s ON s.stock_id = si.stock_id "
                        + "WHERE s.is_active = 1", Integer.class);
        assertEquals(linkedFromDb.intValue(), body.getIndustryLinkedStockCount());
        Integer industryCountFromDb = jdbc.queryForObject("SELECT COUNT(*) FROM industry", Integer.class);
        assertEquals(industryCountFromDb.intValue(), body.getIndustryCount());
    }

    // ---------- idempotent: running twice with the same industry fixture doesn't grow anything ----------

    @Test
    void import_runTwice_industryCountAndStockIndustryRowCount_stable() {
        expectSource1(jsonArray(stockDayRow("9879", "測試冪等股")));
        expectSource2(jsonArray(industryRow("9879", "24")));
        ResponseEntity<UniverseImportResponse> first = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        int industryCountAfterFirst = first.getBody().getIndustryCount();
        long stockIndustryCountAfterFirst = countAll("stock_industry");

        mockServer.reset();
        expectSource1(jsonArray(stockDayRow("9879", "測試冪等股")));
        expectSource2(jsonArray(industryRow("9879", "24")));
        ResponseEntity<UniverseImportResponse> second = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, second.getStatusCode());

        assertEquals(industryCountAfterFirst, second.getBody().getIndustryCount());
        assertEquals(stockIndustryCountAfterFirst, countAll("stock_industry"));
        Integer linkRowsFor9879 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_industry WHERE stock_id = '9879'", Integer.class);
        assertEquals(1, linkRowsFor9879);
    }

    // ---------- whole-set replace: a stray pre-existing link is removed, only the new one remains ----------

    @Test
    void import_wholeSetReplace_staleLinkRemoved_onlyLatestRemains() {
        // Pre-seed the stock and an unrelated stray industry link, simulating a previous import
        // (or manual edit) that pointed this stock at the wrong industry.
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES "
                + "('9880', '整組取代測試股', 'TSE', 1)");
        Integer staleIndustryId = upsertIndustryDirect("整合測試產業-舊分類");
        jdbc.update("INSERT INTO stock_industry (stock_id, industry_id) VALUES (?, ?)", "9880", staleIndustryId);
        Integer linksBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_industry WHERE stock_id = '9880'", Integer.class);
        assertEquals(1, linksBefore);

        expectSource1(jsonArray(stockDayRow("9880", "整組取代測試股")));
        expectSource2(jsonArray(industryRow("9880", "24"))); // real code -> 半導體業, replaces the stray link

        ResponseEntity<UniverseImportResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Integer linksAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_industry WHERE stock_id = '9880'", Integer.class);
        assertEquals(1, linksAfter, "stray link must be replaced, not accumulated");
        String remainingName = jdbc.queryForObject(
                "SELECT i.industry_name FROM stock_industry si JOIN industry i ON i.industry_id = si.industry_id "
                        + "WHERE si.stock_id = '9880'", String.class);
        assertEquals("半導體業", remainingName);
    }

    // ---------- a stock not covered by source 2 this run keeps its existing link untouched ----------

    @Test
    void import_stockNotCoveredBySource2_existingLinkUntouched() {
        // Create via the real catalog endpoint, matching the acceptance criterion's own scenario.
        CreateStockRequest createRequest = new CreateStockRequest();
        createRequest.setStockId("9881");
        createRequest.setStockName("未涵蓋股票測試");
        createRequest.setMarket("TSE");
        ResponseEntity<StockDetailDto> createResponse = rest.postForEntity(
                "/api/stocks", createRequest, StockDetailDto.class);
        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());

        Integer industryId = upsertIndustryDirect("整合測試產業-未涵蓋保留");
        jdbc.update("INSERT INTO stock_industry (stock_id, industry_id) VALUES (?, ?)", "9881", industryId);

        // source 1 / source 2 both reference a different stock only -- 9881 appears nowhere.
        expectSource1(jsonArray(stockDayRow("9882", "測試填充股")));
        expectSource2(jsonArray(industryRow("9882", "26")));

        ResponseEntity<UniverseImportResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Integer linksAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_industry WHERE stock_id = '9881' AND industry_id = ?",
                Integer.class, industryId);
        assertEquals(1, linksAfter, "a stock absent from this run's industry source must keep its existing link");
    }

    // ---------- industry_id for an existing name stays the same across a re-import ----------

    @Test
    void import_industryId_stableAcrossReImport_forExistingName() {
        expectSource1(jsonArray(stockDayRow("9883", "測試主鍵穩定股")));
        expectSource2(jsonArray(industryRow("9883", "26"))); // 光電業
        ResponseEntity<UniverseImportResponse> first = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        Integer industryIdBefore = jdbc.queryForObject(
                "SELECT industry_id FROM industry WHERE industry_name = '光電業'", Integer.class);

        mockServer.reset();
        expectSource1(jsonArray(stockDayRow("9883", "測試主鍵穩定股")));
        expectSource2(jsonArray(industryRow("9883", "26")));
        ResponseEntity<UniverseImportResponse> second = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, second.getStatusCode());
        Integer industryIdAfter = jdbc.queryForObject(
                "SELECT industry_id FROM industry WHERE industry_name = '光電業'", Integer.class);

        assertEquals(industryIdBefore, industryIdAfter);
    }

    // ---------- industry source unavailable -> still 200, UNAVAILABLE, stock update unaffected, no industry writes ----------

    @Test
    void import_industrySourceUnavailable_stillReturns200_stockUpdatesProceed_industryUnchanged() {
        long industryCountBefore = countAll("industry");
        long stockIndustryCountBefore = countAll("stock_industry");

        expectSource1(jsonArray(stockDayRow("9884", "產業別來源失敗測試股")));
        mockServer.expect(requestTo(industryUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new IOException("simulated industry-source connection failure");
                });

        ResponseEntity<UniverseImportResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        UniverseImportResponse body = response.getBody();
        assertEquals(UniverseImportResponse.INDUSTRY_STATUS_UNAVAILABLE, body.getIndustrySourceStatus());
        assertEquals(1, body.getInsertedCount()); // stock master update still succeeded

        String name = jdbc.queryForObject(
                "SELECT stock_name FROM stock WHERE stock_id = '9884'", String.class);
        assertEquals("產業別來源失敗測試股", name);

        assertEquals(industryCountBefore, countAll("industry"));
        assertEquals(stockIndustryCountBefore, countAll("stock_industry"));
    }

    // ---------- industry source empty array -> 200, EMPTY, no stock_industry writes ----------

    @Test
    void import_industrySourceEmptyArray_returns200_statusEmpty_stockIndustryUnchanged() {
        long stockIndustryCountBefore = countAll("stock_industry");

        expectSource1(jsonArray(stockDayRow("9885", "產業別空陣列測試股")));
        expectSource2("[]");

        ResponseEntity<UniverseImportResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(UniverseImportResponse.INDUSTRY_STATUS_EMPTY, response.getBody().getIndustrySourceStatus());
        assertEquals(1, response.getBody().getInsertedCount());
        assertEquals(stockIndustryCountBefore, countAll("stock_industry"));
    }

    // ---------- industry source malformed -> 200, MALFORMED, no stock_industry writes ----------

    @Test
    void import_industrySourceMalformed_returns200_statusMalformed_stockIndustryUnchanged() {
        long stockIndustryCountBefore = countAll("stock_industry");

        expectSource1(jsonArray(stockDayRow("9886", "產業別格式錯誤測試股")));
        expectSource2("{\"not\":\"an-array\"}");

        ResponseEntity<UniverseImportResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(UniverseImportResponse.INDUSTRY_STATUS_MALFORMED, response.getBody().getIndustrySourceStatus());
        assertEquals(1, response.getBody().getInsertedCount());
        assertEquals(stockIndustryCountBefore, countAll("stock_industry"));
    }

    // ---------- non-ordinary-share codes in the industry source produce no stock_industry rows ----------

    @Test
    void import_industrySource_nonOrdinaryShareCodes_produceNoStockIndustryRows() {
        expectSource1(jsonArray(stockDayRow("9877", "產業別篩選填充股")));
        expectSource2(jsonArray(
                industryRow("0050", "24"),
                industryRow("2881A", "24"),
                industryRow("910322", "24")));

        ResponseEntity<UniverseImportResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        for (String skippedCode : new String[]{"0050", "2881A", "910322"}) {
            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_industry WHERE stock_id = ?", Integer.class, skippedCode);
            assertEquals(0, rows, skippedCode + " must not get a stock_industry row");
        }
    }

    // ---------- helpers ----------

    private void expectSource1(String fixtureJson) {
        mockServer.expect(requestTo(twseUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixtureJson, MediaType.APPLICATION_JSON));
    }

    private void expectSource2(String fixtureJson) {
        mockServer.expect(requestTo(industryUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixtureJson, MediaType.APPLICATION_JSON));
    }

    private String stockDayRow(String code, String name) {
        return "{\"Date\":\"1150901\",\"Code\":\"" + code + "\",\"Name\":\"" + name + "\","
                + "\"TradeVolume\":\"1000\",\"TradeValue\":\"88000\",\"OpeningPrice\":\"88.00\","
                + "\"HighestPrice\":\"89.00\",\"LowestPrice\":\"87.50\",\"ClosingPrice\":\"88.50\","
                + "\"Transaction\":\"12\"}";
    }

    private String industryRow(String companyId, String industryCode) {
        return "{\"公司代號\":\"" + companyId + "\",\"產業別\":\"" + industryCode + "\"}";
    }

    private String jsonArray(String... rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rows[i]);
        }
        return sb.append(']').toString();
    }

    /** Direct UPSERT-by-name into `industry`, mirroring IndustryMapper#upsertByName, for tests
     * that need to seed a pre-existing industry row before the import runs. */
    private Integer upsertIndustryDirect(String industryName) {
        jdbc.update("INSERT INTO industry (industry_name) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE industry_id = LAST_INSERT_ID(industry_id)", industryName);
        Integer id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Integer.class);
        assertNotNull(id);
        return id;
    }

    private long countAll(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    /** Concatenation of stock_name for the given ids, in id order, for before/after comparison. */
    private String namesOf(String... stockIds) {
        StringBuilder sb = new StringBuilder();
        for (String id : stockIds) {
            String name = jdbc.queryForObject(
                    "SELECT stock_name FROM stock WHERE stock_id = ?", String.class, id);
            sb.append(id).append('=').append(name).append(';');
        }
        return sb.toString();
    }
}
