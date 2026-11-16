package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.MaSqueezeBreakoutDetailDto;
import com.stock.dto.ParamDto;
import com.stock.dto.PresetDto;
import com.stock.dto.StrategyResultDto;
import com.stock.dto.StrategySelectionDto;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * MA_SQUEEZE_BREAKOUT — specs/backend/strategy-scan.md, "均線型態" / "均線糾結突破". Trading day D is a
 * hit when
 * <ol>
 *   <li>every trading day t in D-squeezeDays..D-1 (D excluded) has
 *       {@code (max(MA5,MA20,MA60) - min(...)) / mean(...) < squeezePercent%};</li>
 *   <li>D's close is strictly above D's MA5, MA20 and MA60;</li>
 *   <li>D's MA5 is strictly above D-1's MA5.</li>
 * </ol>
 * MA(n) is the shared close-price simple average ({@link MovingAverage}) — the same lines MA_BREAKOUT
 * uses — computed at scan time from the loaded daily bars; stock_daily_indicator is never touched.
 * D needs {@code squeezeDays + 59} trading days before it (the window's first day needs an MA60).
 * `buyDate` is the trading day after D ({@link #nextTradingDate}); a D on the stock's latest bar
 * yields pendingConfirm rather than a hit. The first hit in the range is reported.
 */
@Component
@Order(13)
public class MaSqueezeBreakoutDetector implements PatternDetector {

    public static final String CODE = "MA_SQUEEZE_BREAKOUT";

    public static final int SQUEEZE_DAYS_MIN = 1;
    public static final int SQUEEZE_DAYS_MAX = 60;
    public static final int SQUEEZE_DAYS_DEFAULT = 10;
    public static final BigDecimal SQUEEZE_PERCENT_MIN = new BigDecimal("0.1");
    public static final BigDecimal SQUEEZE_PERCENT_MAX = new BigDecimal("10");
    public static final BigDecimal SQUEEZE_PERCENT_DEFAULT = new BigDecimal("3");

    private static final int MA_SHORT = 5;
    private static final int MA_MID = 20;
    private static final int MA_LONG = 60;

    private static final BigDecimal THREE = BigDecimal.valueOf(3);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int DETAIL_SCALE = 2;

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "均線糾結突破";
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
        return "前指定天數內每日 MA5／MA20／MA60 三線差距 ÷ 三線平均皆低於門檻，當日收盤同時高於三線且 MA5 高於前一日";
    }

    @Override
    public boolean acceptsRisePercent() {
        return false;
    }

    @Override
    public boolean acceptsSqueezeParams() {
        return true;
    }

    @Override
    public int getSqueezeDaysMin() {
        return SQUEEZE_DAYS_MIN;
    }

    @Override
    public int getSqueezeDaysMax() {
        return SQUEEZE_DAYS_MAX;
    }

    @Override
    public BigDecimal getSqueezePercentMin() {
        return SQUEEZE_PERCENT_MIN;
    }

    @Override
    public BigDecimal getSqueezePercentMax() {
        return SQUEEZE_PERCENT_MAX;
    }

    @Override
    public List<ParamDto> getParams() {
        return List.of(
                new ParamDto("squeezeDays", "糾結天數", "日", BigDecimal.valueOf(SQUEEZE_DAYS_DEFAULT),
                        BigDecimal.valueOf(SQUEEZE_DAYS_MIN), BigDecimal.valueOf(SQUEEZE_DAYS_MAX), BigDecimal.ONE),
                new ParamDto("squeezePercent", "糾結幅度", "%", SQUEEZE_PERCENT_DEFAULT,
                        SQUEEZE_PERCENT_MIN, SQUEEZE_PERCENT_MAX, new BigDecimal("0.1")));
    }

    private static int resolveSqueezeDays(StrategySelectionDto selection) {
        return selection.getSqueezeDays() != null ? selection.getSqueezeDays().intValueExact() : SQUEEZE_DAYS_DEFAULT;
    }

    private static BigDecimal resolveSqueezePercent(StrategySelectionDto selection) {
        return selection.getSqueezePercent() != null ? selection.getSqueezePercent() : SQUEEZE_PERCENT_DEFAULT;
    }

    @Override
    public int requiredLookbackTradingDays(StrategySelectionDto selection) {
        return resolveSqueezeDays(selection) + MA_LONG - 1;
    }

    @Override
    public int requiredConfirmTradingDaysAfterEndDate(StrategySelectionDto selection) {
        // buyDate is the trading day after D — see specs/backend/strategy-scan.md, "進場日（buyDate）".
        return 1;
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           StrategySelectionDto selection) {
        int squeezeDays = resolveSqueezeDays(selection);
        BigDecimal squeezePercent = resolveSqueezePercent(selection);
        // D's index must leave squeezeDays window days before it, the first of which needs an MA60.
        int firstJudgeableIndex = squeezeDays + MA_LONG - 1;

        // Lazily filled per-index MA caches: a window day is shared by up to squeezeDays candidate Ds.
        int size = bars.size();
        BigDecimal[] ma5 = new BigDecimal[size];
        BigDecimal[] ma20 = new BigDecimal[size];
        BigDecimal[] ma60 = new BigDecimal[size];

        boolean anyJudged = false;
        boolean anyUnresolvedMatch = false;

        for (int i = firstJudgeableIndex; i < size; i++) {
            LocalDate tradeDate = bars.get(i).getTradeDate();
            if (tradeDate.isBefore(startDate)) {
                continue;
            }
            if (tradeDate.isAfter(endDate)) {
                break;
            }
            anyJudged = true;

            BigDecimal close = bars.get(i).getClosePrice();
            BigDecimal dMa5 = ma(bars, ma5, i, MA_SHORT);
            BigDecimal dMa20 = ma(bars, ma20, i, MA_MID);
            BigDecimal dMa60 = ma(bars, ma60, i, MA_LONG);
            BigDecimal prevMa5 = ma(bars, ma5, i - 1, MA_SHORT);
            // Breakout above all three lines at once, and MA5 turning up — both strict.
            if (close.compareTo(dMa5) <= 0 || close.compareTo(dMa20) <= 0 || close.compareTo(dMa60) <= 0
                    || dMa5.compareTo(prevMa5) <= 0) {
                continue;
            }
            if (!isSqueezedWindow(bars, ma5, ma20, ma60, i, squeezeDays, squeezePercent)) {
                continue;
            }

            // buyDate: the trading day after D; missing means this hit is unresolved
            // (pendingConfirm), not a non-match — see specs/backend/strategy-scan.md, "進場日（buyDate）".
            LocalDate buyDate = nextTradingDate(bars, i);
            if (buyDate == null) {
                anyUnresolvedMatch = true;
                continue;
            }
            BigDecimal maxSpread = BigDecimal.ZERO;
            for (int t = i - squeezeDays; t < i; t++) {
                BigDecimal spread = spreadPercent(ma(bars, ma5, t, MA_SHORT), ma(bars, ma20, t, MA_MID),
                        ma(bars, ma60, t, MA_LONG));
                if (spread.compareTo(maxSpread) > 0) {
                    maxSpread = spread;
                }
            }
            MaSqueezeBreakoutDetailDto detail = new MaSqueezeBreakoutDetailDto(scale(close), scale(dMa5),
                    scale(dMa20), scale(dMa60), scale(prevMa5), bars.get(i - squeezeDays).getTradeDate(),
                    scale(maxSpread));
            // The first hit in the range is the one reported (spec: 收斂).
            return PatternDetectionOutcome.hit(tradeDate, buyDate, detail);
        }

        if (!anyJudged) {
            return PatternDetectionOutcome.insufficientData();
        }
        return PatternDetectionOutcome.noMatch(anyUnresolvedMatch);
    }

    /**
     * Whether every day in D-squeezeDays..D-1 has spread / mean {@code < squeezePercent}%, compared
     * division-free on the un-rounded MAs: {@code (max - min) * 3 * 100 < squeezePercent * (sum)}.
     */
    private static boolean isSqueezedWindow(List<StockDailyPrice> bars, BigDecimal[] ma5, BigDecimal[] ma20,
                                            BigDecimal[] ma60, int dIndex, int squeezeDays,
                                            BigDecimal squeezePercent) {
        for (int t = dIndex - squeezeDays; t < dIndex; t++) {
            BigDecimal a = ma(bars, ma5, t, MA_SHORT);
            BigDecimal b = ma(bars, ma20, t, MA_MID);
            BigDecimal c = ma(bars, ma60, t, MA_LONG);
            BigDecimal range = a.max(b).max(c).subtract(a.min(b).min(c));
            BigDecimal sum = a.add(b).add(c);
            if (range.multiply(THREE).multiply(HUNDRED).compareTo(squeezePercent.multiply(sum)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** (max - min) / mean * 100 of the three lines — detail only, never used for the judgment. */
    private static BigDecimal spreadPercent(BigDecimal a, BigDecimal b, BigDecimal c) {
        BigDecimal range = a.max(b).max(c).subtract(a.min(b).min(c));
        BigDecimal sum = a.add(b).add(c);
        if (sum.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return range.multiply(THREE).multiply(HUNDRED).divide(sum, MovingAverage.CALC_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal ma(List<StockDailyPrice> bars, BigDecimal[] cache, int index, int n) {
        if (cache[index] == null) {
            cache[index] = MovingAverage.simple(bars, index, n);
        }
        return cache[index];
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(DETAIL_SCALE, RoundingMode.HALF_UP);
    }

    @Override
    public void populateResultParams(StrategySelectionDto selection, StrategyResultDto result) {
        result.setSqueezeDays(resolveSqueezeDays(selection));
        result.setSqueezePercent(resolveSqueezePercent(selection));
    }
}
