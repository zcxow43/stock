package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One pattern (BOX_BREAKOUT / HIGHER_LOWS / RISING_SUPPORT / REBOUND / CUMULATIVE_RISE) in the
 * GET /api/strategies catalogue. `presets` is a three-entry list for the three sensitivity-preset
 * strategies and an empty list for CUMULATIVE_RISE/REBOUND; `description`/`params` are the mirror
 * image — null/omitted for the preset strategies, populated for CUMULATIVE_RISE/REBOUND — see
 * specs/backend/strategy-scan.md, "presets 與 params 的關係". `paramGroups` names optional,
 * whole-group-togglable subsets of `params` (currently only REBOUND's "rise" group) — see "選用參數群組";
 * null/omitted for every other strategy.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StrategyDto {

    private String code;
    private String name;
    private String description;
    private List<PresetDto> presets;
    private List<ParamGroupDto> paramGroups;
    private List<ParamDto> params;

    public StrategyDto() {
    }

    public StrategyDto(String code, String name, List<PresetDto> presets) {
        this(code, name, null, presets, null, null);
    }

    public StrategyDto(String code, String name, String description, List<PresetDto> presets,
                        List<ParamGroupDto> paramGroups, List<ParamDto> params) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.presets = presets;
        this.paramGroups = paramGroups;
        this.params = params;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<PresetDto> getPresets() {
        return presets;
    }

    public void setPresets(List<PresetDto> presets) {
        this.presets = presets;
    }

    public List<ParamGroupDto> getParamGroups() {
        return paramGroups;
    }

    public void setParamGroups(List<ParamGroupDto> paramGroups) {
        this.paramGroups = paramGroups;
    }

    public List<ParamDto> getParams() {
        return params;
    }

    public void setParams(List<ParamDto> params) {
        this.params = params;
    }
}
