package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.HigherLowsDetailDto;
import com.stock.dto.LowPointDto;
import com.stock.dto.PresetDto;
import com.stock.dto.StrategySelectionDto;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HIGHER_LOWS — specs/backend/strategy-scan.md, "底底高" and "底底高改以 MA5 平滑線為判定基準". A swing low is a
 * trading day D whose MA5 (5-trading-day simple moving average of CLOSE, D inclusive) is strictly
 * below the MA5 of every one of the `swingBars` trading days on both sides; a day without a defined
 * MA5 (fewer than 5 bars behind it) never participates. Among the swing lows found within the
 * scanned range, a hit is any run of `requiredRises` consecutive rises each meeting `risePercent`
 * — compared on MA5 values, never on raw lows; when several such runs exist, the latest-dated one
 * is reported. Raw lows are reported in the detail purely for reference.
 */
@Component
@Order(2)
public class HigherLowsDetector implements PatternDetector {

    public static final String CODE = "HIGHER_LOWS";
    private static final int PRICE_SCALE = 2;
    private static final int CALC_SCALE = 10;
    private static final int MA_WINDOW = 5;

    /**
     * One preset's full identity: thresholds plus the name/description shown in the catalogue.
     * Kept as a single record per code (rather than parallel maps) so there is exactly one place to
     * update when a threshold or its wording changes. The description text is itself the wire
     * contract (specs/backend/strategy-scan.md API contract example) and is literal, hand-written
     * text — not generated from the numeric fields — so it must be edited in lockstep with them.
     */
    private static final class Params {
        final String name;
        final String description;
        final int swingBars;
        final int requiredRises;
        final BigDecimal risePercent;

        Params(String name, String description, int swingBars, int requiredRises, BigDecimal risePercent) {
            this.name = name;
            this.description = description;
            this.swingBars = swingBars;
            this.requiredRises = requiredRises;
            this.risePercent = risePercent;
        }
    }

    private static final class SwingLow {
        final LocalDate tradeDate;
        final BigDecimal ma5;
        final BigDecimal low;

        SwingLow(LocalDate tradeDate, BigDecimal ma5, BigDecimal low) {
            this.tradeDate = tradeDate;
            this.ma5 = ma5;
            this.low = low;
        }
    }

    private final Map<String, Params> presetParams = new LinkedHashMap<>();

    public HigherLowsDetector() {
        presetParams.put("STRICT",
                new Params("嚴格", "以 5 日均線為基準，左右各 5 根，需 3 段遞增，每段高過 2%", 5, 3, new BigDecimal("0.02")));
        presetParams.put("STANDARD",
                new Params("標準", "以 5 日均線為基準，左右各 3 根，需 2 段遞增，每段高過 1%", 3, 2, new BigDecimal("0.01")));
        presetParams.put("LOOSE",
                new Params("寬鬆", "以 5 日均線為基準，左右各 2 根，需 2 段遞增，高過即計", 2, 2, BigDecimal.ZERO));
    }

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "底底高";
    }

    @Override
    public boolean supportsPreset(String presetCode) {
        return presetCode != null && presetParams.containsKey(presetCode);
    }

    @Override
    public List<PresetDto> getPresets() {
        List<PresetDto> result = new ArrayList<>();
        for (Map.Entry<String, Params> entry : presetParams.entrySet()) {
            result.add(new PresetDto(entry.getKey(), entry.getValue().name, entry.getValue().description));
        }
        return result;
    }

    @Override
    public int requiredLookbackTradingDays(StrategySelectionDto selection) {
        // swingBars for the left-side comparison, plus 4 more so the leftmost compared day itself
        // has a defined MA5 — see specs/backend/strategy-scan.md, "swingBars + 4 個交易日".
        return presetParams.get(selection.getPreset()).swingBars + (MA_WINDOW - 1);
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           StrategySelectionDto selection) {
        Params params = presetParams.get(selection.getPreset());
        // risePercent overrides the per-leg rise threshold (specs/backend/strategy-scan.md, 底底高's
        // override mapping); swingBars/requiredRises stay preset-driven.
        BigDecimal risePercent = resolveRatio(selection.getRisePercent(), params.risePercent);

        int preCount = 0;
        while (preCount < bars.size() && bars.get(preCount).getTradeDate().isBefore(startDate)) {
            preCount++;
        }
        if (preCount < params.swingBars + (MA_WINDOW - 1)) {
            return PatternDetectionOutcome.insufficientData();
        }

        List<SwingLow> swingLows = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            LocalDate date = bars.get(i).getTradeDate();
            if (date.isBefore(startDate) || date.isAfter(endDate)) {
                continue;
            }
            if (i - params.swingBars < 0 || i + params.swingBars >= bars.size()) {
                continue; // left/right side bars insufficient -> not a candidate, not the whole-stock flag
            }
            if (i - params.swingBars - (MA_WINDOW - 1) < 0) {
                continue; // the leftmost compared day (i - swingBars) itself needs a defined MA5
            }
            BigDecimal ma5D = ma5(bars, i);
            boolean isSwingLow = true;
            for (int j = i - params.swingBars; j <= i + params.swingBars && isSwingLow; j++) {
                if (j == i) {
                    continue;
                }
                if (ma5D.compareTo(ma5(bars, j)) >= 0) {
                    isSwingLow = false;
                }
            }
            if (isSwingLow) {
                swingLows.add(new SwingLow(date, ma5D, bars.get(i).getLowPrice()));
            }
        }

        int currentRun = 0;
        int lastQualifyingEnd = -1;
        for (int k = 1; k < swingLows.size(); k++) {
            BigDecimal prev = swingLows.get(k - 1).ma5;
            BigDecimal curr = swingLows.get(k).ma5;
            BigDecimal threshold = prev.multiply(BigDecimal.ONE.add(risePercent));
            boolean qualifies = curr.compareTo(prev) > 0 && curr.compareTo(threshold) >= 0;
            if (qualifies) {
                currentRun++;
            } else {
                currentRun = 0;
            }
            if (currentRun >= params.requiredRises) {
                lastQualifyingEnd = k;
            }
        }

        if (lastQualifyingEnd < 0) {
            return PatternDetectionOutcome.noMatch(false);
        }

        int windowStart = lastQualifyingEnd - params.requiredRises;
        List<LowPointDto> lows = new ArrayList<>();
        for (int k = windowStart; k <= lastQualifyingEnd; k++) {
            SwingLow s = swingLows.get(k);
            lows.add(new LowPointDto(s.tradeDate, s.ma5.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    s.low.setScale(PRICE_SCALE, RoundingMode.HALF_UP)));
        }

        LocalDate signalDate = swingLows.get(lastQualifyingEnd).tradeDate;
        return PatternDetectionOutcome.hit(signalDate, new HigherLowsDetailDto(lows));
    }

    /** MA5 at bars index {@code index} — the arithmetic mean of the 5 closes ending at (and
     *  including) that index. Caller must ensure {@code index >= MA_WINDOW - 1}. */
    private static BigDecimal ma5(List<StockDailyPrice> bars, int index) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int k = index - (MA_WINDOW - 1); k <= index; k++) {
            sum = sum.add(bars.get(k).getClosePrice());
        }
        return sum.divide(BigDecimal.valueOf(MA_WINDOW), CALC_SCALE, RoundingMode.HALF_UP);
    }
}
