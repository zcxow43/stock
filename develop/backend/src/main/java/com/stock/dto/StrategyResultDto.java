package com.stock.dto;

import java.util.List;

/** One strategy's outcome within the scan response — hits (型態偵測結果), never buy/sell advice. */
public class StrategyResultDto {

    private String strategy;
    private String preset;
    private int matchedCount;
    private List<StrategyHitDto> items;
    private List<String> insufficientData;
    private List<String> pendingConfirm;

    public StrategyResultDto() {
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getPreset() {
        return preset;
    }

    public void setPreset(String preset) {
        this.preset = preset;
    }

    public int getMatchedCount() {
        return matchedCount;
    }

    public void setMatchedCount(int matchedCount) {
        this.matchedCount = matchedCount;
    }

    public List<StrategyHitDto> getItems() {
        return items;
    }

    public void setItems(List<StrategyHitDto> items) {
        this.items = items;
    }

    public List<String> getInsufficientData() {
        return insufficientData;
    }

    public void setInsufficientData(List<String> insufficientData) {
        this.insufficientData = insufficientData;
    }

    public List<String> getPendingConfirm() {
        return pendingConfirm;
    }

    public void setPendingConfirm(List<String> pendingConfirm) {
        this.pendingConfirm = pendingConfirm;
    }
}
