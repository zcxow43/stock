package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

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
 *
 * <p>`investors`/`windowDays`/`ratioPercent`/`buyDays`/`topN` are accepted only by the three
 * institutional-trade patterns (INSTITUTIONAL_NET_RATIO/INSTITUTIONAL_CONSECUTIVE_BUY/
 * INSTITUTIONAL_STRENGTH_RANK) — see specs/backend/strategy-scan.md, "法人籌碼型態". `windowDays`/
 * `ratioPercent`/`buyDays`/`topN` are declared as BigDecimal for the same reason `days` is: a
 * non-integer/out-of-scale value deserializes successfully so it can be rejected by this field's
 * own dedicated error code instead of a generic JSON-parsing 400.
 *
 * <p>`fastPeriod`/`slowPeriod` are accepted only by MACD_GOLDEN_CROSS; `jThreshold` only by
 * KDJ_GOLDEN_CROSS — see specs/backend/strategy-scan.md, "技術指標型態". All three are declared as
 * BigDecimal for the same reason as the fields above.
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
    private List<String> investors;
    private BigDecimal windowDays;
    private BigDecimal ratioPercent;
    private BigDecimal buyDays;
    private BigDecimal topN;
    private BigDecimal fastPeriod;
    private BigDecimal slowPeriod;
    private BigDecimal jThreshold;

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

    public List<String> getInvestors() {
        return investors;
    }

    public void setInvestors(List<String> investors) {
        this.investors = investors;
    }

    public BigDecimal getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(BigDecimal windowDays) {
        this.windowDays = windowDays;
    }

    public BigDecimal getRatioPercent() {
        return ratioPercent;
    }

    public void setRatioPercent(BigDecimal ratioPercent) {
        this.ratioPercent = ratioPercent;
    }

    public BigDecimal getBuyDays() {
        return buyDays;
    }

    public void setBuyDays(BigDecimal buyDays) {
        this.buyDays = buyDays;
    }

    public BigDecimal getTopN() {
        return topN;
    }

    public void setTopN(BigDecimal topN) {
        this.topN = topN;
    }

    public BigDecimal getFastPeriod() {
        return fastPeriod;
    }

    public void setFastPeriod(BigDecimal fastPeriod) {
        this.fastPeriod = fastPeriod;
    }

    public BigDecimal getSlowPeriod() {
        return slowPeriod;
    }

    public void setSlowPeriod(BigDecimal slowPeriod) {
        this.slowPeriod = slowPeriod;
    }

    // Jackson's default (legacy) getter/setter name mangling lower-cases a *whole* leading run of
    // consecutive capitals, so "getJThreshold"/"setJThreshold" would otherwise bind to the wire
    // property "jthreshold" (all lower-case), not the spec's `jThreshold` — see specs/backend/
    // strategy-scan.md, "回應回 jThreshold". Pin the wire name explicitly rather than rely on the
    // mangling algorithm for this one two-capital-letter name.
    @JsonProperty("jThreshold")
    public BigDecimal getJThreshold() {
        return jThreshold;
    }

    @JsonProperty("jThreshold")
    public void setJThreshold(BigDecimal jThreshold) {
        this.jThreshold = jThreshold;
    }
}
