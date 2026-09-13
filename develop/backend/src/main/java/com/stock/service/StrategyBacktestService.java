package com.stock.service;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.BacktestDuplicateItemDto;
import com.stock.dto.BacktestItemRequestDto;
import com.stock.dto.BacktestRequestDto;
import com.stock.dto.BacktestResponseDto;
import com.stock.dto.BacktestResultItemDto;
import com.stock.exception.DuplicateBacktestItemException;
import com.stock.exception.InvalidBuyDateException;
import com.stock.exception.NoBacktestItemsException;
import com.stock.exception.TooManyStocksException;
import com.stock.exception.UnknownStockIdException;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/strategies/backtest — read-only, stateless recomputation of "buy-day close in,
 * highest open out" per stock over stock_daily_price. Writes nothing and persists no result (see
 * specs/backend/strategy-backtest.md, Overview): every call recomputes from scratch.
 *
 * <p>The buy date (`buyDate`) is supplied by the caller (strategy-scan's reported entry day —
 * confirmation-complete D+2 for RISING_SUPPORT, the signal date itself for every other pattern;
 * see specs/backend/strategy-scan.md) and is never recomputed or guessed here (specs/backend/
 * strategy-backtest.md, "買進日由請求指定，本端點不推算"). This endpoint does not accept or depend on
 * any strategy code or signal date.
 */
@Service
public class StrategyBacktestService {

    /**
     * Absurd-payload guard only, deliberately NOT strategy-scan's stockIds cap
     * (specs/backend/strategy-backtest.md, "上限為什麼不是 200"): that 200 bounds a list the user
     * types, while this bounds a hit list the market sizes — a full-market scan legitimately
     * returns 500+ items, and the frontend must send all of them. This counts ITEMS
     * (`(stockId, buyDate)` pairs), not distinct stocks: one stock can contribute several
     * buy dates within a scan window (see "一筆＝一個買進日"), so the bound sits at
     * "stock-count × plausible buy days per stock", not at the stock count itself. Do not
     * re-alias this to MAX_STOCK_IDS: the two move for different reasons.
     */
    public static final int MAX_ITEMS = 20000;
    public static final int LOT_SIZE = 1000;

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final int PERCENT_SCALE = 2;
    private static final int YUAN_SCALE = 0;
    private static final int DIVISION_SCALE = 10;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal LOT_SIZE_DECIMAL = BigDecimal.valueOf(LOT_SIZE);

    private final StockMapper stockMapper;
    private final StockDailyPriceMapper priceMapper;

    public StrategyBacktestService(StockMapper stockMapper, StockDailyPriceMapper priceMapper) {
        this.stockMapper = stockMapper;
        this.priceMapper = priceMapper;
    }

    public BacktestResponseDto backtest(BacktestRequestDto request) {
        List<BacktestItemRequestDto> items = validate(request == null ? null : request.getItems());

        LocalDate asOfDate = LocalDate.now(TAIPEI);
        LocalDate minBuyDate = items.stream()
                .map(BacktestItemRequestDto::getBuyDate)
                .min(Comparator.naturalOrder())
                .orElse(asOfDate);

        // Distinct stock ids only: the same stockId can now legitimately appear in multiple items
        // (each with its own buyDate — "一筆＝一個買進日"), but the series each of those items
        // reads from is keyed by stockId alone, so there is no reason to query for the same id
        // more than once.
        List<String> stockIds = new ArrayList<>(items.size());
        Set<String> seenStockIds = new HashSet<>();
        for (BacktestItemRequestDto item : items) {
            if (seenStockIds.add(item.getStockId())) {
                stockIds.add(item.getStockId());
            }
        }
        // Single batched range query covering every item's own (buyDate, asOfDate] window at
        // once — one round trip regardless of how many stocks are in the list (specs/backend/
        // strategy-backtest.md, "效能"). Each stock's own buyDate/asOfDate boundaries are then
        // applied in memory per item below.
        Map<String, List<StockDailyPrice>> seriesByStock = loadSeries(stockIds, minBuyDate, asOfDate);

        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        int backtestedCount = 0;
        List<BacktestResultItemDto> resultItems = new ArrayList<>(items.size());

        for (BacktestItemRequestDto item : items) {
            List<StockDailyPrice> rows = seriesByStock.getOrDefault(item.getStockId(), Collections.emptyList());

            BacktestResultItemDto resultItem = new BacktestResultItemDto();
            resultItem.setStockId(item.getStockId());
            resultItem.setBuyDate(item.getBuyDate());

            BigDecimal buyPrice = findBuyPrice(rows, item.getBuyDate());
            resultItem.setBuyPrice(buyPrice);

            if (buyPrice != null) {
                SellPick pick = findSellPick(rows, item.getBuyDate());
                if (pick != null) {
                    BigDecimal returnPercent = computeReturnPercent(buyPrice, pick.openPrice);
                    BigDecimal profit = computeProfit(buyPrice, pick.openPrice);
                    resultItem.setSellDate(pick.tradeDate);
                    resultItem.setSellPrice(pick.openPrice);
                    resultItem.setReturnPercent(returnPercent);
                    resultItem.setProfit(profit);

                    totalCost = totalCost.add(buyPrice.multiply(LOT_SIZE_DECIMAL));
                    totalProfit = totalProfit.add(profit);
                    backtestedCount++;
                }
            }
            resultItems.add(resultItem);
        }

        BacktestResponseDto response = new BacktestResponseDto();
        response.setAsOfDate(asOfDate);
        response.setLotSize(LOT_SIZE);
        response.setTotalCost(totalCost.setScale(YUAN_SCALE, RoundingMode.HALF_UP));
        response.setTotalProfit(totalProfit.setScale(YUAN_SCALE, RoundingMode.HALF_UP));
        response.setTotalReturnPercent(backtestedCount == 0 ? null : computeTotalReturnPercent(totalProfit, totalCost));
        response.setBacktestedCount(backtestedCount);
        response.setItems(resultItems);
        return response;
    }

    private List<BacktestItemRequestDto> validate(List<BacktestItemRequestDto> items) {
        if (items == null || items.isEmpty()) {
            throw new NoBacktestItemsException();
        }
        if (items.size() > MAX_ITEMS) {
            throw new TooManyStocksException(MAX_ITEMS);
        }

        List<String> ids = new ArrayList<>(items.size());
        for (BacktestItemRequestDto item : items) {
            ids.add(item.getStockId());
        }

        // Existence-only check (no stock name/market needed here) — reuses the same purpose-built
        // helper the write-side endpoints use, and (per its own contract) does not filter is_active,
        // matching this endpoint's "delisted stocks may be named explicitly" rule.
        List<String> distinctIds = new ArrayList<>(new LinkedHashSet<>(ids));
        Set<String> existingIds = new HashSet<>(stockMapper.findExistingStockIds(distinctIds));
        List<String> unknownIds = new ArrayList<>();
        for (String id : distinctIds) {
            if (!existingIds.contains(id)) {
                unknownIds.add(id);
            }
        }
        if (!unknownIds.isEmpty()) {
            throw new UnknownStockIdException(unknownIds);
        }

        List<BacktestDuplicateItemDto> duplicatedItems = findDuplicateItems(items);
        if (!duplicatedItems.isEmpty()) {
            throw new DuplicateBacktestItemException(duplicatedItems);
        }

        LocalDate today = LocalDate.now(TAIPEI);
        for (BacktestItemRequestDto item : items) {
            // A missing buyDate is treated the same as one after today: both are dates the caller
            // could not honestly have bought on.
            if (item.getBuyDate() == null || item.getBuyDate().isAfter(today)) {
                throw new InvalidBuyDateException(item.getStockId());
            }
        }

        return items;
    }

    /**
     * The uniqueness key is the `(stockId, buyDate)` PAIR, not `stockId` alone
     * (specs/backend/strategy-backtest.md, "一筆＝一個買進日") — the same stock may legitimately
     * appear more than once as long as each occurrence has a different buy date. Hash-based
     * rather than a nested scan: at the raised MAX_ITEMS a linear scan per element is quadratic
     * over a list that is now legitimately market-sized. LinkedHashMap keeps the reported items in
     * request order and reports each offending combination once even if it repeats 3+ times.
     */
    private List<BacktestDuplicateItemDto> findDuplicateItems(List<BacktestItemRequestDto> items) {
        Set<String> seen = new HashSet<>();
        Map<String, BacktestDuplicateItemDto> duplicated = new LinkedHashMap<>();
        for (BacktestItemRequestDto item : items) {
            String key = item.getStockId() + "|" + item.getBuyDate();
            if (!seen.add(key)) {
                duplicated.putIfAbsent(key, new BacktestDuplicateItemDto(item.getStockId(), item.getBuyDate()));
            }
        }
        return new ArrayList<>(duplicated.values());
    }

    private Map<String, List<StockDailyPrice>> loadSeries(List<String> stockIds, LocalDate minBuyDate,
                                                            LocalDate asOfDate) {
        Map<String, List<StockDailyPrice>> seriesByStock = new HashMap<>();
        List<StockDailyPrice> rows = priceMapper.findByStockIdsAndDateRange(stockIds, minBuyDate, asOfDate);
        for (StockDailyPrice row : rows) {
            seriesByStock.computeIfAbsent(row.getStockId(), k -> new ArrayList<>()).add(row);
        }
        return seriesByStock;
    }

    /**
     * The buy day's own close, or null when that stock has no row for that exact trade date
     * (specs/backend/strategy-backtest.md, "無法回測的標的" case 2) or when that row's close is not
     * positive (case 3 — a 0 means the stock did not trade that day, not that it was worth 0; see
     * "價格為 0 的日子不是行情"). Returning null for the 0 case is what keeps computeReturnPercent
     * from dividing by zero.
     */
    private BigDecimal findBuyPrice(List<StockDailyPrice> rows, LocalDate buyDate) {
        for (StockDailyPrice row : rows) {
            if (row.getTradeDate().equals(buyDate)) {
                return isTradedPrice(row.getClosePrice()) ? row.getClosePrice() : null;
            }
        }
        return null;
    }

    /** A price only counts as a real quote when it is present and strictly positive — a 0 is how a
     *  no-trade day is recorded (specs/backend/strategy-backtest.md, "價格為 0 的日子不是行情"). */
    private boolean isTradedPrice(BigDecimal price) {
        return price != null && price.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * The highest open price strictly after buyDate (rows are already bounded to <= asOfDate by
     * the batched query), and the earliest trade date it occurs on. `rows` is ascending by
     * trade_date, so only updating on a strictly-greater open keeps the first (earliest) occurrence
     * of a tied maximum — specs/backend/strategy-backtest.md, "同值取最早". Returns null when no
     * trading day with a real (positive) open exists after buyDate (case 1 of "無法回測的標的").
     */
    private SellPick findSellPick(List<StockDailyPrice> rows, LocalDate buyDate) {
        BigDecimal maxOpen = null;
        LocalDate maxDate = null;
        for (StockDailyPrice row : rows) {
            if (!row.getTradeDate().isAfter(buyDate)) {
                continue;
            }
            BigDecimal open = row.getOpenPrice();
            // A 0 open is a no-trade day, not a sellable price — never a sell candidate.
            if (!isTradedPrice(open)) {
                continue;
            }
            if (maxOpen == null || open.compareTo(maxOpen) > 0) {
                maxOpen = open;
                maxDate = row.getTradeDate();
            }
        }
        return maxOpen == null ? null : new SellPick(maxDate, maxOpen);
    }

    private BigDecimal computeReturnPercent(BigDecimal buyPrice, BigDecimal sellPrice) {
        return sellPrice.subtract(buyPrice)
                .divide(buyPrice, DIVISION_SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal computeProfit(BigDecimal buyPrice, BigDecimal sellPrice) {
        return sellPrice.subtract(buyPrice).multiply(LOT_SIZE_DECIMAL).setScale(YUAN_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal computeTotalReturnPercent(BigDecimal totalProfit, BigDecimal totalCost) {
        return totalProfit.divide(totalCost, DIVISION_SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static final class SellPick {
        private final LocalDate tradeDate;
        private final BigDecimal openPrice;

        private SellPick(LocalDate tradeDate, BigDecimal openPrice) {
            this.tradeDate = tradeDate;
            this.openPrice = openPrice;
        }
    }
}
