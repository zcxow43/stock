package com.stock.service.external;

import com.stock.service.external.dto.NormalizedInstitutionalTradeRow;
import com.stock.service.external.dto.T86Response;
import com.stock.util.NormalizeUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Fetches the exchange's three-major-institutional-investors daily report (T86) for ONE specific
 * past trading day (spec: specs/backend/institutional-trade-ingestion.md, 資料源（已測事實）). One
 * request covers the whole listed market, independent of how many stocks are actually targeted —
 * same request-cost shape as {@link TwseMiIndexClient}.
 *
 * <p>Shares {@link TwseMiIndexClient#SOURCE_CODE} ("TWSE") deliberately: both endpoints live on
 * {@code www.twse.com.tw}, and a block from that host applies to both (spec: 與 MI_INDEX 共用同一道
 * 請求間隔閘門，封鎖狀態也共用 — "來源" 在這裡的正確解讀是施加封鎖的那個主機，而不是各自的端點).
 *
 * <p>Every column is located by its exact {@code fields} name, never by array index (spec:
 * 以 fields 的欄名定位每一欄，不得以陣列索引取值) — the same discipline {@link TwseMiIndexClient} applies
 * to MI_INDEX's per-stock table. Missing a required field name, or failing to parse any of a row's
 * 17 numeric values, is treated as a whole-day format error (spec: 格式錯誤（缺少必要欄名、數值無法解析）
 * | 該日整日不寫入): every numeric column in {@code stock_institutional_trade} is {@code NOT NULL}
 * with no default, so a value this client could not confidently parse must never reach the mapper.
 */
@Component
public class TwseInstitutionalTradeClient {

    private static final String STOCK_ID_FIELD = "證券代號";
    private static final String FOREIGN_BUY_FIELD = "外陸資買進股數(不含外資自營商)";
    private static final String FOREIGN_SELL_FIELD = "外陸資賣出股數(不含外資自營商)";
    private static final String FOREIGN_NET_FIELD = "外陸資買賣超股數(不含外資自營商)";
    private static final String FOREIGN_DEALER_BUY_FIELD = "外資自營商買進股數";
    private static final String FOREIGN_DEALER_SELL_FIELD = "外資自營商賣出股數";
    private static final String FOREIGN_DEALER_NET_FIELD = "外資自營商買賣超股數";
    private static final String TRUST_BUY_FIELD = "投信買進股數";
    private static final String TRUST_SELL_FIELD = "投信賣出股數";
    private static final String TRUST_NET_FIELD = "投信買賣超股數";
    private static final String DEALER_NET_FIELD = "自營商買賣超股數";
    private static final String DEALER_SELF_BUY_FIELD = "自營商買進股數(自行買賣)";
    private static final String DEALER_SELF_SELL_FIELD = "自營商賣出股數(自行買賣)";
    private static final String DEALER_SELF_NET_FIELD = "自營商買賣超股數(自行買賣)";
    private static final String DEALER_HEDGE_BUY_FIELD = "自營商買進股數(避險)";
    private static final String DEALER_HEDGE_SELL_FIELD = "自營商賣出股數(避險)";
    private static final String DEALER_HEDGE_NET_FIELD = "自營商買賣超股數(避險)";
    private static final String TOTAL_NET_FIELD = "三大法人買賣超股數";

    private static final String[] REQUIRED_FIELDS = {
            STOCK_ID_FIELD, FOREIGN_BUY_FIELD, FOREIGN_SELL_FIELD, FOREIGN_NET_FIELD,
            FOREIGN_DEALER_BUY_FIELD, FOREIGN_DEALER_SELL_FIELD, FOREIGN_DEALER_NET_FIELD,
            TRUST_BUY_FIELD, TRUST_SELL_FIELD, TRUST_NET_FIELD, DEALER_NET_FIELD,
            DEALER_SELF_BUY_FIELD, DEALER_SELF_SELL_FIELD, DEALER_SELF_NET_FIELD,
            DEALER_HEDGE_BUY_FIELD, DEALER_HEDGE_SELL_FIELD, DEALER_HEDGE_NET_FIELD, TOTAL_NET_FIELD
    };

    private static final String STAT_OK = "OK";

    private static final DateTimeFormatter DATE_PARAM = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestTemplate restTemplate;
    private final String t86Url;

    public TwseInstitutionalTradeClient(RestTemplate externalApiRestTemplate,
                                         @Value("${app.external.twse-t86-url}") String t86Url) {
        this.restTemplate = externalApiRestTemplate;
        this.t86Url = t86Url;
    }

    /**
     * Fetches and normalizes every security's institutional buy/sell/net row for the given trading
     * day. Returns an empty list both when the source reports no data at all (non-trading day, or
     * a trading day whose report has not been published yet — spec: 尚未發布與尚未抓取，在這個判定下沒有
     * 差別) and when {@code stat} is anything other than {@code "OK"}; neither is a failure.
     *
     * @throws SourceBlockedException on HTTP 403/429 or a quota-indicating body — caller must mark
     *                                this source (shared with MI_INDEX) unavailable and, per spec,
     *                                end this catch-up run without retrying
     * @throws RateLimitedException   on a timeout — caller may retry with backoff
     * @throws ExternalApiException   on any other non-retryable connectivity failure
     * @throws ExternalApiMalformedException on a response missing a required field name, or whose
     *                                        data could not be parsed into the expected shape
     */
    public List<NormalizedInstitutionalTradeRow> fetchTrades(LocalDate date) {
        String url = UriComponentsBuilder.fromHttpUrl(t86Url)
                .queryParam("date", date.format(DATE_PARAM))
                .queryParam("selectType", "ALLBUT0999")
                .queryParam("response", "json")
                .toUriString();

        T86Response response;
        try {
            response = restTemplate.getForObject(url, T86Response.class);
        } catch (HttpStatusCodeException e) {
            if (RetryAfterExtractor.isBlockedResponse(e)) {
                throw new SourceBlockedException(
                        "TWSE T86 blocked for date " + date + ": HTTP " + e.getRawStatusCode(),
                        RetryAfterExtractor.extract(e));
            }
            throw new ExternalApiException(
                    "TWSE T86 request failed for date " + date + ": HTTP " + e.getRawStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new RateLimitedException("TWSE T86 request timed out for date " + date, e);
        } catch (RestClientException e) {
            throw new ExternalApiMalformedException(
                    "TWSE T86 response for date " + date + " could not be parsed: " + e.getMessage(), e);
        }

        if (response == null || !STAT_OK.equals(response.getStat())
                || response.getFields() == null || response.getData() == null) {
            // Non-trading day, or the day's report not yet published -- normal, not an error
            // (spec: {"stat":"很抱歉，沒有符合條件的資料!","total":0} carries no fields/data at all).
            return Collections.emptyList();
        }

        return normalize(response, date);
    }

    private List<NormalizedInstitutionalTradeRow> normalize(T86Response response, LocalDate date) {
        List<String> fields = response.getFields();
        for (String required : REQUIRED_FIELDS) {
            if (!fields.contains(required)) {
                throw new ExternalApiMalformedException(
                        "TWSE T86 response for date " + date + " is missing required field \"" + required + "\"",
                        null);
            }
        }

        int stockIdIdx = fields.indexOf(STOCK_ID_FIELD);
        int foreignBuyIdx = fields.indexOf(FOREIGN_BUY_FIELD);
        int foreignSellIdx = fields.indexOf(FOREIGN_SELL_FIELD);
        int foreignNetIdx = fields.indexOf(FOREIGN_NET_FIELD);
        int foreignDealerBuyIdx = fields.indexOf(FOREIGN_DEALER_BUY_FIELD);
        int foreignDealerSellIdx = fields.indexOf(FOREIGN_DEALER_SELL_FIELD);
        int foreignDealerNetIdx = fields.indexOf(FOREIGN_DEALER_NET_FIELD);
        int trustBuyIdx = fields.indexOf(TRUST_BUY_FIELD);
        int trustSellIdx = fields.indexOf(TRUST_SELL_FIELD);
        int trustNetIdx = fields.indexOf(TRUST_NET_FIELD);
        int dealerNetIdx = fields.indexOf(DEALER_NET_FIELD);
        int dealerSelfBuyIdx = fields.indexOf(DEALER_SELF_BUY_FIELD);
        int dealerSelfSellIdx = fields.indexOf(DEALER_SELF_SELL_FIELD);
        int dealerSelfNetIdx = fields.indexOf(DEALER_SELF_NET_FIELD);
        int dealerHedgeBuyIdx = fields.indexOf(DEALER_HEDGE_BUY_FIELD);
        int dealerHedgeSellIdx = fields.indexOf(DEALER_HEDGE_SELL_FIELD);
        int dealerHedgeNetIdx = fields.indexOf(DEALER_HEDGE_NET_FIELD);
        int totalNetIdx = fields.indexOf(TOTAL_NET_FIELD);

        List<NormalizedInstitutionalTradeRow> rows = new ArrayList<>();
        for (List<String> row : response.getData()) {
            String stockId = valueAt(row, stockIdIdx);
            if (stockId == null || stockId.trim().isEmpty()) {
                continue;
            }
            stockId = stockId.trim();

            Optional<Long> foreignBuy = toLong(row, foreignBuyIdx);
            Optional<Long> foreignSell = toLong(row, foreignSellIdx);
            Optional<Long> foreignNet = toLong(row, foreignNetIdx);
            Optional<Long> foreignDealerBuy = toLong(row, foreignDealerBuyIdx);
            Optional<Long> foreignDealerSell = toLong(row, foreignDealerSellIdx);
            Optional<Long> foreignDealerNet = toLong(row, foreignDealerNetIdx);
            Optional<Long> trustBuy = toLong(row, trustBuyIdx);
            Optional<Long> trustSell = toLong(row, trustSellIdx);
            Optional<Long> trustNet = toLong(row, trustNetIdx);
            Optional<Long> dealerNet = toLong(row, dealerNetIdx);
            Optional<Long> dealerSelfBuy = toLong(row, dealerSelfBuyIdx);
            Optional<Long> dealerSelfSell = toLong(row, dealerSelfSellIdx);
            Optional<Long> dealerSelfNet = toLong(row, dealerSelfNetIdx);
            Optional<Long> dealerHedgeBuy = toLong(row, dealerHedgeBuyIdx);
            Optional<Long> dealerHedgeSell = toLong(row, dealerHedgeSellIdx);
            Optional<Long> dealerHedgeNet = toLong(row, dealerHedgeNetIdx);
            Optional<Long> totalNet = toLong(row, totalNetIdx);

            if (!allPresent(foreignBuy, foreignSell, foreignNet, foreignDealerBuy, foreignDealerSell,
                    foreignDealerNet, trustBuy, trustSell, trustNet, dealerNet, dealerSelfBuy, dealerSelfSell,
                    dealerSelfNet, dealerHedgeBuy, dealerHedgeSell, dealerHedgeNet, totalNet)) {
                // Every numeric column is NOT NULL with no default (specs/dba/stock-institutional-trade.md);
                // a row this client cannot confidently parse in full must fail the whole day rather than
                // reach the mapper with a missing value (spec: 數值無法解析 -> 該日整日不寫入).
                throw new ExternalApiMalformedException(
                        "TWSE T86 response for date " + date + " has an unparseable numeric value for stock "
                                + stockId, null);
            }

            rows.add(new NormalizedInstitutionalTradeRow(stockId, date,
                    foreignBuy.get(), foreignSell.get(), foreignNet.get(),
                    foreignDealerBuy.get(), foreignDealerSell.get(), foreignDealerNet.get(),
                    trustBuy.get(), trustSell.get(), trustNet.get(),
                    dealerNet.get(), dealerSelfBuy.get(), dealerSelfSell.get(), dealerSelfNet.get(),
                    dealerHedgeBuy.get(), dealerHedgeSell.get(), dealerHedgeNet.get(), totalNet.get()));
        }
        return rows;
    }

    private boolean allPresent(Optional<?>... values) {
        for (Optional<?> value : values) {
            if (!value.isPresent()) {
                return false;
            }
        }
        return true;
    }

    private Optional<Long> toLong(List<String> row, int index) {
        return NormalizeUtil.toBigDecimal(valueAt(row, index)).map(BigDecimal::longValue);
    }

    private String valueAt(List<String> row, int index) {
        return (index < 0 || index >= row.size()) ? null : row.get(index);
    }
}
