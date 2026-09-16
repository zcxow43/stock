package com.stock.service.pattern;

import com.stock.domain.StockDailyPrice;
import com.stock.domain.StockInstitutionalTrade;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared per-stock, per-trading-day window computation used by all three institutional patterns —
 * see specs/backend/strategy-scan.md, "法人籌碼型態" 共通規則. Package-private: this is an
 * implementation detail of the three {@link InstitutionalPatternDetector}s, not a public seam.
 */
final class InstitutionalWindowMath {

    private InstitutionalWindowMath() {
    }

    /**
     * One trading day D's window result for a single stock: the window's summed volume and net
     * shares (foreign/trust), plus whether every day in the window individually had a positive net
     * (needed by INSTITUTIONAL_CONSECUTIVE_BUY only — computed for every window regardless, since the
     * per-day scan is already being done and the extra bookkeeping is free). {@code barIndex} is
     * this D's index within the stock's own {@code bars} list, so callers can look up D's next
     * trading day (the entry day, `buyDate`) by simple adjacency.
     */
    static final class DayWindow {
        final LocalDate date;
        final LocalDate windowStartDate;
        final int barIndex;
        final long volumeShares;
        final long foreignNetShares;
        final long trustNetShares;
        final boolean foreignAllPositive;
        final boolean trustAllPositive;

        DayWindow(LocalDate date, LocalDate windowStartDate, int barIndex, long volumeShares,
                  long foreignNetShares, long trustNetShares, boolean foreignAllPositive,
                  boolean trustAllPositive) {
            this.date = date;
            this.windowStartDate = windowStartDate;
            this.barIndex = barIndex;
            this.volumeShares = volumeShares;
            this.foreignNetShares = foreignNetShares;
            this.trustNetShares = trustNetShares;
            this.foreignAllPositive = foreignAllPositive;
            this.trustAllPositive = trustAllPositive;
        }
    }

    /**
     * Every trading day D within [startDate, endDate] that CAN be judged for this stock: enough
     * lookback bars exist for a full {@code windowDays}-bar window ending at D, AND every trading day
     * in that window has institutional data already fetched (see spec: "窗口內有尚未抓取法人資料的日期 →
     * 該檔在該 D 不判定"). Days that cannot be judged are simply absent — an empty result for the whole
     * range is this stock's INSTITUTIONAL_* insufficientData signal (see spec: "區間內沒有任何一個 D 能判定
     * 的股票...→ 列入 insufficientData").
     */
    static List<DayWindow> computeEvaluableWindows(List<StockDailyPrice> bars,
                                                    List<StockInstitutionalTrade> institutionalRows,
                                                    Set<LocalDate> fetchedInstitutionalDates,
                                                    LocalDate startDate, LocalDate endDate, int windowDays) {
        Map<LocalDate, StockInstitutionalTrade> byDate = new HashMap<>();
        for (StockInstitutionalTrade row : institutionalRows) {
            byDate.put(row.getTradeDate(), row);
        }

        int preCount = 0;
        while (preCount < bars.size() && bars.get(preCount).getTradeDate().isBefore(startDate)) {
            preCount++;
        }

        List<DayWindow> result = new ArrayList<>();
        for (int i = preCount; i < bars.size(); i++) {
            LocalDate d = bars.get(i).getTradeDate();
            if (d.isAfter(endDate)) {
                break;
            }
            int windowStart = i - (windowDays - 1);
            if (windowStart < 0) {
                continue;
            }

            boolean allFetched = true;
            long volumeSum = 0;
            long foreignNet = 0;
            long trustNet = 0;
            boolean foreignAllPositive = true;
            boolean trustAllPositive = true;
            for (int j = windowStart; j <= i; j++) {
                LocalDate dt = bars.get(j).getTradeDate();
                if (!fetchedInstitutionalDates.contains(dt)) {
                    allFetched = false;
                    break;
                }
                volumeSum += bars.get(j).getVolume();
                StockInstitutionalTrade row = byDate.get(dt);
                long dayForeign = row != null ? row.getForeignNetShares() : 0L;
                long dayTrust = row != null ? row.getTrustNetShares() : 0L;
                foreignNet += dayForeign;
                trustNet += dayTrust;
                if (dayForeign <= 0) {
                    foreignAllPositive = false;
                }
                if (dayTrust <= 0) {
                    trustAllPositive = false;
                }
            }
            if (!allFetched) {
                continue;
            }
            result.add(new DayWindow(d, bars.get(windowStart).getTradeDate(), i, volumeSum, foreignNet, trustNet,
                    foreignAllPositive, trustAllPositive));
        }
        return result;
    }
}
