package com.stock.dto;

import java.math.BigDecimal;

/**
 * One entry of the POST /api/strategies/scan request's `strategies` array. `risePercent` is an
 * optional per-strategy override of that strategy's rise/drop threshold — null means "use the
 * preset's own value". Which underlying field it overrides differs by strategy code; see
 * specs/backend/strategy-scan.md, "型態定義" for the per-strategy mapping table (e.g. it overrides
 * BOX_BREAKOUT's breakoutPercent and REBOUND's dropPercent, not a field literally named
 * risePercent in either case).
 */
public class StrategySelectionDto {

    private String code;
    private String preset;
    private BigDecimal risePercent;

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
}
