package com.stock.service;

import com.stock.domain.SimulatedTrade;
import com.stock.domain.SimulatedTradeSettlementPrice;
import com.stock.domain.Stock;
import com.stock.domain.StockDailyPrice;
import com.stock.domain.TradingDayNeighbors;
import com.stock.dto.CreateSimulatedTradeRequest;
import com.stock.dto.SimulatedTradeItemDto;
import com.stock.dto.SimulatedTradeResponseDto;
import com.stock.exception.DuplicateSimulatedTradeException;
import com.stock.exception.InvalidSimulatedTradeBuyDateException;
import com.stock.exception.InvalidPriceBasisException;
import com.stock.exception.InvalidSettleDateException;
import com.stock.exception.InvalidStockIdException;
import com.stock.exception.NoPriceBeforeTodayException;
import com.stock.exception.NoPriceOnBuyDateException;
import com.stock.exception.SimulatedTradeNotFoundException;
import com.stock.exception.UnknownStockIdException;
import com.stock.mapper.SimulatedTradeMapper;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockMapper;
import com.stock.util.TradingCostCalculator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GET/POST/DELETE /api/simulated-trades — specs/backend/simulated-trade.md. Only the facts fixed
 * at creation time live in {@code simulated_trade} (see specs/dba/simulated-trade.md); current
 * price, unrealized profit and return are recomputed from {@code stock_daily_price} on every read
 * and never persisted.
 *
 * <p>The trading-cost model ({@link TradingCostCalculator}) is exactly the one
 * {@code StrategyBacktestService} uses for POST /api/strategies/backtest — the two endpoints
 * differ only in which price stands in for the sell side (highest open in a window there, today's
 * close here).
 */
@Service
public class SimulatedTradeService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final SimulatedTradeMapper simulatedTradeMapper;
    private final StockMapper stockMapper;
    private final StockDailyPriceMapper priceMapper;

    public SimulatedTradeService(SimulatedTradeMapper simulatedTradeMapper, StockMapper stockMapper,
                                  StockDailyPriceMapper priceMapper) {
        this.simulatedTradeMapper = simulatedTradeMapper;
        this.stockMapper = stockMapper;
        this.priceMapper = priceMapper;
    }

    public SimulatedTradeResponseDto list(String rawSettleDate) {
        LocalDate asOfDate = LocalDate.now(TAIPEI);
        LocalDate settleDate = snapToTradingDay(parseSettleDate(rawSettleDate, asOfDate), asOfDate);
        // 全市場上一次收盤日（specs/backend/simulated-trade.md, "預設買進日（defaultBuyDate）"）— one
        // query, not scoped to any stock; null when the database holds no price rows at all.
        LocalDate defaultBuyDate = priceMapper.findMarketWideLatestPositiveCloseDate(asOfDate);
        List<SimulatedTrade> trades = simulatedTradeMapper.findAllOrderedByBuyDateDescStockIdAsc();
        return buildResponse(asOfDate, defaultBuyDate, settleDate, trades);
    }

    @Transactional
    public SimulatedTradeItemDto create(CreateSimulatedTradeRequest request) {
        String stockId = normalizeStockId(request == null ? null : request.getStockId());
        if (stockId == null) {
            throw new InvalidStockIdException();
        }

        boolean useOpen = parsePriceBasisIsOpen(request.getPriceBasis(), request.getBuyDate());

        Stock stock = stockMapper.findById(stockId);
        if (stock == null) {
            throw new UnknownStockIdException(Collections.singletonList(stockId));
        }

        LocalDate today = LocalDate.now(TAIPEI);
        String rawBuyDate = request.getBuyDate();
        LocalDate buyDate;
        BigDecimal buyPrice;
        if (rawBuyDate == null || rawBuyDate.trim().isEmpty()) {
            // 省略時：trade_date <= 今日 且 close_price > 0 的最大 trade_date（specs/backend/
            // simulated-trade.md, "一筆持股怎麼建立"）. Note this is "<=" at the call site below but
            // findLatestPositiveCloseBefore takes an exclusive upper bound, so today is passed as
            // the (exclusive) boundary — today's own close is never picked before market close.
            StockDailyPrice buyRow = priceMapper.findLatestPositiveCloseBefore(stockId, today);
            if (buyRow == null) {
                throw new NoPriceBeforeTodayException(stockId);
            }
            buyDate = buyRow.getTradeDate();
            buyPrice = buyRow.getClosePrice();
        } else {
            // 指定買進日（本次新增）: 必須為合法日期、不得晚於今日，否則 400 INVALID_BUY_DATE；那一天
            // 必須正好有該檔的收盤價（存在且 > 0），否則 400 NO_PRICE_ON_BUY_DATE — 不得自動改用鄰近
            // 交易日 (specs/backend/simulated-trade.md, "指定買進日（本次新增）").
            LocalDate requestedDate = parseBuyDate(rawBuyDate.trim());
            if (requestedDate.isAfter(today)) {
                throw new InvalidSimulatedTradeBuyDateException(rawBuyDate);
            }
            StockDailyPrice buyRow = priceMapper.findOne(stockId, requestedDate);
            BigDecimal basisPrice = buyRow == null ? null : (useOpen ? buyRow.getOpenPrice() : buyRow.getClosePrice());
            if (basisPrice == null || basisPrice.signum() <= 0) {
                throw new NoPriceOnBuyDateException(stockId, requestedDate);
            }
            buyDate = requestedDate;
            buyPrice = basisPrice;
        }

        SimulatedTrade trade = new SimulatedTrade();
        trade.setStockId(stockId);
        trade.setBuyDate(buyDate);
        trade.setBuyPrice(buyPrice);
        trade.setShares(TradingCostCalculator.LOT_SIZE);

        try {
            simulatedTradeMapper.insert(trade);
        } catch (DuplicateKeyException e) {
            // Closes the check-then-act race the same way StockCatalogService#createStock does:
            // (stock_id, buy_date) is unique in the schema (specs/dba/simulated-trade.md), so a
            // concurrent duplicate insert surfaces here rather than as a raw 500.
            throw new DuplicateSimulatedTradeException(stockId, buyDate);
        }

        // The buy day itself always has a positive close (that is how it was chosen), so the
        // current-price lookup below is guaranteed to find at least that one row.
        Map<String, StockDailyPrice> currentByStock =
                loadCurrentPrices(Collections.singletonList(stockId), today);
        StockDailyPrice currentRow = currentByStock.get(stockId);
        return toItemDto(trade, stock.getStockName(), currentRow);
    }

    @Transactional
    public void delete(Long id) {
        int affected = simulatedTradeMapper.deleteById(id);
        if (affected == 0) {
            throw new SimulatedTradeNotFoundException(id);
        }
    }

    private SimulatedTradeResponseDto buildResponse(LocalDate asOfDate, LocalDate defaultBuyDate,
                                                      LocalDate settleDate, List<SimulatedTrade> trades) {
        SimulatedTradeResponseDto response = new SimulatedTradeResponseDto();
        response.setAsOfDate(asOfDate);
        response.setDefaultBuyDate(defaultBuyDate);
        response.setSettleDate(settleDate);
        response.setLotSize(TradingCostCalculator.LOT_SIZE);
        response.setFeeRatePercent(TradingCostCalculator.FEE_RATE_PERCENT);
        response.setTaxRatePercent(TradingCostCalculator.TAX_RATE_PERCENT);

        if (trades.isEmpty()) {
            // 清單為空時直接回空結果，不把空集合交給資料庫 (specs/backend/simulated-trade.md, "處理流程").
            response.setTotalCost(BigDecimal.ZERO);
            response.setTotalUnrealizedProfit(BigDecimal.ZERO);
            response.setTotalReturnPercent(null);
            response.setItems(Collections.emptyList());
            return response;
        }

        // LinkedHashSet, not a List#contains scan: keeps dedup O(n) instead of O(n^2) as holdings
        // grow, while still handing the batched queries below a stable-order distinct id list.
        Set<String> distinctStockIds = new LinkedHashSet<>();
        for (SimulatedTrade trade : trades) {
            distinctStockIds.add(trade.getStockId());
        }
        List<String> stockIds = new ArrayList<>(distinctStockIds);

        // Two bounded, batched queries total — not one per row (specs/backend/simulated-trade.md,
        // "以整份代號清單為條件批次查詢各檔的現價...與名稱—不得逐筆各發一次查詢").
        Map<String, StockDailyPrice> currentByStock = loadCurrentPrices(stockIds, asOfDate);
        Map<String, String> nameByStock = loadStockNames(stockIds);

        // One more batched query, only when a settleDate was requested: its count is independent of
        // the number of holdings.
        Map<Long, SimulatedTradeSettlementPrice> settlementByTrade = new HashMap<>();
        if (settleDate != null) {
            for (SimulatedTradeSettlementPrice row : simulatedTradeMapper.findSettlementPrices(settleDate)) {
                settlementByTrade.put(row.getTradeId(), row);
            }
        }

        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalUnrealizedProfit = BigDecimal.ZERO;
        BigDecimal settledTotalCost = BigDecimal.ZERO;
        BigDecimal settledTotalProfit = BigDecimal.ZERO;
        boolean anySettled = false;
        List<SimulatedTradeItemDto> items = new ArrayList<>(trades.size());
        for (SimulatedTrade trade : trades) {
            StockDailyPrice currentRow = currentByStock.get(trade.getStockId());
            SimulatedTradeItemDto item =
                    toItemDto(trade, nameByStock.get(trade.getStockId()), currentRow);
            SimulatedTradeSettlementPrice settlementPrice = settlementByTrade.get(trade.getId());
            if (settlementPrice != null) {
                item.setSettlement(toSettlement(trade, item.getCost(), settlementPrice));
                settledTotalCost = settledTotalCost.add(item.getCost());
                settledTotalProfit = settledTotalProfit.add(item.getSettlement().getProfit());
                anySettled = true;
            }
            items.add(item);
            totalCost = totalCost.add(item.getCost());
            totalUnrealizedProfit = totalUnrealizedProfit.add(item.getUnrealizedProfit());
        }

        response.setTotalCost(totalCost);
        response.setTotalUnrealizedProfit(totalUnrealizedProfit);
        response.setTotalReturnPercent(TradingCostCalculator.returnPercent(totalUnrealizedProfit, totalCost));
        response.setSettledTotalCost(settledTotalCost);
        response.setSettledTotalProfit(settledTotalProfit);
        response.setSettledTotalReturnPercent(
                anySettled ? TradingCostCalculator.returnPercent(settledTotalProfit, settledTotalCost) : null);
        response.setItems(items);
        return response;
    }

    private SimulatedTradeItemDto.Settlement toSettlement(SimulatedTrade trade, BigDecimal cost,
                                                           SimulatedTradeSettlementPrice sellRow) {
        BigDecimal sellPrice = sellRow.getOpenPrice();
        BigDecimal sellFee = TradingCostCalculator.fee(sellPrice, trade.getShares());
        BigDecimal sellTax = TradingCostCalculator.tax(sellPrice, trade.getShares());
        BigDecimal profit = TradingCostCalculator.profit(sellPrice, trade.getShares(), sellFee, sellTax, cost);
        SimulatedTradeItemDto.Settlement settlement = new SimulatedTradeItemDto.Settlement();
        settlement.setSellDate(sellRow.getTradeDate());
        settlement.setSellPrice(sellPrice);
        settlement.setSellFee(sellFee);
        settlement.setSellTax(sellTax);
        settlement.setProfit(profit);
        settlement.setReturnPercent(TradingCostCalculator.returnPercent(profit, cost));
        return settlement;
    }

    /**
     * Replaces a validated settleDate with the nearest market-wide trading day (fewest calendar days;
     * tie -> earlier; never after asOfDate). Keeps the date as-is when no trading day exists.
     */
    private LocalDate snapToTradingDay(LocalDate requested, LocalDate asOfDate) {
        if (requested == null) {
            return null;
        }
        TradingDayNeighbors n = priceMapper.findTradingDayNeighbors(requested, asOfDate);
        LocalDate prev = n == null ? null : n.getPrevious();
        LocalDate next = n == null ? null : n.getNext();
        if (prev == null) {
            return next != null ? next : requested;
        }
        if (next == null) {
            return prev;
        }
        long back = ChronoUnit.DAYS.between(prev, requested);
        long forward = ChronoUnit.DAYS.between(requested, next);
        return forward < back ? next : prev;
    }

    /** Absent -> null; malformed or after today -> INVALID_SETTLE_DATE echoing the raw string. */
    private LocalDate parseSettleDate(String raw, LocalDate today) {
        if (raw == null) {
            return null;
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidSettleDateException(raw);
        }
        if (parsed.isAfter(today)) {
            throw new InvalidSettleDateException(raw);
        }
        return parsed;
    }

    private SimulatedTradeItemDto toItemDto(SimulatedTrade trade, String stockName, StockDailyPrice currentRow) {
        if (currentRow == null) {
            // Cannot happen for a row this service itself wrote: buyDate always carries a positive
            // close (that is how it was selected), and buyDate <= asOfDate trivially, so the
            // current-price lookup always finds at least that one row.
            throw new IllegalStateException("No priceable trade date found for stock " + trade.getStockId());
        }

        SimulatedTradeItemDto dto = new SimulatedTradeItemDto();
        dto.setId(trade.getId());
        dto.setStockId(trade.getStockId());
        dto.setStockName(stockName);
        dto.setBuyDate(trade.getBuyDate());
        dto.setBuyPrice(trade.getBuyPrice());
        dto.setShares(trade.getShares());

        BigDecimal buyFee = TradingCostCalculator.fee(trade.getBuyPrice(), trade.getShares());
        BigDecimal cost = TradingCostCalculator.cost(trade.getBuyPrice(), trade.getShares(), buyFee);
        dto.setBuyFee(buyFee);
        dto.setCost(cost);

        BigDecimal currentPrice = currentRow.getClosePrice();
        dto.setCurrentDate(currentRow.getTradeDate());
        dto.setCurrentPrice(currentPrice);

        BigDecimal sellFee = TradingCostCalculator.fee(currentPrice, trade.getShares());
        BigDecimal sellTax = TradingCostCalculator.tax(currentPrice, trade.getShares());
        BigDecimal unrealizedProfit = TradingCostCalculator.profit(currentPrice, trade.getShares(), sellFee, sellTax, cost);
        BigDecimal returnPercent = TradingCostCalculator.returnPercent(unrealizedProfit, cost);
        dto.setSellFee(sellFee);
        dto.setSellTax(sellTax);
        dto.setUnrealizedProfit(unrealizedProfit);
        dto.setReturnPercent(returnPercent);
        return dto;
    }

    private Map<String, StockDailyPrice> loadCurrentPrices(List<String> stockIds, LocalDate asOfDate) {
        List<StockDailyPrice> rows = priceMapper.findLatestPositiveCloseOnOrBeforeByStockIds(stockIds, asOfDate);
        Map<String, StockDailyPrice> byStock = new HashMap<>();
        for (StockDailyPrice row : rows) {
            byStock.put(row.getStockId(), row);
        }
        return byStock;
    }

    private Map<String, String> loadStockNames(List<String> stockIds) {
        Map<String, String> byStock = new HashMap<>();
        for (Stock stock : stockMapper.findByIds(stockIds)) {
            byStock.put(stock.getStockId(), stock.getStockName());
        }
        return byStock;
    }

    /**
     * Parses a trimmed, non-blank {@code buyDate} string as an ISO-8601 local date (the same format
     * the wire contract's examples use, e.g. {@code "2026-09-18"}). Any format failure surfaces as
     * {@link InvalidSimulatedTradeBuyDateException} carrying the original raw string, per
     * specs/backend/simulated-trade.md, "指定買進日（本次新增）" — never a generic 500.
     */
    private LocalDate parseBuyDate(String trimmedBuyDate) {
        try {
            return LocalDate.parse(trimmedBuyDate);
        } catch (DateTimeParseException e) {
            throw new InvalidSimulatedTradeBuyDateException(trimmedBuyDate);
        }
    }

    /**
     * priceBasis: absent or CLOSE -> false, OPEN -> true; anything else, or OPEN without a buyDate,
     * -> INVALID_PRICE_BASIS (specs/backend/simulated-trade.md).
     */
    private boolean parsePriceBasisIsOpen(String priceBasis, String buyDate) {
        if (priceBasis == null || "CLOSE".equals(priceBasis)) {
            return false;
        }
        if ("OPEN".equals(priceBasis)) {
            if (buyDate == null || buyDate.trim().isEmpty()) {
                throw new InvalidPriceBasisException("priceBasis OPEN requires buyDate");
            }
            return true;
        }
        throw new InvalidPriceBasisException("Unknown priceBasis: " + priceBasis);
    }

    /** Trims and treats a blank result as absent — {@code null}/empty/whitespace-only all count as missing. */
    private String normalizeStockId(String rawStockId) {
        if (rawStockId == null) {
            return null;
        }
        String trimmed = rawStockId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
