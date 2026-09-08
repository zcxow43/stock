package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.CumulativeRiseDetailDto;
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
 * CUMULATIVE_RISE — specs/backend/strategy-scan.md, "累積上漲改為可輸入天數（本次新增）". Unlike the other
 * four detectors, this one has no sensitivity presets: the caller supplies `days` (look-back
 * trading-day count, default 20, range 1-90) and `risePercent` (default 15, range 0-50) directly.
 * For each trading day D within the scanned range, within the `days` trading days up to and
 * including D, find the lowest close L (date Ld); D must be the highest close from Ld (exclusive)
 * through D (inclusive); and the rise from L to D's close must meet `risePercent`. Requires no
 * single-day rise and no confirmation. Never overrides
 * {@link PatternDetector#requiredConfirmTradingDaysAfterEndDate}, so pendingConfirm is always empty.
 */
@Component
@Order(5)
public class CumulativeRiseDetector implements PatternDetector {

    public static final String CODE = "CUMULATIVE_RISE";

    public static final int DAYS_MIN = 1;
    public static final int DAYS_MAX = 90;
    public static final int DAYS_DEFAULT = 20;
    private static final BigDecimal RISE_PERCENT_DEFAULT = new BigDecimal("15");
    private static final BigDecimal RISE_PERCENT_DEFAULT_RATIO = new BigDecimal("0.15");

    private static final int PRICE_SCALE = 2;
    private static final int CALC_SCALE = 10;

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "累積上漲";
    }

    @Override
    public boolean usesPresets() {
        return false;
    }

    @Override
    public boolean acceptsDaysField() {
        return true;
    }

    @Override
    public boolean supportsPreset(String presetCode) {
        // This detector has no presets at all — a request must not carry `preset` (see
        // StrategyScanService's PRESET_NOT_APPLICABLE check, gated on usesPresets()==false, so
        // this method is never actually consulted for CUMULATIVE_RISE).
        return false;
    }

    @Override
    public List<PresetDto> getPresets() {
        return Collections.emptyList();
    }

    @Override
    public String getDescription() {
        return "回看指定天數，自窗口內最低收盤累積漲幅達門檻的最高點";
    }

    @Override
    public List<ParamDto> getParams() {
        return List.of(
                new ParamDto("days", "天數", "日", BigDecimal.valueOf(DAYS_DEFAULT), BigDecimal.valueOf(DAYS_MIN),
                        BigDecimal.valueOf(DAYS_MAX), BigDecimal.valueOf(1)),
                new ParamDto("risePercent", "漲幅門檻", "%", RISE_PERCENT_DEFAULT, BigDecimal.ZERO,
                        new BigDecimal("50"), new BigDecimal("0.1")));
    }

    @Override
    public int getDaysMin() {
        return DAYS_MIN;
    }

    @Override
    public int getDaysMax() {
        return DAYS_MAX;
    }

    @Override
    public int getDaysDefault() {
        return DAYS_DEFAULT;
    }

    @Override
    public int requiredLookbackTradingDays(StrategySelectionDto selection) {
        // The window is `days` bars *including* D itself, so only days - 1 bars are needed strictly
        // before D (and hence before startDate for the earliest possible D).
        return resolveDays(daysOverride(selection)) - 1;
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           StrategySelectionDto selection) {
        int days = resolveDays(daysOverride(selection));
        BigDecimal risePercent = resolveRatio(selection.getRisePercent(), RISE_PERCENT_DEFAULT_RATIO);

        int preCount = 0;
        while (preCount < bars.size() && bars.get(preCount).getTradeDate().isBefore(startDate)) {
            preCount++;
        }
        if (preCount < days - 1) {
            return PatternDetectionOutcome.insufficientData();
        }

        LocalDate lastSignalDate = null;
        CumulativeRiseDetailDto lastDetail = null;

        for (int i = preCount; i < bars.size(); i++) {
            StockDailyPrice d = bars.get(i);
            if (d.getTradeDate().isAfter(endDate)) {
                break;
            }
            int windowStart = i - (days - 1);
            if (windowStart < 0) {
                continue;
            }

            // 1./2. lowest close within the lookback window (including D); first occurrence wins on ties.
            BigDecimal trough = null;
            LocalDate troughDate = null;
            int troughIndex = -1;
            for (int j = windowStart; j <= i; j++) {
                BigDecimal c = bars.get(j).getClosePrice();
                if (trough == null || c.compareTo(trough) < 0) {
                    trough = c;
                    troughDate = bars.get(j).getTradeDate();
                    troughIndex = j;
                }
            }

            BigDecimal peak;
            if (troughIndex == i) {
                if (days == 1) {
                    // days=1: the window is exactly D itself, so D is trivially both the trough and
                    // the peak (0% rise) — see specs/backend/strategy-scan.md, "days = 1 是合法值，但
                    // 幾乎不會命中". Must still be evaluated (not skipped) so risePercent=0 can hit.
                    peak = trough;
                } else {
                    // D is itself the window's trough — no rise has happened yet from it.
                    continue;
                }
            } else {
                // 3. D must be the highest close strictly after the trough, through D itself.
                peak = null;
                for (int j = troughIndex + 1; j <= i; j++) {
                    BigDecimal c = bars.get(j).getClosePrice();
                    if (peak == null || c.compareTo(peak) > 0) {
                        peak = c;
                    }
                }
            }
            BigDecimal dClose = d.getClosePrice();
            if (dClose.compareTo(peak) != 0) {
                continue;
            }

            // 4. rise percent
            BigDecimal riseRatio = dClose.subtract(trough).divide(trough, CALC_SCALE, RoundingMode.HALF_UP);
            if (riseRatio.compareTo(risePercent) < 0) {
                continue;
            }

            BigDecimal risePercentActual = riseRatio.multiply(BigDecimal.valueOf(100))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

            lastSignalDate = d.getTradeDate();
            lastDetail = new CumulativeRiseDetailDto(
                    troughDate,
                    trough.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    dClose.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    risePercentActual);
        }

        if (lastSignalDate != null) {
            return PatternDetectionOutcome.hit(lastSignalDate, lastDetail);
        }
        // Never produces pendingConfirm — this pattern does not confirm the rise continued.
        return PatternDetectionOutcome.noMatch(false);
    }

    private static int resolveDays(Integer daysOverride) {
        return daysOverride != null ? daysOverride : DAYS_DEFAULT;
    }

    private static Integer daysOverride(StrategySelectionDto selection) {
        return selection.getDays() != null ? selection.getDays().intValue() : null;
    }

    @Override
    public void populateResultParams(StrategySelectionDto selection, StrategyResultDto result) {
        result.setDays(resolveDays(daysOverride(selection)));
    }
}
