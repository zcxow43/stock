package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.CumulativeRiseDetailDto;
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
 * CUMULATIVE_RISE — specs/backend/strategy-scan.md, "累積上漲". The exact mirror image of
 * {@link ReboundDetector}: for each trading day D within the scanned range, within the `lookback`
 * trading days up to and including D, find the lowest close L (date Ld); D must be the highest
 * close from Ld (exclusive) through D (inclusive); and the rise from L to D's close must meet
 * `risePercent`. Requires no single-day rise and no confirmation — unlike RISING_SUPPORT, a slow
 * multi-day climb qualifies as long as the cumulative rise clears the threshold. Never overrides
 * {@link #requiredConfirmTradingDaysAfterEndDate}, so pendingConfirm is always empty.
 */
@Component
@Order(5)
public class CumulativeRiseDetector implements PatternDetector {

    public static final String CODE = "CUMULATIVE_RISE";
    private static final int PRICE_SCALE = 2;
    private static final int CALC_SCALE = 10;

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
        final int lookback;
        final BigDecimal risePercent;

        Params(String name, String description, int lookback, BigDecimal risePercent) {
            this.name = name;
            this.description = description;
            this.lookback = lookback;
            this.risePercent = risePercent;
        }
    }

    private final Map<String, Params> presetParams = new LinkedHashMap<>();

    public CumulativeRiseDetector() {
        presetParams.put("STRICT",
                new Params("嚴格", "回看 20 日，自區間最低收盤累積漲幅 ≥ 20% 的最高點", 20, new BigDecimal("0.20")));
        presetParams.put("STANDARD",
                new Params("標準", "回看 20 日，自區間最低收盤累積漲幅 ≥ 15% 的最高點", 20, new BigDecimal("0.15")));
        presetParams.put("LOOSE",
                new Params("寬鬆", "回看 10 日，自區間最低收盤累積漲幅 ≥ 10% 的最高點", 10, new BigDecimal("0.10")));
    }

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "累積上漲";
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
        // The window is `lookback` bars *including* D itself, so only lookback - 1 bars are needed
        // strictly before D (and hence before startDate for the earliest possible D).
        return presetParams.get(presetCode).lookback - 1;
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           String presetCode, BigDecimal risePercentOverride) {
        Params params = presetParams.get(presetCode);
        // risePercent overrides this pattern's own risePercent (specs/backend/strategy-scan.md,
        // 累積上漲's override mapping); lookback stays preset-driven.
        BigDecimal risePercent = resolveRatio(risePercentOverride, params.risePercent);

        int preCount = 0;
        while (preCount < bars.size() && bars.get(preCount).getTradeDate().isBefore(startDate)) {
            preCount++;
        }
        if (preCount < params.lookback - 1) {
            return PatternDetectionOutcome.insufficientData();
        }

        LocalDate lastSignalDate = null;
        CumulativeRiseDetailDto lastDetail = null;

        for (int i = preCount; i < bars.size(); i++) {
            StockDailyPrice d = bars.get(i);
            if (d.getTradeDate().isAfter(endDate)) {
                break;
            }
            int windowStart = i - (params.lookback - 1);
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

            // 3. D must be the highest close strictly after the trough, through D itself.
            if (troughIndex == i) {
                // D is itself the window's trough — no rise has happened yet from it.
                continue;
            }
            BigDecimal peak = null;
            for (int j = troughIndex + 1; j <= i; j++) {
                BigDecimal c = bars.get(j).getClosePrice();
                if (peak == null || c.compareTo(peak) > 0) {
                    peak = c;
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
}
