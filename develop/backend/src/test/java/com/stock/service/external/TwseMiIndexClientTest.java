package com.stock.service.external;

import com.stock.service.external.dto.NormalizedPriceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pure unit tests (no Spring context, no DB) for {@link TwseMiIndexClient} — the ALL-mode
 * day-by-day snapshot source (spec: specs/backend/stock-price-ingestion.md, 逐日全市場快照（交易所
 * MI_INDEX）— 已測事實 / 逐日全市場快照為 ALL 模式主路徑（本次新增）).
 */
class TwseMiIndexClientTest {

    private static final String BASE_URL = "https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX";
    private static final String STOCK_FIELDS = "\"證券代號\",\"證券名稱\",\"成交股數\",\"成交筆數\",\"成交金額\","
            + "\"開盤價\",\"最高價\",\"最低價\",\"收盤價\",\"漲跌(+/-)\",\"漲跌價差\",\"最後揭示買價\",\"最後揭示買量\","
            + "\"最後揭示賣價\",\"最後揭示賣量\",\"本益比\"";

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private TwseMiIndexClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        client = new TwseMiIndexClient(restTemplate, BASE_URL);
    }

    private String url(LocalDate date) {
        return BASE_URL + "?date=" + date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                + "&type=ALL&response=json";
    }

    @Test
    void findsStockTable_byFieldsZeroEqualsStockCode_regardlessOfArrayPosition() {
        // The decoy tables come FIRST and the real 個股行情 table comes THIRD -- proving lookup is
        // by fields[0], never by a hardcoded tables[index] (spec: 不得以陣列索引取表).
        LocalDate date = LocalDate.of(2026, 9, 4);
        String body = "{\"tables\":["
                + "{\"fields\":[\"指數\",\"收盤指數\"],\"data\":[[\"發行量加權股價指數\",\"17000.00\"]]},"
                + "{\"fields\":[\"漲跌證券數\",\"上漲\",\"下跌\"],\"data\":[[\"統計\",\"500\",\"400\"]]},"
                + "{\"fields\":[" + STOCK_FIELDS + "],\"data\":["
                + "[\"2330\",\"台積電\",\"19,214,481\",\"60,122\",\"46,545,167,227\",\"2,430.00\",\"2,435.00\","
                + "\"2,410.00\",\"2,420.00\",\"<p style='color:red'>+</p>\",\"10.00\",\"\",\"\",\"\",\"\",\"20.5\"]"
                + "]}"
                + "]}";
        mockServer.expect(requestTo(url(date))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<NormalizedPriceRow> rows = client.fetchSnapshot(date);

        assertEquals(1, rows.size());
        NormalizedPriceRow row = rows.get(0);
        assertEquals("2330", row.getStockId());
        assertEquals(date, row.getTradeDate());
        assertEquals(0, row.getOpen().compareTo(new BigDecimal("2430.00")));
        assertEquals(0, row.getHigh().compareTo(new BigDecimal("2435.00")));
        assertEquals(0, row.getLow().compareTo(new BigDecimal("2410.00")));
        assertEquals(0, row.getClose().compareTo(new BigDecimal("2420.00")));
        assertEquals(19214481L, row.getVolume());
        assertEquals(0, row.getTurnover().compareTo(new BigDecimal("46545167227")));
        assertEquals(60122, row.getTransactionCount());
    }

    @Test
    void nonTradingDay_stockTableAbsentFromTables_returnsEmptyList_notAnError() {
        LocalDate date = LocalDate.of(2026, 9, 6); // Sunday
        String body = "{\"tables\":[{\"fields\":[\"指數\",\"收盤指數\"],\"data\":[]}]}";
        mockServer.expect(requestTo(url(date))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<NormalizedPriceRow> rows = client.fetchSnapshot(date);
        assertTrue(rows.isEmpty());
    }

    @Test
    void nonTradingDay_stockTablePresentButDataEmpty_returnsEmptyList_notAnError() {
        LocalDate date = LocalDate.of(2026, 9, 6);
        String body = "{\"tables\":[{\"fields\":[" + STOCK_FIELDS + "],\"data\":[]}]}";
        mockServer.expect(requestTo(url(date))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<NormalizedPriceRow> rows = client.fetchSnapshot(date);
        assertTrue(rows.isEmpty());
    }

    @Test
    void suspendedRow_blankPriceFields_droppedNeverZeroFilled() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        String body = "{\"tables\":[{\"fields\":[" + STOCK_FIELDS + "],\"data\":["
                + "[\"1234\",\"停牌股\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"],"
                + "[\"5678\",\"正常股\",\"1,000\",\"10\",\"10,000\",\"9.00\",\"9.50\",\"8.50\",\"9.20\","
                + "\"<p></p>\",\"0.10\",\"\",\"\",\"\",\"\",\"10.0\"]"
                + "]}]}";
        mockServer.expect(requestTo(url(date))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<NormalizedPriceRow> rows = client.fetchSnapshot(date);
        assertEquals(1, rows.size());
        assertEquals("5678", rows.get(0).getStockId());
    }

    @Test
    void http403_throwsSourceBlocked() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        mockServer.expect(requestTo(url(date))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThrows(SourceBlockedException.class, () -> client.fetchSnapshot(date));
    }

    @Test
    void http429_throwsSourceBlocked() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        mockServer.expect(requestTo(url(date))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThrows(SourceBlockedException.class, () -> client.fetchSnapshot(date));
    }

    @Test
    void getCode_returnsTwse() {
        assertEquals("TWSE", client.getCode());
    }
}
