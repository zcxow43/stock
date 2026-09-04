package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.PresetDto;
import com.stock.dto.ReboundDetailDto;
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
 * REBOUND — specs/backend/strategy-scan.md, "反彈". For each trading day D within the scanned
 * range: within the `lookback` trading days up to and including D, find the highest close H (date
 * Hd); D must be the lowest close from Hd (exclusive) through D (inclusive) — otherwise every day
 * of an ongoing decline would each match once; and the drop from H to D's close must meet
 * `dropPercent`. Does not confirm that a rebound actually followed — {@link
 * #requiredConfirmTradingDaysAfterEndDate} is never overridden, so pendingConfirm is always empty.
 */
@Component
@Order(4)
public class ReboundDetector implements PatternDetector {

    public static final String CODE = "REBOUND";
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
        final BigDecimal dropPercent;

        Params(String name, String description, int lookback, BigDecimal dropPercent) {
            this.name = name;
            this.description = description;
            this.lookback = lookback;
            this.dropPercent = dropPercent;
        }
    }

    private final Map<String, Params> presetParams = new LinkedHashMap<>();

    public ReboundDetector() {
        presetParams.put("STRICT",
                new Params("嚴格", "回看 20 日，自區間最高收盤跌幅 ≥ 20% 的最低點", 20, new BigDecimal("0.20")));
        presetParams.put("STANDARD",
                new Params("標準", "回看 20 日，自區間最高收盤跌幅 ≥ 15% 的最低點", 20, new BigDecimal("0.15")));
        presetParams.put("LOOSE",
                new Params("寬鬆", "回看 10 日，自區間最高收盤跌幅 ≥ 10% 的最低點", 10, new BigDecimal("0.10")));
    }

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "反彈";
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
        // risePercent overrides dropPercent (specs/backend/strategy-scan.md, 反彈's override mapping
        // — the field name is reused for request-shape consistency, but for REBOUND it is a drop
        // threshold, not a rise threshold); lookback stays preset-driven.
        BigDecimal dropPercent = resolveRatio(risePercentOverride, params.dropPercent);

        int preCount = 0;
        while (preCount < bars.size() && bars.get(preCount).getTradeDate().isBefore(startDate)) {
            preCount++;
        }
        if (preCount < params.lookback - 1) {
            return PatternDetectionOutcome.insufficientData();
        }

        LocalDate lastSignalDate = null;
        ReboundDetailDto lastDetail = null;

        for (int i = preCount; i < bars.size(); i++) {
            StockDailyPrice d = bars.get(i);
            if (d.getTradeDate().isAfter(endDate)) {
                break;
            }
            int windowStart = i - (params.lookback - 1);
            if (windowStart < 0) {
                continue;
            }

            // 1./2. highest close within the lookback window (including D); first occurrence wins on ties.
            BigDecimal peak = null;
            LocalDate peakDate = null;
            int peakIndex = -1;
            for (int j = windowStart; j <= i; j++) {
                BigDecimal c = bars.get(j).getClosePrice();
                if (peak == null || c.compareTo(peak) > 0) {
                    peak = c;
                    peakDate = bars.get(j).getTradeDate();
                    peakIndex = j;
                }
            }

            // 3. D must be the lowest close strictly after the peak, through D itself.
            if (peakIndex == i) {
                // D is itself the window's peak — no decline has happened yet from it.
                continue;
            }
            BigDecimal trough = null;
            for (int j = peakIndex + 1; j <= i; j++) {
                BigDecimal c = bars.get(j).getClosePrice();
                if (trough == null || c.compareTo(trough) < 0) {
                    trough = c;
                }
            }
            BigDecimal dClose = d.getClosePrice();
            if (dClose.compareTo(trough) != 0) {
                continue;
            }

            // 4. drop percent
            BigDecimal dropRatio = peak.subtract(dClose).divide(peak, CALC_SCALE, RoundingMode.HALF_UP);
            if (dropRatio.compareTo(dropPercent) < 0) {
                continue;
            }

            BigDecimal dropPercentActual = dropRatio.multiply(BigDecimal.valueOf(100))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

            lastSignalDate = d.getTradeDate();
            lastDetail = new ReboundDetailDto(
                    peakDate,
                    peak.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    dClose.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    dropPercentActual);
        }

        if (lastSignalDate != null) {
            return PatternDetectionOutcome.hit(lastSignalDate, lastDetail);
        }
        // Never produces pendingConfirm — this pattern does not confirm the rebound actually happened.
        return PatternDetectionOutcome.noMatch(false);
    }
}
