package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.BoxBreakoutDetailDto;
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
 * BOX_BREAKOUT — specs/backend/strategy-scan.md, "箱型突破". For each trading day D within the
 * scanned range: build the box from the `lookback` trading days strictly before D, require it was
 * consolidating (unless the preset skips that check), require D's close to break above the box top
 * by `breakoutPercent`, require volume confirmation, and require `confirmBars` closes above the box
 * top (1 = D alone, 2 = D and its immediate next trading day).
 */
@Component
@Order(1)
public class BoxBreakoutDetector implements PatternDetector {

    public static final String CODE = "BOX_BREAKOUT";
    private static final int VOLUME_AVG_DAYS = 5;
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
        final BigDecimal rangeMaxPercent; // null = not validated (LOOSE skips the consolidation check)
        final BigDecimal breakoutPercent;
        final BigDecimal volumeMultiple; // null = not validated (LOOSE skips the volume check)
        final int confirmBars;

        Params(String name, String description, int lookback, BigDecimal rangeMaxPercent,
               BigDecimal breakoutPercent, BigDecimal volumeMultiple, int confirmBars) {
            this.name = name;
            this.description = description;
            this.lookback = lookback;
            this.rangeMaxPercent = rangeMaxPercent;
            this.breakoutPercent = breakoutPercent;
            this.volumeMultiple = volumeMultiple;
            this.confirmBars = confirmBars;
        }
    }

    private final Map<String, Params> presetParams = new LinkedHashMap<>();

    public BoxBreakoutDetector() {
        presetParams.put("STRICT", new Params("嚴格", "回看 60 根，箱高 < 5%，突破 2% 且量增 2 倍，需連 2 根確認",
                60, new BigDecimal("0.05"), new BigDecimal("0.02"), new BigDecimal("2.0"), 2));
        presetParams.put("STANDARD", new Params("標準", "回看 20 根，箱高 < 8%，突破 1.5% 且量增 1.5 倍",
                20, new BigDecimal("0.08"), new BigDecimal("0.015"), new BigDecimal("1.5"), 1));
        presetParams.put("LOOSE", new Params("寬鬆", "回看 20 根，不驗證盤整，收盤突破上緣即計",
                20, null, BigDecimal.ZERO, null, 1));
    }

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "箱型突破";
    }

    @Override
    public boolean supportsPreset(String presetCode) {
        return presetCode != null && presetParams.containsKey(presetCode);
    }

    @Override
    public BigDecimal getRisePercentMax() {
        // specs/backend/strategy-scan.md, "risePercent 的上限逐型態認定" — breakoutPercent (the excess
        // over the box top) cannot meaningfully exceed 20%.
        return new BigDecimal("20");
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
        return presetParams.get(selection.getPreset()).lookback;
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           StrategySelectionDto selection) {
        Params params = presetParams.get(selection.getPreset());
        // risePercent overrides breakoutPercent (specs/backend/strategy-scan.md, 箱型突破's override
        // mapping); lookback/rangeMaxPercent/volumeMultiple/confirmBars stay preset-driven.
        BigDecimal breakoutPercent = resolveRatio(selection.getRisePercent(), params.breakoutPercent);

        int preCount = 0;
        while (preCount < bars.size() && bars.get(preCount).getTradeDate().isBefore(startDate)) {
            preCount++;
        }
        if (preCount < params.lookback) {
            return PatternDetectionOutcome.insufficientData();
        }

        LocalDate lastSignalDate = null;
        BoxBreakoutDetailDto lastDetail = null;
        boolean pendingConfirm = false;

        for (int i = preCount; i < bars.size(); i++) {
            StockDailyPrice d = bars.get(i);
            if (d.getTradeDate().isAfter(endDate)) {
                break;
            }

            // 1. box: lookback trading days strictly before D
            BigDecimal boxHigh = null;
            BigDecimal boxLow = null;
            for (int j = i - params.lookback; j < i; j++) {
                StockDailyPrice b = bars.get(j);
                if (boxHigh == null || b.getHighPrice().compareTo(boxHigh) > 0) {
                    boxHigh = b.getHighPrice();
                }
                if (boxLow == null || b.getLowPrice().compareTo(boxLow) < 0) {
                    boxLow = b.getLowPrice();
                }
            }

            // 2. consolidation precondition
            if (params.rangeMaxPercent != null) {
                BigDecimal mid = boxHigh.add(boxLow).divide(BigDecimal.valueOf(2), CALC_SCALE, RoundingMode.HALF_UP);
                if (mid.compareTo(BigDecimal.ZERO) == 0
                        || boxHigh.subtract(boxLow).divide(mid, CALC_SCALE, RoundingMode.HALF_UP)
                                .compareTo(params.rangeMaxPercent) >= 0) {
                    continue;
                }
            }

            // 3. breakout
            BigDecimal breakoutThreshold = boxHigh.multiply(BigDecimal.ONE.add(breakoutPercent));
            if (d.getClosePrice().compareTo(breakoutThreshold) <= 0) {
                continue;
            }

            // 4. volume confirmation
            BigDecimal avgVolume = averageVolume(bars, i);
            if (params.volumeMultiple != null) {
                BigDecimal volumeThreshold = avgVolume.multiply(params.volumeMultiple);
                if (BigDecimal.valueOf(d.getVolume()).compareTo(volumeThreshold) <= 0) {
                    continue;
                }
            }

            // 5. confirm bars
            if (params.confirmBars >= 2) {
                if (i + 1 >= bars.size()) {
                    // D is the last trading day in range with no next-day data yet.
                    pendingConfirm = true;
                    continue;
                }
                StockDailyPrice next = bars.get(i + 1);
                if (next.getClosePrice().compareTo(boxHigh) <= 0) {
                    continue;
                }
            }

            BigDecimal breakoutPercentActual = d.getClosePrice().subtract(boxHigh)
                    .divide(boxHigh, CALC_SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            BigDecimal volumeRatio = avgVolume.compareTo(BigDecimal.ZERO) == 0 ? null
                    : BigDecimal.valueOf(d.getVolume()).divide(avgVolume, CALC_SCALE, RoundingMode.HALF_UP)
                            .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

            lastSignalDate = d.getTradeDate();
            lastDetail = new BoxBreakoutDetailDto(
                    boxHigh.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    boxLow.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    d.getClosePrice().setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    breakoutPercentActual,
                    volumeRatio);
        }

        if (lastSignalDate != null) {
            return PatternDetectionOutcome.hit(lastSignalDate, lastDetail);
        }
        return PatternDetectionOutcome.noMatch(pendingConfirm);
    }

    private BigDecimal averageVolume(List<StockDailyPrice> bars, int dIndex) {
        long sum = 0;
        for (int j = dIndex - VOLUME_AVG_DAYS; j < dIndex; j++) {
            sum += bars.get(j).getVolume();
        }
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(VOLUME_AVG_DAYS), CALC_SCALE, RoundingMode.HALF_UP);
    }
}
