package com.stock.service.external;

import com.stock.service.external.dto.NormalizedPriceRow;
import com.stock.service.external.dto.TwseDailyRow;
import com.stock.service.external.dto.TwseSnapshotResult;
import com.stock.service.external.dto.TwseSnapshotRow;
import com.stock.util.NormalizeUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetches the whole-market daily snapshot from TWSE's openapi in a single request.
 * See specs/backend/stock-price-ingestion.md for the source contract.
 */
@Component
public class TwseClient {

    private final RestTemplate restTemplate;
    private final String dailyAllUrl;

    public TwseClient(RestTemplate externalApiRestTemplate,
                       @Value("${app.external.twse-daily-all-url}") String dailyAllUrl) {
        this.restTemplate = externalApiRestTemplate;
        this.dailyAllUrl = dailyAllUrl;
    }

    /** Fetches the current whole-market snapshot. Rows without a trade (no price data) are skipped. */
    public TwseSnapshotResult fetchDailyAll() {
        TwseDailyRow[] rawRows;
        try {
            rawRows = restTemplate.getForObject(dailyAllUrl, TwseDailyRow[].class);
        } catch (RestClientException e) {
            throw new ExternalApiException("Failed to fetch TWSE daily snapshot: " + e.getMessage(), e);
        }
        if (rawRows == null || rawRows.length == 0) {
            throw new ExternalApiException("TWSE daily snapshot returned no data");
        }

        LocalDate tradeDate = null;
        List<TwseSnapshotRow> rows = new ArrayList<>();
        for (TwseDailyRow raw : rawRows) {
            if (tradeDate == null && raw.getDate() != null) {
                tradeDate = NormalizeUtil.toIsoDate(raw.getDate());
            }
            Optional<NormalizedPriceRow> normalized = normalize(raw, tradeDate);
            normalized.ifPresent(price ->
                    rows.add(new TwseSnapshotRow(raw.getCode(), raw.getName(), price)));
        }
        if (tradeDate == null) {
            throw new ExternalApiException("TWSE daily snapshot rows carry no Date field");
        }
        return new TwseSnapshotResult(tradeDate, rows);
    }

    /**
     * Fetches the same whole-market daily snapshot as {@link #fetchDailyAll()}, but returns the
     * raw, unfiltered rows exactly as the source sent them — no no-trade-row filtering, no trade
     * date resolution. Used by the stock universe import (specs/backend/stock-universe-import.md),
     * which only needs {@code Code}/{@code Name} and applies its own "ordinary share" eligibility
     * rule; {@link #fetchDailyAll()}'s price-presence filtering exists for price ingestion and
     * would wrongly drop listed-but-not-traded-today stocks from the universe.
     *
     * <p>Distinguishes connectivity failures from unparsable responses so the caller can map them
     * to different error codes: {@link ExternalApiException} for connection failure/timeout/non-2xx,
     * {@link ExternalApiMalformedException} for a response that could not be parsed into the
     * expected shape. Does not throw on an empty/absent array — that is left to the caller, which
     * has its own semantics for what "empty" means (spec: 空回應的處理).
     */
    public TwseDailyRow[] fetchDailyAllRaw() {
        try {
            return restTemplate.getForObject(dailyAllUrl, TwseDailyRow[].class);
        } catch (ResourceAccessException | RestClientResponseException e) {
            throw new ExternalApiException("Failed to fetch TWSE daily snapshot: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new ExternalApiMalformedException(
                    "TWSE daily snapshot response could not be parsed: " + e.getMessage(), e);
        }
    }

    private Optional<NormalizedPriceRow> normalize(TwseDailyRow raw, LocalDate tradeDate) {
        if (raw.getCode() == null || raw.getCode().trim().isEmpty()) {
            return Optional.empty();
        }
        Optional<BigDecimal> open = NormalizeUtil.toBigDecimal(raw.getOpeningPrice());
        Optional<BigDecimal> high = NormalizeUtil.toBigDecimal(raw.getHighestPrice());
        Optional<BigDecimal> low = NormalizeUtil.toBigDecimal(raw.getLowestPrice());
        Optional<BigDecimal> close = NormalizeUtil.toBigDecimal(raw.getClosingPrice());
        // No-trade / suspended stocks: TWSE omits price fields for that row. Do not backfill with zero.
        if (!open.isPresent() || !high.isPresent() || !low.isPresent() || !close.isPresent()) {
            return Optional.empty();
        }
        long volume = NormalizeUtil.toBigDecimal(raw.getTradeVolume()).map(BigDecimal::longValue).orElse(0L);
        BigDecimal turnover = NormalizeUtil.toBigDecimal(raw.getTradeValue()).orElse(BigDecimal.ZERO);
        int transactionCount = NormalizeUtil.toBigDecimal(raw.getTransaction()).map(BigDecimal::intValue).orElse(0);

        LocalDate rowDate = raw.getDate() != null ? NormalizeUtil.toIsoDate(raw.getDate()) : tradeDate;
        return Optional.of(new NormalizedPriceRow(
                raw.getCode(), rowDate, open.get(), high.get(), low.get(), close.get(),
                volume, turnover, transactionCount));
    }
}
