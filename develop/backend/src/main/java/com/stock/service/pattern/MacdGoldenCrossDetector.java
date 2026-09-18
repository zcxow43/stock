package com.stock.service.pattern;

import com.stock.domain.StockDailyIndicator;
import com.stock.domain.StockDailyPrice;
import com.stock.dto.MacdGoldenCrossDetailDto;
import com.stock.dto.ParamDto;
import com.stock.dto.PresetDto;
import com.stock.dto.StrategyResultDto;
import com.stock.dto.StrategySelectionDto;
import com.stock.service.IndicatorCalculationService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * MACD_GOLDEN_CROSS — specs/backend/strategy-scan.md, "技術指標型態" / "MACD 黃金交叉" (本次新增). DIF/DEA
 * are computed at scan time from stock_daily_price via {@link IndicatorCalculationService} — the
 * exact same recursive formula stock-indicator-statistics.md persists into stock_daily_indicator,
 * just with caller-supplied fast/slow EMA periods instead of the fixed 12/26 (see
 * IndicatorCalculationService#computeFull(String, String, List, LocalDate, int, int)). This
 * detector never reads or writes stock_daily_indicator.
 *
 * <p>For each trading day D within [startDate, endDate] that has at least 100 trading days of
 * history strictly before it (within the batched lookback, capped at
 * {@link StockDailyIndicator#WARMUP_TRADING_DAYS}): a golden cross is D-1's OSC (DIF-DEA) being
 * &lt;= 0 and D's OSC being &gt; 0. `buyDate` always equals `signalDate` (no confirmation window),
 * so {@link #requiredConfirmTradingDaysAfterEndDate} stays at its default of 0 and pendingConfirm
 * is always empty.
 */
@Component
@Order(9)
public class MacdGoldenCrossDetector implements PatternDetector {

    public static final String CODE = "MACD_GOLDEN_CROSS";

    public static final int FAST_PERIOD_MIN = 2;
    public static final int FAST_PERIOD_MAX = 50;
    public static final int FAST_PERIOD_DEFAULT = IndicatorCalculationService.DEFAULT_FAST_PERIOD;
    public static final int SLOW_PERIOD_MIN = 3;
    public static final int SLOW_PERIOD_MAX = 100;
    public static final int SLOW_PERIOD_DEFAULT = IndicatorCalculationService.DEFAULT_SLOW_PERIOD;
    public static final int SIGNAL_PERIOD = 9;

    // At least 100 trading days of history strictly before D are required for the recursion to
    // have converged enough for the crossing day itself to be trustworthy — see specs/backend/
    // strategy-scan.md, "暖身" (共通規則 of 技術指標型態). Not to be confused with the 250-day cap on
    // how far back the batched read reaches, declared via requiredLookbackTradingDays below.
    private static final int MIN_TRADING_DAYS_BEFORE_D = 100;

    private static final int DETAIL_SCALE = 4;
    // No stock_daily_indicator row is ever built here, so the param key is purely descriptive —
    // never persisted, never compared against StockDailyIndicator.PARAM_KEY.
    private static final String SCAN_PARAM_KEY = "SCAN_ONLY";

    private final IndicatorCalculationService indicatorCalculationService;

    public MacdGoldenCrossDetector(IndicatorCalculationService indicatorCalculationService) {
        this.indicatorCalculationService = indicatorCalculationService;
    }

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "MACD 黃金交叉";
    }

    @Override
    public boolean usesPresets() {
        return false;
    }

    @Override
    public boolean supportsPreset(String presetCode) {
        return false;
    }

    @Override
    public List<PresetDto> getPresets() {
        return Collections.emptyList();
    }

    @Override
    public String getDescription() {
        return "DIF（短期 EMA − 長期 EMA）由下往上穿越 DEA（DIF 的 9 日 EMA）當日為訊號日";
    }

    @Override
    public boolean acceptsRisePercent() {
        return false;
    }

    @Override
    public boolean acceptsFastPeriod() {
        return true;
    }

    @Override
    public boolean acceptsSlowPeriod() {
        return true;
    }

    @Override
    public int getFastPeriodMin() {
        return FAST_PERIOD_MIN;
    }

    @Override
    public int getFastPeriodMax() {
        return FAST_PERIOD_MAX;
    }

    @Override
    public int getFastPeriodDefault() {
        return FAST_PERIOD_DEFAULT;
    }

    @Override
    public int getSlowPeriodMin() {
        return SLOW_PERIOD_MIN;
    }

    @Override
    public int getSlowPeriodMax() {
        return SLOW_PERIOD_MAX;
    }

    @Override
    public int getSlowPeriodDefault() {
        return SLOW_PERIOD_DEFAULT;
    }

    @Override
    public List<ParamDto> getParams() {
        return List.of(
                ParamDto.numericLessThan("fastPeriod", "短期 EMA", "日", BigDecimal.valueOf(FAST_PERIOD_DEFAULT),
                        BigDecimal.valueOf(FAST_PERIOD_MIN), BigDecimal.valueOf(FAST_PERIOD_MAX), BigDecimal.ONE,
                        "slowPeriod"),
                new ParamDto("slowPeriod", "長期 EMA", "日", BigDecimal.valueOf(SLOW_PERIOD_DEFAULT),
                        BigDecimal.valueOf(SLOW_PERIOD_MIN), BigDecimal.valueOf(SLOW_PERIOD_MAX), BigDecimal.ONE));
    }

    private static int resolveFastPeriod(StrategySelectionDto selection) {
        return selection.getFastPeriod() != null ? selection.getFastPeriod().intValue() : FAST_PERIOD_DEFAULT;
    }

    private static int resolveSlowPeriod(StrategySelectionDto selection) {
        return selection.getSlowPeriod() != null ? selection.getSlowPeriod().intValue() : SLOW_PERIOD_DEFAULT;
    }

    @Override
    public int requiredLookbackTradingDays(StrategySelectionDto selection) {
        // Fixed at 250 regardless of the caller's fastPeriod/slowPeriod — the warmup length is
        // about recursion convergence, not about which EMA periods are being converged.
        return StockDailyIndicator.WARMUP_TRADING_DAYS;
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           StrategySelectionDto selection) {
        if (bars.isEmpty()) {
            return PatternDetectionOutcome.insufficientData();
        }
        int fastPeriod = resolveFastPeriod(selection);
        int slowPeriod = resolveSlowPeriod(selection);
        String stockId = bars.get(0).getStockId();
        List<StockDailyIndicator> indicatorRows = indicatorCalculationService.computeFull(stockId, SCAN_PARAM_KEY,
                bars, null, fastPeriod, slowPeriod);

        boolean anyJudged = false;
        LocalDate lastSignalDate = null;
        MacdGoldenCrossDetailDto lastDetail = null;

        for (int i = MIN_TRADING_DAYS_BEFORE_D; i < bars.size(); i++) {
            LocalDate tradeDate = bars.get(i).getTradeDate();
            if (tradeDate.isBefore(startDate)) {
                continue;
            }
            if (tradeDate.isAfter(endDate)) {
                break;
            }
            anyJudged = true;

            BigDecimal prevOsc = indicatorRows.get(i - 1).getOsc();
            BigDecimal osc = indicatorRows.get(i).getOsc();
            if (prevOsc.compareTo(BigDecimal.ZERO) <= 0 && osc.compareTo(BigDecimal.ZERO) > 0) {
                lastSignalDate = tradeDate;
                lastDetail = new MacdGoldenCrossDetailDto(
                        indicatorRows.get(i).getDif().setScale(DETAIL_SCALE, RoundingMode.HALF_UP),
                        indicatorRows.get(i).getDea().setScale(DETAIL_SCALE, RoundingMode.HALF_UP),
                        osc.setScale(DETAIL_SCALE, RoundingMode.HALF_UP),
                        prevOsc.setScale(DETAIL_SCALE, RoundingMode.HALF_UP));
            }
        }

        if (!anyJudged) {
            return PatternDetectionOutcome.insufficientData();
        }
        if (lastSignalDate != null) {
            return PatternDetectionOutcome.hit(lastSignalDate, lastDetail);
        }
        // Never produces pendingConfirm — buyDate always equals signalDate, no confirmation window.
        return PatternDetectionOutcome.noMatch(false);
    }

    @Override
    public void populateResultParams(StrategySelectionDto selection, StrategyResultDto result) {
        result.setFastPeriod(resolveFastPeriod(selection));
        result.setSlowPeriod(resolveSlowPeriod(selection));
        result.setSignalPeriod(SIGNAL_PERIOD);
    }
}
