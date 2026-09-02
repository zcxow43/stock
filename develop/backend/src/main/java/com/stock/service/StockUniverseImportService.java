package com.stock.service;

import com.stock.domain.Stock;
import com.stock.dto.IndustryLinkCandidate;
import com.stock.dto.UniverseImportResponse;
import com.stock.dto.UniverseUpsertCounts;
import com.stock.exception.UpstreamEmptyException;
import com.stock.exception.UpstreamMalformedException;
import com.stock.exception.UpstreamUnavailableException;
import com.stock.mapper.IndustryMapper;
import com.stock.mapper.StockIndustryMapper;
import com.stock.mapper.StockMapper;
import com.stock.service.external.ExternalApiException;
import com.stock.service.external.ExternalApiMalformedException;
import com.stock.service.external.TwseClient;
import com.stock.service.external.TwseIndustryCodeCatalog;
import com.stock.service.external.dto.TwseCompanyProfileRow;
import com.stock.service.external.dto.TwseDailyRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Imports the whole TSE-listed "ordinary share" universe into the `stock` master, and the
 * exchange's official 產業別 into `industry`/`stock_industry`, in a single call that writes no
 * price data at all. See specs/backend/stock-universe-import.md for the full contract; this class
 * exists precisely so that "which stocks exist, and what industry they're in" (this service) and
 * "these stocks' prices" ({@link PriceIngestionService} / {@link BackfillRunner}) stay two
 * independently triggerable actions.
 *
 * Both external fetches happen here, outside any transaction; the actual UPSERT/replace batch is
 * delegated to {@link PriceIngestionService#applyUniverseImport}, which owns every write into
 * `stock`, `industry`, and `stock_industry` and runs it as one transaction (spec: 服務流程 step 9)
 * — mirroring how {@link StockSyncService#syncDaily} already separates "fetch" from "write" for the
 * daily snapshot path.
 */
@Service
public class StockUniverseImportService {

    private static final Logger log = LoggerFactory.getLogger(StockUniverseImportService.class);

    private static final String MARKET_TSE = "TSE";

    /** "恰為 4 個數字字元、且首字元不為 0" — excludes ETFs (leading 0), special shares (letters),
     * and TDRs/other codes (more than 4 characters). See the spec's 標的篩選 table. Applied to both
     * source 1 (stock list) and source 2 (industry) codes, per spec 服務流程 step 8. */
    private static final Pattern ORDINARY_SHARE_CODE = Pattern.compile("^[1-9]\\d{3}$");

    private final TwseClient twseClient;
    private final StockMapper stockMapper;
    private final IndustryMapper industryMapper;
    private final StockIndustryMapper stockIndustryMapper;
    private final PriceIngestionService priceIngestionService;

    public StockUniverseImportService(TwseClient twseClient, StockMapper stockMapper,
                                       IndustryMapper industryMapper, StockIndustryMapper stockIndustryMapper,
                                       PriceIngestionService priceIngestionService) {
        this.twseClient = twseClient;
        this.stockMapper = stockMapper;
        this.industryMapper = industryMapper;
        this.stockIndustryMapper = stockIndustryMapper;
        this.priceIngestionService = priceIngestionService;
    }

    public UniverseImportResponse importUniverse() {
        TwseDailyRow[] rawRows = fetchRaw();

        if (rawRows == null || rawRows.length == 0) {
            throw new UpstreamEmptyException("TWSE whole-market snapshot returned no rows");
        }
        int fetchedCount = rawRows.length;

        List<Stock> eligible = new ArrayList<>();
        int skippedCount = 0;
        for (TwseDailyRow row : rawRows) {
            String code = trim(row.getCode());
            String name = trim(row.getName());
            if (code == null || !ORDINARY_SHARE_CODE.matcher(code).matches() || name == null || name.isEmpty()) {
                skippedCount++;
                continue;
            }
            eligible.add(new Stock(code, name, MARKET_TSE, Boolean.TRUE));
        }

        if (eligible.isEmpty()) {
            // A response with rows, but not a single one passing the ordinary-share filter, carries
            // the exact same "nothing usable came back" meaning as a bare empty array (spec: 空回應
            // 的處理 step 6) — must not be reported as "0 imported".
            throw new UpstreamEmptyException(
                    "TWSE whole-market snapshot returned rows, but none passed the ordinary-share filter");
        }
        int eligibleCount = eligible.size();

        // Source 2's request happens before the write transaction opens, and never aborts the
        // import on failure (spec: 服務流程 step 7 / 產業別來源失敗的處理) — both sources are fully
        // fetched and parsed before a single transaction writes all three tables.
        IndustryFetchOutcome industryFetch = fetchIndustryCandidates();

        // Runs in its own transaction inside PriceIngestionService; by the time this call returns,
        // the batch has committed, so the count reads below see it.
        UniverseUpsertCounts counts = priceIngestionService.applyUniverseImport(eligible, industryFetch.candidates);
        int totalActiveCount = stockMapper.countActive();
        int industryCount = industryMapper.count();
        int industryLinkedStockCount = stockIndustryMapper.countLinkedActiveStocks();
        int uncategorizedStockCount = totalActiveCount - industryLinkedStockCount;

        return new UniverseImportResponse(fetchedCount, eligibleCount, skippedCount,
                counts.getInsertedCount(), counts.getUpdatedCount(), totalActiveCount,
                industryFetch.status, industryCount, industryLinkedStockCount, uncategorizedStockCount);
    }

    private TwseDailyRow[] fetchRaw() {
        try {
            return twseClient.fetchDailyAllRaw();
        } catch (ExternalApiMalformedException e) {
            throw new UpstreamMalformedException("TWSE whole-market snapshot response could not be parsed", e);
        } catch (ExternalApiException e) {
            throw new UpstreamUnavailableException("TWSE whole-market snapshot could not be fetched", e);
        }
    }

    /**
     * Fetches and parses source 2 (industry). Any failure (connection, empty array, unparsable
     * response) is caught here and reported as a non-`OK` status with an empty candidate list — it
     * never propagates as an exception, per spec 產業別來源失敗的處理.
     */
    private IndustryFetchOutcome fetchIndustryCandidates() {
        TwseCompanyProfileRow[] rawRows;
        try {
            rawRows = twseClient.fetchCompanyProfileRaw();
        } catch (ExternalApiMalformedException e) {
            // Never fails the request (spec: 產業別來源失敗的處理) -- but a real external-dependency
            // failure is still worth an operator noticing, so log it the same way the source-1
            // 502 handlers in GlobalExceptionHandler do, even though this path returns 200.
            log.warn("Industry source response could not be parsed; skipping industry writes this run: {}",
                    e.getMessage(), e);
            return new IndustryFetchOutcome(UniverseImportResponse.INDUSTRY_STATUS_MALFORMED, Collections.emptyList());
        } catch (ExternalApiException e) {
            log.warn("Industry source could not be reached; skipping industry writes this run: {}",
                    e.getMessage(), e);
            return new IndustryFetchOutcome(UniverseImportResponse.INDUSTRY_STATUS_UNAVAILABLE, Collections.emptyList());
        }
        if (rawRows == null || rawRows.length == 0) {
            log.warn("Industry source returned no rows; skipping industry writes this run");
            return new IndustryFetchOutcome(UniverseImportResponse.INDUSTRY_STATUS_EMPTY, Collections.emptyList());
        }

        List<IndustryLinkCandidate> candidates = new ArrayList<>();
        for (TwseCompanyProfileRow row : rawRows) {
            String code = trim(row.getCompanyId());
            if (code == null || !ORDINARY_SHARE_CODE.matcher(code).matches()) {
                continue;
            }
            String industryName = resolveIndustryName(row.getIndustryCode());
            if (industryName == null) {
                continue;
            }
            candidates.add(new IndustryLinkCandidate(code, industryName));
        }
        return new IndustryFetchOutcome(UniverseImportResponse.INDUSTRY_STATUS_OK, candidates);
    }

    /**
     * Resolves the source's `產業別` field to a Chinese industry name. The spec assumes this field
     * already carries the name directly; in practice TWSE's `t187ap03_L` sends a two-digit numeric
     * classification code instead (see {@link TwseIndustryCodeCatalog}). Translates known codes via
     * that catalog, but if the source ever does send the name directly (a non-numeric value), trusts
     * it as-is rather than trying to look it up. Returns {@code null} (row ignored) for an empty
     * field or an unrecognized numeric code — never writes a bare digit string into `industry_name`.
     */
    private static String resolveIndustryName(String rawField) {
        String trimmed = trim(rawField);
        if (trimmed == null) {
            return null;
        }
        boolean numeric = true;
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                numeric = false;
                break;
            }
        }
        return numeric ? TwseIndustryCodeCatalog.nameOf(trimmed) : trimmed;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Outcome of fetching/parsing source 2: the status to report, and the candidates it yielded
     * (always empty unless status is OK). */
    private static final class IndustryFetchOutcome {
        private final String status;
        private final List<IndustryLinkCandidate> candidates;

        private IndustryFetchOutcome(String status, List<IndustryLinkCandidate> candidates) {
            this.status = status;
            this.candidates = candidates;
        }
    }
}
