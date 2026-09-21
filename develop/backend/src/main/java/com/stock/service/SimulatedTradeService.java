package com.stock.service;

import com.stock.domain.SimulatedTrade;
import com.stock.domain.Stock;
import com.stock.domain.StockDailyPrice;
import com.stock.dto.CreateSimulatedTradeRequest;
import com.stock.dto.SimulatedTradeItemDto;
import com.stock.dto.SimulatedTradeResponseDto;
import com.stock.exception.DuplicateSimulatedTradeException;
import com.stock.exception.InvalidSimulatedTradeBuyDateException;
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

    public SimulatedTradeResponseDto list() {
        LocalDate asOfDate = LocalDate.now(TAIPEI);
        // 全市場上一次收盤日（specs/backend/simulated-trade.md, "預設買進日（defaultBuyDate）"）— one
        // query, not scoped to any stock; null when the database holds no price rows at all.
        LocalDate defaultBuyDate = priceMapper.findMarketWideLatestPositiveCloseDate(asOfDate);
        List<SimulatedTrade> trades = simulatedTradeMapper.findAllOrderedByBuyDateDescStockIdAsc();
        return buildResponse(asOfDate, defaultBuyDate, trades);
    }

    @Transactional
    public SimulatedTradeItemDto create(CreateSimulatedTradeRequest request) {
        String stockId = normalizeStockId(request == null ? null : request.getStockId());
        if (stockId == null) {
            throw new InvalidStockIdException();
        }

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
            if (buyRow == null || buyRow.getClosePrice() == null
                    || buyRow.getClosePrice().signum() <= 0) {
                throw new NoPriceOnBuyDateException(stockId, requestedDate);
            }
            buyDate = requestedDate;
            buyPrice = buyRow.getClosePrice();
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
                                                      List<SimulatedTrade> trades) {
        SimulatedTradeResponseDto response = new SimulatedTradeResponseDto();
        response.setAsOfDate(asOfDate);
        response.setDefaultBuyDate(defaultBuyDate);
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

        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalUnrealizedProfit = BigDecimal.ZERO;
        List<SimulatedTradeItemDto> items = new ArrayList<>(trades.size());
        for (SimulatedTrade trade : trades) {
            StockDailyPrice currentRow = currentByStock.get(trade.getStockId());
            SimulatedTradeItemDto item =
                    toItemDto(trade, nameByStock.get(trade.getStockId()), currentRow);
            items.add(item);
            totalCost = totalCost.add(item.getCost());
            totalUnrealizedProfit = totalUnrealizedProfit.add(item.getUnrealizedProfit());
        }

        response.setTotalCost(totalCost);
        response.setTotalUnrealizedProfit(totalUnrealizedProfit);
        response.setTotalReturnPercent(TradingCostCalculator.returnPercent(totalUnrealizedProfit, totalCost));
        response.setItems(items);
        return response;
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

    /** Trims and treats a blank result as absent — {@code null}/empty/whitespace-only all count as missing. */
    private String normalizeStockId(String rawStockId) {
        if (rawStockId == null) {
            return null;
        }
        String trimmed = rawStockId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
