package com.stock;

import com.stock.dto.ErrorResponse;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies specs/backend/stock-universe-import.md: POST /api/stocks/universe/import.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockUniverseImportIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("externalApiRestTemplate")
    private RestTemplate externalApiRestTemplate;

    @Autowired
    private DataSource dataSource;

    @Value("${app.external.twse-daily-all-url}")
    private String twseUrl;

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
        jdbc.update("DELETE FROM stock WHERE stock_id IN ('9871','9872','9873')");
    }

    // ---------- Happy path: single external request, filtering, upsert semantics ----------

    @Test
    void import_singleRequest_filtersToOrdinaryShares_upsertsStockOnly_noPriceRowsWritten() {
        long priceCountBefore = countAll("stock_daily_price");
        long indicatorCountBefore = countAll("stock_daily_indicator");

        // 0050 / 2881A / 910322 are real, already-seeded rows in the live database (from the
        // real-market daily/master sync, which does NOT apply this endpoint's ordinary-share
        // filter). Capture their pre-existing names so we can assert they are left byte-for-byte
        // untouched by this import, rather than wrongly asserting their absence.
        String namesBefore = namesOf("0050", "2881A", "910322");

        String fixture = "["
                + "{\"Date\":\"1150901\",\"Code\":\"9871\",\"Name\":\"測試普通股一\",\"TradeVolume\":\"1000\","
                + "\"TradeValue\":\"88000\",\"OpeningPrice\":\"88.00\",\"HighestPrice\":\"89.00\","
                + "\"LowestPrice\":\"87.50\",\"ClosingPrice\":\"88.50\",\"Transaction\":\"12\"},"
                + "{\"Date\":\"1150901\",\"Code\":\"0050\",\"Name\":\"這個名字不應被寫入\",\"TradeVolume\":\"1000\","
                + "\"TradeValue\":\"88000\",\"OpeningPrice\":\"88.00\",\"HighestPrice\":\"89.00\","
                + "\"LowestPrice\":\"87.50\",\"ClosingPrice\":\"88.50\",\"Transaction\":\"12\"},"
                + "{\"Date\":\"1150901\",\"Code\":\"2881A\",\"Name\":\"這個名字不應被寫入\",\"TradeVolume\":\"1000\","
                + "\"TradeValue\":\"88000\",\"OpeningPrice\":\"88.00\",\"HighestPrice\":\"89.00\","
                + "\"LowestPrice\":\"87.50\",\"ClosingPrice\":\"88.50\",\"Transaction\":\"12\"},"
                + "{\"Date\":\"1150901\",\"Code\":\"910322\",\"Name\":\"這個名字不應被寫入\",\"TradeVolume\":\"1000\","
                + "\"TradeValue\":\"88000\",\"OpeningPrice\":\"88.00\",\"HighestPrice\":\"89.00\","
                + "\"LowestPrice\":\"87.50\",\"ClosingPrice\":\"88.50\",\"Transaction\":\"12\"}"
                + "]";
        mockServer.expect(requestTo(twseUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

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

        mockServer.verify(); // exactly one external call was made

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
    }

    // ---------- Re-running is idempotent: second run inserts 0, updates eligibleCount ----------

    @Test
    void import_runTwice_secondRun_insertsZero_updatesEqualsFirstEligibleCount_rowCountUnchanged() {
        String fixture = "["
                + "{\"Date\":\"1150901\",\"Code\":\"9872\",\"Name\":\"測試重跑一\",\"TradeVolume\":\"1000\","
                + "\"TradeValue\":\"88000\",\"OpeningPrice\":\"88.00\",\"HighestPrice\":\"89.00\","
                + "\"LowestPrice\":\"87.50\",\"ClosingPrice\":\"88.50\",\"Transaction\":\"12\"},"
                + "{\"Date\":\"1150901\",\"Code\":\"9873\",\"Name\":\"測試重跑二\",\"TradeVolume\":\"1000\","
                + "\"TradeValue\":\"88000\",\"OpeningPrice\":\"88.00\",\"HighestPrice\":\"89.00\","
                + "\"LowestPrice\":\"87.50\",\"ClosingPrice\":\"88.50\",\"Transaction\":\"12\"}"
                + "]";

        mockServer.expect(requestTo(twseUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));
        ResponseEntity<UniverseImportResponse> first = rest.postForEntity(
                "/api/stocks/universe/import", null, UniverseImportResponse.class);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(2, first.getBody().getInsertedCount());
        assertEquals(0, first.getBody().getUpdatedCount());

        long stockCountAfterFirst = countAll("stock");

        mockServer.reset();
        mockServer.expect(requestTo(twseUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));
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

        String fixture = "[{\"Date\":\"1150901\",\"Code\":\"9871\",\"Name\":\"匯入後最新名稱\","
                + "\"TradeVolume\":\"1000\",\"TradeValue\":\"88000\",\"OpeningPrice\":\"88.00\","
                + "\"HighestPrice\":\"89.00\",\"LowestPrice\":\"87.50\",\"ClosingPrice\":\"88.50\","
                + "\"Transaction\":\"12\"}]";
        mockServer.expect(requestTo(twseUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

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

    // ---------- Error: upstream empty array -> 502 UPSTREAM_EMPTY, no writes ----------

    @Test
    void import_upstreamReturnsEmptyArray_returns502UpstreamEmpty_noWrites() {
        long stockCountBefore = countAll("stock");

        mockServer.expect(requestTo(twseUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("UPSTREAM_EMPTY", response.getBody().getCode());
        assertEquals(stockCountBefore, countAll("stock"));
    }

    // ---------- Error: upstream response has rows but none are eligible -> 502 UPSTREAM_EMPTY ----------

    @Test
    void import_upstreamReturnsOnlyIneligibleRows_returns502UpstreamEmpty_noWrites() {
        long stockCountBefore = countAll("stock");

        String fixture = "[{\"Date\":\"1150901\",\"Code\":\"0050\",\"Name\":\"元大台灣50\","
                + "\"TradeVolume\":\"1000\",\"TradeValue\":\"88000\",\"OpeningPrice\":\"88.00\","
                + "\"HighestPrice\":\"89.00\",\"LowestPrice\":\"87.50\",\"ClosingPrice\":\"88.50\","
                + "\"Transaction\":\"12\"}]";
        mockServer.expect(requestTo(twseUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("UPSTREAM_EMPTY", response.getBody().getCode());
        assertEquals(stockCountBefore, countAll("stock"));
    }

    // ---------- Error: upstream unreachable -> 502 UPSTREAM_UNAVAILABLE ----------

    @Test
    void import_upstreamConnectionFails_returns502UpstreamUnavailable_noWrites() {
        long stockCountBefore = countAll("stock");

        mockServer.expect(requestTo(twseUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new IOException("simulated connection failure");
                });

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("UPSTREAM_UNAVAILABLE", response.getBody().getCode());
        assertEquals(stockCountBefore, countAll("stock"));
    }

    // ---------- Error: upstream response malformed (not the expected shape) -> 502 UPSTREAM_MALFORMED ----------

    @Test
    void import_upstreamReturnsMalformedJson_returns502UpstreamMalformed_noWrites() {
        long stockCountBefore = countAll("stock");

        mockServer.expect(requestTo(twseUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"not\":\"an-array\"}", MediaType.APPLICATION_JSON));

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                "/api/stocks/universe/import", null, ErrorResponse.class);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("UPSTREAM_MALFORMED", response.getBody().getCode());
        assertEquals(stockCountBefore, countAll("stock"));
    }

    // ---------- helpers ----------

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
