package com.stock.dto;

import java.util.List;

/** One pattern (BOX_BREAKOUT / HIGHER_LOWS) in the GET /api/strategies catalogue. */
public class StrategyDto {

    private String code;
    private String name;
    private List<PresetDto> presets;

    public StrategyDto() {
    }

    public StrategyDto(String code, String name, List<PresetDto> presets) {
        this.code = code;
        this.name = name;
        this.presets = presets;
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

    public List<PresetDto> getPresets() {
        return presets;
    }

    public void setPresets(List<PresetDto> presets) {
        this.presets = presets;
    }
}
