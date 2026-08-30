package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.HigherLowsDetailDto;
import com.stock.dto.LowPointDto;
import com.stock.dto.PresetDto;
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
 * HIGHER_LOWS — specs/backend/strategy-scan.md, "底底高". A swing low is a trading day whose low is
 * strictly below the low of every one of the `swingBars` trading days on both sides. Among the swing
 * lows found within the scanned range, a hit is any run of `requiredRises` consecutive rises each
 * meeting `risePercent`; when several such runs exist, the latest-dated one is reported.
 */
@Component
@Order(2)
public class HigherLowsDetector implements PatternDetector {

    public static final String CODE = "HIGHER_LOWS";
    private static final int PRICE_SCALE = 2;

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
        final BigDecimal low;

        SwingLow(LocalDate tradeDate, BigDecimal low) {
            this.tradeDate = tradeDate;
            this.low = low;
        }
    }

    private final Map<String, Params> presetParams = new LinkedHashMap<>();

    public HigherLowsDetector() {
        presetParams.put("STRICT",
                new Params("嚴格", "左右各 5 根，需 3 段遞增，每段高過 2%", 5, 3, new BigDecimal("0.02")));
        presetParams.put("STANDARD",
                new Params("標準", "左右各 3 根，需 2 段遞增，每段高過 1%", 3, 2, new BigDecimal("0.01")));
        presetParams.put("LOOSE",
                new Params("寬鬆", "左右各 2 根，需 2 段遞增，高過即計", 2, 2, BigDecimal.ZERO));
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
    public int requiredLookbackTradingDays(String presetCode) {
        return presetParams.get(presetCode).swingBars;
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           String presetCode) {
        Params params = presetParams.get(presetCode);

        int preCount = 0;
        while (preCount < bars.size() && bars.get(preCount).getTradeDate().isBefore(startDate)) {
            preCount++;
        }
        if (preCount < params.swingBars) {
            return PatternDetectionOutcome.insufficientData();
        }

        List<SwingLow> swingLows = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            LocalDate date = bars.get(i).getTradeDate();
            if (date.isBefore(startDate) || date.isAfter(endDate)) {
                continue;
            }
            if (i - params.swingBars < 0 || i + params.swingBars >= bars.size()) {
                continue; // left/right side data insufficient -> not a candidate, not the whole-stock flag
            }
            BigDecimal low = bars.get(i).getLowPrice();
            boolean isSwingLow = true;
            for (int j = i - params.swingBars; j <= i + params.swingBars && isSwingLow; j++) {
                if (j == i) {
                    continue;
                }
                if (low.compareTo(bars.get(j).getLowPrice()) >= 0) {
                    isSwingLow = false;
                }
            }
            if (isSwingLow) {
                swingLows.add(new SwingLow(date, low));
            }
        }

        int currentRun = 0;
        int lastQualifyingEnd = -1;
        for (int k = 1; k < swingLows.size(); k++) {
            BigDecimal prev = swingLows.get(k - 1).low;
            BigDecimal curr = swingLows.get(k).low;
            BigDecimal threshold = prev.multiply(BigDecimal.ONE.add(params.risePercent));
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
            lows.add(new LowPointDto(s.tradeDate, s.low.setScale(PRICE_SCALE, RoundingMode.HALF_UP)));
        }

        LocalDate signalDate = swingLows.get(lastQualifyingEnd).tradeDate;
        return PatternDetectionOutcome.hit(signalDate, new HigherLowsDetailDto(lows));
    }
}
