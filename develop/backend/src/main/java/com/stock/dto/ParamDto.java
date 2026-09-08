package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * One directly-typable parameter of a no-preset strategy (currently CUMULATIVE_RISE's `days`/
 * `risePercent` and REBOUND's `dropDays`/`dropPercent`/`riseDays`/`risePercent`) within the
 * GET /api/strategies catalogue — see specs/backend/strategy-scan.md, "presets 與 params 的關係". The
 * wire field is literally named "default", a reserved word in Java, hence the {@code @JsonProperty}
 * on {@link #getDefaultValue()}. `group`, when present, names the {@link ParamGroupDto#getCode()}
 * this param belongs to (an optional, whole-group-togglable set of params) — see "選用參數群組";
 * omitted (not just null) for the ungrouped, always-effective params.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ParamDto {

    private String code;
    private String name;
    private String unit;
    private BigDecimal defaultValue;
    private BigDecimal min;
    private BigDecimal max;
    private BigDecimal step;
    private String group;

    public ParamDto() {
    }

    public ParamDto(String code, String name, String unit, BigDecimal defaultValue, BigDecimal min, BigDecimal max,
                     BigDecimal step) {
        this(code, name, unit, defaultValue, min, max, step, null);
    }

    public ParamDto(String code, String name, String unit, BigDecimal defaultValue, BigDecimal min, BigDecimal max,
                     BigDecimal step, String group) {
        this.code = code;
        this.name = name;
        this.unit = unit;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.step = step;
        this.group = group;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    @JsonProperty("default")
    public BigDecimal getDefaultValue() {
        return defaultValue;
    }

    @JsonProperty("default")
    public void setDefaultValue(BigDecimal defaultValue) {
        this.defaultValue = defaultValue;
    }

    public BigDecimal getMin() {
        return min;
    }

    public void setMin(BigDecimal min) {
        this.min = min;
    }

    public BigDecimal getMax() {
        return max;
    }

    public void setMax(BigDecimal max) {
        this.max = max;
    }

    public BigDecimal getStep() {
        return step;
    }

    public void setStep(BigDecimal step) {
        this.step = step;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }
}
