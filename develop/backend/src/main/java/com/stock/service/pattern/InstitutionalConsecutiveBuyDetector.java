package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.domain.StockInstitutionalTrade;
import com.stock.dto.InstitutionalConsecutiveBuyDetailDto;
import com.stock.dto.InvestorConsecutiveBuyDto;
import com.stock.dto.ParamDto;
import com.stock.dto.ParamOptionDto;
import com.stock.dto.StrategyResultDto;
import com.stock.dto.StrategySelectionDto;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * INSTITUTIONAL_CONSECUTIVE_BUY — specs/backend/strategy-scan.md, "法人連續買超". For each vetted
 * investor and each judgeable trading day D: every day within the `buyDays` window (including D)
 * must individually have a positive net-buy for this investor. Both investors judged
 * independently; either clearing is a hit.
 */
@Component
@Order(7)
public class InstitutionalConsecutiveBuyDetector implements InstitutionalPatternDetector {

    public static final String CODE = "INSTITUTIONAL_CONSECUTIVE_BUY";

    public static final int BUY_DAYS_MIN = 1;
    public static final int BUY_DAYS_MAX = 20;
    public static final int BUY_DAYS_DEFAULT = 5;

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "法人連續買超";
    }

    @Override
    public String getDescription() {
        return "外資（不含外資自營商）或投信連續指定天數每日買超";
    }

    @Override
    public boolean acceptsBuyDays() {
        return true;
    }

    @Override
    public List<ParamDto> getParams() {
        return List.of(
                new ParamDto("investors", "法人", "multiSelect",
                        List.of(new ParamOptionDto("FOREIGN", "外資"), new ParamOptionDto("TRUST", "投信")),
                        DEFAULT_INVESTORS, 1),
                new ParamDto("buyDays", "連續買超天數", "日", BigDecimal.valueOf(BUY_DAYS_DEFAULT),
                        BigDecimal.valueOf(BUY_DAYS_MIN), BigDecimal.valueOf(BUY_DAYS_MAX), BigDecimal.ONE));
    }

    private static int resolveBuyDays(StrategySelectionDto selection) {
        return selection.getBuyDays() != null ? selection.getBuyDays().intValue() : BUY_DAYS_DEFAULT;
    }

    @Override
    public int requiredLookbackTradingDays(StrategySelectionDto selection) {
        return resolveBuyDays(selection) - 1;
    }

    @Override
    public Map<String, PatternDetectionOutcome> detectAll(List<String> targetIds,
            Map<String, List<StockDailyPrice>> priceSeriesByStock,
            Map<String, List<StockInstitutionalTrade>> institutionalSeriesByStock,
            Set<LocalDate> fetchedInstitutionalDates, LocalDate startDate, LocalDate endDate,
            StrategySelectionDto selection) {
        int buyDays = resolveBuyDays(selection);
        List<String> investors = InstitutionalPatternDetector.resolveInvestors(selection);
        boolean wantForeign = investors.contains(FOREIGN);
        boolean wantTrust = investors.contains(TRUST);

        Map<String, PatternDetectionOutcome> outcomes = new LinkedHashMap<>();
        for (String stockId : targetIds) {
            List<StockDailyPrice> bars = priceSeriesByStock.getOrDefault(stockId, List.of());
            List<StockInstitutionalTrade> instRows = institutionalSeriesByStock.getOrDefault(stockId, List.of());
            List<InstitutionalWindowMath.DayWindow> windows = InstitutionalWindowMath.computeEvaluableWindows(bars,
                    instRows, fetchedInstitutionalDates, startDate, endDate, buyDays);

            if (windows.isEmpty()) {
                outcomes.put(stockId, PatternDetectionOutcome.insufficientData());
                continue;
            }

            LocalDate lastSignalDate = null;
            LocalDate lastBuyDate = null;
            InstitutionalConsecutiveBuyDetailDto lastDetail = null;
            boolean anyUnresolvedMatch = false;

            for (InstitutionalWindowMath.DayWindow dw : windows) {
                List<String> matched = new ArrayList<>(2);
                InvestorConsecutiveBuyDto foreignDetail = null;
                InvestorConsecutiveBuyDto trustDetail = null;

                if (wantForeign && dw.foreignAllPositive) {
                    matched.add(FOREIGN);
                    foreignDetail = new InvestorConsecutiveBuyDto(dw.foreignNetShares);
                }
                if (wantTrust && dw.trustAllPositive) {
                    matched.add(TRUST);
                    trustDetail = new InvestorConsecutiveBuyDto(dw.trustNetShares);
                }

                if (matched.isEmpty()) {
                    continue;
                }
                if (dw.barIndex + 1 < bars.size()) {
                    lastSignalDate = dw.date;
                    lastBuyDate = bars.get(dw.barIndex + 1).getTradeDate();
                    lastDetail = new InstitutionalConsecutiveBuyDetailDto(dw.windowStartDate, matched, foreignDetail,
                            trustDetail);
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
        result.setBuyDays(resolveBuyDays(selection));
    }
}
