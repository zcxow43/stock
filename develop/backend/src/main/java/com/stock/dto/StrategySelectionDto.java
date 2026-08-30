package com.stock.dto;

/** One entry of the POST /api/strategies/scan request's `strategies` array. */
public class StrategySelectionDto {

    private String code;
    private String preset;

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
}
