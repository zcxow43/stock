package com.stock.service;

import com.stock.domain.RealTrade;
import com.stock.domain.Stock;
import com.stock.domain.StockDailyPrice;
import com.stock.dto.CreateRealTradeRequest;
import com.stock.dto.RealTradeItemDto;
import com.stock.dto.RealTradeResponseDto;
import com.stock.dto.UpdateRealTradeRequest;
import com.stock.exception.InvalidBuyPriceException;
import com.stock.exception.InvalidSharesException;
import com.stock.exception.InvalidTargetSellPriceException;
import com.stock.exception.InvalidSimulatedTradeBuyDateException;
import com.stock.exception.InvalidStockIdException;
import com.stock.exception.RealTradeNotFoundException;
import com.stock.exception.UnknownStockIdException;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockMapper;
import com.stock.util.TradingCostCalculator;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GET/POST/PATCH/DELETE /api/real-trades — specs/backend/real-trade.md. The mirror image of
 * {@link SimulatedTradeService}: same cost model ({@link TradingCostCalculator}), same current-price
 * lookup, same ordering rule. What differs is storage (a CSV, see {@link RealTradeCsvStore}) and that
 * buy price and share count come from the caller. Nothing here writes to the database.
 */
@Service
public class RealTradeService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final int MAX_PRICE_SCALE = 2;

    private final RealTradeCsvStore store;
    private final StockMapper stockMapper;
    private final StockDailyPriceMapper priceMapper;

    public RealTradeService(RealTradeCsvStore store, StockMapper stockMapper, StockDailyPriceMapper priceMapper) {
        this.store = store;
        this.stockMapper = stockMapper;
        this.priceMapper = priceMapper;
    }

    public RealTradeResponseDto list() {
        LocalDate asOfDate = LocalDate.now(TAIPEI);
        RealTradeCsvStore.Snapshot snapshot = store.read();
        List<RealTrade> trades = snapshot.getTrades();

        RealTradeResponseDto response = new RealTradeResponseDto();
        response.setAsOfDate(asOfDate);
        response.setFeeRatePercent(TradingCostCalculator.FEE_RATE_PERCENT);
        response.setTaxRatePercent(TradingCostCalculator.TAX_RATE_PERCENT);
        response.setSkippedLines(snapshot.getSkippedLines());

        if (trades.isEmpty()) {
            response.setTotalCost(BigDecimal.ZERO);
            response.setTotalUnrealizedProfit(BigDecimal.ZERO);
            response.setTotalReturnPercent(null);
            response.setItems(Collections.emptyList());
            return response;
        }

        // Two batched queries regardless of how many rows the file holds.
        Set<String> distinctIds = new LinkedHashSet<>();
        for (RealTrade trade : trades) {
            distinctIds.add(trade.getStockId());
        }
        List<String> stockIds = new ArrayList<>(distinctIds);
        Map<String, StockDailyPrice> currentByStock = loadCurrentPrices(stockIds, asOfDate);
        Map<String, String> nameByStock = new HashMap<>();
        for (Stock stock : stockMapper.findByIds(stockIds)) {
            nameByStock.put(stock.getStockId(), stock.getStockName());
        }

        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalUnrealizedProfit = BigDecimal.ZERO;
        // Denominator of totalReturnPercent: only rows that have a profit to put in the numerator.
        BigDecimal pricedCost = BigDecimal.ZERO;
        List<RealTradeItemDto> items = new ArrayList<>(trades.size());
        for (RealTrade trade : trades) {
            RealTradeItemDto item = toItemDto(trade, nameByStock.get(trade.getStockId()),
                    currentByStock.get(trade.getStockId()));
            items.add(item);
            if (item.isExcluded()) {
                continue; // reported in items with its own numbers, but left out of every total
            }
            totalCost = totalCost.add(item.getCost());
            if (item.getUnrealizedProfit() != null) {
                totalUnrealizedProfit = totalUnrealizedProfit.add(item.getUnrealizedProfit());
                pricedCost = pricedCost.add(item.getCost());
            }
        }
        items.sort(Comparator.comparing(RealTradeItemDto::getBuyDate).reversed()
                .thenComparing(Comparator.comparing(RealTradeItemDto::getStockId))
                .thenComparingInt(RealTradeItemDto::getId));

        response.setTotalCost(totalCost);
        response.setTotalUnrealizedProfit(totalUnrealizedProfit);
        response.setTotalReturnPercent(pricedCost.signum() == 0
                ? null : TradingCostCalculator.returnPercent(totalUnrealizedProfit, pricedCost));
        response.setItems(items);
        return response;
    }

    public RealTradeItemDto create(CreateRealTradeRequest request) {
        if (request == null) {
            request = new CreateRealTradeRequest(); // a literal JSON `null` body: every field is missing
        }
        String stockId = request.getStockId() == null ? "" : request.getStockId().trim();
        if (stockId.isEmpty()) {
            throw new InvalidStockIdException();
        }
        Stock stock = stockMapper.findById(stockId);
        if (stock == null) {
            throw new UnknownStockIdException(Collections.singletonList(stockId));
        }

        LocalDate today = LocalDate.now(TAIPEI);
        LocalDate buyDate = parseBuyDate(request.getBuyDate(), today);
        BigDecimal buyPrice = request.getBuyPrice();
        if (buyPrice == null || buyPrice.signum() <= 0 || buyPrice.stripTrailingZeros().scale() > MAX_PRICE_SCALE) {
            throw new InvalidBuyPriceException(buyPrice);
        }
        BigDecimal sharesValue = request.getShares();
        if (sharesValue == null || sharesValue.signum() <= 0 || sharesValue.stripTrailingZeros().scale() > 0
                || sharesValue.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new InvalidSharesException(sharesValue);
        }

        RealTrade trade = store.append(stockId, buyDate, buyPrice, sharesValue.intValueExact());
        StockDailyPrice current = loadCurrentPrices(Collections.singletonList(stockId), today).get(stockId);
        return toItemDto(trade, stock.getStockName(), current);
    }

    public void delete(String rawId) {
        store.delete(parseId(rawId));
    }

    /** PATCH: only {@code excluded} and {@code targetSellPrice} can change; an absent field is left alone. */
    public RealTradeItemDto update(String rawId, UpdateRealTradeRequest request) {
        Long id = parseId(rawId);
        if (request == null) {
            request = new UpdateRealTradeRequest(); // a literal JSON `null` body: nothing to change
        }
        BigDecimal target = request.getTargetSellPrice();
        if (request.isTargetSellPricePresent() && target != null
                && (target.signum() <= 0 || target.stripTrailingZeros().scale() > MAX_PRICE_SCALE)) {
            throw new InvalidTargetSellPriceException(target);
        }
        RealTrade trade = store.update(id, request.getExcluded(), request.isTargetSellPricePresent(), target);
        Stock stock = stockMapper.findById(trade.getStockId());
        StockDailyPrice current = loadCurrentPrices(Collections.singletonList(trade.getStockId()),
                LocalDate.now(TAIPEI)).get(trade.getStockId());
        return toItemDto(trade, stock == null ? null : stock.getStockName(), current);
    }

    /** A path segment that is not an integer is the spec's 404, with no id to echo. */
    private Long parseId(String rawId) {
        try {
            return Long.valueOf(rawId.trim());
        } catch (NumberFormatException e) {
            throw new RealTradeNotFoundException(null);
        }
    }

    private LocalDate parseBuyDate(String raw, LocalDate today) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new InvalidSimulatedTradeBuyDateException(raw);
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidSimulatedTradeBuyDateException(raw);
        }
        if (parsed.isAfter(today)) {
            throw new InvalidSimulatedTradeBuyDateException(raw);
        }
        return parsed;
    }

    /** Cost needs only buy price and shares; the sell side exists only when a current price does. */
    private RealTradeItemDto toItemDto(RealTrade trade, String stockName, StockDailyPrice currentRow) {
        RealTradeItemDto dto = new RealTradeItemDto();
        dto.setId(trade.getId());
        dto.setStockId(trade.getStockId());
        dto.setStockName(stockName);
        dto.setBuyDate(trade.getBuyDate());
        dto.setBuyPrice(trade.getBuyPrice());
        dto.setShares(trade.getShares());
        dto.setExcluded(trade.isExcluded());
        dto.setTargetSellPrice(trade.getTargetSellPrice());

        BigDecimal buyFee = TradingCostCalculator.fee(trade.getBuyPrice(), trade.getShares());
        BigDecimal cost = TradingCostCalculator.cost(trade.getBuyPrice(), trade.getShares(), buyFee);
        dto.setBuyFee(buyFee);
        dto.setCost(cost);

        if (currentRow == null) {
            return dto;
        }
        BigDecimal currentPrice = currentRow.getClosePrice();
        BigDecimal sellFee = TradingCostCalculator.fee(currentPrice, trade.getShares());
        BigDecimal sellTax = TradingCostCalculator.tax(currentPrice, trade.getShares());
        BigDecimal profit = TradingCostCalculator.profit(currentPrice, trade.getShares(), sellFee, sellTax, cost);
        dto.setCurrentDate(currentRow.getTradeDate());
        dto.setCurrentPrice(currentPrice);
        dto.setSellFee(sellFee);
        dto.setSellTax(sellTax);
        dto.setUnrealizedProfit(profit);
        dto.setReturnPercent(TradingCostCalculator.returnPercent(profit, cost));
        return dto;
    }

    private Map<String, StockDailyPrice> loadCurrentPrices(List<String> stockIds, LocalDate asOfDate) {
        Map<String, StockDailyPrice> byStock = new HashMap<>();
        for (StockDailyPrice row : priceMapper.findLatestPositiveCloseOnOrBeforeByStockIds(stockIds, asOfDate)) {
            byStock.put(row.getStockId(), row);
        }
        return byStock;
    }
}
