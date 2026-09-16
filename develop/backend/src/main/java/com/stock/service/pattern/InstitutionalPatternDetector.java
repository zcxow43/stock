package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.domain.StockInstitutionalTrade;
import com.stock.dto.PresetDto;
import com.stock.dto.StrategySelectionDto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One of the three institutional-trade (三大法人) patterns — INSTITUTIONAL_NET_RATIO,
 * INSTITUTIONAL_CONSECUTIVE_BUY, INSTITUTIONAL_STRENGTH_RANK — see specs/backend/strategy-scan.md,
 * "法人籌碼型態". These extend {@link PatternDetector} (so the catalogue/validation seam — presets,
 * params, `acceptsXxx` flags — stays exactly one interface hierarchy) but replace the single-stock
 * {@link #detect} with {@link #detectAll}, a whole-population batch method.
 *
 * <p>This is a genuine, spec-required difference in shape, not a stylistic one:
 * INSTITUTIONAL_STRENGTH_RANK's per-day rank can only be computed once every target stock's
 * strength for that day is known ("排名必須在全部股票都算完之後才能做" — specs/backend/strategy-scan.md,
 * "處理流程"), which a per-stock {@code detect(bars, ...)} call has no way to see. Rather than force
 * that cross-stock computation through a single-stock signature (e.g. by inventing an ad-hoc
 * "shared context" object threaded through every detector, including the five price patterns that
 * have no use for it), the batch shape is made explicit here. {@link #detect} is given a default
 * that throws, since it is never called for these three codes (StrategyScanService dispatches to
 * {@link #detectAll} instead, based on this interface).
 */
public interface InstitutionalPatternDetector extends PatternDetector {

    String FOREIGN = "FOREIGN";
    String TRUST = "TRUST";
    List<String> DEFAULT_INVESTORS = List.of(FOREIGN, TRUST);

    @Override
    default boolean usesPresets() {
        return false;
    }

    @Override
    default boolean supportsPreset(String presetCode) {
        return false;
    }

    @Override
    default List<PresetDto> getPresets() {
        return Collections.emptyList();
    }

    @Override
    default boolean acceptsRisePercent() {
        return false;
    }

    @Override
    default boolean acceptsInvestors() {
        return true;
    }

    /**
     * Institutional daily reports are published only after the market closes, so the earliest
     * entry day that does not use future data is the trading day after the signal day, for every
     * preset-less selection of every one of the three patterns — see specs/backend/strategy-scan.md,
     * "為什麼 buyDate 是下一個交易日，不是 D 本身".
     */
    @Override
    default int requiredConfirmTradingDaysAfterEndDate(String presetCode) {
        return 1;
    }

    @Override
    default PatternDetectionOutcome detect(List<StockDailyPrice> bars, LocalDate startDate, LocalDate endDate,
                                            StrategySelectionDto selection) {
        throw new UnsupportedOperationException(
                getCode() + " is a batch-only institutional detector; use detectAll instead");
    }

    /**
     * Resolves the request's `investors` to the effective subset, always ordered
     * (FOREIGN, TRUST) regardless of the request's own array order — see specs/backend/
     * strategy-scan.md, "detail.matchedInvestors 列出實際達標的一方（依 FOREIGN、TRUST 的固定順序）". Omitted
     * (null/empty already rejected as INVALID_INVESTORS before this is ever called) defaults to both.
     */
    static List<String> resolveInvestors(StrategySelectionDto selection) {
        List<String> requested = selection.getInvestors();
        if (requested == null || requested.isEmpty()) {
            return DEFAULT_INVESTORS;
        }
        List<String> ordered = new ArrayList<>(2);
        if (requested.contains(FOREIGN)) {
            ordered.add(FOREIGN);
        }
        if (requested.contains(TRUST)) {
            ordered.add(TRUST);
        }
        return ordered;
    }

    /**
     * Computes one {@link PatternDetectionOutcome} per target stock at once. `priceSeriesByStock`
     * holds each stock's OHLCV series (lookback bars strictly before startDate, the scanned window,
     * and up to 1 trading day strictly after endDate for `buyDate` — see
     * {@link #requiredConfirmTradingDaysAfterEndDate}); `institutionalSeriesByStock` holds each
     * stock's {@code stock_institutional_trade} rows over the same lookback-through-endDate span
     * (never beyond endDate: "法人資料本身只讀到 endDate 為止"); `fetchedInstitutionalDates` is the set of
     * trading days, across ALL securities (not just the target population), that have at least one
     * institutional-trade row — the "已抓取" signal a day's window must be entirely covered by before
     * it can be judged at all.
     */
    Map<String, PatternDetectionOutcome> detectAll(List<String> targetIds,
            Map<String, List<StockDailyPrice>> priceSeriesByStock,
            Map<String, List<StockInstitutionalTrade>> institutionalSeriesByStock,
            Set<LocalDate> fetchedInstitutionalDates,
            LocalDate startDate, LocalDate endDate,
            StrategySelectionDto selection);
}
