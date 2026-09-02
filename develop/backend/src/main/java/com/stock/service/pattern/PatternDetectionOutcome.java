package com.stock.service.pattern;

import java.time.LocalDate;

/**
 * Result of running one {@link PatternDetector} over one stock's price series. Exactly one of
 * {@link #isInsufficientData()}, {@link #isPendingConfirm()}, or a real hit (non-null
 * {@link #getSignalDate()}) applies for BOX_BREAKOUT and RISING_SUPPORT; HIGHER_LOWS never produces
 * pendingConfirm. insufficientData is evaluated before any per-day scan is attempted and is
 * mutually exclusive with a hit — see specs/backend/strategy-scan.md, "insufficientData 與 matchedCount 互斥".
 */
public final class PatternDetectionOutcome {

    private final boolean insufficientData;
    private final boolean pendingConfirm;
    private final LocalDate signalDate;
    private final Object detail;

    private PatternDetectionOutcome(boolean insufficientData, boolean pendingConfirm, LocalDate signalDate,
                                     Object detail) {
        this.insufficientData = insufficientData;
        this.pendingConfirm = pendingConfirm;
        this.signalDate = signalDate;
        this.detail = detail;
    }

    public static PatternDetectionOutcome insufficientData() {
        return new PatternDetectionOutcome(true, false, null, null);
    }

    public static PatternDetectionOutcome noMatch(boolean pendingConfirm) {
        return new PatternDetectionOutcome(false, pendingConfirm, null, null);
    }

    public static PatternDetectionOutcome hit(LocalDate signalDate, Object detail) {
        return new PatternDetectionOutcome(false, false, signalDate, detail);
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

    public Object getDetail() {
        return detail;
    }
}
