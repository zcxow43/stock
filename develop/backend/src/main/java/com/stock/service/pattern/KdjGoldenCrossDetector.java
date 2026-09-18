package com.stock.service.pattern;

import com.stock.domain.StockDailyIndicator;
import com.stock.domain.StockDailyPrice;
import com.stock.dto.KdjGoldenCrossDetailDto;
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
 * KDJ_GOLDEN_CROSS — specs/backend/strategy-scan.md, "技術指標型態" / "KDJ 黃金交叉" (本次新增). K/D/J are
 * computed at scan time from stock_daily_price via {@link IndicatorCalculationService}, the same
 * fixed KD(9,3,3) recursion stock-indicator-statistics.md persists into stock_daily_indicator; this
 * detector never reads or writes that table. Only `jThreshold` is caller-configurable — KD's own
 * (9,3,3) periods are fixed and not exposed as a request field.
 *
 * <p>For each trading day D within [startDate, endDate] with at least 100 trading days of history
 * strictly before it: J crossing up through both K and D is, per spec, equivalent to — and judged
 * as — K crossing up through D (D-1's K &lt;= D and D's K &gt; D), since J is a linear function of
 * K and D and comparing J directly against K/D risks rounding inconsistencies at the crossing point.
 * The additional condition is D-1's J strictly below `jThreshold`. `buyDate` always equals
 * `signalDate`, so pendingConfirm is always empty.
 */
@Component
@Order(10)
public class KdjGoldenCrossDetector implements PatternDetector {

    public static final String CODE = "KDJ_GOLDEN_CROSS";

    public static final BigDecimal J_THRESHOLD_MIN = new BigDecimal("-100");
    public static final BigDecimal J_THRESHOLD_MAX = new BigDecimal("100");
    public static final BigDecimal J_THRESHOLD_DEFAULT = new BigDecimal("40");

    // Same warmup convergence requirement as MACD_GOLDEN_CROSS — see specs/backend/
    // strategy-scan.md, "暖身" (共通規則 of 技術指標型態), shared verbatim between the two patterns.
    private static final int MIN_TRADING_DAYS_BEFORE_D = 100;

    private static final int DETAIL_SCALE = 4;
    private static final String SCAN_PARAM_KEY = "SCAN_ONLY";

    private final IndicatorCalculationService indicatorCalculationService;

    public KdjGoldenCrossDetector(IndicatorCalculationService indicatorCalculationService) {
        this.indicatorCalculationService = indicatorCalculationService;
    }

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "KDJ 黃金交叉";
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
        return "KD(9,3,3) 的 J 由下往上同時穿越 K 與 D 當日為訊號日，且前一交易日 J 低於門檻";
    }

    @Override
    public boolean acceptsRisePercent() {
        return false;
    }

    @Override
    public boolean acceptsJThreshold() {
        return true;
    }

    @Override
    public BigDecimal getJThresholdMin() {
        return J_THRESHOLD_MIN;
    }

    @Override
    public BigDecimal getJThresholdMax() {
        return J_THRESHOLD_MAX;
    }

    @Override
    public BigDecimal getJThresholdDefault() {
        return J_THRESHOLD_DEFAULT;
    }

    @Override
    public List<ParamDto> getParams() {
        return List.of(new ParamDto("jThreshold", "J 門檻", "", J_THRESHOLD_DEFAULT, J_THRESHOLD_MIN,
                J_THRESHOLD_MAX, new BigDecimal("0.1")));
    }

    private static BigDecimal resolveJThreshold(StrategySelectionDto selection) {
        return selection.getJThreshold() != null ? selection.getJThreshold() : J_THRESHOLD_DEFAULT;
    }

    @Override
    public int requiredLookbackTradingDays(StrategySelectionDto selection) {
        return StockDailyIndicator.WARMUP_TRADING_DAYS;
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           StrategySelectionDto selection) {
        if (bars.isEmpty()) {
            return PatternDetectionOutcome.insufficientData();
        }
        BigDecimal jThreshold = resolveJThreshold(selection);
        String stockId = bars.get(0).getStockId();
        // fastPeriod/slowPeriod are irrelevant to KD — the default MACD periods are passed only
        // because computeFull always derives both MACD and KD in one recursion; K/D/J themselves
        // never depend on them.
        List<StockDailyIndicator> indicatorRows = indicatorCalculationService.computeFull(stockId, SCAN_PARAM_KEY,
                bars, null, IndicatorCalculationService.DEFAULT_FAST_PERIOD,
                IndicatorCalculationService.DEFAULT_SLOW_PERIOD);

        boolean anyJudged = false;
        LocalDate lastSignalDate = null;
        KdjGoldenCrossDetailDto lastDetail = null;

        for (int i = MIN_TRADING_DAYS_BEFORE_D; i < bars.size(); i++) {
            LocalDate tradeDate = bars.get(i).getTradeDate();
            if (tradeDate.isBefore(startDate)) {
                continue;
            }
            if (tradeDate.isAfter(endDate)) {
                break;
            }
            anyJudged = true;

            BigDecimal prevK = indicatorRows.get(i - 1).getKValue();
            BigDecimal prevD = indicatorRows.get(i - 1).getDValue();
            BigDecimal prevJ = indicatorRows.get(i - 1).getJValue();
            BigDecimal k = indicatorRows.get(i).getKValue();
            BigDecimal d = indicatorRows.get(i).getDValue();
            BigDecimal j = indicatorRows.get(i).getJValue();

            boolean crossedUp = prevK.compareTo(prevD) <= 0 && k.compareTo(d) > 0;
            boolean prevJBelowThreshold = prevJ.compareTo(jThreshold) < 0;
            if (crossedUp && prevJBelowThreshold) {
                lastSignalDate = tradeDate;
                lastDetail = new KdjGoldenCrossDetailDto(
                        k.setScale(DETAIL_SCALE, RoundingMode.HALF_UP),
                        d.setScale(DETAIL_SCALE, RoundingMode.HALF_UP),
                        j.setScale(DETAIL_SCALE, RoundingMode.HALF_UP),
                        prevK.setScale(DETAIL_SCALE, RoundingMode.HALF_UP),
                        prevD.setScale(DETAIL_SCALE, RoundingMode.HALF_UP),
                        prevJ.setScale(DETAIL_SCALE, RoundingMode.HALF_UP));
            }
        }

        if (!anyJudged) {
            return PatternDetectionOutcome.insufficientData();
        }
        if (lastSignalDate != null) {
            return PatternDetectionOutcome.hit(lastSignalDate, lastDetail);
        }
        return PatternDetectionOutcome.noMatch(false);
    }

    @Override
    public void populateResultParams(StrategySelectionDto selection, StrategyResultDto result) {
        result.setJThreshold(resolveJThreshold(selection));
    }
}
