package com.stock.service;

import com.stock.domain.Stock;
import com.stock.domain.StockDailyPrice;
import com.stock.domain.StockInstitutionalTrade;
import com.stock.dto.ScanRequestDto;
import com.stock.dto.ScanResponseDto;
import com.stock.dto.StrategyHitDto;
import com.stock.dto.StrategyResultDto;
import com.stock.dto.StrategySelectionDto;
import com.stock.exception.DaysNotApplicableException;
import com.stock.exception.DuplicateStrategyException;
import com.stock.exception.InvalidBuyDaysException;
import com.stock.exception.InvalidDateRangeException;
import com.stock.exception.InvalidDropDaysException;
import com.stock.exception.InvalidDropPercentException;
import com.stock.exception.InvalidInvestorsException;
import com.stock.exception.InvalidRatioPercentException;
import com.stock.exception.InvalidRiseDaysException;
import com.stock.exception.InvalidRisePercentException;
import com.stock.exception.InvalidStrategyDaysException;
import com.stock.exception.InvalidTopNException;
import com.stock.exception.InvalidWindowDaysException;
import com.stock.exception.NoStrategySelectedException;
import com.stock.exception.ParamNotApplicableException;
import com.stock.exception.PresetNotApplicableException;
import com.stock.exception.TooManyStocksException;
import com.stock.exception.UnknownStockIdException;
import com.stock.exception.UnknownStrategyException;
import com.stock.mapper.StockDailyPriceMapper;
import com.stock.mapper.StockInstitutionalTradeMapper;
import com.stock.mapper.StockMapper;
import com.stock.service.pattern.InstitutionalPatternDetector;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * POST /api/strategies/scan — read-only pattern detection over stock_daily_price. Persists
 * nothing (see specs/backend/strategy-scan.md, Overview): every call re-scans from scratch.
 */
@Service
public class StrategyScanService {

    public static final int MAX_STOCK_IDS = 200;
    private static final BigDecimal RISE_PERCENT_MIN = BigDecimal.ZERO;
    // REBOUND's dropPercent has only ever been the one strategy that owns it, so — unlike
    // risePercent — its upper bound stays a single fixed value rather than a per-detector one
    // (specs/backend/strategy-scan.md, "反彈的 dropPercent 維持 0~50 不變").
    private static final BigDecimal DROP_PERCENT_MAX = new BigDecimal("50");
    private static final int RISE_PERCENT_MAX_SCALE = 1;
    // Shared, fixed upper bounds for the three institutional params that are not per-detector (see
    // specs/backend/strategy-scan.md, "法人籌碼型態" parameter tables) — windowDays/buyDays share one
    // bound across the detectors that accept them, so a single constant each is enough.
    private static final int WINDOW_OR_BUY_DAYS_MIN = 1;
    private static final int WINDOW_OR_BUY_DAYS_MAX = 20;
    private static final BigDecimal RATIO_PERCENT_MAX = new BigDecimal("100");
    private static final int TOP_N_MIN = 1;
    private static final int TOP_N_MAX = 50;

    private final StockMapper stockMapper;
    private final StockDailyPriceMapper priceMapper;
    private final StockInstitutionalTradeMapper institutionalTradeMapper;
    private final Map<String, PatternDetector> detectorsByCode;

    public StrategyScanService(StockMapper stockMapper, StockDailyPriceMapper priceMapper,
                                StockInstitutionalTradeMapper institutionalTradeMapper,
                                List<PatternDetector> detectors) {
        this.stockMapper = stockMapper;
        this.priceMapper = priceMapper;
        this.institutionalTradeMapper = institutionalTradeMapper;
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
        InstitutionalData institutionalData =
                loadInstitutionalData(selections, targetIds, seriesByStock, startDate, endDate);

        List<StrategyResultDto> results = new ArrayList<>(selections.size());
        for (StrategySelectionDto selection : selections) {
            results.add(runStrategy(selection, targetIds, stockNames, seriesByStock, institutionalData, startDate,
                    endDate));
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
            // Preset validity only applies to the four preset-driven strategies; CUMULATIVE_RISE
            // (usesPresets()==false) has no presets to validate against here — an explicit `preset`
            // sent for it is its own, more specific error (PRESET_NOT_APPLICABLE), checked below
            // once every code is confirmed known.
            if (detector.usesPresets() && !detector.supportsPreset(selection.getPreset())) {
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
            PatternDetector detector = detectorsByCode.get(selection.getCode());
            if (detector.usesPresets()) {
                if (selection.getDays() != null) {
                    throw new DaysNotApplicableException(selection.getCode());
                }
                rejectReboundOnlyParams(selection);
            } else {
                if (selection.getPreset() != null) {
                    throw new PresetNotApplicableException(selection.getCode());
                }
                if (detector.acceptsDaysField()) {
                    validateDays(selection, detector);
                } else if (selection.getDays() != null) {
                    throw new DaysNotApplicableException(selection.getCode());
                }
                if (detector.acceptsReboundParams()) {
                    validateReboundParams(selection);
                } else {
                    rejectReboundOnlyParams(selection);
                }
            }
            validateInstitutionalParams(selection, detector);
            if (selection.getRisePercent() != null && !detector.acceptsRisePercent()) {
                // The three institutional patterns judge shares/volume, never a price rise — a
                // risePercent sent to them must be rejected, not silently ignored (specs/backend/
                // strategy-scan.md, "對三個法人籌碼型態帶了 risePercent").
                throw new ParamNotApplicableException(selection.getCode(), "risePercent");
            }
            validateRisePercent(selection, detector);
        }
        return selections;
    }

    /**
     * Validates the five institutional-only fields (`investors`/`windowDays`/`ratioPercent`/
     * `buyDays`/`topN`) uniformly for every strategy: rejects any of them sent to a detector that
     * does not declare {@code acceptsXxx()} for that field (PARAM_NOT_APPLICABLE, naming the field),
     * then range/shape-validates the ones the detector does accept — see specs/backend/
     * strategy-scan.md, "法人籌碼型態" 共通規則 and 驗證與用語.
     */
    private void validateInstitutionalParams(StrategySelectionDto selection, PatternDetector detector) {
        String code = selection.getCode();
        if (selection.getInvestors() != null && !detector.acceptsInvestors()) {
            throw new ParamNotApplicableException(code, "investors");
        }
        if (selection.getWindowDays() != null && !detector.acceptsWindowDays()) {
            throw new ParamNotApplicableException(code, "windowDays");
        }
        if (selection.getRatioPercent() != null && !detector.acceptsRatioPercent()) {
            throw new ParamNotApplicableException(code, "ratioPercent");
        }
        if (selection.getBuyDays() != null && !detector.acceptsBuyDays()) {
            throw new ParamNotApplicableException(code, "buyDays");
        }
        if (selection.getTopN() != null && !detector.acceptsTopN()) {
            throw new ParamNotApplicableException(code, "topN");
        }

        if (detector.acceptsInvestors()) {
            validateInvestors(selection);
        }
        if (detector.acceptsWindowDays()) {
            validateIntInRange(selection.getWindowDays(), WINDOW_OR_BUY_DAYS_MIN, WINDOW_OR_BUY_DAYS_MAX,
                    () -> new InvalidWindowDaysException(code));
        }
        if (detector.acceptsRatioPercent()) {
            validatePercentInRange(selection.getRatioPercent(), RATIO_PERCENT_MAX,
                    () -> new InvalidRatioPercentException(code));
        }
        if (detector.acceptsBuyDays()) {
            validateIntInRange(selection.getBuyDays(), WINDOW_OR_BUY_DAYS_MIN, WINDOW_OR_BUY_DAYS_MAX,
                    () -> new InvalidBuyDaysException(code));
        }
        if (detector.acceptsTopN()) {
            validateIntInRange(selection.getTopN(), TOP_N_MIN, TOP_N_MAX, () -> new InvalidTopNException(code));
        }
    }

    /**
     * `investors` may be omitted (null, meaning "both" — resolved at detection time); when present
     * it must be non-empty, contain only FOREIGN/TRUST, and have no duplicate — see
     * specs/backend/strategy-scan.md, "investors 為空陣列、含 FOREIGN／TRUST 以外的值、或有重複".
     */
    private void validateInvestors(StrategySelectionDto selection) {
        List<String> investors = selection.getInvestors();
        if (investors == null) {
            return;
        }
        if (investors.isEmpty()) {
            throw new InvalidInvestorsException(selection.getCode());
        }
        Set<String> seen = new HashSet<>();
        for (String investor : investors) {
            if (!InstitutionalPatternDetector.FOREIGN.equals(investor)
                    && !InstitutionalPatternDetector.TRUST.equals(investor)) {
                throw new InvalidInvestorsException(selection.getCode());
            }
            if (!seen.add(investor)) {
                throw new InvalidInvestorsException(selection.getCode());
            }
        }
    }

    /**
     * Rejects REBOUND's own params (`requireRise`/`dropDays`/`dropPercent`/`riseDays`) when sent to
     * a strategy that does not accept them — see specs/backend/strategy-scan.md, "對 REBOUND 以外的型態
     * 帶了 requireRise／dropDays／dropPercent／riseDays".
     */
    private void rejectReboundOnlyParams(StrategySelectionDto selection) {
        if (selection.getRequireRise() != null) {
            throw new ParamNotApplicableException(selection.getCode(), "requireRise");
        }
        if (selection.getDropDays() != null) {
            throw new ParamNotApplicableException(selection.getCode(), "dropDays");
        }
        if (selection.getDropPercent() != null) {
            throw new ParamNotApplicableException(selection.getCode(), "dropPercent");
        }
        if (selection.getRiseDays() != null) {
            throw new ParamNotApplicableException(selection.getCode(), "riseDays");
        }
    }

    /**
     * REBOUND-only validation: when `requireRise` resolves to false, `riseDays`/`risePercent` must
     * not be sent (PARAM_NOT_APPLICABLE); `dropDays`/`riseDays`/`dropPercent` are range/scale
     * validated. `risePercent`'s own [0, 50]/one-decimal validation is covered generically by
     * {@link #validateRisePercent} right after this method returns.
     */
    private void validateReboundParams(StrategySelectionDto selection) {
        boolean requireRise = selection.getRequireRise() == null || selection.getRequireRise();
        if (!requireRise) {
            if (selection.getRiseDays() != null) {
                throw new ParamNotApplicableException(selection.getCode(), "riseDays");
            }
            if (selection.getRisePercent() != null) {
                throw new ParamNotApplicableException(selection.getCode(), "risePercent");
            }
        }
        validateIntInRange(selection.getDropDays(), 1, 90, () -> new InvalidDropDaysException(selection.getCode()));
        if (requireRise) {
            validateIntInRange(selection.getRiseDays(), 1, 90,
                    () -> new InvalidRiseDaysException(selection.getCode()));
        }
        validatePercentInRange(selection.getDropPercent(), DROP_PERCENT_MAX,
                () -> new InvalidDropPercentException(selection.getCode()));
    }

    private void validateIntInRange(BigDecimal value, int min, int max,
                                     Supplier<RuntimeException> exceptionSupplier) {
        if (value == null) {
            return;
        }
        if (value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw exceptionSupplier.get();
        }
        try {
            value.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw exceptionSupplier.get();
        }
    }

    private void validatePercentInRange(BigDecimal value, BigDecimal max,
                                         Supplier<RuntimeException> exceptionSupplier) {
        if (value == null) {
            return;
        }
        if (value.compareTo(RISE_PERCENT_MIN) < 0 || value.compareTo(max) > 0) {
            throw exceptionSupplier.get();
        }
        try {
            value.setScale(RISE_PERCENT_MAX_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw exceptionSupplier.get();
        }
    }

    private void validateDays(StrategySelectionDto selection, PatternDetector detector) {
        BigDecimal days = selection.getDays();
        if (days == null) {
            return;
        }
        if (days.compareTo(BigDecimal.valueOf(detector.getDaysMin())) < 0
                || days.compareTo(BigDecimal.valueOf(detector.getDaysMax())) > 0) {
            throw new InvalidStrategyDaysException(selection.getCode());
        }
        try {
            // setScale(0, UNNECESSARY) throws iff `days` carries a non-zero fractional part, i.e.
            // is not a whole number (mirrors validateRisePercent's decimal-scale check).
            days.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidStrategyDaysException(selection.getCode());
        }
    }

    /**
     * `risePercent`'s upper bound is per-strategy, not a single system-wide value — each detector
     * declares its own via {@link PatternDetector#getRisePercentMax()} — see specs/backend/
     * strategy-scan.md, "risePercent 的上限逐型態認定，不是全系統一個值".
     */
    private void validateRisePercent(StrategySelectionDto selection, PatternDetector detector) {
        BigDecimal risePercent = selection.getRisePercent();
        if (risePercent == null) {
            return;
        }
        BigDecimal max = detector.getRisePercentMax();
        if (risePercent.compareTo(RISE_PERCENT_MIN) < 0 || risePercent.compareTo(max) > 0) {
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
            maxLookback = Math.max(maxLookback, detector.requiredLookbackTradingDays(selection));
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

    /** Holder for the one shared institutional-trade read used by all selected institutional
     *  strategies within a single scan — see specs/backend/strategy-scan.md, "同一次掃描中三個型態共用同一份
     *  讀取結果". */
    private static final class InstitutionalData {
        final Map<String, List<StockInstitutionalTrade>> seriesByStock;
        final Set<LocalDate> fetchedDates;
        final LocalDate dataThroughDate;

        InstitutionalData(Map<String, List<StockInstitutionalTrade>> seriesByStock, Set<LocalDate> fetchedDates,
                           LocalDate dataThroughDate) {
            this.seriesByStock = seriesByStock;
            this.fetchedDates = fetchedDates;
            this.dataThroughDate = dataThroughDate;
        }
    }

    /**
     * Batched institutional-trade read, performed once per scan regardless of how many of the three
     * institutional strategies are selected together (specs/backend/strategy-scan.md, "同一次掃描中三個
     * 型態共用同一份讀取結果"): one range query for the rows themselves, one distinct-dates query for the
     * "已抓取" signal (scoped to ALL securities, not just the target population — spec: "該日法人資料已抓取
     * （stock_institutional_trade 在該日有任何一列）"). The read's lower bound is the earliest trade_date
     * already present in {@code seriesByStock} — the same batched price lookback already reaches back
     * far enough to cover every institutional strategy's own `windowDays`/`buyDays` lookback need,
     * since {@link #loadSeries} takes the max lookback across ALL selected strategies including
     * these. Institutional data is read only through endDate ("法人資料本身只讀到 endDate 為止") — never the
     * confirm-after price row fetched for `buyDate`.
     */
    private InstitutionalData loadInstitutionalData(List<StrategySelectionDto> selections, List<String> targetIds,
                                                      Map<String, List<StockDailyPrice>> seriesByStock,
                                                      LocalDate startDate, LocalDate endDate) {
        boolean needsInstitutionalData = false;
        for (StrategySelectionDto selection : selections) {
            if (detectorsByCode.get(selection.getCode()) instanceof InstitutionalPatternDetector) {
                needsInstitutionalData = true;
                break;
            }
        }
        if (!needsInstitutionalData || targetIds.isEmpty()) {
            return new InstitutionalData(Collections.emptyMap(), Collections.emptySet(), null);
        }

        LocalDate fetchStart = startDate;
        for (List<StockDailyPrice> bars : seriesByStock.values()) {
            for (StockDailyPrice bar : bars) {
                if (bar.getTradeDate().isBefore(fetchStart)) {
                    fetchStart = bar.getTradeDate();
                }
            }
        }

        Map<String, List<StockInstitutionalTrade>> seriesByStockId = new LinkedHashMap<>();
        for (String id : targetIds) {
            seriesByStockId.put(id, new ArrayList<>());
        }
        List<StockInstitutionalTrade> rows =
                institutionalTradeMapper.findByStockIdsAndDateRange(targetIds, fetchStart, endDate);
        for (StockInstitutionalTrade row : rows) {
            seriesByStockId.computeIfAbsent(row.getStockId(), k -> new ArrayList<>()).add(row);
        }

        List<LocalDate> fetchedDatesList = institutionalTradeMapper.findFetchedTradeDates(fetchStart, endDate);
        Set<LocalDate> fetchedDates = new HashSet<>(fetchedDatesList);
        LocalDate dataThroughDate = fetchedDates.stream().max(LocalDate::compareTo).orElse(null);

        return new InstitutionalData(seriesByStockId, fetchedDates, dataThroughDate);
    }

    private StrategyResultDto runStrategy(StrategySelectionDto selection, List<String> targetIds,
                                           Map<String, String> stockNames,
                                           Map<String, List<StockDailyPrice>> seriesByStock,
                                           InstitutionalData institutionalData,
                                           LocalDate startDate, LocalDate endDate) {
        PatternDetector detector = detectorsByCode.get(selection.getCode());
        List<StrategyHitDto> items = new ArrayList<>();
        List<String> insufficientData = new ArrayList<>();
        List<String> pendingConfirm = new ArrayList<>();

        Map<String, PatternDetectionOutcome> outcomesByStock;
        if (detector instanceof InstitutionalPatternDetector) {
            InstitutionalPatternDetector institutionalDetector = (InstitutionalPatternDetector) detector;
            outcomesByStock = institutionalDetector.detectAll(targetIds, seriesByStock,
                    institutionalData.seriesByStock, institutionalData.fetchedDates, startDate, endDate, selection);
        } else {
            outcomesByStock = new LinkedHashMap<>();
            for (String stockId : targetIds) {
                List<StockDailyPrice> bars = seriesByStock.getOrDefault(stockId, Collections.emptyList());
                outcomesByStock.put(stockId, detector.detect(bars, startDate, endDate, selection));
            }
        }

        for (String stockId : targetIds) {
            PatternDetectionOutcome outcome = outcomesByStock.get(stockId);
            if (outcome.isInsufficientData()) {
                insufficientData.add(stockId);
            } else if (outcome.isHit()) {
                items.add(new StrategyHitDto(stockId, stockNames.get(stockId), outcome.getSignalDate(),
                        outcome.getBuyDate(), outcome.getDetail()));
            } else if (outcome.isPendingConfirm()) {
                pendingConfirm.add(stockId);
            }
        }

        items.sort(Comparator.comparing(StrategyHitDto::getSignalDate).reversed()
                .thenComparing(StrategyHitDto::getStockId));

        StrategyResultDto result = new StrategyResultDto();
        result.setStrategy(selection.getCode());
        // Mutually exclusive on the wire (specs/backend/strategy-scan.md, "掃描回應中 CUMULATIVE_RISE
        // 那一筆回 days ... 其餘四筆回 preset"): each detector knows its own header shape (preset,
        // days, REBOUND's requireRise/dropDays/dropPercent/riseDays/risePercent, or an institutional
        // detector's investors/windowDays/ratioPercent/buyDays/topN).
        detector.populateResultParams(selection, result);
        if (detector instanceof InstitutionalPatternDetector) {
            // Shared across all institutional strategies in this scan (see loadInstitutionalData) —
            // not selection-derived, so it is not part of populateResultParams's contract.
            result.setDataThroughDate(institutionalData.dataThroughDate);
        }
        result.setMatchedCount(items.size());
        result.setItems(items);
        result.setInsufficientData(insufficientData);
        result.setPendingConfirm(pendingConfirm);
        return result;
    }
}
