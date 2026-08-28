package com.stock.service;

import com.stock.domain.StatSeriesRow;
import com.stock.domain.Stock;
import com.stock.domain.StockDailyIndicator;
import com.stock.dto.StatisticsItemDto;
import com.stock.dto.StatisticsResponseDto;
import com.stock.dto.StatisticsSeriesRowDto;
import com.stock.dto.StatisticsSummaryDto;
import com.stock.exception.InvalidDateRangeException;
import com.stock.exception.SeriesNotAllowedForAllScopeException;
import com.stock.exception.TooManyStockIdsException;
import com.stock.exception.UnknownStockIdException;
import com.stock.mapper.StockDailyIndicatorMapper;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockMapper;
import com.stock.mapper.StockStatisticsMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * GET /api/stocks/statistics — the two-month (default) price + MACD/KD statistics query.
 * Series is always built from a LEFT JOIN of stock_daily_price to stock_daily_indicator
 * (StockStatisticsMapper#findSeries): price fields are never null, indicator fields are null
 * whenever that trade date hasn't had its indicator computed yet — see
 * specs/backend/stock-indicator-statistics.md, "指標尚未運算時的行為".
 */
@Service
public class StockStatisticsService {

    public static final int MAX_STOCK_IDS = 50;
    private static final int WARMUP_CAP = StockDailyIndicator.WARMUP_TRADING_DAYS;
    private static final int PRICE_SCALE = 2;
    private static final int INDICATOR_SCALE = 4;

    private final StockMapper stockMapper;
    private final StockDailyPriceMapper priceMapper;
    private final StockDailyIndicatorMapper indicatorMapper;
    private final StockStatisticsMapper statisticsMapper;

    public StockStatisticsService(StockMapper stockMapper, StockDailyPriceMapper priceMapper,
                                   StockDailyIndicatorMapper indicatorMapper, StockStatisticsMapper statisticsMapper) {
        this.stockMapper = stockMapper;
        this.priceMapper = priceMapper;
        this.indicatorMapper = indicatorMapper;
        this.statisticsMapper = statisticsMapper;
    }

    public StatisticsResponseDto getStatistics(String stockIdsParam, LocalDate startDateParam,
                                                LocalDate endDateParam, Boolean includeSeriesParam) {
        List<String> rawIds = parseStockIds(stockIdsParam);
        if (rawIds.size() > MAX_STOCK_IDS) {
            throw new TooManyStockIdsException(MAX_STOCK_IDS);
        }

        boolean selected = !rawIds.isEmpty();
        String scope = selected ? "SELECTED" : "ALL";

        boolean includeSeries;
        if (selected) {
            includeSeries = includeSeriesParam == null || includeSeriesParam;
        } else {
            if (Boolean.TRUE.equals(includeSeriesParam)) {
                throw new SeriesNotAllowedForAllScopeException();
            }
            includeSeries = false;
        }

        List<String> targetIds;
        Map<String, String> stockNames = new LinkedHashMap<>();
        if (selected) {
            targetIds = new ArrayList<>(new LinkedHashSet<>(rawIds));
            List<Stock> stocks = stockMapper.findByIds(targetIds);
            Map<String, String> byId = new LinkedHashMap<>();
            for (Stock s : stocks) {
                byId.put(s.getStockId(), s.getStockName());
            }
            List<String> unknown = new ArrayList<>();
            for (String id : targetIds) {
                if (!byId.containsKey(id)) {
                    unknown.add(id);
                }
            }
            if (!unknown.isEmpty()) {
                throw new UnknownStockIdException(unknown);
            }
            stockNames.putAll(byId);
        } else {
            List<Stock> stocks = stockMapper.findActiveStocks();
            targetIds = new ArrayList<>(stocks.size());
            for (Stock s : stocks) {
                targetIds.add(s.getStockId());
                stockNames.put(s.getStockId(), s.getStockName());
            }
        }

        LocalDate endDate = endDateParam != null ? endDateParam : priceMapper.findGlobalLatestTradeDate();
        LocalDate startDate;
        if (startDateParam != null) {
            startDate = startDateParam;
        } else if (endDate != null) {
            LocalDate candidate = priceMapper.findGlobalFirstTradeDateOnOrAfter(endDate.minusMonths(2));
            startDate = candidate != null ? candidate : endDate.minusMonths(2);
        } else {
            startDate = null;
        }

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new InvalidDateRangeException("startDate must not be after endDate");
        }

        List<StatisticsItemDto> items = new ArrayList<>(targetIds.size());
        if (!targetIds.isEmpty() && startDate != null && endDate != null) {
            List<StatSeriesRow> allRows = statisticsMapper.findSeries(
                    targetIds, startDate, endDate, StockDailyIndicator.PARAM_KEY);
            Map<String, List<StatSeriesRow>> byStock = new LinkedHashMap<>();
            for (StatSeriesRow row : allRows) {
                byStock.computeIfAbsent(row.getStockId(), k -> new ArrayList<>()).add(row);
            }

            for (String stockId : targetIds) {
                List<StatSeriesRow> rows = byStock.getOrDefault(stockId, java.util.Collections.emptyList());
                items.add(buildItem(stockId, stockNames.get(stockId), rows, startDate, includeSeries));
            }
        } else {
            for (String stockId : targetIds) {
                items.add(buildItem(stockId, stockNames.get(stockId), java.util.Collections.emptyList(),
                        startDate, includeSeries));
            }
        }

        return new StatisticsResponseDto(startDate, endDate, StockDailyIndicator.PARAM_KEY, scope,
                items.size(), items);
    }

    private StatisticsItemDto buildItem(String stockId, String stockName, List<StatSeriesRow> rows,
                                         LocalDate startDate, boolean includeSeries) {
        StatisticsItemDto item = new StatisticsItemDto();
        item.setStockId(stockId);
        item.setStockName(stockName);
        item.setTradingDays(rows.size());

        boolean warmupSufficient = false;
        if (!rows.isEmpty() && startDate != null) {
            int count = indicatorMapper.countNonWarmupBefore(stockId, StockDailyIndicator.PARAM_KEY, startDate, WARMUP_CAP);
            warmupSufficient = count >= WARMUP_CAP;
        }
        item.setWarmupSufficient(warmupSufficient);

        if (rows.isEmpty()) {
            item.setSummary(null);
        } else {
            item.setSummary(buildSummary(rows));
        }

        if (includeSeries) {
            List<StatisticsSeriesRowDto> series = new ArrayList<>(rows.size());
            for (StatSeriesRow row : rows) {
                series.add(toSeriesDto(row));
            }
            item.setSeries(series);
        }

        return item;
    }

    private StatisticsSeriesRowDto toSeriesDto(StatSeriesRow row) {
        StatisticsSeriesRowDto dto = new StatisticsSeriesRowDto();
        dto.setTradeDate(row.getTradeDate());
        dto.setOpen(price(row.getOpenPrice()));
        dto.setHigh(price(row.getHighPrice()));
        dto.setLow(price(row.getLowPrice()));
        dto.setClose(price(row.getClosePrice()));
        dto.setVolume(row.getVolume());
        dto.setDif(indicator(row.getDif()));
        dto.setDea(indicator(row.getDea()));
        dto.setOsc(indicator(row.getOsc()));
        dto.setK(indicator(row.getKValue()));
        dto.setD(indicator(row.getDValue()));
        dto.setJ(indicator(row.getJValue()));
        return dto;
    }

    private StatisticsSummaryDto buildSummary(List<StatSeriesRow> rows) {
        StatisticsSummaryDto summary = new StatisticsSummaryDto();

        StatSeriesRow first = rows.get(0);
        StatSeriesRow last = rows.get(rows.size() - 1);

        summary.setFirstOpen(price(first.getOpenPrice()));
        summary.setLastClose(price(last.getClosePrice()));

        BigDecimal highest = null;
        LocalDate highestDate = null;
        BigDecimal lowest = null;
        LocalDate lowestDate = null;
        long totalVolume = 0;

        StatSeriesRow latestWithIndicator = null;
        for (StatSeriesRow row : rows) {
            if (highest == null || row.getHighPrice().compareTo(highest) > 0) {
                highest = row.getHighPrice();
                highestDate = row.getTradeDate();
            }
            if (lowest == null || row.getLowPrice().compareTo(lowest) < 0) {
                lowest = row.getLowPrice();
                lowestDate = row.getTradeDate();
            }
            totalVolume += row.getVolume();
            if (row.getDif() != null) {
                latestWithIndicator = row;
            }
        }

        summary.setHighest(price(highest));
        summary.setHighestDate(highestDate);
        summary.setLowest(price(lowest));
        summary.setLowestDate(lowestDate);

        BigDecimal changeAmount = last.getClosePrice().subtract(first.getOpenPrice());
        summary.setChangeAmount(price(changeAmount));
        if (first.getOpenPrice().compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal changePercent = changeAmount
                    .divide(first.getOpenPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            summary.setChangePercent(changePercent);
        }

        summary.setTotalVolume(totalVolume);
        summary.setAvgVolume(rows.isEmpty() ? 0
                : BigDecimal.valueOf(totalVolume)
                        .divide(BigDecimal.valueOf(rows.size()), 0, RoundingMode.HALF_UP)
                        .longValueExact());

        if (latestWithIndicator != null) {
            summary.setLatestDif(indicator(latestWithIndicator.getDif()));
            summary.setLatestDea(indicator(latestWithIndicator.getDea()));
            summary.setLatestOsc(indicator(latestWithIndicator.getOsc()));
            summary.setLatestK(indicator(latestWithIndicator.getKValue()));
            summary.setLatestD(indicator(latestWithIndicator.getDValue()));
            summary.setLatestJ(indicator(latestWithIndicator.getJValue()));

            int macdGolden = 0;
            int macdDeath = 0;
            int kdGolden = 0;
            int kdDeath = 0;
            for (int i = 1; i < rows.size(); i++) {
                StatSeriesRow prev = rows.get(i - 1);
                StatSeriesRow curr = rows.get(i);
                if (prev.getOsc() != null && curr.getOsc() != null) {
                    boolean prevLe = prev.getOsc().compareTo(BigDecimal.ZERO) <= 0;
                    boolean prevGe = prev.getOsc().compareTo(BigDecimal.ZERO) >= 0;
                    boolean currGt = curr.getOsc().compareTo(BigDecimal.ZERO) > 0;
                    boolean currLt = curr.getOsc().compareTo(BigDecimal.ZERO) < 0;
                    if (prevLe && currGt) {
                        macdGolden++;
                    }
                    if (prevGe && currLt) {
                        macdDeath++;
                    }
                }
                if (prev.getKValue() != null && prev.getDValue() != null
                        && curr.getKValue() != null && curr.getDValue() != null) {
                    boolean prevLe = prev.getKValue().compareTo(prev.getDValue()) <= 0;
                    boolean prevGe = prev.getKValue().compareTo(prev.getDValue()) >= 0;
                    boolean currGt = curr.getKValue().compareTo(curr.getDValue()) > 0;
                    boolean currLt = curr.getKValue().compareTo(curr.getDValue()) < 0;
                    if (prevLe && currGt) {
                        kdGolden++;
                    }
                    if (prevGe && currLt) {
                        kdDeath++;
                    }
                }
            }
            summary.setMacdGoldenCross(macdGolden);
            summary.setMacdDeathCross(macdDeath);
            summary.setKdGoldenCross(kdGolden);
            summary.setKdDeathCross(kdDeath);
        }

        return summary;
    }

    private BigDecimal price(BigDecimal value) {
        return value == null ? null : value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal indicator(BigDecimal value) {
        return value == null ? null : value.setScale(INDICATOR_SCALE, RoundingMode.HALF_UP);
    }

    private List<String> parseStockIds(String stockIdsParam) {
        if (stockIdsParam == null || stockIdsParam.trim().isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String part : stockIdsParam.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
