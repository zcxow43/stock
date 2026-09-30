package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The one moving-average definition in the system — simple average of the CLOSE over n trading
 * days, the bar itself included — see specs/backend/strategy-scan.md, "底底高" (MA5) and "站上均線"
 * ("全系統只有這一種均線"). Shared by HIGHER_LOWS and MA_BREAKOUT so "5 日均線" never means two
 * different lines. Computed on demand from the already-loaded daily bars; nothing is persisted.
 */
final class MovingAverage {

    static final int CALC_SCALE = 10;

    private MovingAverage() {
    }

    /** Whether {@code bars} holds n closes ending at (and including) {@code index}. */
    static boolean isDefined(int index, int n) {
        return index >= n - 1;
    }

    /**
     * MA(n) at {@code index}: the arithmetic mean of the n closes ending at (and including) that
     * index. Callers must check {@link #isDefined} first.
     */
    static BigDecimal simple(List<StockDailyPrice> bars, int index, int n) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int k = index - (n - 1); k <= index; k++) {
            sum = sum.add(bars.get(k).getClosePrice());
        }
        return sum.divide(BigDecimal.valueOf(n), CALC_SCALE, RoundingMode.HALF_UP);
    }
}
