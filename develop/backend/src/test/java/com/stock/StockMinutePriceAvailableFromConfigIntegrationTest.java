package com.stock;

import com.stock.dto.MinuteBarResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Dedicated Spring context with {@code app.minute-price.available-from} overridden to 2026-08-01
 * (far later than the production default 2023-05-23) — proves availableFrom is genuinely read from
 * configuration and drives BOTH the response field and the OUT_OF_WINDOW boundary together (spec:
 * availableFrom 取自設定...程式中不存在寫死的 2023-05-23). If either value were hardcoded, this override
 * would move only one of them and this test would catch the mismatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.minute-price.available-from=2026-08-01"})
class StockMinutePriceAvailableFromConfigIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    @Qualifier("externalApiRestTemplate")
    private RestTemplate externalApiRestTemplate;

    @Autowired
    private DataSource dataSource;

    @Value("${app.external.fugle-base-url}")
    private String fugleBaseUrl;

    private MockRestServiceServer mockServer;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        mockServer = MockRestServiceServer.bindTo(externalApiRestTemplate).build();
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    private void cleanupTestData() {
        jdbc.update("DELETE FROM stock_minute_price WHERE stock_id LIKE 'ZC%'");
        jdbc.update("DELETE FROM stock_minute_fetch_status WHERE stock_id LIKE 'ZC%'");
        jdbc.update("DELETE FROM stock_daily_price WHERE stock_id LIKE 'ZC%'");
        jdbc.update("DELETE FROM stock WHERE stock_id LIKE 'ZC%'");
    }

    @Test
    void response_reflectsTheOverriddenAvailableFrom() {
        String stockId = "ZC01";
        LocalDate tradeDate = LocalDate.of(2026, 8, 5); // after the overridden availableFrom, Fugle window
        seedStock(stockId, "設定值測試一");
        seedDailyPrice(stockId, tradeDate);

        mockServer.expect(requestTo(fugleUrl(stockId, tradeDate)))
                .andRespond(withSuccess(fugleFixture(tradeDate), MediaType.APPLICATION_JSON));

        MinuteBarResponse body = getMinuteBars(stockId, tradeDate).getBody();
        assertEquals(LocalDate.of(2026, 8, 1), body.getAvailableFrom(),
                "response.availableFrom must reflect the overridden config value, not the 2023-05-23 default");
    }

    @Test
    void dateJustBeforeTheOverriddenAvailableFrom_isOutOfWindow_evenThoughItsAfterTheDefault() {
        String stockId = "ZC02";
        // 2026-07-31: after the DEFAULT availableFrom (2023-05-23) but before the OVERRIDDEN one
        // (2026-08-01). Under the default config this date would be fetched via Fugle; under this
        // override it must be OUT_OF_WINDOW instead -- proving the boundary really moved with the
        // config, not just the displayed value.
        LocalDate tradeDate = LocalDate.of(2026, 7, 31);
        seedStock(stockId, "設定值測試二");
        seedDailyPrice(stockId, tradeDate);

        // No mock expectation registered: a request to Fugle (or Yahoo) here fails the test.
        MinuteBarResponse body = getMinuteBars(stockId, tradeDate).getBody();
        assertEquals("OUT_OF_WINDOW", body.getDataStatus());
        assertEquals(LocalDate.of(2026, 8, 1), body.getAvailableFrom());
        assertTrue(body.getBars().isEmpty());
    }

    @Test
    void dateOnOrAfterTheOverriddenAvailableFrom_stillRoutesToFugle() {
        String stockId = "ZC03";
        LocalDate tradeDate = LocalDate.of(2026, 8, 1); // exactly the overridden boundary, inclusive
        seedStock(stockId, "設定值測試三");
        seedDailyPrice(stockId, tradeDate);

        mockServer.expect(requestTo(fugleUrl(stockId, tradeDate)))
                .andRespond(withSuccess(fugleFixture(tradeDate), MediaType.APPLICATION_JSON));

        MinuteBarResponse body = getMinuteBars(stockId, tradeDate).getBody();
        assertEquals("AVAILABLE", body.getDataStatus());
        assertEquals("FUGLE", body.getSource());
        mockServer.verify();
    }

    // ---------- helpers ----------

    private void seedStock(String stockId, String name) {
        jdbc.update("INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES (?, ?, 'TSE', 1)", stockId, name);
    }

    private void seedDailyPrice(String stockId, LocalDate tradeDate) {
        jdbc.update("INSERT INTO stock_daily_price "
                        + "(stock_id, trade_date, open_price, high_price, low_price, close_price, volume, turnover, transaction_count, source) "
                        + "VALUES (?, ?, 100.00, 105.00, 99.00, 104.00, 500000, 0, 0, 'TEST')",
                stockId, java.sql.Date.valueOf(tradeDate));
    }

    private ResponseEntity<MinuteBarResponse> getMinuteBars(String stockId, LocalDate tradeDate) {
        return rest.getForEntity("/api/stocks/" + stockId + "/minute-bars?tradeDate=" + tradeDate, MinuteBarResponse.class);
    }

    private String fugleUrl(String stockId, LocalDate tradeDate) {
        return fugleBaseUrl + "/" + stockId + "?from=" + tradeDate + "&to=" + tradeDate
                + "&timeframe=1&fields=open,high,low,close,volume&sort=asc";
    }

    private String fugleFixture(LocalDate tradeDate) {
        String time = "09:00:00.000+08:00";
        return "{\"data\":[{\"date\":\"" + tradeDate + "T" + time + "\","
                + "\"open\":100,\"high\":101,\"low\":99,\"close\":100.5,\"volume\":10}]}";
    }
}
