package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.ConfirmCloseDto;
import com.stock.dto.ParamDto;
import com.stock.dto.PresetDto;
import com.stock.dto.RisingSupportDetailDto;
import com.stock.dto.StrategyResultDto;
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
 * RISING_SUPPORT — specs/backend/strategy-scan.md, "上漲支撐". For each trading day D within the
 * scanned range: D's close must break above the highest close of the `lookback` trading days
 * strictly before D, D's rise over D-1's close must meet `risePercent`, and then `confirmBars`
 * trading days (a request-level field, 1 or 2, default 2 — orthogonal to preset) starting at D+1
 * must all close strictly above D-1's close — the support line is the launch point of the rise
 * itself, not the lookback high. No volume check.
 */
@Component
@Order(3)
public class RisingSupportDetector implements PatternDetector {

    public static final String CODE = "RISING_SUPPORT";
    public static final int CONFIRM_BARS_MIN = 1;
    public static final int CONFIRM_BARS_MAX = 2;
    public static final int CONFIRM_BARS_DEFAULT = 2;
    private static final int PRICE_SCALE = 2;
    private static final int CALC_SCALE = 10;

    /**
     * One preset's full identity: thresholds plus the name/description shown in the catalogue.
     * Kept as a single record per code (rather than parallel maps) so there is exactly one place to
     * update when a threshold or its wording changes. The description text is itself the wire
     * contract (specs/backend/strategy-scan.md API contract example) and is literal, hand-written
     * text — not generated from the numeric fields — so it must be edited in lockstep with them.
     * confirmBars is a request-level field (see {@link #CONFIRM_BARS_DEFAULT} and the class javadoc),
     * not part of this per-preset table — every preset's confirmation length is the same, caller-
     * chosen value.
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

    public RisingSupportDetector() {
        presetParams.put("STRICT", new Params("嚴格",
                "收盤突破前 20 日收盤高點且單日漲幅 ≥ 5%，其後不跌破起漲收盤",
                20, new BigDecimal("0.05")));
        presetParams.put("STANDARD", new Params("標準",
                "收盤突破前 10 日收盤高點且單日漲幅 ≥ 3%，其後不跌破起漲收盤",
                10, new BigDecimal("0.03")));
        presetParams.put("LOOSE", new Params("寬鬆",
                "收盤突破前 5 日收盤高點且單日漲幅 ≥ 2%，其後不跌破起漲收盤",
                5, new BigDecimal("0.02")));
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
    public BigDecimal getRisePercentMax() {
        // specs/backend/strategy-scan.md, "risePercent 的上限逐型態認定" — this strategy's risePercent is
        // a *single day's* gain, and Taiwan's daily price-limit alone caps that at 10%; 20 leaves
        // headroom while still rejecting requests that can never match.
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
    public boolean acceptsConfirmBars() {
        return true;
    }

    @Override
    public int getConfirmBarsMin() {
        return CONFIRM_BARS_MIN;
    }

    @Override
    public int getConfirmBarsMax() {
        return CONFIRM_BARS_MAX;
    }

    @Override
    public int getConfirmBarsDefault() {
        return CONFIRM_BARS_DEFAULT;
    }

    @Override
    public List<ParamDto> getParams() {
        // Orthogonal to the preset — see the class javadoc and specs/backend/strategy-scan.md, "上漲
        // 支撐的確認長度可選（confirmBars）"; this is the one strategy that carries both presets and params.
        return List.of(new ParamDto("confirmBars", "確認天數", "日", BigDecimal.valueOf(CONFIRM_BARS_DEFAULT),
                BigDecimal.valueOf(CONFIRM_BARS_MIN), BigDecimal.valueOf(CONFIRM_BARS_MAX), BigDecimal.ONE));
    }

    private static int resolveConfirmBars(StrategySelectionDto selection) {
        return selection.getConfirmBars() != null ? selection.getConfirmBars().intValue() : CONFIRM_BARS_DEFAULT;
    }

    @Override
    public int requiredLookbackTradingDays(StrategySelectionDto selection) {
        return presetParams.get(selection.getPreset()).lookback;
    }

    @Override
    public int requiredConfirmTradingDaysAfterEndDate(StrategySelectionDto selection) {
        // confirmBars trading days to confirm the support held, plus 1 more so buyDate (the trading
        // day after the confirmation-completion day D+confirmBars) is resolvable — see
        // specs/backend/strategy-scan.md, "上漲支撐的確認資料取自 endDate 之後".
        return resolveConfirmBars(selection) + 1;
    }

    @Override
    public PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                           StrategySelectionDto selection) {
        Params params = presetParams.get(selection.getPreset());
        // risePercent overrides the single-day rise threshold (specs/backend/strategy-scan.md, 上漲
        // 支撐's override mapping); lookback stays preset-driven. confirmBars is the request's own
        // field (1 or 2, default 2), orthogonal to preset.
        BigDecimal risePercent = resolveRatio(selection.getRisePercent(), params.risePercent);
        int confirmBars = resolveConfirmBars(selection);

        int preCount = 0;
        while (preCount < bars.size() && bars.get(preCount).getTradeDate().isBefore(startDate)) {
            preCount++;
        }
        if (preCount < params.lookback) {
            return PatternDetectionOutcome.insufficientData();
        }

        LocalDate lastSignalDate = null;
        LocalDate lastBuyDate = null;
        RisingSupportDetailDto lastDetail = null;
        boolean anyUnresolvedMatch = false;

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
            if (supportClose.compareTo(BigDecimal.ZERO) == 0) {
                continue; // a zero support-line close makes the rise ratio undefined; skip, don't crash.
            }
            BigDecimal riseRatio = dClose.subtract(supportClose)
                    .divide(supportClose, CALC_SCALE, RoundingMode.HALF_UP);
            if (riseRatio.compareTo(risePercent) < 0) {
                continue;
            }

            // 3./4./5. confirmation: D+1..D+confirmBars must all close strictly above supportClose;
            // missing confirm data -> unresolved, not a non-match.
            if (i + confirmBars >= bars.size()) {
                anyUnresolvedMatch = true;
                continue;
            }
            List<ConfirmCloseDto> confirmCloses = new ArrayList<>(confirmBars);
            boolean allHeldAboveSupport = true;
            for (int k = 1; k <= confirmBars; k++) {
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

            // buyDate: the trading day after the confirmation-completion day D+confirmBars — see
            // specs/backend/strategy-scan.md, "上漲支撐的訊號日與進場日刻意不同". Missing means this hit is
            // unresolved (pendingConfirm), not a non-match.
            LocalDate buyDate = nextTradingDate(bars, i + confirmBars);
            if (buyDate == null) {
                anyUnresolvedMatch = true;
                continue;
            }

            BigDecimal risePercentActual = riseRatio.multiply(BigDecimal.valueOf(100))
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

            lastSignalDate = d.getTradeDate();
            lastBuyDate = buyDate;
            lastDetail = new RisingSupportDetailDto(
                    supportClose.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    dClose.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    risePercentActual,
                    priorHighClose.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    confirmCloses);
        }

        if (lastSignalDate != null) {
            return PatternDetectionOutcome.hit(lastSignalDate, lastBuyDate, lastDetail);
        }
        return PatternDetectionOutcome.noMatch(anyUnresolvedMatch);
    }

    @Override
    public void populateResultParams(StrategySelectionDto selection, StrategyResultDto result) {
        // confirmBars is the one exception (alongside BOX_BREAKOUT's requireVolume) to "preset and a
        // parameter field never coexist" — orthogonal to preset, so both must be echoed together —
        // see specs/backend/strategy-scan.md, "results 依 strategies 送入的順序回傳".
        result.setPreset(selection.getPreset());
        result.setConfirmBars(resolveConfirmBars(selection));
    }
}
