package com.stock.service;

import com.stock.domain.HighLow;
import com.stock.domain.StockDailyIndicator;
import com.stock.domain.StockDailyPrice;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure MACD/KD computation — no DB access, no Spring context beyond being wired as a bean.
 * Formulas and parameters are the single contract in specs/dba/stock-daily-indicator.md
 * ("指標定義"), reproduced here:
 *
 * <pre>
 * DIF = EMA12(close) - EMA26(close)
 * DEA = EMA9(DIF)
 * OSC = DIF - DEA
 *
 * RSV = (close - min(low, 9d)) / (max(high, 9d) - min(low, 9d)) * 100   (50 when the window is flat)
 * K   = 2/3 * K(prev) + 1/3 * RSV
 * D   = 2/3 * D(prev) + 1/3 * K
 * J   = 3K - 2D
 * </pre>
 *
 * Every recursive state value (ema_fast, ema_slow, dea, k, d) is rounded to STATE_SCALE (8 decimal
 * places — matching the DECIMAL(18,8)/DECIMAL(12,8) storage columns) immediately after being
 * computed, before being fed into the next step. This is what makes an INCREMENTAL step produce
 * a bit-identical result to the same day computed as part of a FULL run: both round off at exactly
 * the same point (when the value would be persisted), so resuming from a stored (already-rounded)
 * previous row is equivalent to continuing an unbroken in-memory recursion.
 */
@Service
public class IndicatorCalculationService {

    private static final int STATE_SCALE = 8;
    private static final RoundingMode RM = RoundingMode.HALF_UP;
    private static final int KD_WINDOW = 9;

    private static final BigDecimal SEED_KD = new BigDecimal("50");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal THREE = new BigDecimal("3");

    /**
     * Computes the full recursive series for one stock over the given ascending-by-date rows.
     * The first row in {@code ascRows} is always treated as the synthetic start of the recursion
     * (EMA seeded at that day's close, K/D seeded at 50) — this is what the spec's warmup mechanism
     * relies on: callers fetch ~250 extra trading days before their real output start and pass the
     * whole window in here, then mark everything before {@code outputStartDateOrNull} as warmup.
     *
     * @param outputStartDateOrNull rows with tradeDate before this are flagged is_warmup=1;
     *                              null means "no artificial warmup boundary" (mark nothing as warmup —
     *                              used when the request has no explicit startDate, i.e. the whole
     *                              available history is the requested output).
     */
    public List<StockDailyIndicator> computeFull(String stockId, String paramKey,
                                                  List<StockDailyPrice> ascRows,
                                                  LocalDate outputStartDateOrNull) {
        List<StockDailyIndicator> results = new ArrayList<>(ascRows.size());

        BigDecimal emaFast = null;
        BigDecimal emaSlow = null;
        BigDecimal dea = null;
        BigDecimal k = null;
        BigDecimal d = null;

        for (int i = 0; i < ascRows.size(); i++) {
            StockDailyPrice row = ascRows.get(i);
            BigDecimal close = row.getClosePrice();

            BigDecimal dif;
            if (i == 0) {
                emaFast = round(close);
                emaSlow = round(close);
                dif = round(emaFast.subtract(emaSlow));
                dea = round(dif);
            } else {
                emaFast = ema(close, emaFast, 12);
                emaSlow = ema(close, emaSlow, 26);
                dif = round(emaFast.subtract(emaSlow));
                dea = ema(dif, dea, 9);
            }
            BigDecimal osc = round(dif.subtract(dea));

            BigDecimal rsv = rsvFromWindow(ascRows, i, close);
            BigDecimal prevK = (i == 0) ? SEED_KD : k;
            BigDecimal prevD = (i == 0) ? SEED_KD : d;
            k = kdStep(prevK, rsv);
            d = kdStep(prevD, k);
            BigDecimal j = round(k.multiply(THREE).subtract(d.multiply(TWO)));

            boolean warmup = outputStartDateOrNull != null && row.getTradeDate().isBefore(outputStartDateOrNull);

            results.add(buildRow(stockId, row.getTradeDate(), paramKey, emaFast, emaSlow, dif, dea, osc,
                    rsv, k, d, j, warmup));
        }
        return results;
    }

    /**
     * Advances the recursion exactly one trading day from a previously persisted (non-warmup) row.
     * Throws IllegalStateException if {@code previous} is null — callers must never seed a missing
     * previous state with a default value (spec: "不得以初始值 50 起算補上"); the caller is
     * responsible for detecting that condition before calling this method and routing the stock to
     * a full rebuild instead.
     */
    public StockDailyIndicator computeIncrementalStep(String stockId, String paramKey,
                                                        StockDailyIndicator previous,
                                                        StockDailyPrice current,
                                                        HighLow recentHighLow) {
        if (previous == null) {
            throw new IllegalStateException("Cannot advance incrementally without a previous indicator row");
        }
        BigDecimal close = current.getClosePrice();

        BigDecimal emaFast = ema(close, previous.getEmaFast(), 12);
        BigDecimal emaSlow = ema(close, previous.getEmaSlow(), 26);
        BigDecimal dif = round(emaFast.subtract(emaSlow));
        BigDecimal dea = ema(dif, previous.getDea(), 9);
        BigDecimal osc = round(dif.subtract(dea));

        BigDecimal rsv = rsv(close, recentHighLow.getHighPrice(), recentHighLow.getLowPrice());
        BigDecimal k = kdStep(previous.getKValue(), rsv);
        BigDecimal d = kdStep(previous.getDValue(), k);
        BigDecimal j = round(k.multiply(THREE).subtract(d.multiply(TWO)));

        return buildRow(stockId, current.getTradeDate(), paramKey, emaFast, emaSlow, dif, dea, osc,
                rsv, k, d, j, false);
    }

    private BigDecimal rsvFromWindow(List<StockDailyPrice> ascRows, int index, BigDecimal close) {
        int from = Math.max(0, index - (KD_WINDOW - 1));
        BigDecimal high = null;
        BigDecimal low = null;
        for (int i = from; i <= index; i++) {
            BigDecimal h = ascRows.get(i).getHighPrice();
            BigDecimal l = ascRows.get(i).getLowPrice();
            if (high == null || h.compareTo(high) > 0) {
                high = h;
            }
            if (low == null || l.compareTo(low) < 0) {
                low = l;
            }
        }
        return rsv(close, high, low);
    }

    private BigDecimal rsv(BigDecimal close, BigDecimal windowHigh, BigDecimal windowLow) {
        if (windowHigh.compareTo(windowLow) == 0) {
            return SEED_KD;
        }
        return round(close.subtract(windowLow)
                .divide(windowHigh.subtract(windowLow), STATE_SCALE + 4, RM)
                .multiply(HUNDRED));
    }

    /** EMA_t = (2*price + (period-1)*EMA_(t-1)) / (period+1) — the fraction form of the standard EMA recursion. */
    private BigDecimal ema(BigDecimal price, BigDecimal prevEma, int period) {
        BigDecimal numerator = price.multiply(TWO).add(prevEma.multiply(BigDecimal.valueOf(period - 1L)));
        return numerator.divide(BigDecimal.valueOf(period + 1L), STATE_SCALE, RM);
    }

    /** K_t = (2*K_(t-1) + RSV) / 3, and D_t = (2*D_(t-1) + K_t) / 3 share this same shape. */
    private BigDecimal kdStep(BigDecimal prev, BigDecimal input) {
        return prev.multiply(TWO).add(input).divide(THREE, STATE_SCALE, RM);
    }

    private BigDecimal round(BigDecimal value) {
        return value.setScale(STATE_SCALE, RM);
    }

    private StockDailyIndicator buildRow(String stockId, LocalDate tradeDate, String paramKey,
                                          BigDecimal emaFast, BigDecimal emaSlow, BigDecimal dif, BigDecimal dea,
                                          BigDecimal osc, BigDecimal rsv, BigDecimal k, BigDecimal d, BigDecimal j,
                                          boolean warmup) {
        StockDailyIndicator row = new StockDailyIndicator();
        row.setStockId(stockId);
        row.setTradeDate(tradeDate);
        row.setParamKey(paramKey);
        row.setEmaFast(emaFast);
        row.setEmaSlow(emaSlow);
        row.setDif(dif);
        row.setDea(dea);
        row.setOsc(osc);
        row.setRsv(rsv);
        row.setKValue(k);
        row.setDValue(d);
        row.setJValue(j);
        row.setWarmup(warmup);
        return row;
    }
}
