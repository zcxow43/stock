package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.ConfirmCloseDto;
import com.stock.dto.PresetDto;
import com.stock.dto.RisingSupportDetailDto;
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
 * RISING_SUPPORT — specs/backend/strategy-scan.md, "上漲支撐". For each trading day D within the
 * scanned range: D's close must break above the highest close of the `lookback` trading days
 * strictly before D, D's rise over D-1's close must meet `risePercent`, and then D+1 and D+2 (fixed
 * at 2 trading days regardless of preset) must both close strictly above D-1's close — the support
 * line is the launch point of the rise itself, not the lookback high. No volume check.
 */
@Component
@Order(3)
public class RisingSupportDetector implements PatternDetector {

    public static final String CODE = "RISING_SUPPORT";
    private static final int CONFIRM_BARS = 2;
    private static final int PRICE_SCALE = 2;
    private static final int CALC_SCALE = 10;

    /**
     * One preset's full identity: thresholds plus the name/description shown in the catalogue.
     * Kept as a single record per code (rather than parallel maps) so there is exactly one place to
     * update when a threshold or its wording changes. The description text is itself the wire
     * contract (specs/backend/strategy-scan.md API contract example) and is literal, hand-written
     * text — not generated from the numeric fields — so it must be edited in lockstep with them.
     * confirmBars is carried here too even though it is fixed at 2 for every preset, so the whole
     * parameter table — including "this does not vary" — lives in one place.
     */
    private static final class Params {
        final String name;
        final String description;
        final int lookback;
        final BigDecimal risePercent;
        final int confirmBars;

        Params(String name, String description, int lookback, BigDecimal risePercent, int confirmBars) {
            this.name = name;
            this.description = description;
            this.lookback = lookback;
            this.risePercent = risePercent;
            this.confirmBars = confirmBars;
        }
    }

    private final Map<String, Params> presetParams = new LinkedHashMap<>();

    public RisingSupportDetector() {
        presetParams.put("STRICT", new Params("嚴格",
                "收盤突破前 20 日收盤高點且單日漲幅 ≥ 5%，其後 2 日不跌破起漲收盤",
                20, new BigDecimal("0.05"), CONFIRM_BARS));
        presetParams.put("STANDARD", new Params("標準",
                "收盤突破前 10 日收盤高點且單日漲幅 ≥ 3%，其後 2 日不跌破起漲收盤",
                10, new BigDecimal("0.03"), CONFIRM_BARS));
        presetParams.put("LOOSE", new Params("寬鬆",
                "收盤突破前 5 日收盤高點且單日漲幅 ≥ 2%，其後 2 日不跌破起漲收盤",
                5, new BigDecimal("0.02"), CONFIRM_BARS));
    }

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "上漲支撐";
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
        return presetParams.get(presetCode).lookback;
    }

    @Override
    public int requiredConfirmTradingDaysAfterEndDate(String presetCode) {
        return presetParams.get(presetCode).confirmBars;
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           String presetCode, BigDecimal risePercentOverride) {
        Params params = presetParams.get(presetCode);
        // risePercent overrides the single-day rise threshold (specs/backend/strategy-scan.md, 上漲
        // 支撐's override mapping); lookback and the fixed 2-day confirmBars stay preset-driven.
        BigDecimal risePercent = resolveRatio(risePercentOverride, params.risePercent);

        int preCount = 0;
        while (preCount < bars.size() && bars.get(preCount).getTradeDate().isBefore(startDate)) {
            preCount++;
        }
        if (preCount < params.lookback) {
            return PatternDetectionOutcome.insufficientData();
        }

        LocalDate lastSignalDate = null;
        RisingSupportDetailDto lastDetail = null;
        boolean pendingConfirm = false;

        for (int i = preCount; i < bars.size(); i++) {
            StockDailyPrice d = bars.get(i);
            if (d.getTradeDate().isAfter(endDate)) {
                // D itself must fall within [startDate, endDate]; any bars beyond endDate exist only
                // to confirm an earlier D and are never evaluated as a signal day themselves.
                break;
            }
            if (i - params.lookback < 0) {
                continue;
            }

            // 1. breakout above the prior lookback window's highest close
            BigDecimal priorHighClose = null;
            for (int j = i - params.lookback; j < i; j++) {
                BigDecimal c = bars.get(j).getClosePrice();
                if (priorHighClose == null || c.compareTo(priorHighClose) > 0) {
                    priorHighClose = c;
                }
            }
            BigDecimal dClose = d.getClosePrice();
            if (dClose.compareTo(priorHighClose) <= 0) {
                continue;
            }

            // 2. rise percent over D-1's close (the launch point / support line)
            BigDecimal supportClose = bars.get(i - 1).getClosePrice();
            BigDecimal riseRatio = dClose.subtract(supportClose)
                    .divide(supportClose, CALC_SCALE, RoundingMode.HALF_UP);
            if (riseRatio.compareTo(risePercent) < 0) {
                continue;
            }

            // 3./4./5. confirmation: D+1 and D+2 (fixed at 2, never varies by preset) must both close
            // strictly above supportClose; missing confirm data -> pendingConfirm, not a non-match.
            if (i + params.confirmBars >= bars.size()) {
                pendingConfirm = true;
                continue;
            }
            List<ConfirmCloseDto> confirmCloses = new ArrayList<>(params.confirmBars);
            boolean allHeldAboveSupport = true;
            for (int k = 1; k <= params.confirmBars; k++) {
                StockDailyPrice confirmBar = bars.get(i + k);
                confirmCloses.add(new ConfirmCloseDto(confirmBar.getTradeDate(),
                        confirmBar.getClosePrice().setScale(PRICE_SCALE, RoundingMode.HALF_UP)));
                if (confirmBar.getClosePrice().compareTo(supportClose) <= 0) {
                    allHeldAboveSupport = false;
                }
            }
            if (!allHeldAboveSupport) {
                continue;
            }

            BigDecimal risePercentActual = riseRatio.multiply(BigDecimal.valueOf(100))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

            lastSignalDate = d.getTradeDate();
            lastDetail = new RisingSupportDetailDto(
                    supportClose.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    dClose.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    risePercentActual,
                    priorHighClose.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    confirmCloses);
        }

        if (lastSignalDate != null) {
            return PatternDetectionOutcome.hit(lastSignalDate, lastDetail);
        }
        return PatternDetectionOutcome.noMatch(pendingConfirm);
    }
}
