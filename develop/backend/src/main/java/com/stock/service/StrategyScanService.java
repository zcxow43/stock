package com.stock.service;

import com.stock.domain.Stock;
import com.stock.domain.StockDailyPrice;
import com.stock.dto.ScanRequestDto;
import com.stock.dto.ScanResponseDto;
import com.stock.dto.StrategyHitDto;
import com.stock.dto.StrategyResultDto;
import com.stock.dto.StrategySelectionDto;
import com.stock.exception.DuplicateStrategyException;
import com.stock.exception.InvalidDateRangeException;
import com.stock.exception.InvalidRisePercentException;
import com.stock.exception.NoStrategySelectedException;
import com.stock.exception.TooManyStocksException;
import com.stock.exception.UnknownStockIdException;
import com.stock.exception.UnknownStrategyException;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockMapper;
import com.stock.service.pattern.PatternDetectionOutcome;
import com.stock.service.pattern.PatternDetector;
import com.stock.util.CommonStockCodeUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * POST /api/strategies/scan — read-only pattern detection over stock_daily_price. Persists
 * nothing (see specs/backend/strategy-scan.md, Overview): every call re-scans from scratch.
 */
@Service
public class StrategyScanService {

    public static final int MAX_STOCK_IDS = 200;
    private static final BigDecimal RISE_PERCENT_MIN = BigDecimal.ZERO;
    private static final BigDecimal RISE_PERCENT_MAX = new BigDecimal("50");
    private static final int RISE_PERCENT_MAX_SCALE = 1;

    private final StockMapper stockMapper;
    private final StockDailyPriceMapper priceMapper;
    private final Map<String, PatternDetector> detectorsByCode;

    public StrategyScanService(StockMapper stockMapper, StockDailyPriceMapper priceMapper,
                                List<PatternDetector> detectors) {
        this.stockMapper = stockMapper;
        this.priceMapper = priceMapper;
        Map<String, PatternDetector> byCode = new LinkedHashMap<>();
        for (PatternDetector detector : detectors) {
            byCode.put(detector.getCode(), detector);
        }
        this.detectorsByCode = byCode;
    }

    public ScanResponseDto scan(ScanRequestDto request) {
        List<StrategySelectionDto> selections = validateStrategies(request.getStrategies());

        // Omitted -> true (specs/backend/strategy-scan.md, "commonStocksOnly（預設 true）"); ignored
        // entirely whenever stockIds names an explicit list.
        boolean commonStocksOnly = !Boolean.FALSE.equals(request.getCommonStocksOnly());
        List<Stock> targetStocks = resolveTargetStocks(request.getStockIds(), commonStocksOnly);

        LocalDate endDate = request.getEndDate() != null ? request.getEndDate() : LocalDate.now();
        LocalDate startDate = request.getStartDate() != null ? request.getStartDate() : endDate.minusMonths(1);
        if (startDate.isAfter(endDate)) {
            throw new InvalidDateRangeException("startDate must not be after endDate");
        }

        List<String> targetIds = new ArrayList<>(targetStocks.size());
        Map<String, String> stockNames = new LinkedHashMap<>();
        for (Stock s : targetStocks) {
            targetIds.add(s.getStockId());
            stockNames.put(s.getStockId(), s.getStockName());
        }

        Map<String, List<StockDailyPrice>> seriesByStock = loadSeries(selections, targetIds, startDate, endDate);

        List<StrategyResultDto> results = new ArrayList<>(selections.size());
        for (StrategySelectionDto selection : selections) {
            results.add(runStrategy(selection, targetIds, stockNames, seriesByStock, startDate, endDate));
        }

        ScanResponseDto response = new ScanResponseDto();
        response.setStartDate(startDate);
        response.setEndDate(endDate);
        response.setScannedStocks(targetIds.size());
        response.setResults(results);
        return response;
    }

    private List<StrategySelectionDto> validateStrategies(List<StrategySelectionDto> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new NoStrategySelectedException();
        }

        List<String> unknown = new ArrayList<>();
        List<String> seenCodes = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        for (StrategySelectionDto selection : selections) {
            PatternDetector detector = detectorsByCode.get(selection.getCode());
            if (detector == null) {
                unknown.add(String.valueOf(selection.getCode()));
                continue;
            }
            if (!detector.supportsPreset(selection.getPreset())) {
                unknown.add(selection.getCode() + ":" + selection.getPreset());
                continue;
            }
            if (seenCodes.contains(selection.getCode())) {
                if (!duplicated.contains(selection.getCode())) {
                    duplicated.add(selection.getCode());
                }
            } else {
                seenCodes.add(selection.getCode());
            }
        }
        if (!unknown.isEmpty()) {
            throw new UnknownStrategyException(unknown);
        }
        if (!duplicated.isEmpty()) {
            throw new DuplicateStrategyException(duplicated);
        }
        // Only reached once every code/preset is known — "strategy" in the error must name a real
        // strategy. Checked in submission order so the first offending selection is the one reported.
        for (StrategySelectionDto selection : selections) {
            validateRisePercent(selection);
        }
        return selections;
    }

    private void validateRisePercent(StrategySelectionDto selection) {
        BigDecimal risePercent = selection.getRisePercent();
        if (risePercent == null) {
            return;
        }
        if (risePercent.compareTo(RISE_PERCENT_MIN) < 0 || risePercent.compareTo(RISE_PERCENT_MAX) > 0) {
            throw new InvalidRisePercentException(selection.getCode());
        }
        try {
            // setScale(..., UNNECESSARY) throws iff rounding to 1 decimal place would lose precision,
            // i.e. iff the value genuinely carries more than one decimal digit — trailing zeros
            // (e.g. "2.50") are not "more than one decimal place" and pass.
            risePercent.setScale(RISE_PERCENT_MAX_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidRisePercentException(selection.getCode());
        }
    }

    private List<Stock> resolveTargetStocks(List<String> requestedIds, boolean commonStocksOnly) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            List<Stock> active = stockMapper.findActiveStocks();
            if (!commonStocksOnly) {
                return active;
            }
            // 普通股 filter shared verbatim with the universe import / stock catalog (specs/backend/
            // strategy-scan.md, "普通股篩選"); scan-population only, never touches the stock table.
            List<Stock> commonOnly = new ArrayList<>();
            for (Stock s : active) {
                if (CommonStockCodeUtil.isCommonStockCode(s.getStockId())) {
                    commonOnly.add(s);
                }
            }
            return commonOnly;
        }
        if (requestedIds.size() > MAX_STOCK_IDS) {
            throw new TooManyStocksException(MAX_STOCK_IDS);
        }

        // Explicitly named ids may include delisted stocks (spec: "使用者明確指名時不代掃描範圍過濾").
        List<String> dedup = new ArrayList<>(new LinkedHashSet<>(requestedIds));
        List<Stock> found = stockMapper.findByIds(dedup);
        Map<String, Stock> byId = new LinkedHashMap<>();
        for (Stock s : found) {
            byId.put(s.getStockId(), s);
        }
        List<String> unknownIds = new ArrayList<>();
        for (String id : dedup) {
            if (!byId.containsKey(id)) {
                unknownIds.add(id);
            }
        }
        if (!unknownIds.isEmpty()) {
            throw new UnknownStockIdException(unknownIds);
        }

        List<Stock> result = new ArrayList<>(dedup.size());
        for (String id : dedup) {
            result.add(byId.get(id));
        }
        return result;
    }

    /**
     * Batched price read: a fixed, small number of queries regardless of how many stocks are
     * scanned — one range query for [startDate, endDate], one window-function query for the lookback
     * bars strictly before startDate, and (only when a selected strategy needs it, e.g.
     * RISING_SUPPORT's D+1/D+2) one more window-function query for confirmation bars strictly after
     * endDate. See specs/backend/strategy-scan.md, "行情讀取必須批次進行" and "上漲支撐的確認資料取自 endDate 之後".
     */
    private Map<String, List<StockDailyPrice>> loadSeries(List<StrategySelectionDto> selections,
                                                            List<String> targetIds, LocalDate startDate,
                                                            LocalDate endDate) {
        Map<String, List<StockDailyPrice>> seriesByStock = new LinkedHashMap<>();
        for (String id : targetIds) {
            seriesByStock.put(id, new ArrayList<>());
        }
        if (targetIds.isEmpty()) {
            return seriesByStock;
        }

        int maxLookback = 0;
        int maxConfirmAfter = 0;
        for (StrategySelectionDto selection : selections) {
            PatternDetector detector = detectorsByCode.get(selection.getCode());
            maxLookback = Math.max(maxLookback, detector.requiredLookbackTradingDays(selection.getPreset()));
            maxConfirmAfter = Math.max(maxConfirmAfter,
                    detector.requiredConfirmTradingDaysAfterEndDate(selection.getPreset()));
        }

        if (maxLookback > 0) {
            List<StockDailyPrice> lookbackRows =
                    priceMapper.findRecentBeforeDateByStockIds(targetIds, startDate, maxLookback);
            for (StockDailyPrice row : lookbackRows) {
                seriesByStock.computeIfAbsent(row.getStockId(), k -> new ArrayList<>()).add(row);
            }
        }
        List<StockDailyPrice> windowRows = priceMapper.findByStockIdsAndDateRange(targetIds, startDate, endDate);
        for (StockDailyPrice row : windowRows) {
            seriesByStock.computeIfAbsent(row.getStockId(), k -> new ArrayList<>()).add(row);
        }
        if (maxConfirmAfter > 0) {
            List<StockDailyPrice> confirmRows =
                    priceMapper.findRecentAfterDateByStockIds(targetIds, endDate, maxConfirmAfter);
            for (StockDailyPrice row : confirmRows) {
                seriesByStock.computeIfAbsent(row.getStockId(), k -> new ArrayList<>()).add(row);
            }
        }
        return seriesByStock;
    }

    private StrategyResultDto runStrategy(StrategySelectionDto selection, List<String> targetIds,
                                           Map<String, String> stockNames,
                                           Map<String, List<StockDailyPrice>> seriesByStock,
                                           LocalDate startDate, LocalDate endDate) {
        PatternDetector detector = detectorsByCode.get(selection.getCode());
        List<StrategyHitDto> items = new ArrayList<>();
        List<String> insufficientData = new ArrayList<>();
        List<String> pendingConfirm = new ArrayList<>();

        for (String stockId : targetIds) {
            List<StockDailyPrice> bars = seriesByStock.getOrDefault(stockId, Collections.emptyList());
            PatternDetectionOutcome outcome = detector.detect(bars, startDate, endDate, selection.getPreset(),
                    selection.getRisePercent());
            if (outcome.isInsufficientData()) {
                insufficientData.add(stockId);
            } else if (outcome.isHit()) {
                items.add(new StrategyHitDto(stockId, stockNames.get(stockId), outcome.getSignalDate(),
                        outcome.getDetail()));
            } else if (outcome.isPendingConfirm()) {
                pendingConfirm.add(stockId);
            }
        }

        items.sort(Comparator.comparing(StrategyHitDto::getSignalDate).reversed()
                .thenComparing(StrategyHitDto::getStockId));

        StrategyResultDto result = new StrategyResultDto();
        result.setStrategy(selection.getCode());
        result.setPreset(selection.getPreset());
        result.setMatchedCount(items.size());
        result.setItems(items);
        result.setInsufficientData(insufficientData);
        result.setPendingConfirm(pendingConfirm);
        return result;
    }
}
