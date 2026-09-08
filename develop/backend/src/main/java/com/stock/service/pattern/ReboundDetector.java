package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.ParamDto;
import com.stock.dto.ParamGroupDto;
import com.stock.dto.PresetDto;
import com.stock.dto.ReboundDetailDto;
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
 * REBOUND — specs/backend/strategy-scan.md, "反彈" and "反彈改為跌段＋選用漲段的雙段參數、移除靈敏度". No sensitivity
 * presets; every threshold is directly typable:
 *
 * <p><b>Stage 1 — trough T</b>: within the `dropDays` trading days up to and including T, find the
 * highest close H (date Hd); T must be the lowest close from Hd (exclusive) through T (inclusive);
 * the drop from H to T's close must meet `dropPercent`.
 *
 * <p><b>Stage 2 — rebound day S</b> (only when `requireRise` is true): within the `riseDays`
 * trading days after T (exclusive of T), S is the FIRST day whose rise from T's close meets
 * `risePercent`. `signalDate` is S; when `requireRise` is false there is no stage 2 and
 * `signalDate` is T itself. The rebound window need not have fully elapsed — already met is a hit,
 * not-yet-met is simply a miss, never pendingConfirm ({@link #requiredConfirmTradingDaysAfterEndDate}
 * is never overridden, so pendingConfirm is always empty).
 */
@Component
@Order(4)
public class ReboundDetector implements PatternDetector {

    public static final String CODE = "REBOUND";
    private static final int PRICE_SCALE = 2;
    private static final int CALC_SCALE = 10;

    public static final int DROP_DAYS_MIN = 1;
    public static final int DROP_DAYS_MAX = 90;
    public static final int DROP_DAYS_DEFAULT = 3;
    private static final BigDecimal DROP_PERCENT_DEFAULT = new BigDecimal("10");
    private static final BigDecimal DROP_PERCENT_DEFAULT_RATIO = new BigDecimal("0.10");

    public static final int RISE_DAYS_MIN = 1;
    public static final int RISE_DAYS_MAX = 90;
    public static final int RISE_DAYS_DEFAULT = 1;
    private static final BigDecimal RISE_PERCENT_DEFAULT = new BigDecimal("5");
    private static final BigDecimal RISE_PERCENT_DEFAULT_RATIO = new BigDecimal("0.05");

    private static final boolean REQUIRE_RISE_DEFAULT = true;

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "反彈";
    }

    @Override
    public boolean usesPresets() {
        return false;
    }

    @Override
    public boolean supportsPreset(String presetCode) {
        // No presets at all — a request must not carry `preset` (PRESET_NOT_APPLICABLE, checked in
        // StrategyScanService once usesPresets()==false is known; this method is never consulted).
        return false;
    }

    @Override
    public List<PresetDto> getPresets() {
        return Collections.emptyList();
    }

    @Override
    public String getDescription() {
        return "先在回看窗口內自最高收盤跌幅達門檻築出谷底，其後指定天數內自谷底反彈幅度達門檻";
    }

    @Override
    public List<ParamGroupDto> getParamGroups() {
        return List.of(new ParamGroupDto("rise", "另外要求反彈漲幅", Boolean.TRUE));
    }

    @Override
    public List<ParamDto> getParams() {
        return List.of(
                new ParamDto("dropDays", "下跌天數", "日", BigDecimal.valueOf(DROP_DAYS_DEFAULT),
                        BigDecimal.valueOf(DROP_DAYS_MIN), BigDecimal.valueOf(DROP_DAYS_MAX), BigDecimal.ONE),
                new ParamDto("dropPercent", "跌幅門檻", "%", DROP_PERCENT_DEFAULT, BigDecimal.ZERO,
                        new BigDecimal("50"), new BigDecimal("0.1")),
                new ParamDto("riseDays", "反彈天數", "日", BigDecimal.valueOf(RISE_DAYS_DEFAULT),
                        BigDecimal.valueOf(RISE_DAYS_MIN), BigDecimal.valueOf(RISE_DAYS_MAX), BigDecimal.ONE, "rise"),
                new ParamDto("risePercent", "反彈幅度", "%", RISE_PERCENT_DEFAULT, BigDecimal.ZERO,
                        new BigDecimal("50"), new BigDecimal("0.1"), "rise"));
    }

    @Override
    public boolean acceptsReboundParams() {
        return true;
    }

    private static boolean resolveRequireRise(StrategySelectionDto selection) {
        return selection.getRequireRise() == null ? REQUIRE_RISE_DEFAULT : selection.getRequireRise();
    }

    private static int resolveDropDays(StrategySelectionDto selection) {
        return selection.getDropDays() != null ? selection.getDropDays().intValue() : DROP_DAYS_DEFAULT;
    }

    private static int resolveRiseDays(StrategySelectionDto selection) {
        return selection.getRiseDays() != null ? selection.getRiseDays().intValue() : RISE_DAYS_DEFAULT;
    }

    @Override
    public int requiredLookbackTradingDays(StrategySelectionDto selection) {
        boolean requireRise = resolveRequireRise(selection);
        int dropDays = resolveDropDays(selection);
        // The drop window is `dropDays` bars *including* T itself, so only dropDays - 1 bars are
        // needed strictly before T. T itself may legitimately fall up to `riseDays` trading days
        // before startDate (its rebound day S could land exactly on startDate) — see
        // specs/backend/strategy-scan.md, "反彈需要 dropDays − 1 + riseDays 個交易日".
        return requireRise ? (dropDays - 1 + resolveRiseDays(selection)) : (dropDays - 1);
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           StrategySelectionDto selection) {
        boolean requireRise = resolveRequireRise(selection);
        int dropDays = resolveDropDays(selection);
        int riseDays = requireRise ? resolveRiseDays(selection) : 0;
        BigDecimal dropPercent = resolveRatio(selection.getDropPercent(), DROP_PERCENT_DEFAULT_RATIO);
        BigDecimal risePercent = requireRise ? resolveRatio(selection.getRisePercent(), RISE_PERCENT_DEFAULT_RATIO)
                : null;

        int preCount = 0;
        while (preCount < bars.size() && bars.get(preCount).getTradeDate().isBefore(startDate)) {
            preCount++;
        }
        int requiredPre = requireRise ? (dropDays - 1 + riseDays) : (dropDays - 1);
        if (preCount < requiredPre) {
            return PatternDetectionOutcome.insufficientData();
        }

        int startIdx = preCount;
        int endIdx = startIdx - 1;
        for (int i = startIdx; i < bars.size(); i++) {
            if (bars.get(i).getTradeDate().isAfter(endDate)) {
                break;
            }
            endIdx = i;
        }
        // T's own trough may fall up to `riseDays` trading days before startDate, so its rebound day
        // S can still land within [startDate, endDate] — see requiredLookbackTradingDays above.
        int tRangeStart = requireRise ? Math.max(0, startIdx - riseDays) : startIdx;

        LocalDate lastSignalDate = null;
        ReboundDetailDto lastDetail = null;

        for (int t = tRangeStart; t <= endIdx; t++) {
            int windowStart = t - (dropDays - 1);
            if (windowStart < 0) {
                continue;
            }

            // 1./2. highest close within [windowStart, t]; first occurrence wins on ties.
            BigDecimal peak = null;
            LocalDate peakDate = null;
            int peakIndex = -1;
            for (int j = windowStart; j <= t; j++) {
                BigDecimal c = bars.get(j).getClosePrice();
                if (peak == null || c.compareTo(peak) > 0) {
                    peak = c;
                    peakDate = bars.get(j).getTradeDate();
                    peakIndex = j;
                }
            }

            // 3. T must be the lowest close strictly after the peak, through T itself.
            BigDecimal trough;
            if (peakIndex == t) {
                if (dropDays == 1) {
                    // The window is exactly T itself, so T is trivially both the peak and the
                    // trough (0% drop) — see specs/backend/strategy-scan.md, "dropDays: 1 為合法
                    // 請求...除非 dropPercent 為 0 否則零命中". Must still be evaluated (not skipped) so
                    // dropPercent=0 can hit.
                    trough = peak;
                } else {
                    continue; // T is itself the window's peak -> no decline has happened yet from it.
                }
            } else {
                trough = null;
                for (int j = peakIndex + 1; j <= t; j++) {
                    BigDecimal c = bars.get(j).getClosePrice();
                    if (trough == null || c.compareTo(trough) < 0) {
                        trough = c;
                    }
                }
            }
            BigDecimal tClose = bars.get(t).getClosePrice();
            if (tClose.compareTo(trough) != 0) {
                continue;
            }
            if (peak.compareTo(BigDecimal.ZERO) == 0) {
                continue; // a zero-price window high makes the drop ratio undefined; skip, don't crash.
            }

            // 4. drop percent
            BigDecimal dropRatio = peak.subtract(tClose).divide(peak, CALC_SCALE, RoundingMode.HALF_UP);
            if (dropRatio.compareTo(dropPercent) < 0) {
                continue;
            }
            BigDecimal dropPercentActual = dropRatio.multiply(BigDecimal.valueOf(100))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

            LocalDate signalDate;
            BigDecimal risePercentActual = null;
            if (requireRise) {
                // 5./6./7. first day within (T, T + riseDays] whose rise from T's close meets
                // risePercent; the window need not have fully elapsed (spec: "漲段窗口尚未跑滿仍照判定").
                LocalDate foundDate = null;
                BigDecimal foundRiseRatio = null;
                int riseWindowEnd = Math.min(t + riseDays, bars.size() - 1);
                if (tClose.compareTo(BigDecimal.ZERO) != 0) {
                    for (int k = t + 1; k <= riseWindowEnd; k++) {
                        BigDecimal c = bars.get(k).getClosePrice();
                        BigDecimal riseRatio = c.subtract(tClose).divide(tClose, CALC_SCALE, RoundingMode.HALF_UP);
                        if (riseRatio.compareTo(risePercent) >= 0) {
                            foundDate = bars.get(k).getTradeDate();
                            foundRiseRatio = riseRatio;
                            break;
                        }
                    }
                }
                if (foundDate == null) {
                    continue; // no rebound found within the window -> this trough does not qualify.
                }
                signalDate = foundDate;
                risePercentActual = foundRiseRatio.multiply(BigDecimal.valueOf(100))
                        .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            } else {
                signalDate = bars.get(t).getTradeDate();
            }
            if (signalDate.isBefore(startDate) || signalDate.isAfter(endDate)) {
                continue; // the signal itself must land within the scanned range.
            }

            if (lastSignalDate == null || !signalDate.isBefore(lastSignalDate)) {
                lastSignalDate = signalDate;
                lastDetail = new ReboundDetailDto(peakDate, peak.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                        bars.get(t).getTradeDate(), tClose.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                        dropPercentActual, risePercentActual);
            }
        }

        if (lastSignalDate != null) {
            return PatternDetectionOutcome.hit(lastSignalDate, lastDetail);
        }
        // Never produces pendingConfirm — "已達標就命中、不等窗口跑滿" means an unmet window is simply a miss.
        return PatternDetectionOutcome.noMatch(false);
    }

    @Override
    public void populateResultParams(StrategySelectionDto selection, StrategyResultDto result) {
        boolean requireRise = resolveRequireRise(selection);
        result.setRequireRise(requireRise);
        result.setDropDays(resolveDropDays(selection));
        result.setDropPercent(selection.getDropPercent() != null ? selection.getDropPercent() : DROP_PERCENT_DEFAULT);
        if (requireRise) {
            result.setRiseDays(resolveRiseDays(selection));
            result.setRisePercent(
                    selection.getRisePercent() != null ? selection.getRisePercent() : RISE_PERCENT_DEFAULT);
        }
    }
}
