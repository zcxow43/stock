package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.domain.StockInstitutionalTrade;
import com.stock.dto.InstitutionalStrengthRankDetailDto;
import com.stock.dto.InvestorStrengthDto;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * INSTITUTIONAL_STRENGTH_RANK — specs/backend/strategy-scan.md, "法人買超強度排名". For each vetted
 * investor and each judgeable trading day D, strength is {@code windowNetBuy / windowVolumeSum};
 * only stocks with a strictly positive window net buy participate; the top `topN` by strength (raw,
 * unrounded value; ties broken by ascending stockId) become that day's hits for that investor.
 * Ranking is necessarily a whole-population, per-day computation (spec: "排名必須在全部股票都算完之後才能
 * 做") — see {@link InstitutionalPatternDetector}'s class javadoc for why this needs {@code detectAll}
 * rather than a per-stock {@code detect}.
 */
@Component
@Order(8)
public class InstitutionalStrengthRankDetector implements InstitutionalPatternDetector {

    public static final String CODE = "INSTITUTIONAL_STRENGTH_RANK";

    public static final int WINDOW_DAYS_MIN = 1;
    public static final int WINDOW_DAYS_MAX = 20;
    public static final int WINDOW_DAYS_DEFAULT = 5;
    public static final int TOP_N_MIN = 1;
    public static final int TOP_N_MAX = 50;
    public static final int TOP_N_DEFAULT = 10;

    private static final int PERCENT_SCALE = 2;

    /** One stock's strength on one trading day — the ranking pool's raw sort key. */
    private static final class Candidate {
        final String stockId;
        final BigDecimal strength;

        Candidate(String stockId, BigDecimal strength) {
            this.stockId = stockId;
            this.strength = strength;
        }
    }

    @Override
    public String getCode() {
        return CODE;
    }

    @Override
    public String getName() {
        return "法人買超強度排名";
    }

    @Override
    public String getDescription() {
        return "外資（不含外資自營商）或投信近指定天數買超合計 ÷ 成交股數合計為強度，只有淨買超者參與，每個交易日外資與投信各取強度前幾名";
    }

    @Override
    public boolean acceptsWindowDays() {
        return true;
    }

    @Override
    public boolean acceptsTopN() {
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
                new ParamDto("topN", "取前幾名", "名", BigDecimal.valueOf(TOP_N_DEFAULT),
                        BigDecimal.valueOf(TOP_N_MIN), BigDecimal.valueOf(TOP_N_MAX), BigDecimal.ONE));
    }

    private static int resolveWindowDays(StrategySelectionDto selection) {
        return selection.getWindowDays() != null ? selection.getWindowDays().intValue() : WINDOW_DAYS_DEFAULT;
    }

    private static int resolveTopN(StrategySelectionDto selection) {
        return selection.getTopN() != null ? selection.getTopN().intValue() : TOP_N_DEFAULT;
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
        int topN = resolveTopN(selection);
        List<String> investors = InstitutionalPatternDetector.resolveInvestors(selection);
        boolean wantForeign = investors.contains(FOREIGN);
        boolean wantTrust = investors.contains(TRUST);

        // Pass 1: every stock's evaluable windows, and the per-day ranking candidate pools.
        Map<String, List<InstitutionalWindowMath.DayWindow>> windowsByStock = new LinkedHashMap<>();
        Map<LocalDate, List<Candidate>> foreignCandidates = new HashMap<>();
        Map<LocalDate, List<Candidate>> trustCandidates = new HashMap<>();
        for (String stockId : targetIds) {
            List<StockDailyPrice> bars = priceSeriesByStock.getOrDefault(stockId, List.of());
            List<StockInstitutionalTrade> instRows = institutionalSeriesByStock.getOrDefault(stockId, List.of());
            List<InstitutionalWindowMath.DayWindow> windows = InstitutionalWindowMath.computeEvaluableWindows(bars,
                    instRows, fetchedInstitutionalDates, startDate, endDate, windowDays);
            windowsByStock.put(stockId, windows);

            for (InstitutionalWindowMath.DayWindow dw : windows) {
                if (dw.volumeShares <= 0) {
                    continue;
                }
                if (wantForeign && dw.foreignNetShares > 0) {
                    foreignCandidates.computeIfAbsent(dw.date, k -> new ArrayList<>())
                            .add(new Candidate(stockId, strength(dw.foreignNetShares, dw.volumeShares)));
                }
                if (wantTrust && dw.trustNetShares > 0) {
                    trustCandidates.computeIfAbsent(dw.date, k -> new ArrayList<>())
                            .add(new Candidate(stockId, strength(dw.trustNetShares, dw.volumeShares)));
                }
            }
        }

        // Pass 2: rank each day's candidate pool — this scan's population only (spec: "名次在本次掃描
        // 母體內計算"), raw strength descending, ties broken by ascending stockId.
        Map<LocalDate, Map<String, Integer>> foreignRank = rankByDate(foreignCandidates, topN);
        Map<LocalDate, Map<String, Integer>> trustRank = rankByDate(trustCandidates, topN);

        // Pass 3: collapse each stock to its most recent hit-with-buyDate (or pendingConfirm).
        Map<String, PatternDetectionOutcome> outcomes = new LinkedHashMap<>();
        for (String stockId : targetIds) {
            List<StockDailyPrice> bars = priceSeriesByStock.getOrDefault(stockId, List.of());
            List<InstitutionalWindowMath.DayWindow> windows = windowsByStock.get(stockId);

            if (windows.isEmpty()) {
                outcomes.put(stockId, PatternDetectionOutcome.insufficientData());
                continue;
            }

            LocalDate lastSignalDate = null;
            LocalDate lastBuyDate = null;
            InstitutionalStrengthRankDetailDto lastDetail = null;
            boolean anyUnresolvedMatch = false;

            for (InstitutionalWindowMath.DayWindow dw : windows) {
                List<String> matched = new ArrayList<>(2);
                InvestorStrengthDto foreignDetail = null;
                InvestorStrengthDto trustDetail = null;

                Integer foreignRankValue = foreignRank.getOrDefault(dw.date, Map.of()).get(stockId);
                if (foreignRankValue != null) {
                    matched.add(FOREIGN);
                    foreignDetail = new InvestorStrengthDto(foreignRankValue,
                            strength(dw.foreignNetShares, dw.volumeShares).multiply(BigDecimal.valueOf(100))
                                    .setScale(PERCENT_SCALE, RoundingMode.HALF_UP),
                            dw.foreignNetShares);
                }
                Integer trustRankValue = trustRank.getOrDefault(dw.date, Map.of()).get(stockId);
                if (trustRankValue != null) {
                    matched.add(TRUST);
                    trustDetail = new InvestorStrengthDto(trustRankValue,
                            strength(dw.trustNetShares, dw.volumeShares).multiply(BigDecimal.valueOf(100))
                                    .setScale(PERCENT_SCALE, RoundingMode.HALF_UP),
                            dw.trustNetShares);
                }

                if (matched.isEmpty()) {
                    continue;
                }
                if (dw.barIndex + 1 < bars.size()) {
                    lastSignalDate = dw.date;
                    lastBuyDate = bars.get(dw.barIndex + 1).getTradeDate();
                    lastDetail = new InstitutionalStrengthRankDetailDto(dw.windowStartDate, dw.volumeShares, matched,
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

    private static BigDecimal strength(long netShares, long volumeShares) {
        return BigDecimal.valueOf(netShares)
                .divide(BigDecimal.valueOf(volumeShares), PatternDetector.RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static Map<LocalDate, Map<String, Integer>> rankByDate(Map<LocalDate, List<Candidate>> candidatesByDate,
                                                                     int topN) {
        Map<LocalDate, Map<String, Integer>> result = new HashMap<>();
        for (Map.Entry<LocalDate, List<Candidate>> entry : candidatesByDate.entrySet()) {
            List<Candidate> ranked = new ArrayList<>(entry.getValue());
            ranked.sort(Comparator.comparing((Candidate c) -> c.strength).reversed()
                    .thenComparing(c -> c.stockId));
            Map<String, Integer> rankByStock = new HashMap<>();
            int limit = Math.min(topN, ranked.size());
            for (int i = 0; i < limit; i++) {
                rankByStock.put(ranked.get(i).stockId, i + 1);
            }
            result.put(entry.getKey(), rankByStock);
        }
        return result;
    }

    @Override
    public void populateResultParams(StrategySelectionDto selection, StrategyResultDto result) {
        result.setInvestors(InstitutionalPatternDetector.resolveInvestors(selection));
        result.setWindowDays(resolveWindowDays(selection));
        result.setTopN(resolveTopN(selection));
    }
}
