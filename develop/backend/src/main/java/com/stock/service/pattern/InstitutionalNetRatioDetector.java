package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.domain.StockInstitutionalTrade;
import com.stock.dto.InstitutionalNetRatioDetailDto;
import com.stock.dto.InvestorNetRatioDto;
import com.stock.dto.ParamDto;
import com.stock.dto.ParamOptionDto;
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
import java.util.Set;

/**
 * INSTITUTIONAL_NET_RATIO — specs/backend/strategy-scan.md, "法人買賣超佔比". For each vetted investor
 * and each judgeable trading day D: sum the window's signed net shares (positive = net buy,
 * negative = net sell), then {@code |sum| / windowVolumeSum} must clear `ratioPercent` and the sum
 * itself must not be zero. Both investors judged independently; either clearing is a hit.
 */
@Component
@Order(6)
public class InstitutionalNetRatioDetector implements InstitutionalPatternDetector {

    public static final String CODE = "INSTITUTIONAL_NET_RATIO";

    public static final int WINDOW_DAYS_MIN = 1;
    public static final int WINDOW_DAYS_MAX = 20;
    public static final int WINDOW_DAYS_DEFAULT = 5;
    private static final BigDecimal RATIO_PERCENT_DEFAULT = new BigDecimal("10");

    private static final int PERCENT_SCALE = 2;
    private static final int CALC_SCALE = 10;

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "法人買賣超佔比";
    }

    @Override
    public String getDescription() {
        return "外資（不含外資自營商）或投信近指定天數買賣超合計取絕對值 ÷ 成交股數合計，達門檻即命中，淨買超與淨賣超皆計";
    }

    @Override
    public boolean acceptsWindowDays() {
        return true;
    }

    @Override
    public boolean acceptsRatioPercent() {
        return true;
    }

    @Override
    public List<ParamDto> getParams() {
        return List.of(
                new ParamDto("investors", "法人", "multiSelect",
                        List.of(new ParamOptionDto("FOREIGN", "外資"), new ParamOptionDto("TRUST", "投信")),
                        DEFAULT_INVESTORS, 1),
                new ParamDto("windowDays", "天數", "日", BigDecimal.valueOf(WINDOW_DAYS_DEFAULT),
                        BigDecimal.valueOf(WINDOW_DAYS_MIN), BigDecimal.valueOf(WINDOW_DAYS_MAX), BigDecimal.ONE),
                new ParamDto("ratioPercent", "佔比門檻", "%", RATIO_PERCENT_DEFAULT, BigDecimal.ZERO,
                        new BigDecimal("100"), new BigDecimal("0.1")));
    }

    private static int resolveWindowDays(StrategySelectionDto selection) {
        return selection.getWindowDays() != null ? selection.getWindowDays().intValue() : WINDOW_DAYS_DEFAULT;
    }

    private static BigDecimal resolveRatioPercent(StrategySelectionDto selection) {
        return selection.getRatioPercent() != null ? selection.getRatioPercent() : RATIO_PERCENT_DEFAULT;
    }

    @Override
    public int requiredLookbackTradingDays(StrategySelectionDto selection) {
        return resolveWindowDays(selection) - 1;
    }

    @Override
    public Map<String, PatternDetectionOutcome> detectAll(List<String> targetIds,
            Map<String, List<StockDailyPrice>> priceSeriesByStock,
            Map<String, List<StockInstitutionalTrade>> institutionalSeriesByStock,
            Set<LocalDate> fetchedInstitutionalDates, LocalDate startDate, LocalDate endDate,
            StrategySelectionDto selection) {
        int windowDays = resolveWindowDays(selection);
        BigDecimal ratioThreshold = resolveRatioPercent(selection)
                .divide(BigDecimal.valueOf(100), CALC_SCALE, RoundingMode.HALF_UP);
        List<String> investors = InstitutionalPatternDetector.resolveInvestors(selection);
        boolean wantForeign = investors.contains(FOREIGN);
        boolean wantTrust = investors.contains(TRUST);

        Map<String, PatternDetectionOutcome> outcomes = new LinkedHashMap<>();
        for (String stockId : targetIds) {
            List<StockDailyPrice> bars = priceSeriesByStock.getOrDefault(stockId, List.of());
            List<StockInstitutionalTrade> instRows = institutionalSeriesByStock.getOrDefault(stockId, List.of());
            List<InstitutionalWindowMath.DayWindow> windows = InstitutionalWindowMath.computeEvaluableWindows(bars,
                    instRows, fetchedInstitutionalDates, startDate, endDate, windowDays);

            if (windows.isEmpty()) {
                outcomes.put(stockId, PatternDetectionOutcome.insufficientData());
                continue;
            }

            LocalDate lastSignalDate = null;
            LocalDate lastBuyDate = null;
            InstitutionalNetRatioDetailDto lastDetail = null;
            boolean anyUnresolvedMatch = false;

            for (InstitutionalWindowMath.DayWindow dw : windows) {
                List<String> matched = new ArrayList<>(2);
                InvestorNetRatioDto foreignDetail = null;
                InvestorNetRatioDto trustDetail = null;

                if (wantForeign && dw.volumeShares > 0 && dw.foreignNetShares != 0) {
                    BigDecimal ratio = new BigDecimal(Math.abs(dw.foreignNetShares))
                            .divide(BigDecimal.valueOf(dw.volumeShares), CALC_SCALE, RoundingMode.HALF_UP);
                    if (ratio.compareTo(ratioThreshold) >= 0) {
                        matched.add(FOREIGN);
                        foreignDetail = new InvestorNetRatioDto(dw.foreignNetShares,
                                ratio.multiply(BigDecimal.valueOf(100)).setScale(PERCENT_SCALE, RoundingMode.HALF_UP),
                                dw.foreignNetShares > 0 ? "BUY" : "SELL");
                    }
                }
                if (wantTrust && dw.volumeShares > 0 && dw.trustNetShares != 0) {
                    BigDecimal ratio = new BigDecimal(Math.abs(dw.trustNetShares))
                            .divide(BigDecimal.valueOf(dw.volumeShares), CALC_SCALE, RoundingMode.HALF_UP);
                    if (ratio.compareTo(ratioThreshold) >= 0) {
                        matched.add(TRUST);
                        trustDetail = new InvestorNetRatioDto(dw.trustNetShares,
                                ratio.multiply(BigDecimal.valueOf(100)).setScale(PERCENT_SCALE, RoundingMode.HALF_UP),
                                dw.trustNetShares > 0 ? "BUY" : "SELL");
                    }
                }

                if (matched.isEmpty()) {
                    continue;
                }
                if (dw.barIndex + 1 < bars.size()) {
                    lastSignalDate = dw.date;
                    lastBuyDate = bars.get(dw.barIndex + 1).getTradeDate();
                    lastDetail = new InstitutionalNetRatioDetailDto(dw.windowStartDate, dw.volumeShares, matched,
                            foreignDetail, trustDetail);
                } else {
                    anyUnresolvedMatch = true;
                }
            }

            if (lastSignalDate != null) {
                outcomes.put(stockId, PatternDetectionOutcome.hit(lastSignalDate, lastBuyDate, lastDetail));
            } else {
                outcomes.put(stockId, PatternDetectionOutcome.noMatch(anyUnresolvedMatch));
            }
        }
        return outcomes;
    }

    @Override
    public void populateResultParams(StrategySelectionDto selection, StrategyResultDto result) {
        result.setInvestors(InstitutionalPatternDetector.resolveInvestors(selection));
        result.setWindowDays(resolveWindowDays(selection));
        result.setRatioPercent(resolveRatioPercent(selection));
    }
}
