package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.MaBreakoutDetailDto;
import com.stock.dto.ParamDto;
import com.stock.dto.ParamOptionDto;
import com.stock.dto.PresetDto;
import com.stock.dto.StrategyResultDto;
import com.stock.dto.StrategySelectionDto;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MA_BREAKOUT — specs/backend/strategy-scan.md, "均線型態" / "站上均線". One pattern covering three
 * moving averages (MA5/MA20/MA60, chosen by the `maPeriods` multiSelect): for each selected line
 * independently, trading day D qualifies when D-1's close is {@code <=} D-1's MA(n) AND D's close is
 * {@code >} D's MA(n); any one selected line qualifying is one hit, D is the `signalDate`. MA(n) is
 * the shared close-price simple average ({@link MovingAverage}), computed here at scan time from the
 * loaded daily bars — stock_daily_indicator is never read or written. No volume check and no hold
 * confirmation; `buyDate` is the trading day after D ({@link #nextTradingDate}), and a D on the
 * stock's latest bar yields pendingConfirm rather than a hit.
 */
@Component
@Order(11)
public class MaBreakoutDetector implements PatternDetector {

    public static final String CODE = "MA_BREAKOUT";

    /** Selectable lines in the fixed canonical order (also the order of the wire arrays). */
    private static final List<String> PERIOD_CODES = List.of("MA5", "MA20", "MA60");
    private static final int[] PERIOD_DAYS = {5, 20, 60};

    private static final int DETAIL_SCALE = 2;

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "站上均線";
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
        return "收盤價由下往上站上均線當日為訊號日：前一交易日收盤未高於均線，當日收盤高於均線";
    }

    @Override
    public boolean acceptsRisePercent() {
        return false;
    }

    @Override
    public List<String> getMaPeriodCodes() {
        return PERIOD_CODES;
    }

    @Override
    public List<ParamDto> getParams() {
        List<ParamOptionDto> options = new ArrayList<>(PERIOD_CODES.size());
        for (String code : PERIOD_CODES) {
            options.add(new ParamOptionDto(code, code));
        }
        return List.of(new ParamDto("maPeriods", "均線", "multiSelect", options, PERIOD_CODES, 1));
    }

    /** The selected lines' indexes into {@link #PERIOD_CODES}, always ascending regardless of the
     *  request array's own order; an omitted `maPeriods` means all three. */
    private static List<Integer> resolveSelected(StrategySelectionDto selection) {
        List<String> requested = selection.getMaPeriods();
        List<Integer> selected = new ArrayList<>(PERIOD_CODES.size());
        for (int p = 0; p < PERIOD_CODES.size(); p++) {
            if (requested == null || requested.contains(PERIOD_CODES.get(p))) {
                selected.add(p);
            }
        }
        return selected;
    }

    @Override
    public int requiredLookbackTradingDays(StrategySelectionDto selection) {
        int max = 0;
        for (int p : resolveSelected(selection)) {
            max = Math.max(max, PERIOD_DAYS[p]);
        }
        return max;
    }

    @Override
    public int requiredConfirmTradingDaysAfterEndDate(StrategySelectionDto selection) {
        // buyDate is the trading day after D — see specs/backend/strategy-scan.md, "進場日（buyDate）".
        return 1;
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           StrategySelectionDto selection) {
        List<Integer> selected = resolveSelected(selection);

        boolean anyJudged = false;
        LocalDate lastSignalDate = null;
        LocalDate lastBuyDate = null;
        MaBreakoutDetailDto lastDetail = null;
        boolean anyUnresolvedMatch = false;

        for (int i = 1; i < bars.size(); i++) {
            LocalDate tradeDate = bars.get(i).getTradeDate();
            if (tradeDate.isBefore(startDate)) {
                continue;
            }
            if (tradeDate.isAfter(endDate)) {
                break;
            }
            BigDecimal close = bars.get(i).getClosePrice();
            BigDecimal prevClose = bars.get(i - 1).getClosePrice();

            BigDecimal[] ma = new BigDecimal[PERIOD_CODES.size()];
            BigDecimal[] prevMa = new BigDecimal[PERIOD_CODES.size()];
            List<String> matched = new ArrayList<>(PERIOD_CODES.size());
            for (int p : selected) {
                int n = PERIOD_DAYS[p];
                // A line is judgeable on D only with an MA(n) on both D-1 and D; otherwise it simply
                // does not participate that day — not a miss, not a whole-stock flag.
                if (MovingAverage.isDefined(i, n)) {
                    ma[p] = MovingAverage.simple(bars, i, n);
                }
                if (MovingAverage.isDefined(i - 1, n)) {
                    prevMa[p] = MovingAverage.simple(bars, i - 1, n);
                }
                if (ma[p] == null || prevMa[p] == null) {
                    continue;
                }
                anyJudged = true;
                if (prevClose.compareTo(prevMa[p]) <= 0 && close.compareTo(ma[p]) > 0) {
                    matched.add(PERIOD_CODES.get(p));
                }
            }
            if (matched.isEmpty()) {
                continue;
            }

            // buyDate: the trading day after D; missing means this hit is unresolved
            // (pendingConfirm), not a non-match — see specs/backend/strategy-scan.md, "進場日（buyDate）".
            LocalDate buyDate = nextTradingDate(bars, i);
            if (buyDate == null) {
                anyUnresolvedMatch = true;
                continue;
            }
            lastSignalDate = tradeDate;
            lastBuyDate = buyDate;
            lastDetail = new MaBreakoutDetailDto(scale(close), scale(prevClose), matched,
                    scale(ma[0]), scale(ma[1]), scale(ma[2]),
                    scale(prevMa[0]), scale(prevMa[1]), scale(prevMa[2]));
        }

        if (lastSignalDate != null) {
            return PatternDetectionOutcome.hit(lastSignalDate, lastBuyDate, lastDetail);
        }
        if (!anyJudged) {
            return PatternDetectionOutcome.insufficientData();
        }
        return PatternDetectionOutcome.noMatch(anyUnresolvedMatch);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(DETAIL_SCALE, RoundingMode.HALF_UP);
    }

    @Override
    public void populateResultParams(StrategySelectionDto selection, StrategyResultDto result) {
        List<String> used = new ArrayList<>(PERIOD_CODES.size());
        for (int p : resolveSelected(selection)) {
            used.add(PERIOD_CODES.get(p));
        }
        result.setMaPeriods(used);
    }
}
