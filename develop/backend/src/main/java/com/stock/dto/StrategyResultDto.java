package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * One strategy's outcome within the scan response — hits (型態偵測結果), never buy/sell advice.
 * `preset` and `days` are mutually exclusive: preset-driven strategies echo `preset` and omit
 * `days`; CUMULATIVE_RISE echoes `days` (the value actually used) and omits `preset` — see
 * specs/backend/strategy-scan.md, "results 依 strategies 送入的順序回傳". REBOUND echoes none of
 * `preset`/`days` but instead `requireRise`/`dropDays`/`dropPercent` (and, when `requireRise` is
 * true, `riseDays`/`risePercent`) — see "反彈改為跌段＋選用漲段的雙段參數、移除靈敏度".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StrategyResultDto {

    private String strategy;
    private String preset;
    private Integer days;
    private Boolean requireRise;
    private Integer dropDays;
    private BigDecimal dropPercent;
    private Integer riseDays;
    private BigDecimal risePercent;
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

    public Integer getDays() {
        return days;
    }

    public void setDays(Integer days) {
        this.days = days;
    }

    public Boolean getRequireRise() {
        return requireRise;
    }

    public void setRequireRise(Boolean requireRise) {
        this.requireRise = requireRise;
    }

    public Integer getDropDays() {
        return dropDays;
    }

    public void setDropDays(Integer dropDays) {
        this.dropDays = dropDays;
    }

    public BigDecimal getDropPercent() {
        return dropPercent;
    }

    public void setDropPercent(BigDecimal dropPercent) {
        this.dropPercent = dropPercent;
    }

    public Integer getRiseDays() {
        return riseDays;
    }

    public void setRiseDays(Integer riseDays) {
        this.riseDays = riseDays;
    }

    public BigDecimal getRisePercent() {
        return risePercent;
    }

    public void setRisePercent(BigDecimal risePercent) {
        this.risePercent = risePercent;
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
