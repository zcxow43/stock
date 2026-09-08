package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.ParamDto;
import com.stock.dto.ParamGroupDto;
import com.stock.dto.PresetDto;
import com.stock.dto.StrategyResultDto;
import com.stock.dto.StrategySelectionDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * One pattern (BOX_BREAKOUT / HIGHER_LOWS / RISING_SUPPORT / REBOUND / CUMULATIVE_RISE). Each
 * implementation owns its own STRICT/STANDARD/LOOSE parameter table and the catalogue description
 * text for each preset — see specs/backend/strategy-scan.md, "型態定義". Presets change thresholds
 * only, never the algorithm.
 *
 * <p>{@link #detect} and {@link #requiredLookbackTradingDays} take the whole request-side
 * {@link StrategySelectionDto} as a single parameter object, rather than a growing list of
 * individually-overridable arguments — REBOUND alone needs five independent inputs
 * (requireRise/dropDays/dropPercent/riseDays/risePercent), so a per-field overload per detector
 * would sprawl. Each implementation simply reads whichever of the selection's fields are relevant
 * to it.
 */
public interface PatternDetector {

    BigDecimal PERCENT_DIVISOR = BigDecimal.valueOf(100);
    int RATIO_SCALE = 10;

    /** Wire code, e.g. "BOX_BREAKOUT". */
    String getCode();

    /** Human-readable name for the catalogue, e.g. "箱型突破". */
    String getName();

    /** Whether presetCode names one of this detector's three sensitivity presets. */
    boolean supportsPreset(String presetCode);

    /** The three presets (code/name/description) for GET /api/strategies. */
    List<PresetDto> getPresets();

    /**
     * Whether this detector is selected via one of three sensitivity presets (STRICT/STANDARD/
     * LOOSE) — true for BOX_BREAKOUT/HIGHER_LOWS/RISING_SUPPORT. False for detectors that instead
     * take fully-specified numeric parameters directly (CUMULATIVE_RISE's `days`/`risePercent`,
     * REBOUND's `requireRise`/`dropDays`/`dropPercent`/`riseDays`/`risePercent`) — see
     * specs/backend/strategy-scan.md, "presets 與 params 的關係". Drives whether a scan request for
     * this strategy must carry `preset` (true) or must not carry `preset` at all (false).
     */
    default boolean usesPresets() {
        return true;
    }

    /**
     * Strategy-level description shown on the catalogue card for detectors where
     * {@link #usesPresets()} is false (there is no per-preset description to fall back on) — see
     * specs/backend/strategy-scan.md, "presets 與 params 的關係". Null for preset-driven detectors.
     */
    default String getDescription() {
        return null;
    }

    /**
     * The directly-typable parameters (code/name/unit/default/min/max/step/group) shown on the
     * catalogue card for detectors where {@link #usesPresets()} is false. Empty for preset-driven
     * detectors.
     */
    default List<ParamDto> getParams() {
        return Collections.emptyList();
    }

    /**
     * Optional, whole-group-togglable subsets of {@link #getParams()} (currently only REBOUND's
     * "rise" group) — see specs/backend/strategy-scan.md, "選用參數群組". Null (omitted on the wire,
     * per {@link com.stock.dto.StrategyDto}'s {@code @JsonInclude(NON_NULL)}) for every other
     * detector — unlike {@link #getPresets()}, an empty `paramGroups` is not itself meaningful, so
     * there is no reason to send `"paramGroups": []` for strategies that never have any.
     */
    default List<ParamGroupDto> getParamGroups() {
        return null;
    }

    /**
     * Whether this detector accepts the request's `days` field — true only for CUMULATIVE_RISE.
     * Distinct from {@link #usesPresets()}==false: REBOUND is also preset-less but does not accept
     * `days` (it has its own `dropDays`/`riseDays` instead) — see specs/backend/strategy-scan.md,
     * "对 REBOUND 帶了 days → 400 DAYS_NOT_APPLICABLE".
     */
    default boolean acceptsDaysField() {
        return false;
    }

    /**
     * Inclusive lower/upper bounds and default for the `days` parameter, and the value actually
     * used when a scan omits `days` — meaningful only when {@link #acceptsDaysField()} is true.
     * Exposed here (rather than referenced as constants on the concrete class) so the generic scan
     * pipeline (StrategyScanService) can validate/default `days` without depending on which
     * concrete detector it is talking to.
     */
    default int getDaysMin() {
        return 0;
    }

    default int getDaysMax() {
        return 0;
    }

    default int getDaysDefault() {
        return 0;
    }

    /**
     * Whether this detector accepts REBOUND's own params (`requireRise`/`dropDays`/`dropPercent`/
     * `riseDays`) — true only for REBOUND. Every other strategy must reject them with
     * PARAM_NOT_APPLICABLE — see specs/backend/strategy-scan.md, "反彈改為跌段＋選用漲段的雙段參數、移除靈敏度".
     */
    default boolean acceptsReboundParams() {
        return false;
    }

    /**
     * Trading days of history required strictly before the scan's startDate for this selection —
     * drives how far back the batched lookback pre-fetch must reach (specs/backend/strategy-scan.md,
     * "區間與資料前置需求"). REBOUND/CUMULATIVE_RISE return lookback − 1, since their own window
     * already counts D (or T) itself as one of the `lookback` bars; REBOUND additionally adds
     * `riseDays` when `requireRise` is true, since its trough may legitimately fall before
     * startDate.
     */
    int requiredLookbackTradingDays(StrategySelectionDto selection);

    /**
     * Trading days of confirmation data required strictly *after* the scan's endDate for this
     * preset — 0 (the default) when the pattern needs nothing beyond endDate. Drives whether the
     * batched price read also fetches a small window after endDate, e.g. RISING_SUPPORT's D+1/D+2
     * (specs/backend/strategy-scan.md, "上漲支撐的確認資料取自 endDate 之後"). Fixed per pattern rather than
     * per preset when the pattern's own rule says the confirmation length does not vary by sensitivity.
     * REBOUND/CUMULATIVE_RISE never override this — they do not confirm and so never produce
     * pendingConfirm (specs/backend/strategy-scan.md, "反彈與累積上漲一律不產生 pendingConfirm").
     */
    default int requiredConfirmTradingDaysAfterEndDate(String presetCode) {
        return 0;
    }

    /**
     * Detects this pattern for a single stock. `bars` is one stock's full OHLCV series, ascending by
     * trade_date, containing the lookback bars strictly before startDate, every bar within
     * [startDate, endDate], and — only for patterns that declare a non-zero
     * {@link #requiredConfirmTradingDaysAfterEndDate} — up to that many trading days after endDate.
     * Adjacent list entries are adjacent *trading* days; calendar gaps from suspensions are never
     * interpolated.
     *
     * @param selection the request's per-strategy selection, already validated. Each
     *                   implementation reads whichever of its own fields it needs (preset,
     *                   risePercent override, days, or REBOUND's requireRise/dropDays/dropPercent/
     *                   riseDays) and ignores the rest.
     */
    PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                    StrategySelectionDto selection);

    /**
     * Populates the scan response's per-strategy header fields (`preset`, or `days`, or REBOUND's
     * `requireRise`/`dropDays`/`dropPercent`/`riseDays`/`risePercent`) with the values actually used
     * — defaulted when the request omitted them — see specs/backend/strategy-scan.md, "results 依
     * strategies 送入的順序回傳". Default implementation covers every preset-driven detector; only
     * {@link CumulativeRiseDetector} and {@link ReboundDetector} override it.
     */
    default void populateResultParams(StrategySelectionDto selection, StrategyResultDto result) {
        result.setPreset(selection.getPreset());
    }

    /**
     * Converts a whole-percent override (e.g. {@code 2.5} meaning 2.5%) into the ratio form
     * (e.g. {@code 0.025}) every detector's threshold math is written in, or returns the preset's
     * own ratio unchanged when no override was given. Shared here — rather than duplicated per
     * detector — because every implementation performs exactly this substitution and nothing else
     * with the override value.
     */
    default BigDecimal resolveRatio(BigDecimal risePercentOverride, BigDecimal presetRatio) {
        if (risePercentOverride == null) {
            return presetRatio;
        }
        return risePercentOverride.divide(PERCENT_DIVISOR, RATIO_SCALE, RoundingMode.HALF_UP);
    }
}
