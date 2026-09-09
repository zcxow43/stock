package com.stock.service;

import com.stock.domain.Industry;
import com.stock.domain.Stock;
import com.stock.domain.StockDailyPrice;
import com.stock.domain.StockIndustry;
import com.stock.domain.StockSyncProgress;
import com.stock.dto.DailySyncResponse;
import com.stock.dto.IndustryLinkCandidate;
import com.stock.dto.UniverseUpsertCounts;
import com.stock.mapper.IndustryMapper;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockIndustryMapper;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockSyncProgressMapper;
import com.stock.service.external.dto.NormalizedPriceRow;
import com.stock.service.external.dto.TwseSnapshotResult;
import com.stock.service.external.dto.TwseSnapshotRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * Owns every write into `stock` and `stock_daily_price`. All writes are UPSERTs so
 * that re-running a day or a stock is idempotent (spec: 寫入語意 / 資料源回傳事後更正的值).
 */
@Service
public class PriceIngestionService {

    private static final String SOURCE_TWSE = "TWSE";
    private static final String MARKET_TSE = "TSE";

    private final StockMapper stockMapper;
    private final StockDailyPriceMapper priceMapper;
    private final StockSyncProgressMapper progressMapper;
    private final IndustryMapper industryMapper;
    private final StockIndustryMapper stockIndustryMapper;

    public PriceIngestionService(StockMapper stockMapper, StockDailyPriceMapper priceMapper,
                                  StockSyncProgressMapper progressMapper, IndustryMapper industryMapper,
                                  StockIndustryMapper stockIndustryMapper) {
        this.stockMapper = stockMapper;
        this.priceMapper = priceMapper;
        this.progressMapper = progressMapper;
        this.industryMapper = industryMapper;
        this.stockIndustryMapper = stockIndustryMapper;
    }

    /**
     * Writes a whole-market daily snapshot: upserts the stock master (name/market/active)
     * and every stock's price row for that trade date, in one transaction.
     */
    @Transactional
    public DailySyncResponse applyDailySnapshot(TwseSnapshotResult snapshot) {
        List<TwseSnapshotRow> rows = snapshot.getRows();
        Set<String> existingStockIds = new HashSet<>(priceMapper.findStockIdsByTradeDate(snapshot.getTradeDate()));

        int inserted = 0;
        int updated = 0;
        int stockMasterUpserted = 0;
        List<String> stockIds = new ArrayList<>(rows.size());

        for (TwseSnapshotRow row : rows) {
            stockMapper.upsert(new Stock(row.getStockId(), row.getStockName(), MARKET_TSE, Boolean.TRUE));
            stockMasterUpserted++;

            priceMapper.upsert(toDomain(row.getPrice(), SOURCE_TWSE));
            stockIds.add(row.getStockId());
            if (existingStockIds.contains(row.getStockId())) {
                updated++;
            } else {
                inserted++;
            }
        }

        // Every path that writes a trading day's prices must advance last_synced_date for whatever
        // PRICE_BACKFILL progress rows already exist among today's snapshot stocks (spec: 寫入行情的
        // 路徑都必須推進進度) — otherwise the data sits in stock_daily_price already while catchUp
        // still thinks the whole population is behind, and reopens it every single trading day.
        // Advances using the snapshot's OWN trade date, never today's calendar date (see method
        // doc on applySnapshotDay for why), only forward, and never inserts a new progress row for
        // a stock that doesn't already have one (see advanceLastSyncedDateForExisting).
        progressMapper.advanceLastSyncedDateForExisting(
                stockIds, StockSyncProgress.JOB_PRICE_BACKFILL, snapshot.getTradeDate());

        return new DailySyncResponse(snapshot.getTradeDate(), rows.size(), inserted, updated, stockMasterUpserted);
    }

    /**
     * Writes one trading day's whole-market snapshot rows — filtered down to exactly the ALL-mode
     * backfill's target population, per stock — and advances that same population's
     * last_synced_date to this day, in one transaction (spec: 逐日回補的處理流程 step 4/5, 單日的行情寫入
     * 與 last_synced_date 推進在同一個交易邊界內完成 — a simulated progress-advance failure must roll the
     * price rows for this day back too).
     *
     * A code appearing in {@code rows} but outside {@code targetIds} (ETF/special share/TDR, or a
     * ticker the target population simply never included) is silently dropped: this path is a
     * PRICE source, never a universe source (spec: 逐日快照只寫入本次目標母體內的標的...多一條寫主檔的路徑正是
     * 分類與 is_active 最容易悄悄不一致的成因) — no {@code stock} row is ever written here.
     *
     * {@code targetIds}' last_synced_date is advanced even on a day with zero rows (non-trading
     * day) — {@link #applyDailySnapshot} of an empty {@code rows} is exactly step 3's "視為正常處理
     * 完畢" case, and advancing last_synced_date is how that day is remembered as already handled.
     */
    @Transactional
    public void applySnapshotDay(List<NormalizedPriceRow> rows, Set<String> targetIds, String jobType,
                                  LocalDate tradeDate) {
        for (NormalizedPriceRow row : rows) {
            if (!targetIds.contains(row.getStockId())) {
                continue;
            }
            priceMapper.upsert(toDomain(row, SOURCE_TWSE));
        }
        progressMapper.advanceLastSyncedDateForExisting(new ArrayList<>(targetIds), jobType, tradeDate);
    }

    /**
     * Writes one stock's backfilled rows and advances its progress to DONE in a single
     * transaction, so a crash never leaves "prices written but progress not updated"
     * (which would cause a resumed run to re-fetch data it already has).
     *
     * last_synced_date is recorded as the requested range's endDate, not the trade date of the
     * last row actually written: the source omits weekends/holidays entirely, so recording the
     * last real trading day would make every later catch-up re-request that already-processed
     * empty tail forever (spec: `last_synced_date` 的認定).
     *
     * {@code source} is whichever {@link com.stock.service.external.PriceHistorySource} actually
     * supplied these rows this run (YAHOO or FINMIND) — recorded per spec: 每一檔的處理順序 step 2,
     * 並在 stock_daily_price.source 記下實際取得該列的來源.
     */
    @Transactional
    public void applyBackfillResult(String stockId, String jobType, List<NormalizedPriceRow> rows,
                                     LocalDate requestedEndDate, String source) {
        for (NormalizedPriceRow row : rows) {
            priceMapper.upsert(toDomain(row, source));
        }
        progressMapper.markDone(stockId, jobType, requestedEndDate);
    }

    /**
     * UPSERTs an already-filtered universe-import batch into `stock` only — no price row is ever
     * written here (spec: stock-universe-import.md 寫入語意). On conflict, only `stock_name` is
     * refreshed; `is_active`/`market` are left exactly as they are, so a stock a user has
     * deliberately deactivated via {@code DELETE /api/stocks/{stockId}} is never silently
     * reactivated, and a listed-but-not-traded-today stock is never mistaken for delisted.
     *
     * Existing ids are resolved up front via a single query rather than relying on MySQL's
     * ON DUPLICATE KEY UPDATE affected-row count, which cannot distinguish "matched but the name
     * happened not to change" (0 rows affected) from "no match" (1 row affected) the way a
     * pre-computed existing-id set can.
     */
    @Transactional
    public UniverseUpsertCounts applyUniverseImport(List<Stock> eligible,
                                                      List<IndustryLinkCandidate> industryCandidates) {
        List<String> ids = new ArrayList<>(eligible.size());
        for (Stock stock : eligible) {
            ids.add(stock.getStockId());
        }
        Set<String> existingIds = new HashSet<>(stockMapper.findExistingStockIds(ids));

        int inserted = 0;
        int updated = 0;
        for (Stock stock : eligible) {
            stockMapper.upsertUniverse(stock);
            if (existingIds.contains(stock.getStockId())) {
                updated++;
            } else {
                inserted++;
            }
        }

        // Runs after the stock UPSERT above, still inside this same transaction (spec: 產業別的
        // 寫入語意 — "在 stock UPSERT 之後、同一個交易邊界內進行").
        applyIndustryLinks(industryCandidates);

        return new UniverseUpsertCounts(inserted, updated);
    }

    /**
     * Upserts `industry` by name, then whole-set-replaces `stock_industry` for exactly the stocks
     * this run's industry source covers (and that exist in `stock`). A no-op when
     * industryCandidates is empty — either the industry source failed/was empty this run, or its
     * response yielded no usable candidates; either way `industry`/`stock_industry` are left
     * completely untouched (spec: 服務流程 step 9's "產業別清單為空時，第 2、3 步整段跳過").
     */
    private void applyIndustryLinks(List<IndustryLinkCandidate> industryCandidates) {
        if (industryCandidates.isEmpty()) {
            return;
        }

        Set<String> distinctNames = new LinkedHashSet<>();
        for (IndustryLinkCandidate candidate : industryCandidates) {
            distinctNames.add(candidate.getIndustryName());
        }
        Map<String, Integer> industryIdByName = new HashMap<>();
        for (String name : distinctNames) {
            Industry industry = new Industry(name);
            industryMapper.upsertByName(industry);
            industryIdByName.put(name, industry.getIndustryId());
        }

        List<String> candidateStockIds = new ArrayList<>(industryCandidates.size());
        for (IndustryLinkCandidate candidate : industryCandidates) {
            candidateStockIds.add(candidate.getStockId());
        }
        // "存在於 stock 中" — resolved against this transaction's current state, so stocks the
        // UPSERT above just inserted are already visible here.
        Set<String> targetStockIds = new HashSet<>(stockMapper.findExistingStockIds(candidateStockIds));
        if (targetStockIds.isEmpty()) {
            return;
        }

        // "先刪除其在 stock_industry 的既有關聯，再寫入本次取得的關聯" — strictly limited to
        // targetStockIds; a stock this run's source did not cover is never touched.
        stockIndustryMapper.deleteByStockIds(new ArrayList<>(targetStockIds));

        List<StockIndustry> links = new ArrayList<>();
        for (IndustryLinkCandidate candidate : industryCandidates) {
            if (!targetStockIds.contains(candidate.getStockId())) {
                continue;
            }
            links.add(new StockIndustry(candidate.getStockId(), industryIdByName.get(candidate.getIndustryName())));
        }
        stockIndustryMapper.insertBatch(links);
    }

    private StockDailyPrice toDomain(NormalizedPriceRow row, String source) {
        StockDailyPrice price = new StockDailyPrice();
        price.setStockId(row.getStockId());
        price.setTradeDate(row.getTradeDate());
        price.setOpenPrice(row.getOpen());
        price.setHighPrice(row.getHigh());
        price.setLowPrice(row.getLow());
        price.setClosePrice(row.getClose());
        price.setVolume(row.getVolume());
        price.setTurnover(row.getTurnover());
        price.setTransactionCount(row.getTransactionCount());
        price.setSource(source);
        return price;
    }
}
