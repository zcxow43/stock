package com.stock.service;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.BacktestItemRequestDto;
import com.stock.dto.BacktestRequestDto;
import com.stock.dto.BacktestResponseDto;
import com.stock.dto.BacktestResultItemDto;
import com.stock.exception.DuplicateStockIdException;
import com.stock.exception.InvalidSignalDateException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/strategies/backtest — read-only, stateless recomputation of "signal-day close in,
 * highest open out" per stock over stock_daily_price. Writes nothing and persists no result (see
 * specs/backend/strategy-backtest.md, Overview): every call recomputes from scratch.
 */
@Service
public class StrategyBacktestService {

    /**
     * Absurd-payload guard only, deliberately NOT strategy-scan's stockIds cap
     * (specs/backend/strategy-backtest.md, "上限為什麼不是 200"): that 200 bounds a list the user
     * types, while this bounds a hit list the market sizes — a full-market scan legitimately
     * returns 500+ items, and the frontend must send all of them. Bounded by the scan universe
     * (common stocks are under 1,500), so normal operation never reaches it. Do not re-alias this
     * to MAX_STOCK_IDS: the two move for different reasons.
     */
    public static final int MAX_ITEMS = 2000;
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
        LocalDate minSignalDate = items.stream()
                .map(BacktestItemRequestDto::getSignalDate)
                .min(Comparator.naturalOrder())
                .orElse(asOfDate);

        List<String> stockIds = new ArrayList<>(items.size());
        for (BacktestItemRequestDto item : items) {
            stockIds.add(item.getStockId());
        }
        // Single batched range query covering every item's own (signalDate, asOfDate] window at
        // once — one round trip regardless of how many stocks are in the list (specs/backend/
        // strategy-backtest.md, "效能"). Each stock's own signalDate/asOfDate boundaries are then
        // applied in memory per item below.
        Map<String, List<StockDailyPrice>> seriesByStock = loadSeries(stockIds, minSignalDate, asOfDate);

        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        int backtestedCount = 0;
        List<BacktestResultItemDto> resultItems = new ArrayList<>(items.size());

        for (BacktestItemRequestDto item : items) {
            List<StockDailyPrice> rows = seriesByStock.getOrDefault(item.getStockId(), Collections.emptyList());

            BacktestResultItemDto resultItem = new BacktestResultItemDto();
            resultItem.setStockId(item.getStockId());
            resultItem.setSignalDate(item.getSignalDate());

            BigDecimal buyPrice = findBuyPrice(rows, item.getSignalDate());
            resultItem.setBuyPrice(buyPrice);

            if (buyPrice != null) {
                SellPick pick = findSellPick(rows, item.getSignalDate());
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

        List<String> duplicatedIds = findDuplicates(ids);
        if (!duplicatedIds.isEmpty()) {
            throw new DuplicateStockIdException(duplicatedIds);
        }

        LocalDate today = LocalDate.now(TAIPEI);
        for (BacktestItemRequestDto item : items) {
            // A missing signalDate is treated the same as one after today: both are dates the
            // caller could not honestly have signalled on.
            if (item.getSignalDate() == null || item.getSignalDate().isAfter(today)) {
                throw new InvalidSignalDateException(item.getStockId());
            }
        }

        return items;
    }

    private List<String> findDuplicates(List<String> ids) {
        // Hash-based rather than List.contains: at the raised MAX_ITEMS a linear scan per element
        // is quadratic over a list that is now legitimately market-sized. LinkedHashSet keeps the
        // reported ids in request order.
        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new LinkedHashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                duplicated.add(id);
            }
        }
        return new ArrayList<>(duplicated);
    }

    private Map<String, List<StockDailyPrice>> loadSeries(List<String> stockIds, LocalDate minSignalDate,
                                                            LocalDate asOfDate) {
        Map<String, List<StockDailyPrice>> seriesByStock = new HashMap<>();
        List<StockDailyPrice> rows = priceMapper.findByStockIdsAndDateRange(stockIds, minSignalDate, asOfDate);
        for (StockDailyPrice row : rows) {
            seriesByStock.computeIfAbsent(row.getStockId(), k -> new ArrayList<>()).add(row);
        }
        return seriesByStock;
    }

    /** The signal day's own close, or null when that stock has no row for that exact trade date
     *  (specs/backend/strategy-backtest.md, "無法回測的標的" case 2). */
    private BigDecimal findBuyPrice(List<StockDailyPrice> rows, LocalDate signalDate) {
        for (StockDailyPrice row : rows) {
            if (row.getTradeDate().equals(signalDate)) {
                return row.getClosePrice();
            }
        }
        return null;
    }

    /**
     * The highest open price strictly after signalDate (rows are already bounded to <= asOfDate by
     * the batched query), and the earliest trade date it occurs on. `rows` is ascending by
     * trade_date, so only updating on a strictly-greater open keeps the first (earliest) occurrence
     * of a tied maximum — specs/backend/strategy-backtest.md, "同值取最早". Returns null when no
     * trading day exists after signalDate (case 1 of "無法回測的標的").
     */
    private SellPick findSellPick(List<StockDailyPrice> rows, LocalDate signalDate) {
        BigDecimal maxOpen = null;
        LocalDate maxDate = null;
        for (StockDailyPrice row : rows) {
            if (!row.getTradeDate().isAfter(signalDate)) {
                continue;
            }
            BigDecimal open = row.getOpenPrice();
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
