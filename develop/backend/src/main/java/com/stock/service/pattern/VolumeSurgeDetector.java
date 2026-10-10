package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.ParamDto;
import com.stock.dto.PresetDto;
import com.stock.dto.StrategyResultDto;
import com.stock.dto.StrategySelectionDto;
import com.stock.dto.VolumeSurgeDetailDto;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * VOLUME_SURGE — specs/backend/strategy-scan.md, "量能型態" / "量開始變多". Trading day D is the first day
 * volume surges: {@code volume(D) >= baseline(D) * (1 + increasePercent/100)} where baseline(D) is the
 * mean volume of the 5 trading days before D (D itself excluded), AND D-1 does not satisfy the same
 * rule against its own baseline (D-2..D-6). Price is never looked at. A baseline of 0 means "not
 * surging" for that day. Computed at scan time from the already-loaded daily bars; no table or query
 * is added.
 *
 * <p>The comparison is exact integer/BigDecimal arithmetic on raw volumes —
 * {@code volume * 5 * 100 >= sum5 * (100 + increasePercent)} — so equality at the threshold is a hit
 * and no floating-point or pre-rounded figure can flip it. `buyDate` is the trading day after D
 * ({@link #nextTradingDate}); a D on the stock's latest bar yields pendingConfirm rather than a hit.
 */
@Component
@Order(12)
public class VolumeSurgeDetector implements PatternDetector {

    public static final String CODE = "VOLUME_SURGE";

    public static final BigDecimal INCREASE_PERCENT_MIN = BigDecimal.ZERO;
    public static final BigDecimal INCREASE_PERCENT_MAX = new BigDecimal("1000");
    public static final BigDecimal INCREASE_PERCENT_DEFAULT = new BigDecimal("50");

    /** Fixed baseline window; the same one BOX_BREAKOUT's volume condition uses, never caller-configurable. */
    private static final int BASELINE_DAYS = 5;
    /** D needs D-1..D-5 for its baseline plus D-6 for D-1's baseline. */
    private static final int MIN_TRADING_DAYS_BEFORE_D = BASELINE_DAYS + 1;

    private static final BigDecimal PERCENT_BASE = BigDecimal.valueOf(100);
    private static final int DETAIL_SCALE = 2;

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "量開始變多";
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
        return "當日成交量較前 5 個交易日均量增加達門檻，且前一交易日尚未達標——放量的第一天為訊號日";
    }

    @Override
    public boolean acceptsRisePercent() {
        return false;
    }

    @Override
    public boolean acceptsIncreasePercent() {
        return true;
    }

    @Override
    public BigDecimal getIncreasePercentMin() {
        return INCREASE_PERCENT_MIN;
    }

    @Override
    public BigDecimal getIncreasePercentMax() {
        return INCREASE_PERCENT_MAX;
    }

    @Override
    public BigDecimal getIncreasePercentDefault() {
        return INCREASE_PERCENT_DEFAULT;
    }

    @Override
    public List<ParamDto> getParams() {
        return List.of(new ParamDto("increasePercent", "量增門檻", "%", INCREASE_PERCENT_DEFAULT,
                INCREASE_PERCENT_MIN, INCREASE_PERCENT_MAX, new BigDecimal("0.1")));
    }

    private static BigDecimal resolveIncreasePercent(StrategySelectionDto selection) {
        return selection.getIncreasePercent() != null ? selection.getIncreasePercent() : INCREASE_PERCENT_DEFAULT;
    }

    @Override
    public int requiredLookbackTradingDays(StrategySelectionDto selection) {
        return MIN_TRADING_DAYS_BEFORE_D;
    }

    @Override
    public int requiredConfirmTradingDaysAfterEndDate(StrategySelectionDto selection) {
        // buyDate is the trading day after D — see specs/backend/strategy-scan.md, "進場日（buyDate）".
        return 1;
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           StrategySelectionDto selection) {
        // multiplier = 100 + increasePercent; volume(D) * 5 * 100 >= sum5 * multiplier is the
        // rearranged, division-free form of volume >= (sum5 / 5) * (1 + increasePercent / 100).
        BigDecimal multiplier = PERCENT_BASE.add(resolveIncreasePercent(selection));

        boolean anyJudged = false;
        LocalDate lastSignalDate = null;
        LocalDate lastBuyDate = null;
        VolumeSurgeDetailDto lastDetail = null;
        boolean anyUnresolvedMatch = false;

        for (int i = MIN_TRADING_DAYS_BEFORE_D; i < bars.size(); i++) {
            LocalDate tradeDate = bars.get(i).getTradeDate();
            if (tradeDate.isBefore(startDate)) {
                continue;
            }
            if (tradeDate.isAfter(endDate)) {
                break;
            }
            anyJudged = true;

            long sum = baselineSum(bars, i);
            long prevSum = baselineSum(bars, i - 1);
            if (!isSurge(bars.get(i).getVolume(), sum, multiplier)
                    || isSurge(bars.get(i - 1).getVolume(), prevSum, multiplier)) {
                continue;
            }

            // buyDate: the trading day after D; missing means this hit is unresolved
            // (pendingConfirm), not a non-match — see specs/backend/strategy-scan.md, "進場日（buyDate）".
            LocalDate buyDate = nextTradingDate(bars, i);
            if (buyDate == null) {
                anyUnresolvedMatch = true;
                continue;
            }
            long volume = bars.get(i).getVolume();
            long prevVolume = bars.get(i - 1).getVolume();
            lastSignalDate = tradeDate;
            lastBuyDate = buyDate;
            lastDetail = new VolumeSurgeDetailDto(volume, averageOf(sum), increaseOf(volume, sum),
                    prevVolume, averageOf(prevSum), increaseOf(prevVolume, prevSum));
        }

        if (!anyJudged) {
            return PatternDetectionOutcome.insufficientData();
        }
        if (lastSignalDate != null) {
            return PatternDetectionOutcome.hit(lastSignalDate, lastBuyDate, lastDetail);
        }
        return PatternDetectionOutcome.noMatch(anyUnresolvedMatch);
    }

    /** Sum of the {@link #BASELINE_DAYS} volumes strictly before {@code index}. */
    private static long baselineSum(List<StockDailyPrice> bars, int index) {
        long sum = 0;
        for (int j = index - BASELINE_DAYS; j < index; j++) {
            sum += bars.get(j).getVolume();
        }
        return sum;
    }

    /** Surge test on raw figures; a zero baseline has no comparison base and is never a surge. */
    private static boolean isSurge(long volume, long baselineSum, BigDecimal multiplier) {
        if (baselineSum <= 0) {
            return false;
        }
        BigDecimal left = BigDecimal.valueOf(volume).multiply(BigDecimal.valueOf(BASELINE_DAYS))
                .multiply(PERCENT_BASE);
        BigDecimal right = BigDecimal.valueOf(baselineSum).multiply(multiplier);
        return left.compareTo(right) >= 0;
    }

    private static long averageOf(long baselineSum) {
        return BigDecimal.valueOf(baselineSum)
                .divide(BigDecimal.valueOf(BASELINE_DAYS), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** {@code (volume / baseline - 1) * 100} = {@code (volume * 500 - sum * 100) / sum}, two decimals;
     *  null when the baseline is zero. */
    private static BigDecimal increaseOf(long volume, long baselineSum) {
        if (baselineSum <= 0) {
            return null;
        }
        BigDecimal numerator = BigDecimal.valueOf(volume).multiply(BigDecimal.valueOf(BASELINE_DAYS))
                .multiply(PERCENT_BASE).subtract(BigDecimal.valueOf(baselineSum).multiply(PERCENT_BASE));
        return numerator.divide(BigDecimal.valueOf(baselineSum), DETAIL_SCALE, RoundingMode.HALF_UP);
    }

    @Override
    public void populateResultParams(StrategySelectionDto selection, StrategyResultDto result) {
        result.setIncreasePercent(resolveIncreasePercent(selection));
    }
}
