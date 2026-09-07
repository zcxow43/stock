package com.stock.service;

import com.stock.domain.Stock;
import com.stock.domain.StockDailyPrice;
import com.stock.domain.StockIndustryLink;
import com.stock.dto.IndustryGainGroupDto;
import com.stock.dto.MomentumGainResponseDto;
import com.stock.dto.StockGainItemDto;
import com.stock.exception.InvalidDateRangeException;
import com.stock.exception.InvalidDaysException;
import com.stock.exception.InvalidMetricException;
import com.stock.exception.InvalidMinGainException;
import com.stock.exception.InvalidModeException;
import com.stock.exception.InvalidSortException;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockIndustryMapper;
import com.stock.mapper.StockMapper;
import com.stock.util.CommonStockCodeUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GET /api/momentum/gain — read-only industry gain ranking over stock_daily_price
 * (specs/backend/industry-gain-ranking.md). Persists nothing: every call recomputes from scratch,
 * same rationale as {@link StrategyScanService}.
 *
 * <p>Query count is fixed regardless of population size — see specs/backend/industry-gain-ranking.md,
 * "行情與關聯的讀取必須批次進行": one period query (DAYS mode only) or one global trading-day-count
 * query (WEEKS mode only), one population query, one batched window range query, one batched
 * pre-startDate lookback query, and — only when at least one stock matched — one batched
 * industry-link query. None of these scale with the number of scanned stocks.
 */
@Service
public class MomentumGainService {

    private static final BigDecimal MIN_GAIN_FLOOR = new BigDecimal("-100");
    private static final BigDecimal MIN_GAIN_CEILING = new BigDecimal("1000");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SCALE = 2;
    private static final int INTERMEDIATE_SCALE = 10;
    private static final String UNCLASSIFIED_NAME = "未分類";
    private static final String SORT_MATCH_COUNT = "MATCH_COUNT";
    private static final String SORT_AVG_GAIN = "AVG_GAIN";

    private final StockMapper stockMapper;
    private final StockDailyPriceMapper priceMapper;
    private final StockIndustryMapper stockIndustryMapper;

    public MomentumGainService(StockMapper stockMapper, StockDailyPriceMapper priceMapper,
                                StockIndustryMapper stockIndustryMapper) {
        this.stockMapper = stockMapper;
        this.priceMapper = priceMapper;
        this.stockIndustryMapper = stockIndustryMapper;
    }

    public MomentumGainResponseDto getGain(String metricParam, String modeParam, Integer days,
                                            LocalDate startDateParam, LocalDate endDateParam,
                                            BigDecimal minGainParam, boolean commonStocksOnly,
                                            String sortParam) {
        String metric = validateMetric(metricParam);
        String mode = validateMode(modeParam);
        BigDecimal minGain = validateMinGain(minGainParam).setScale(SCALE, RoundingMode.HALF_UP);
        String sort = validateSort(sortParam);

        LocalDate startDate;
        LocalDate endDate;
        int tradingDays;

        if ("DAYS".equals(mode)) {
            validateDays(days);
            List<LocalDate> recentDates = priceMapper.findRecentDistinctTradeDates(days); // Query: period
            if (recentDates.isEmpty()) {
                startDate = null;
                endDate = null;
                tradingDays = 0;
            } else {
                startDate = recentDates.get(0);
                endDate = recentDates.get(recentDates.size() - 1);
                tradingDays = recentDates.size();
            }
        } else {
            if (startDateParam == null || endDateParam == null || startDateParam.isAfter(endDateParam)) {
                throw new InvalidDateRangeException("startDate/endDate required and startDate must not be after endDate for mode=WEEKS");
            }
            startDate = startDateParam;
            endDate = endDateParam;
            tradingDays = 0; // resolved below via countDistinctTradeDatesInRange — startDate is never null here
        }

        List<Stock> allActive = stockMapper.findActiveStocks(); // Query: population
        List<Stock> population = allActive;
        if (commonStocksOnly) {
            population = new ArrayList<>(allActive.size());
            for (Stock s : allActive) {
                if (CommonStockCodeUtil.isCommonStockCode(s.getStockId())) {
                    population.add(s);
                }
            }
        }
        int scannedStocks = population.size();

        if ("WEEKS".equals(mode)) {
            tradingDays = priceMapper.countDistinctTradeDatesInRange(startDate, endDate); // Query: WEEKS trading-day count
            if (tradingDays == 0) {
                startDate = null;
                endDate = null;
            }
        }

        if (population.isEmpty() || startDate == null) {
            return emptyResponse(metric, mode, sort, startDate, endDate, Math.max(tradingDays, 0), minGain, scannedStocks);
        }

        List<String> targetIds = new ArrayList<>(population.size());
        Map<String, String> stockNames = new LinkedHashMap<>();
        for (Stock s : population) {
            targetIds.add(s.getStockId());
            stockNames.put(s.getStockId(), s.getStockName());
        }

        List<StockDailyPrice> windowRows =
                priceMapper.findByStockIdsAndDateRange(targetIds, startDate, endDate); // Query: window
        List<StockDailyPrice> lookbackRows =
                priceMapper.findRecentBeforeDateByStockIds(targetIds, startDate, 1); // Query: lookback

        Map<String, List<StockDailyPrice>> barsByStock = new LinkedHashMap<>();
        for (StockDailyPrice row : windowRows) {
            barsByStock.computeIfAbsent(row.getStockId(), k -> new ArrayList<>()).add(row);
        }
        Map<String, BigDecimal> lookbackCloseByStock = new LinkedHashMap<>();
        for (StockDailyPrice row : lookbackRows) {
            lookbackCloseByStock.put(row.getStockId(), row.getClosePrice());
        }

        int insufficientDataCount = 0;
        Map<String, StockGainItemDto> matchedItemsByStock = new LinkedHashMap<>();

        for (String stockId : targetIds) {
            List<StockDailyPrice> bars = barsByStock.get(stockId);
            BigDecimal lookbackClose = lookbackCloseByStock.get(stockId);
            if (bars == null || bars.isEmpty() || lookbackClose == null) {
                insufficientDataCount++;
                continue;
            }
            bars.sort(Comparator.comparing(StockDailyPrice::getTradeDate));

            // A zero close_price (real production data: certain ETN/warrant-type instruments show
            // 0.00 on days they don't trade) makes the daily percentage change undefined — dividing
            // by it would throw ArithmeticException. Treat such a stock as insufficient data rather
            // than crashing the whole batch or fabricating a +/-infinite gain for it.
            BigDecimal sumPct = BigDecimal.ZERO;
            BigDecimal prevClose = lookbackClose;
            boolean hasZeroClose = prevClose.compareTo(BigDecimal.ZERO) == 0;
            for (StockDailyPrice bar : bars) {
                if (hasZeroClose) {
                    break;
                }
                BigDecimal dailyPct = bar.getClosePrice().subtract(prevClose)
                        .divide(prevClose, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
                        .multiply(HUNDRED);
                sumPct = sumPct.add(dailyPct);
                prevClose = bar.getClosePrice();
                hasZeroClose = prevClose.compareTo(BigDecimal.ZERO) == 0;
            }
            if (hasZeroClose) {
                insufficientDataCount++;
                continue;
            }

            BigDecimal rawValue = "SUM".equals(metric)
                    ? sumPct
                    : sumPct.divide(BigDecimal.valueOf(bars.size()), INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
            BigDecimal gain = rawValue.setScale(SCALE, RoundingMode.HALF_UP);

            if (gain.compareTo(minGain) < 0) {
                continue;
            }

            StockDailyPrice first = bars.get(0);
            StockDailyPrice last = bars.get(bars.size() - 1);

            StockGainItemDto item = new StockGainItemDto();
            item.setStockId(stockId);
            item.setStockName(stockNames.get(stockId));
            item.setGain(gain);
            item.setTradingDays(bars.size());
            item.setStartClose(first.getClosePrice().setScale(SCALE, RoundingMode.HALF_UP));
            item.setEndClose(last.getClosePrice().setScale(SCALE, RoundingMode.HALF_UP));
            item.setFirstTradeDate(first.getTradeDate());
            item.setLastTradeDate(last.getTradeDate());
            matchedItemsByStock.put(stockId, item);
        }

        List<StockIndustryLink> links = stockIndustryMapper
                .findLinksByStockIds(new ArrayList<>(matchedItemsByStock.keySet())); // Query: industry links (only fires when there are matches — mapper no-ops on empty input)

        Map<Integer, List<StockGainItemDto>> itemsByIndustryId = new LinkedHashMap<>();
        Map<Integer, String> industryNameById = new LinkedHashMap<>();
        Set<String> stocksWithLink = new LinkedHashSet<>();
        for (StockIndustryLink link : links) {
            StockGainItemDto item = matchedItemsByStock.get(link.getStockId());
            if (item == null) {
                continue;
            }
            itemsByIndustryId.computeIfAbsent(link.getIndustryId(), k -> new ArrayList<>()).add(item);
            industryNameById.putIfAbsent(link.getIndustryId(), link.getIndustryName());
            stocksWithLink.add(link.getStockId());
        }

        List<StockGainItemDto> unclassifiedItems = new ArrayList<>();
        for (Map.Entry<String, StockGainItemDto> entry : matchedItemsByStock.entrySet()) {
            if (!stocksWithLink.contains(entry.getKey())) {
                unclassifiedItems.add(entry.getValue());
            }
        }

        Comparator<StockGainItemDto> itemComparator = Comparator
                .comparing(StockGainItemDto::getGain, Comparator.reverseOrder())
                .thenComparing(StockGainItemDto::getStockId);

        List<IndustryGainGroupDto> industries = new ArrayList<>();
        for (Map.Entry<Integer, List<StockGainItemDto>> entry : itemsByIndustryId.entrySet()) {
            List<StockGainItemDto> items = entry.getValue();
            items.sort(itemComparator);
            IndustryGainGroupDto group = new IndustryGainGroupDto();
            group.setIndustryId(entry.getKey());
            group.setIndustryName(industryNameById.get(entry.getKey()));
            group.setMatchedCount(items.size());
            group.setAvgGain(averageGain(items));
            group.setItems(items);
            industries.add(group);
        }
        industries.sort(industryComparator(sort));

        if (!unclassifiedItems.isEmpty()) {
            unclassifiedItems.sort(itemComparator);
            IndustryGainGroupDto unclassified = new IndustryGainGroupDto();
            unclassified.setIndustryId(null);
            unclassified.setIndustryName(UNCLASSIFIED_NAME);
            unclassified.setMatchedCount(unclassifiedItems.size());
            unclassified.setAvgGain(averageGain(unclassifiedItems));
            unclassified.setItems(unclassifiedItems);
            industries.add(unclassified);
        }

        MomentumGainResponseDto response = new MomentumGainResponseDto();
        response.setMetric(metric);
        response.setMode(mode);
        response.setSort(sort);
        response.setStartDate(startDate);
        response.setEndDate(endDate);
        response.setTradingDays(tradingDays);
        response.setMinGain(minGain);
        response.setScannedStocks(scannedStocks);
        response.setMatchedStockCount(matchedItemsByStock.size());
        response.setInsufficientDataCount(insufficientDataCount);
        response.setIndustries(industries);
        return response;
    }

    private MomentumGainResponseDto emptyResponse(String metric, String mode, String sort, LocalDate startDate,
                                                    LocalDate endDate, int tradingDays, BigDecimal minGain,
                                                    int scannedStocks) {
        MomentumGainResponseDto response = new MomentumGainResponseDto();
        response.setMetric(metric);
        response.setMode(mode);
        response.setSort(sort);
        response.setStartDate(startDate);
        response.setEndDate(endDate);
        response.setTradingDays(tradingDays);
        response.setMinGain(minGain);
        response.setScannedStocks(scannedStocks);
        response.setMatchedStockCount(0);
        response.setInsufficientDataCount(0);
        response.setIndustries(new ArrayList<>());
        return response;
    }

    private String validateMetric(String metric) {
        if (!"SUM".equals(metric) && !"AVERAGE".equals(metric)) {
            throw new InvalidMetricException(metric);
        }
        return metric;
    }

    private String validateMode(String mode) {
        if (!"DAYS".equals(mode) && !"WEEKS".equals(mode)) {
            throw new InvalidModeException(mode);
        }
        return mode;
    }

    private void validateDays(Integer days) {
        if (days == null || days < 1 || days > 120) {
            throw new InvalidDaysException(days);
        }
    }

    private BigDecimal validateMinGain(BigDecimal minGain) {
        if (minGain == null || minGain.compareTo(MIN_GAIN_FLOOR) < 0 || minGain.compareTo(MIN_GAIN_CEILING) > 0) {
            throw new InvalidMinGainException(minGain);
        }
        return minGain;
    }

    private String validateSort(String sort) {
        if (sort == null) {
            return SORT_MATCH_COUNT;
        }
        if (!SORT_MATCH_COUNT.equals(sort) && !SORT_AVG_GAIN.equals(sort)) {
            throw new InvalidSortException(sort);
        }
        return sort;
    }

    /**
     * Mean of a block's already-rounded {@code items[].gain} values (spec "產業別彙總": the display
     * values, not the unrounded per-day sums), rounded to 2 decimals. {@code items} is never empty
     * here — a block is only ever built from at least one matched stock.
     */
    private BigDecimal averageGain(List<StockGainItemDto> items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (StockGainItemDto item : items) {
            sum = sum.add(item.getGain());
        }
        return sum.divide(BigDecimal.valueOf(items.size()), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Ordering between industry blocks only (never affects the item ordering within a block).
     * MATCH_COUNT: matchedCount desc, then industryName asc. AVG_GAIN: avgGain desc, then
     * matchedCount desc, then industryName asc. The "未分類" block is appended after this sort
     * runs, so it never participates in either ordering.
     */
    private Comparator<IndustryGainGroupDto> industryComparator(String sort) {
        if (SORT_AVG_GAIN.equals(sort)) {
            return Comparator
                    .comparing(IndustryGainGroupDto::getAvgGain, Comparator.reverseOrder())
                    .thenComparing(Comparator.comparingInt(IndustryGainGroupDto::getMatchedCount).reversed())
                    .thenComparing(IndustryGainGroupDto::getIndustryName);
        }
        return Comparator
                .comparingInt(IndustryGainGroupDto::getMatchedCount).reversed()
                .thenComparing(IndustryGainGroupDto::getIndustryName);
    }
}
