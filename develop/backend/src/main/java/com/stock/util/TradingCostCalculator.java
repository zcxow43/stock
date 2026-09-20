package com.stock.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one trading-cost model shared by every endpoint that estimates a Taiwan-market buy/sell:
 * fee 0.1425% on both buy and sell, tax 0.3% on sell only, each of the three line items floored to
 * whole yuan independently, all in exact decimal arithmetic (never {@code double}).
 *
 * <p>Extracted out of {@code StrategyBacktestService} (specs/backend/strategy-backtest.md, 「交易成
 * 本」) so that {@code SimulatedTradeService} (specs/backend/simulated-trade.md) reuses the exact
 * same constants and rounding instead of maintaining a second copy that could quietly drift —
 * simulated-trade's own spec says explicitly: 「交易成本與 specs/backend/strategy-backtest.md 的『交易
 * 成本』完全同一套，不另立一份定義」. The only thing that differs between the two callers is which price
 * stands in for the sell side (highest open in a window vs. today's close) — that choice stays in
 * each caller, not here.
 */
public final class TradingCostCalculator {

    /** 1 張 = 1000 股, the position size both callers use for every line/lot. */
    public static final int LOT_SIZE = 1000;

    /**
     * Fee/tax rates as literal decimal percentages, declared with {@code BigDecimal(String)} (never
     * {@code double}): {@code 200.00 × 1000 × 0.1425%} must land exactly on {@code 285}, and a
     * binary-float route there can silently produce {@code 284.999...} that then floors to the
     * wrong yuan.
     */
    public static final BigDecimal FEE_RATE_PERCENT = new BigDecimal("0.1425");
    public static final BigDecimal TAX_RATE_PERCENT = new BigDecimal("0.3");

    private static final BigDecimal FEE_RATE = FEE_RATE_PERCENT.divide(BigDecimal.valueOf(100));
    private static final BigDecimal TAX_RATE = TAX_RATE_PERCENT.divide(BigDecimal.valueOf(100));

    private static final int YUAN_SCALE = 0;
    private static final int PERCENT_SCALE = 2;
    private static final int DIVISION_SCALE = 10;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private TradingCostCalculator() {
    }

    /** 買進或賣出手續費 = price × shares × 0.1425%，無條件捨去至元. */
    public static BigDecimal fee(BigDecimal price, int shares) {
        return feeOrTax(price, shares, FEE_RATE);
    }

    /** 證交稅（只在賣出時收）= price × shares × 0.3%，無條件捨去至元. */
    public static BigDecimal tax(BigDecimal price, int shares) {
        return feeOrTax(price, shares, TAX_RATE);
    }

    /**
     * A single trading-cost line item (buy fee, sell fee, or sell tax), floored to whole yuan on
     * its own — each of the three costs is floored independently, never summed first and floored
     * once. No broker discount and no minimum fee are applied.
     */
    private static BigDecimal feeOrTax(BigDecimal price, int shares, BigDecimal rate) {
        return price.multiply(BigDecimal.valueOf(shares)).multiply(rate).setScale(YUAN_SCALE, RoundingMode.FLOOR);
    }

    /** 成本 = 買進價 × shares ＋ 買進手續費（元）— the denominator of returnPercent. */
    public static BigDecimal cost(BigDecimal buyPrice, int shares, BigDecimal buyFee) {
        return buyPrice.multiply(BigDecimal.valueOf(shares)).add(buyFee).setScale(YUAN_SCALE, RoundingMode.HALF_UP);
    }

    /** 收益／未實現損益 = 賣出價 × shares − 賣出手續費 − 證交稅 − 成本（元，可為負值）。 */
    public static BigDecimal profit(BigDecimal sellPrice, int shares, BigDecimal sellFee, BigDecimal sellTax,
                                     BigDecimal cost) {
        return sellPrice.multiply(BigDecimal.valueOf(shares))
                .subtract(sellFee)
                .subtract(sellTax)
                .subtract(cost)
                .setScale(YUAN_SCALE, RoundingMode.HALF_UP);
    }

    /** 報酬率 = 收益 ÷ 成本 × 100，四捨五入至小數第二位（可為負值）。 */
    public static BigDecimal returnPercent(BigDecimal profit, BigDecimal cost) {
        return profit.divide(cost, DIVISION_SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }
}
