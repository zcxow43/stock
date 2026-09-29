package com.stock.service.pattern;

import java.time.LocalDate;

/**
 * Result of running one {@link PatternDetector} over one stock's price series. Exactly one of
 * {@link #isInsufficientData()}, {@link #isPendingConfirm()}, or a real hit (non-null
 * {@link #getSignalDate()}) applies — every one of the ten patterns can now produce all three, since
 * every pattern's `buyDate` requires a trading day beyond its own confirmation-completion day (see
 * specs/backend/strategy-scan.md, "進場日（buyDate）"). insufficientData is evaluated before any
 * per-day scan is attempted and is mutually exclusive with a hit — see "insufficientData 與
 * matchedCount 互斥".
 *
 * <p>Every hit also carries a {@link #getBuyDate()} — the entry trading day, always strictly after
 * {@link #getSignalDate()} (equal to it for none of the ten patterns; see the per-pattern
 * confirmation-completion-day table in "進場日（buyDate）"). {@link #hit(LocalDate, LocalDate, Object)}
 * is the only hit factory: a detector that finds a matching day but cannot yet resolve `buyDate`
 * (no next trading day exists) must report {@link #noMatch(boolean)} with {@code pendingConfirm=true}
 * for that day instead, keeping any earlier, already-resolved hit for the same stock as the one
 * reported — see "同一檔若有較早、已有 buyDate 的命中".
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

    /**
     * A resolved hit: {@code buyDate} must already be known (the next trading day after this
     * pattern's confirmation-completion day) — see specs/backend/strategy-scan.md, "進場日（buyDate）".
     */
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
