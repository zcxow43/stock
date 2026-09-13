package com.stock.service.pattern;

import java.time.LocalDate;

/**
 * Result of running one {@link PatternDetector} over one stock's price series. Exactly one of
 * {@link #isInsufficientData()}, {@link #isPendingConfirm()}, or a real hit (non-null
 * {@link #getSignalDate()}) applies for BOX_BREAKOUT and RISING_SUPPORT; HIGHER_LOWS never produces
 * pendingConfirm. insufficientData is evaluated before any per-day scan is attempted and is
 * mutually exclusive with a hit — see specs/backend/strategy-scan.md, "insufficientData 與 matchedCount 互斥".
 *
 * <p>Every hit also carries a {@link #getBuyDate()} — the entry trading day, which equals
 * {@link #getSignalDate()} for every pattern except RISING_SUPPORT, where it is the confirmation
 * day D+2 (see specs/backend/strategy-scan.md, "上漲支撐的訊號日與進場日刻意不同"). {@link #hit(LocalDate, Object)}
 * covers the common case where the two dates coincide; RISING_SUPPORT uses
 * {@link #hit(LocalDate, LocalDate, Object)} to report them separately.
 */
public final class PatternDetectionOutcome {

    private final boolean insufficientData;
    private final boolean pendingConfirm;
    private final LocalDate signalDate;
    private final LocalDate buyDate;
    private final Object detail;

    private PatternDetectionOutcome(boolean insufficientData, boolean pendingConfirm, LocalDate signalDate,
                                     LocalDate buyDate, Object detail) {
        this.insufficientData = insufficientData;
        this.pendingConfirm = pendingConfirm;
        this.signalDate = signalDate;
        this.buyDate = buyDate;
        this.detail = detail;
    }

    public static PatternDetectionOutcome insufficientData() {
        return new PatternDetectionOutcome(true, false, null, null, null);
    }

    public static PatternDetectionOutcome noMatch(boolean pendingConfirm) {
        return new PatternDetectionOutcome(false, pendingConfirm, null, null, null);
    }

    /** Convenience for the four patterns whose buyDate equals signalDate. */
    public static PatternDetectionOutcome hit(LocalDate signalDate, Object detail) {
        return new PatternDetectionOutcome(false, false, signalDate, signalDate, detail);
    }

    /** RISING_SUPPORT only: signalDate is D, buyDate is D+2 — deliberately different dates. */
    public static PatternDetectionOutcome hit(LocalDate signalDate, LocalDate buyDate, Object detail) {
        return new PatternDetectionOutcome(false, false, signalDate, buyDate, detail);
    }

    public boolean isInsufficientData() {
        return insufficientData;
    }

    public boolean isPendingConfirm() {
        return pendingConfirm;
    }

    public boolean isHit() {
        return signalDate != null;
    }

    public LocalDate getSignalDate() {
        return signalDate;
    }

    public LocalDate getBuyDate() {
        return buyDate;
    }

    public Object getDetail() {
        return detail;
    }
}
