package com.stock.service.external;

import com.stock.service.external.dto.NormalizedInstitutionalTradeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pure unit tests (no Spring context, no DB) for {@link TwseInstitutionalTradeClient} — the T86
 * three-major-institutional-investors source (spec:
 * specs/backend/institutional-trade-ingestion.md, 資料源（已測事實）).
 *
 * <p>The fixtures below are trimmed from a REAL {@code date=20260911} response captured live
 * against {@code https://www.twse.com.tw/rwd/zh/fund/T86} during this task, and a REAL
 * {@code date=20260913} (Sunday) non-trading-day response — not hand-invented shapes. The 2609 and
 * 00632R rows' 17 numeric values are copied verbatim from that capture, so the assertions below
 * that match specs/backend/institutional-trade-ingestion.md's documented acceptance numbers are
 * checking real source data, not a fixture designed to make the assertion trivially pass.
 */
class TwseInstitutionalTradeClientTest {

    private static final String BASE_URL = "https://www.twse.com.tw/rwd/zh/fund/T86";
    private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.BASIC_ISO_DATE;

    // Real field order captured live from date=20260911.
    private static final String[] FIELDS = {
            "證券代號", "證券名稱",
            "外陸資買進股數(不含外資自營商)", "外陸資賣出股數(不含外資自營商)", "外陸資買賣超股數(不含外資自營商)",
            "外資自營商買進股數", "外資自營商賣出股數", "外資自營商買賣超股數",
            "投信買進股數", "投信賣出股數", "投信買賣超股數",
            "自營商買賣超股數",
            "自營商買進股數(自行買賣)", "自營商賣出股數(自行買賣)", "自營商買賣超股數(自行買賣)",
            "自營商買進股數(避險)", "自營商賣出股數(避險)", "自營商買賣超股數(避險)",
            "三大法人買賣超股數"
    };

    // Real row for 00632R captured live from date=20260911 (賣超為負值).
    private static final String[] ROW_00632R = {
            "00632R", "元大台灣50反1   ",
            "4,795,000", "74,787,000", "-69,992,000",
            "0", "0", "0",
            "0", "0", "0",
            "147,788,978",
            "600,000", "0", "600,000",
            "156,534,149", "9,345,171", "147,188,978",
            "77,796,978"
    };

    // Real row for 2609 (陽明) captured live from date=20260911 -- matches the spec's own
    // documented acceptance numbers exactly (foreign_buy=46546290, foreign_sell=23548005,
    // foreign_net=22998285, trust_net=102106, total_net=23747702).
    private static final String[] ROW_2609 = {
            "2609", "陽明            ",
            "46,546,290", "23,548,005", "22,998,285",
            "0", "0", "0",
            "105,000", "2,894", "102,106",
            "647,311",
            "363,568", "350,420", "13,148",
            "708,754", "74,591", "634,163",
            "23,747,702"
    };

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private TwseInstitutionalTradeClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        client = new TwseInstitutionalTradeClient(restTemplate, BASE_URL);
    }

    private String url(LocalDate date) {
        return BASE_URL + "?date=" + date.format(DATE_PARAM) + "&selectType=ALLBUT0999&response=json";
    }

    private String fixture(String[] fields, String[][] rows) {
        StringBuilder fieldsJson = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                fieldsJson.append(",");
            }
            fieldsJson.append("\"").append(fields[i]).append("\"");
        }
        StringBuilder dataJson = new StringBuilder();
        for (int r = 0; r < rows.length; r++) {
            if (r > 0) {
                dataJson.append(",");
            }
            dataJson.append("[");
            for (int c = 0; c < rows[r].length; c++) {
                if (c > 0) {
                    dataJson.append(",");
                }
                dataJson.append("\"").append(rows[r][c]).append("\"");
            }
            dataJson.append("]");
        }
        return "{\"stat\":\"OK\",\"hints\":\"單位：股\",\"fields\":[" + fieldsJson + "],\"data\":[" + dataJson + "]}";
    }

    @Test
    void realFixture_2609Row_normalizesByFieldName_matchesDocumentedRealValues() {
        LocalDate date = LocalDate.of(2026, 9, 11);
        mockServer.expect(requestTo(url(date))).andRespond(
                withSuccess(fixture(FIELDS, new String[][]{ROW_2609}), MediaType.APPLICATION_JSON));

        List<NormalizedInstitutionalTradeRow> rows = client.fetchTrades(date);

        assertEquals(1, rows.size());
        NormalizedInstitutionalTradeRow row = rows.get(0);
        assertEquals("2609", row.getStockId());
        assertEquals(date, row.getTradeDate());
        assertEquals(46546290L, row.getForeignBuyShares());
        assertEquals(23548005L, row.getForeignSellShares());
        assertEquals(22998285L, row.getForeignNetShares());
        assertEquals(0L, row.getForeignDealerBuyShares());
        assertEquals(0L, row.getForeignDealerSellShares());
        assertEquals(0L, row.getForeignDealerNetShares());
        assertEquals(105000L, row.getTrustBuyShares());
        assertEquals(2894L, row.getTrustSellShares());
        assertEquals(102106L, row.getTrustNetShares());
        assertEquals(647311L, row.getDealerNetShares());
        assertEquals(363568L, row.getDealerSelfBuyShares());
        assertEquals(350420L, row.getDealerSelfSellShares());
        assertEquals(13148L, row.getDealerSelfNetShares());
        assertEquals(708754L, row.getDealerHedgeBuyShares());
        assertEquals(74591L, row.getDealerHedgeSellShares());
        assertEquals(634163L, row.getDealerHedgeNetShares());
        assertEquals(23747702L, row.getTotalNetShares());
    }

    @Test
    void realFixture_00632RRow_sellSideNegativeNetSharesParsedCorrectly() {
        LocalDate date = LocalDate.of(2026, 9, 11);
        mockServer.expect(requestTo(url(date))).andRespond(
                withSuccess(fixture(FIELDS, new String[][]{ROW_00632R}), MediaType.APPLICATION_JSON));

        List<NormalizedInstitutionalTradeRow> rows = client.fetchTrades(date);

        assertEquals(1, rows.size());
        assertEquals("00632R", rows.get(0).getStockId());
        assertEquals(-69992000L, rows.get(0).getForeignNetShares());
    }

    @Test
    void fieldsAndDataShuffledTogetherInLockstep_parsesToTheSameResultAsOriginalOrder() {
        LocalDate date = LocalDate.of(2026, 9, 11);
        // A permutation that is NOT the identity -- proves lookup is genuinely by field name, not
        // by position (spec: 以 fields 的欄名定位每一欄，不得以陣列索引取值).
        int[] permutation = {1, 0, 5, 6, 7, 2, 3, 4, 11, 12, 13, 14, 8, 9, 10, 18, 15, 16, 17};
        String[] shuffledFields = permute(FIELDS, permutation);
        String[] shuffledRow = permute(ROW_2609, permutation);

        mockServer.expect(requestTo(url(date))).andRespond(
                withSuccess(fixture(shuffledFields, new String[][]{shuffledRow}), MediaType.APPLICATION_JSON));

        List<NormalizedInstitutionalTradeRow> rows = client.fetchTrades(date);

        assertEquals(1, rows.size());
        NormalizedInstitutionalTradeRow row = rows.get(0);
        assertEquals("2609", row.getStockId());
        assertEquals(46546290L, row.getForeignBuyShares());
        assertEquals(23548005L, row.getForeignSellShares());
        assertEquals(22998285L, row.getForeignNetShares());
        assertEquals(102106L, row.getTrustNetShares());
        assertEquals(23747702L, row.getTotalNetShares());
    }

    private String[] permute(String[] original, int[] permutation) {
        String[] result = new String[original.length];
        for (int i = 0; i < permutation.length; i++) {
            result[i] = original[permutation[i]];
        }
        return result;
    }

    @Test
    void missingRequiredFieldName_throwsMalformed() {
        LocalDate date = LocalDate.of(2026, 9, 11);
        String[] fieldsMissingTrustNet = {
                "證券代號", "證券名稱",
                "外陸資買進股數(不含外資自營商)", "外陸資賣出股數(不含外資自營商)", "外陸資買賣超股數(不含外資自營商)",
                "外資自營商買進股數", "外資自營商賣出股數", "外資自營商買賣超股數",
                "投信買進股數", "投信賣出股數",
                // "投信買賣超股數" deliberately omitted
                "自營商買賣超股數",
                "自營商買進股數(自行買賣)", "自營商賣出股數(自行買賣)", "自營商買賣超股數(自行買賣)",
                "自營商買進股數(避險)", "自營商賣出股數(避險)", "自營商買賣超股數(避險)",
                "三大法人買賣超股數"
        };
        String body = "{\"stat\":\"OK\",\"fields\":[" + quoteJoin(fieldsMissingTrustNet) + "],\"data\":[]}";
        mockServer.expect(requestTo(url(date))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThrows(ExternalApiMalformedException.class, () -> client.fetchTrades(date));
    }

    @Test
    void unparsableNumericValue_throwsMalformed() {
        LocalDate date = LocalDate.of(2026, 9, 11);
        String[] badRow = ROW_2609.clone();
        badRow[2] = "not-a-number"; // 外陸資買進股數(不含外資自營商)
        mockServer.expect(requestTo(url(date))).andRespond(
                withSuccess(fixture(FIELDS, new String[][]{badRow}), MediaType.APPLICATION_JSON));

        assertThrows(ExternalApiMalformedException.class, () -> client.fetchTrades(date));
    }

    @Test
    void realNonTradingDayShape_noFieldsNoData_returnsEmptyList_notAnError() {
        // Captured live from date=20260913 (Sunday).
        LocalDate date = LocalDate.of(2026, 9, 13);
        String body = "{\"stat\":\"很抱歉，沒有符合條件的資料!\",\"total\":0}";
        mockServer.expect(requestTo(url(date))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<NormalizedInstitutionalTradeRow> rows = client.fetchTrades(date);
        assertTrue(rows.isEmpty());
    }

    @Test
    void http403_throwsSourceBlocked() {
        LocalDate date = LocalDate.of(2026, 9, 11);
        mockServer.expect(requestTo(url(date))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThrows(SourceBlockedException.class, () -> client.fetchTrades(date));
    }

    @Test
    void http429_withRetryAfterInBody_throwsSourceBlocked_withRetryAfterSeconds() {
        LocalDate date = LocalDate.of(2026, 9, 11);
        mockServer.expect(requestTo(url(date))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"msg\":\"quota exceeded\",\"retry_after\":120}"));

        SourceBlockedException ex = assertThrows(SourceBlockedException.class, () -> client.fetchTrades(date));
        assertEquals(120L, ex.getRetryAfterSeconds());
    }

    @Test
    void timeout_throwsRateLimited() {
        LocalDate date = LocalDate.of(2026, 9, 11);
        mockServer.expect(requestTo(url(date))).andRespond(request -> {
            throw new IOException("simulated timeout");
        });

        assertThrows(RateLimitedException.class, () -> client.fetchTrades(date));
    }

    @Test
    void serverError_5xx_throwsExternalApiException_notBlocked() {
        LocalDate date = LocalDate.of(2026, 9, 11);
        mockServer.expect(requestTo(url(date))).andRespond(withServerError());

        assertThrows(ExternalApiException.class, () -> client.fetchTrades(date));
    }

    private String quoteJoin(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(values[i]).append("\"");
        }
        return sb.toString();
    }
}
