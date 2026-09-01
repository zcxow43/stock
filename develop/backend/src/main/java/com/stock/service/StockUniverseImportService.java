package com.stock.service;

import com.stock.domain.Stock;
import com.stock.dto.UniverseImportResponse;
import com.stock.dto.UniverseUpsertCounts;
import com.stock.exception.UpstreamEmptyException;
import com.stock.exception.UpstreamMalformedException;
import com.stock.exception.UpstreamUnavailableException;
import com.stock.mapper.StockMapper;
import com.stock.service.external.ExternalApiException;
import com.stock.service.external.ExternalApiMalformedException;
import com.stock.service.external.TwseClient;
import com.stock.service.external.dto.TwseDailyRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Imports the whole TSE-listed "ordinary share" universe into the `stock` master in a single
 * external request, writing no price data at all. See specs/backend/stock-universe-import.md for
 * the full contract; this class exists precisely so that "which stocks exist" (this service) and
 * "these stocks' prices" ({@link PriceIngestionService} / {@link BackfillRunner}) stay two
 * independently triggerable actions.
 *
 * The external fetch happens here, outside any transaction; the actual UPSERT batch is delegated
 * to {@link PriceIngestionService#applyUniverseImport}, which owns every write into `stock` and
 * runs it as one transaction (spec: 服務流程 step 7) — mirroring how {@link StockSyncService#syncDaily}
 * already separates "fetch" from "write" for the daily snapshot path.
 */
@Service
public class StockUniverseImportService {

    private static final String MARKET_TSE = "TSE";

    /** "恰為 4 個數字字元、且首字元不為 0" — excludes ETFs (leading 0), special shares (letters),
     * and TDRs/other codes (more than 4 characters). See the spec's 標的篩選 table. */
    private static final Pattern ORDINARY_SHARE_CODE = Pattern.compile("^[1-9]\\d{3}$");

    private final TwseClient twseClient;
    private final StockMapper stockMapper;
    private final PriceIngestionService priceIngestionService;

    public StockUniverseImportService(TwseClient twseClient, StockMapper stockMapper,
                                       PriceIngestionService priceIngestionService) {
        this.twseClient = twseClient;
        this.stockMapper = stockMapper;
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

        // Runs in its own transaction inside PriceIngestionService; by the time this call
        // returns, the batch has committed, so the countActive() read below sees it.
        UniverseUpsertCounts counts = priceIngestionService.applyUniverseImport(eligible);
        int totalActiveCount = stockMapper.countActive();

        return new UniverseImportResponse(fetchedCount, eligibleCount, skippedCount,
                counts.getInsertedCount(), counts.getUpdatedCount(), totalActiveCount);
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

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
