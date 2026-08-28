package com.stock.service;

import com.stock.domain.HighLow;
import com.stock.domain.StockDailyIndicator;
import com.stock.domain.StockDailyPrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math unit tests for the MACD/KD recursion — no Spring context, no DB. Formulas are the
 * contract in specs/dba/stock-daily-indicator.md; behavior (warmup flagging, incremental ==
 * full for the same day, "50 seed only ever used as the recursion's synthetic starting point")
 * is the contract in specs/backend/stock-indicator-statistics.md.
 */
class IndicatorCalculationServiceTest {

    private static final String STOCK_ID = "SYN";
    private static final String PARAM_KEY = StockDailyIndicator.PARAM_KEY;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.001");

    private final IndicatorCalculationService service = new IndicatorCalculationService();

    @Test
    void jValue_alwaysEqualsThreeKMinusTwoD() {
        List<StockDailyPrice> rows = generateSeries(60, LocalDate.of(2026, 1, 1));
        List<StockDailyIndicator> result = service.computeFull(STOCK_ID, PARAM_KEY, rows, null);

        for (StockDailyIndicator row : result) {
            BigDecimal expectedJ = row.getKValue().multiply(BigDecimal.valueOf(3))
                    .subtract(row.getDValue().multiply(BigDecimal.valueOf(2)))
                    .setScale(8, RoundingMode.HALF_UP);
            assertEquals(0, expectedJ.compareTo(row.getJValue()),
                    "J must equal 3K-2D on " + row.getTradeDate() + " (expected " + expectedJ + ", got " + row.getJValue() + ")");
        }
    }

    @Test
    void firstRow_seedsEmaAtCloseAndAppliesOneKdStepFromTheConventional50Seed() {
        List<StockDailyPrice> rows = generateSeries(5, LocalDate.of(2026, 1, 1));
        List<StockDailyIndicator> result = service.computeFull(STOCK_ID, PARAM_KEY, rows, null);

        StockDailyIndicator first = result.get(0);
        // EMA12 and EMA26 both seeded at day 0's close -> DIF = 0, DEA = DIF = 0
        assertEquals(0, rows.get(0).getClosePrice().setScale(8, RoundingMode.HALF_UP).compareTo(first.getEmaFast()));
        assertEquals(0, rows.get(0).getClosePrice().setScale(8, RoundingMode.HALF_UP).compareTo(first.getEmaSlow()));
        assertEquals(0, BigDecimal.ZERO.setScale(8).compareTo(first.getDif()));
        assertEquals(0, BigDecimal.ZERO.setScale(8).compareTo(first.getDea()));

        // K/D initial value is 50 (spec: "序列起点的 K、D 初始值为 50"); the persisted first row is
        // already one RMA step advanced from that seed using day 0's own RSV.
        BigDecimal expectedK = new BigDecimal("50").multiply(BigDecimal.valueOf(2)).add(first.getRsv())
                .divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_UP);
        BigDecimal expectedD = new BigDecimal("50").multiply(BigDecimal.valueOf(2)).add(expectedK)
                .divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_UP);
        assertEquals(0, expectedK.compareTo(first.getKValue()));
        assertEquals(0, expectedD.compareTo(first.getDValue()));
    }

    @Test
    void incrementalStep_producesBitIdenticalResultToTheSameDayComputedAsPartOfFull() {
        List<StockDailyPrice> rows = generateSeries(30, LocalDate.of(2026, 1, 1));
        List<StockDailyIndicator> full = service.computeFull(STOCK_ID, PARAM_KEY, rows, null);

        StockDailyIndicator previousState = full.get(full.size() - 2);
        StockDailyPrice currentPrice = rows.get(rows.size() - 1);

        int windowStart = Math.max(0, rows.size() - 9);
        BigDecimal high = null;
        BigDecimal low = null;
        for (int i = windowStart; i < rows.size(); i++) {
            BigDecimal h = rows.get(i).getHighPrice();
            BigDecimal l = rows.get(i).getLowPrice();
            if (high == null || h.compareTo(high) > 0) high = h;
            if (low == null || l.compareTo(low) < 0) low = l;
        }
        HighLow recentHighLow = new HighLow();
        recentHighLow.setHighPrice(high);
        recentHighLow.setLowPrice(low);

        StockDailyIndicator incremental = service.computeIncrementalStep(
                STOCK_ID, PARAM_KEY, previousState, currentPrice, recentHighLow);

        StockDailyIndicator expected = full.get(full.size() - 1);
        assertEquals(0, expected.getEmaFast().compareTo(incremental.getEmaFast()));
        assertEquals(0, expected.getEmaSlow().compareTo(incremental.getEmaSlow()));
        assertEquals(0, expected.getDif().compareTo(incremental.getDif()));
        assertEquals(0, expected.getDea().compareTo(incremental.getDea()));
        assertEquals(0, expected.getOsc().compareTo(incremental.getOsc()));
        assertEquals(0, expected.getRsv().compareTo(incremental.getRsv()));
        assertEquals(0, expected.getKValue().compareTo(incremental.getKValue()));
        assertEquals(0, expected.getDValue().compareTo(incremental.getDValue()));
        assertEquals(0, expected.getJValue().compareTo(incremental.getJValue()));
    }

    @Test
    void computeIncrementalStep_rejectsNullPreviousState_neverSeedsFromDefault() {
        StockDailyPrice current = generateSeries(1, LocalDate.of(2026, 1, 1)).get(0);
        HighLow highLow = new HighLow();
        highLow.setHighPrice(new BigDecimal("100"));
        highLow.setLowPrice(new BigDecimal("90"));

        try {
            service.computeIncrementalStep(STOCK_ID, PARAM_KEY, null, current, highLow);
            org.junit.jupiter.api.Assertions.fail("expected IllegalStateException when previous state is null");
        } catch (IllegalStateException expected) {
            // pass - caller (IndicatorRebuildRunner) is responsible for routing to a full rebuild instead
        }
    }

    @Test
    void warmupFlag_marksRowsBeforeOutputStartDateOnly() {
        List<StockDailyPrice> rows = generateSeries(10, LocalDate.of(2026, 1, 1));
        LocalDate outputStart = rows.get(5).getTradeDate();

        List<StockDailyIndicator> result = service.computeFull(STOCK_ID, PARAM_KEY, rows, outputStart);

        for (int i = 0; i < result.size(); i++) {
            StockDailyIndicator row = result.get(i);
            boolean expectedWarmup = row.getTradeDate().isBefore(outputStart);
            assertEquals(expectedWarmup, row.isWarmup(), "warmup flag mismatch on " + row.getTradeDate());
        }
        assertTrue(result.get(4).isWarmup());
        assertFalse(result.get(5).isWarmup());
    }

    @Test
    void warmupFlag_nullOutputStart_marksNothingAsWarmup() {
        List<StockDailyPrice> rows = generateSeries(5, LocalDate.of(2026, 1, 1));
        List<StockDailyIndicator> result = service.computeFull(STOCK_ID, PARAM_KEY, rows, null);
        for (StockDailyIndicator row : result) {
            assertFalse(row.isWarmup());
        }
    }

    /**
     * Spec: "取 250 个交易日（约一年）保留安全边际" — beyond ~200 rows the recursion has converged
     * to the point where extending the lookback further no longer meaningfully changes the output.
     * This reproduces that property synthetically (250 vs 400 days of lookback before the same
     * output window) rather than depending on a live market data fetch — see the spec's own
     * measured table (102/136/157/199/305-bar warmup on 2330) for the real-data version of this
     * same property, verified separately via the live app in this task's Execution Result.
     */
    @Test
    void warmupConvergence_250And400LookbackAgree_toWithin0_001OnOutputDays() {
        List<StockDailyPrice> allRows = generateSeries(450, LocalDate.of(2024, 1, 1));
        LocalDate outputStart = allRows.get(400).getTradeDate();

        List<StockDailyPrice> lookback250 = allRows.subList(400 - 250, allRows.size());
        List<StockDailyPrice> lookback400 = allRows.subList(0, allRows.size());

        List<StockDailyIndicator> result250 = service.computeFull(STOCK_ID, PARAM_KEY, lookback250, outputStart);
        List<StockDailyIndicator> result400 = service.computeFull(STOCK_ID, PARAM_KEY, lookback400, outputStart);

        List<StockDailyIndicator> output250 = onlyNonWarmup(result250);
        List<StockDailyIndicator> output400 = onlyNonWarmup(result400);

        assertEquals(50, output250.size());
        assertEquals(output250.size(), output400.size());

        for (int i = 0; i < output250.size(); i++) {
            StockDailyIndicator a = output250.get(i);
            StockDailyIndicator b = output400.get(i);
            assertEquals(a.getTradeDate(), b.getTradeDate());
            assertWithinTolerance("dif on " + a.getTradeDate(), a.getDif(), b.getDif());
            assertWithinTolerance("dea on " + a.getTradeDate(), a.getDea(), b.getDea());
            assertWithinTolerance("k on " + a.getTradeDate(), a.getKValue(), b.getKValue());
            assertWithinTolerance("d on " + a.getTradeDate(), a.getDValue(), b.getDValue());
        }
    }

    private void assertWithinTolerance(String label, BigDecimal a, BigDecimal b) {
        BigDecimal diff = a.subtract(b).abs();
        assertTrue(diff.compareTo(TOLERANCE) < 0, label + ": diff " + diff + " >= tolerance " + TOLERANCE
                + " (a=" + a + ", b=" + b + ")");
    }

    private List<StockDailyIndicator> onlyNonWarmup(List<StockDailyIndicator> rows) {
        List<StockDailyIndicator> result = new ArrayList<>();
        for (StockDailyIndicator row : rows) {
            if (!row.isWarmup()) {
                result.add(row);
            }
        }
        return result;
    }

    /** Deterministic pseudo-random daily OHLC walk; fixed seed so the test is reproducible. */
    private List<StockDailyPrice> generateSeries(int count, LocalDate start) {
        Random random = new Random(20260827L);
        List<StockDailyPrice> list = new ArrayList<>(count);
        BigDecimal price = new BigDecimal("100.00");
        LocalDate date = start;
        for (int i = 0; i < count; i++) {
            BigDecimal delta = BigDecimal.valueOf((random.nextDouble() - 0.48) * 4).setScale(2, RoundingMode.HALF_UP);
            BigDecimal open = price;
            BigDecimal close = open.add(delta);
            if (close.compareTo(new BigDecimal("1.00")) < 0) {
                close = new BigDecimal("1.00");
            }
            BigDecimal spread1 = BigDecimal.valueOf(random.nextDouble() * 2).setScale(2, RoundingMode.HALF_UP);
            BigDecimal spread2 = BigDecimal.valueOf(random.nextDouble() * 2).setScale(2, RoundingMode.HALF_UP);
            BigDecimal high = open.max(close).add(spread1);
            BigDecimal low = open.min(close).subtract(spread2);
            if (low.compareTo(new BigDecimal("0.50")) < 0) {
                low = new BigDecimal("0.50");
            }

            StockDailyPrice row = new StockDailyPrice();
            row.setStockId(STOCK_ID);
            row.setTradeDate(date);
            row.setOpenPrice(open.setScale(2, RoundingMode.HALF_UP));
            row.setHighPrice(high.setScale(2, RoundingMode.HALF_UP));
            row.setLowPrice(low.setScale(2, RoundingMode.HALF_UP));
            row.setClosePrice(close.setScale(2, RoundingMode.HALF_UP));
            row.setVolume(1000 + i);
            list.add(row);

            price = close;
            date = date.plusDays(1);
        }
        return list;
    }
}
