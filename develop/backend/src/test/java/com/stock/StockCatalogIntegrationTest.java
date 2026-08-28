package com.stock;

import com.stock.dto.ErrorResponse;
import com.stock.dto.StockDetailDto;
import com.stock.dto.StockListItemDto;
import com.stock.dto.StockListResponse;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockCatalogIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        cleanupTestData();
        seedTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    private void cleanupTestData() {
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'ZC%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'ZC%'");
    }

    private void seedTestData() {
        // ZC01: 3 trading days -> latest/previous close + change, tradingDayCount 3
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES ('ZC01', ?, 'TSE', 1)",
                "測試台積電");
        jdbc.update("INSERT INTO stock_daily_price "
                + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, turnover, transaction_count, source) "
                + "VALUES ('ZC01', '2026-08-25', 99, 101, 98, 100.00, 1000000, 100000000, 1000, 'TEST')");
        jdbc.update("INSERT INTO stock_daily_price "
                + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, turnover, transaction_count, source) "
                + "VALUES ('ZC01', '2026-08-26', 100, 106, 99, 105.00, 1100000, 110000000, 1100, 'TEST')");
        jdbc.update("INSERT INTO stock_daily_price "
                + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, turnover, transaction_count, source) "
                + "VALUES ('ZC01', '2026-08-27', 105, 111, 104, 110.00, 1200000, 120000000, 1200, 'TEST')");

        // ZC02: OTC, single trading day -> latestClose set, previousClose/change null
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES ('ZC02', ?, 'OTC', 1)", "策二");
        jdbc.update("INSERT INTO stock_daily_price "
                + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, turnover, transaction_count, source) "
                + "VALUES ('ZC02', '2026-08-27', 50, 51, 49, 50.00, 500000, 25000000, 500, 'TEST')");

        // ZC03: no price data at all -> everything null, still listed
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES ('ZC03', ?, 'TSE', 1)", "策三");

        // ZC04: inactive -> excluded unless includeInactive=true
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES ('ZC04', ?, 'TSE', 0)", "策四");
    }

    private String url(String path) {
        return path;
    }

    @Test
    void list_defaultsToActiveOnly_withPagingMetadata() {
        ResponseEntity<StockListResponse> response =
                rest.getForEntity(url("/api/stocks?keyword=ZC"), StockListResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        StockListResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.getPage());
        assertEquals(50, body.getSize());
        assertEquals(3, body.getTotal()); // ZC01, ZC02, ZC03 - ZC04 is inactive
        assertEquals(1, body.getTotalPages());
        assertEquals(3, body.getItems().size());
        assertTrue(body.getItems().stream().noneMatch(i -> "ZC04".equals(i.getStockId())));
    }

    @Test
    void list_keywordMatchesByStockIdOrStockName() {
        // RestTemplate encodes URI template variables itself; pass the raw keyword, not pre-encoded.
        StockListResponse byName = rest.getForEntity("/api/stocks?keyword={keyword}", StockListResponse.class,
                "測試台積電").getBody();
        assertEquals(1, byName.getTotal());
        assertEquals("ZC01", byName.getItems().get(0).getStockId());

        StockListResponse byId = rest.getForEntity(url("/api/stocks?keyword=ZC01"), StockListResponse.class).getBody();
        assertEquals(1, byId.getTotal());
        assertEquals("ZC01", byId.getItems().get(0).getStockId());
    }

    @Test
    void list_marketFilter_restrictsToOtc_andRejectsInvalidMarket() {
        StockListResponse otc = rest.getForEntity(url("/api/stocks?keyword=ZC&market=OTC"), StockListResponse.class)
                .getBody();
        assertEquals(1, otc.getTotal());
        assertEquals("ZC02", otc.getItems().get(0).getStockId());

        ResponseEntity<ErrorResponse> invalid =
                rest.getForEntity(url("/api/stocks?market=TWSE"), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals("INVALID_MARKET", invalid.getBody().getCode());
    }

    @Test
    void list_includeInactive_togglesDelistedStock() {
        StockListResponse withInactive =
                rest.getForEntity(url("/api/stocks?keyword=ZC&includeInactive=true"), StockListResponse.class)
                        .getBody();
        assertEquals(4, withInactive.getTotal());
        assertTrue(withInactive.getItems().stream().anyMatch(i -> "ZC04".equals(i.getStockId())));
    }

    @Test
    void list_sizeExceeded_returns400() {
        ResponseEntity<ErrorResponse> response = rest.getForEntity(url("/api/stocks?size=500"), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("PAGE_SIZE_EXCEEDED", response.getBody().getCode());
        assertEquals(200, response.getBody().getLimit());
    }

    @Test
    void list_invalidPagination_returns400() {
        ResponseEntity<ErrorResponse> page0 = rest.getForEntity(url("/api/stocks?page=0"), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, page0.getStatusCode());
        assertEquals("INVALID_PAGINATION", page0.getBody().getCode());

        ResponseEntity<ErrorResponse> size0 = rest.getForEntity(url("/api/stocks?size=0"), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, size0.getStatusCode());
        assertEquals("INVALID_PAGINATION", size0.getBody().getCode());
    }

    @Test
    void list_invalidSortField_returns400WithAllowedList() {
        ResponseEntity<ErrorResponse> response =
                rest.getForEntity(url("/api/stocks?sort=changePercent"), ErrorResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_SORT_FIELD", response.getBody().getCode());
        assertEquals(List.of("stockId", "stockName", "market"), response.getBody().getAllowed());
    }

    @Test
    void list_stockWithoutAnyPriceRow_stillListed_withNullLatestClose() {
        StockListResponse response =
                rest.getForEntity(url("/api/stocks?keyword=ZC03"), StockListResponse.class).getBody();
        assertEquals(1, response.getTotal());
        StockListItemDto item = response.getItems().get(0);
        assertEquals("ZC03", item.getStockId());
        assertNull(item.getLatestTradeDate());
        assertNull(item.getLatestClose());
        assertNull(item.getPreviousClose());
        assertNull(item.getChangeAmount());
        assertNull(item.getChangePercent());
        assertNull(item.getLatestVolume());
    }

    @Test
    void list_stockWithSingleTradingDay_hasLatestCloseButNullPreviousAndChange() {
        StockListResponse response =
                rest.getForEntity(url("/api/stocks?keyword=ZC02"), StockListResponse.class).getBody();
        StockListItemDto item = response.getItems().get(0);
        assertEquals(0, new BigDecimal("50.00").compareTo(item.getLatestClose()));
        assertNull(item.getPreviousClose());
        assertNull(item.getChangeAmount());
        assertNull(item.getChangePercent());
    }

    @Test
    void list_latestCloseAndChange_computedFromLastTwoTradingDays() {
        StockListResponse response =
                rest.getForEntity(url("/api/stocks?keyword=ZC01"), StockListResponse.class).getBody();
        StockListItemDto item = response.getItems().get(0);
        assertEquals(0, new BigDecimal("110.00").compareTo(item.getLatestClose()));
        assertEquals(0, new BigDecimal("105.00").compareTo(item.getPreviousClose()));
        assertEquals(0, new BigDecimal("5.00").compareTo(item.getChangeAmount()));
        assertEquals(0, new BigDecimal("4.76").compareTo(item.getChangePercent()));
    }

    @Test
    void detail_returnsFirstTradeDateAndTradingDayCount() {
        ResponseEntity<StockDetailDto> response = rest.getForEntity(url("/api/stocks/ZC01"), StockDetailDto.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        StockDetailDto body = response.getBody();
        assertEquals("ZC01", body.getStockId());
        assertEquals(3, body.getTradingDayCount());
        assertEquals(java.time.LocalDate.of(2026, 8, 25), body.getFirstTradeDate());
        assertEquals(0, new BigDecimal("110.00").compareTo(body.getLatestClose()));
    }

    @Test
    void detail_stockWithoutAnyPriceRow_returnsZeroTradingDayCountAndNullDates() {
        ResponseEntity<StockDetailDto> response = rest.getForEntity(url("/api/stocks/ZC03"), StockDetailDto.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        StockDetailDto body = response.getBody();
        assertEquals(0, body.getTradingDayCount());
        assertNull(body.getFirstTradeDate());
        assertNull(body.getLatestClose());
    }

    @Test
    void detail_unknownStockId_returns404() {
        ResponseEntity<ErrorResponse> response = rest.getForEntity(url("/api/stocks/ZC-DOES-NOT-EXIST"), ErrorResponse.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("STOCK_NOT_FOUND", response.getBody().getCode());
        assertEquals("ZC-DOES-NOT-EXIST", response.getBody().getStockId());
    }
}
