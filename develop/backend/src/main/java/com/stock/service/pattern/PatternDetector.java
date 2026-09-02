package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.dto.PresetDto;

import java.time.LocalDate;
import java.util.List;

/**
 * One pattern (BOX_BREAKOUT / HIGHER_LOWS / RISING_SUPPORT). Each implementation owns its own
 * STRICT/STANDARD/LOOSE parameter table and the catalogue description text for each preset — see
 * specs/backend/strategy-scan.md, "型態定義". Presets change thresholds only, never the algorithm.
 */
public interface PatternDetector {

    /** Wire code, e.g. "BOX_BREAKOUT". */
    String getCode();

    /** Human-readable name for the catalogue, e.g. "箱型突破". */
    String getName();

    /** Whether presetCode names one of this detector's three sensitivity presets. */
    boolean supportsPreset(String presetCode);

    /** The three presets (code/name/description) for GET /api/strategies. */
    List<PresetDto> getPresets();

    /**
     * Trading days of history required strictly before the scan's startDate for this preset —
     * drives how far back the batched lookback pre-fetch must reach (specs/backend/strategy-scan.md,
     * "區間與資料前置需求").
     */
    int requiredLookbackTradingDays(String presetCode);

    /**
     * Trading days of confirmation data required strictly *after* the scan's endDate for this
     * preset — 0 (the default) when the pattern needs nothing beyond endDate. Drives whether the
     * batched price read also fetches a small window after endDate, e.g. RISING_SUPPORT's D+1/D+2
     * (specs/backend/strategy-scan.md, "上漲支撐的確認資料取自 endDate 之後"). Fixed per pattern rather than
     * per preset when the pattern's own rule says the confirmation length does not vary by sensitivity.
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
     */
    PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                    String presetCode);
}
