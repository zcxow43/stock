package com.stock;

import com.stock.dto.CreateStockRequest;
import com.stock.dto.ErrorResponse;
import com.stock.dto.StockDeleteResponse;
import com.stock.dto.StockDetailDto;
import com.stock.dto.StockListResponse;
import com.stock.dto.UpdateStockRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Write side of the stock catalog: POST/PUT/DELETE on /api/stocks. Uses its own 'ZW'-prefixed
 * stock ids so it never touches the live seed data (34 real stocks) or the read-side test's 'ZC'
 * fixtures.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockCatalogWriteIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

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
        jdbc.update("DELETE FROM stock_daily_indicator WHERE stock_id LIKE 'ZW%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'ZW%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'ZW%'");
    }

    private CreateStockRequest createRequest(String stockId, String stockName, String market) {
        CreateStockRequest req = new CreateStockRequest();
        req.setStockId(stockId);
        req.setStockName(stockName);
        req.setMarket(market);
        return req;
    }

    private UpdateStockRequest updateRequest(String stockName, String market, Boolean isActive) {
        UpdateStockRequest req = new UpdateStockRequest();
        req.setStockName(stockName);
        req.setMarket(market);
        req.setIsActive(isActive);
        return req;
    }

    // ---- POST /api/stocks ----

    @Test
    void create_success_returns201AndAppearsInList() {
        ResponseEntity<StockDetailDto> response =
                rest.postForEntity("/api/stocks", createRequest("ZW01", "測試環球晶", "TSE"), StockDetailDto.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        StockDetailDto body = response.getBody();
        assertNotNull(body);
        assertEquals("ZW01", body.getStockId());
        assertEquals("測試環球晶", body.getStockName());
        assertEquals("TSE", body.getMarket());
        assertTrue(body.getIsActive());
        assertEquals(0, body.getTradingDayCount());

        StockListResponse list =
                rest.getForEntity("/api/stocks?keyword=ZW01&includeInactive=true", StockListResponse.class).getBody();
        assertEquals(1, list.getTotal());
        assertTrue(list.getItems().get(0).getIsActive());
    }

    @Test
    void create_duplicateStockId_returns409_andDoesNotOverwriteExistingName() {
        rest.postForEntity("/api/stocks", createRequest("ZW02", "原始名稱", "TSE"), StockDetailDto.class);

        ResponseEntity<ErrorResponse> response =
                rest.postForEntity("/api/stocks", createRequest("ZW02", "惡意改名", "OTC"), ErrorResponse.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("STOCK_ALREADY_EXISTS", response.getBody().getCode());

        StockDetailDto detail = rest.getForEntity("/api/stocks/ZW02", StockDetailDto.class).getBody();
        assertEquals("原始名稱", detail.getStockName());
        assertEquals("TSE", detail.getMarket());
    }

    @Test
    void create_invalidMarket_returns400() {
        ResponseEntity<ErrorResponse> response =
                rest.postForEntity("/api/stocks", createRequest("ZW03", "測試股", "TWSE"), ErrorResponse.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_MARKET", response.getBody().getCode());
    }

    @Test
    void create_blankStockName_returns400WithFields() {
        ResponseEntity<ErrorResponse> response =
                rest.postForEntity("/api/stocks", createRequest("ZW04", "   ", "TSE"), ErrorResponse.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_STOCK_PAYLOAD", response.getBody().getCode());
        assertEquals(List.of("stockName"), response.getBody().getFields());
    }

    // ---- PUT /api/stocks/{stockId} ----

    @Test
    void update_changesNameAndMarket() {
        rest.postForEntity("/api/stocks", createRequest("ZW05", "舊名稱", "TSE"), StockDetailDto.class);

        ResponseEntity<StockDetailDto> response = rest.exchange("/api/stocks/ZW05", HttpMethod.PUT,
                new HttpEntity<>(updateRequest("新名稱", "OTC", true)), StockDetailDto.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        StockDetailDto body = response.getBody();
        assertEquals("新名稱", body.getStockName());
        assertEquals("OTC", body.getMarket());
        assertTrue(body.getIsActive());
    }

    @Test
    void update_reactivatesDelistedStock_soItReappearsInDefaultList() {
        rest.postForEntity("/api/stocks", createRequest("ZW06", "測試股六", "TSE"), StockDetailDto.class);
        rest.exchange("/api/stocks/ZW06", HttpMethod.DELETE, null, StockDeleteResponse.class);

        StockListResponse beforeReactivate =
                rest.getForEntity("/api/stocks?keyword=ZW06", StockListResponse.class).getBody();
        assertEquals(0, beforeReactivate.getTotal());

        ResponseEntity<StockDetailDto> response = rest.exchange("/api/stocks/ZW06", HttpMethod.PUT,
                new HttpEntity<>(updateRequest("測試股六", "TSE", true)), StockDetailDto.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().getIsActive());

        StockListResponse afterReactivate =
                rest.getForEntity("/api/stocks?keyword=ZW06", StockListResponse.class).getBody();
        assertEquals(1, afterReactivate.getTotal());
    }

    @Test
    void update_unknownStockId_returns404() {
        ResponseEntity<ErrorResponse> response = rest.exchange("/api/stocks/ZW-NOPE", HttpMethod.PUT,
                new HttpEntity<>(updateRequest("不存在", "TSE", true)), ErrorResponse.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("STOCK_NOT_FOUND", response.getBody().getCode());
    }

    // ---- DELETE /api/stocks/{stockId} ----

    @Test
    void delete_softDeletesWithoutTouchingPriceOrIndicatorRows() {
        rest.postForEntity("/api/stocks", createRequest("ZW07", "測試股七", "TSE"), StockDetailDto.class);
        jdbc.update("INSERT INTO stock_daily_price "
                + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, turnover, transaction_count, source) "
                + "VALUES ('ZW07', '2026-08-20', 10, 11, 9, 10.00, 1000, 10000, 100, 'TEST')");
        jdbc.update("INSERT INTO stock_daily_indicator "
                + "(stock_id, trade_date, param_key, ema_fast, ema_slow, dif, dea, osc, rsv, k_value, d_value, j_value, is_warmup) "
                + "VALUES ('ZW07', '2026-08-20', 'DEFAULT', 10.0, 10.0, 0.0, 0.0, 0.0, 50.0, 50.0, 50.0, 50.0, 0)");

        long priceCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'ZW07'", Long.class);
        long indicatorCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_indicator WHERE stock_id = 'ZW07'", Long.class);

        ResponseEntity<StockDeleteResponse> response =
                rest.exchange("/api/stocks/ZW07", HttpMethod.DELETE, null, StockDeleteResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        StockDeleteResponse body = response.getBody();
        assertEquals("ZW07", body.getStockId());
        assertFalse(body.getIsActive());

        long priceCountAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = 'ZW07'", Long.class);
        long indicatorCountAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_daily_indicator WHERE stock_id = 'ZW07'", Long.class);
        assertEquals(priceCountBefore, priceCountAfter);
        assertEquals(indicatorCountBefore, indicatorCountAfter);
        assertEquals(1, priceCountAfter);
        assertEquals(1, indicatorCountAfter);
    }

    @Test
    void delete_hidesFromDefaultList_butShowsWithIncludeInactive() {
        rest.postForEntity("/api/stocks", createRequest("ZW08", "測試股八", "TSE"), StockDetailDto.class);
        rest.exchange("/api/stocks/ZW08", HttpMethod.DELETE, null, StockDeleteResponse.class);

        StockListResponse defaultList =
                rest.getForEntity("/api/stocks?keyword=ZW08", StockListResponse.class).getBody();
        assertEquals(0, defaultList.getTotal());

        StockListResponse withInactive =
                rest.getForEntity("/api/stocks?keyword=ZW08&includeInactive=true", StockListResponse.class).getBody();
        assertEquals(1, withInactive.getTotal());
        assertFalse(withInactive.getItems().get(0).getIsActive());
    }

    @Test
    void delete_isIdempotent_repeatedCallStillReturns200() {
        rest.postForEntity("/api/stocks", createRequest("ZW09", "測試股九", "TSE"), StockDetailDto.class);

        ResponseEntity<StockDeleteResponse> first =
                rest.exchange("/api/stocks/ZW09", HttpMethod.DELETE, null, StockDeleteResponse.class);
        assertEquals(HttpStatus.OK, first.getStatusCode());

        ResponseEntity<StockDeleteResponse> second =
                rest.exchange("/api/stocks/ZW09", HttpMethod.DELETE, null, StockDeleteResponse.class);
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertFalse(second.getBody().getIsActive());
    }

    @Test
    void delete_unknownStockId_returns404() {
        ResponseEntity<ErrorResponse> response =
                rest.exchange("/api/stocks/ZW-NOPE", HttpMethod.DELETE, null, ErrorResponse.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("STOCK_NOT_FOUND", response.getBody().getCode());
    }
}
