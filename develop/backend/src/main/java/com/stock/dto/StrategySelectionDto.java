package com.stock.dto;

import java.math.BigDecimal;

/**
 * One entry of the POST /api/strategies/scan request's `strategies` array. `risePercent` is an
 * optional per-strategy override of that strategy's rise/drop threshold — null means "use the
 * preset's own value". Which underlying field it overrides differs by strategy code; see
 * specs/backend/strategy-scan.md, "型態定義" for the per-strategy mapping table (e.g. it overrides
 * BOX_BREAKOUT's breakoutPercent and REBOUND's dropPercent, not a field literally named
 * risePercent in either case). `days` is accepted only by CUMULATIVE_RISE (the one strategy
 * without presets) — declared as BigDecimal, not Integer, so a non-integer value like `20.5`
 * deserializes successfully and can be rejected with the wire contract's own INVALID_DAYS error
 * instead of a generic JSON-parsing 400 (mirrors how `risePercent`'s BigDecimal type lets its own
 * decimal-scale validation run instead of relying on deserialization to reject bad input).
 * `requireRise`/`dropDays`/`dropPercent`/`riseDays` are accepted only by REBOUND — see
 * specs/backend/strategy-scan.md, "反彈改為跌段＋選用漲段的雙段參數、移除靈敏度". `risePercent` doubles as REBOUND's
 * rebound-threshold field (only when `requireRise` is not `false`).
 */
public class StrategySelectionDto {

    private String code;
    private String preset;
    private BigDecimal risePercent;
    private BigDecimal days;
    private Boolean requireRise;
    private BigDecimal dropDays;
    private BigDecimal dropPercent;
    private BigDecimal riseDays;

    public StrategySelectionDto() {
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getPreset() {
        return preset;
    }

    public void setPreset(String preset) {
        this.preset = preset;
    }

    public BigDecimal getRisePercent() {
        return risePercent;
    }

    public void setRisePercent(BigDecimal risePercent) {
        this.risePercent = risePercent;
    }

    public BigDecimal getDays() {
        return days;
    }

    public void setDays(BigDecimal days) {
        this.days = days;
    }

    public Boolean getRequireRise() {
        return requireRise;
    }

    public void setRequireRise(Boolean requireRise) {
        this.requireRise = requireRise;
    }

    public BigDecimal getDropDays() {
        return dropDays;
    }

    public void setDropDays(BigDecimal dropDays) {
        this.dropDays = dropDays;
    }

    public BigDecimal getDropPercent() {
        return dropPercent;
    }

    public void setDropPercent(BigDecimal dropPercent) {
        this.dropPercent = dropPercent;
    }

    public BigDecimal getRiseDays() {
        return riseDays;
    }

    public void setRiseDays(BigDecimal riseDays) {
        this.riseDays = riseDays;
    }
}
