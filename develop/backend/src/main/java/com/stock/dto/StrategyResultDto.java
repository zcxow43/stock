package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One strategy's outcome within the scan response — hits (型態偵測結果), never buy/sell advice.
 * `preset` and `days` are mutually exclusive: preset-driven strategies echo `preset` and omit
 * `days`; CUMULATIVE_RISE echoes `days` (the value actually used) and omits `preset` — see
 * specs/backend/strategy-scan.md, "results 依 strategies 送入的順序回傳". REBOUND echoes none of
 * `preset`/`days` but instead `requireRise`/`dropDays`/`dropPercent` (and, when `requireRise` is
 * true, `riseDays`/`risePercent`) — see "反彈改為跌段＋選用漲段的雙段參數、移除靈敏度".
 *
 * <p>The three institutional-trade patterns echo `investors` plus their own subset of
 * `windowDays`/`ratioPercent`/`buyDays`/`topN`, and additionally `dataThroughDate` — the latest
 * trading day `stock_institutional_trade` actually covers within the pre-loaded period, or
 * {@code null} when it covers none at all — see specs/backend/strategy-scan.md, "法人籌碼型態".
 *
 * <p>MACD_GOLDEN_CROSS echoes `fastPeriod`/`slowPeriod`/`signalPeriod` (the latter always `9`);
 * KDJ_GOLDEN_CROSS echoes `jThreshold` — neither echoes `preset` — see specs/backend/
 * strategy-scan.md, "技術指標型態".
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
    private List<String> investors;
    private Integer windowDays;
    private BigDecimal ratioPercent;
    private Integer buyDays;
    private Integer topN;
    private Integer fastPeriod;
    private Integer slowPeriod;
    private Integer signalPeriod;
    private BigDecimal jThreshold;
    private LocalDate dataThroughDate;
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

    public List<String> getInvestors() {
        return investors;
    }

    public void setInvestors(List<String> investors) {
        this.investors = investors;
    }

    public Integer getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(Integer windowDays) {
        this.windowDays = windowDays;
    }

    public BigDecimal getRatioPercent() {
        return ratioPercent;
    }

    public void setRatioPercent(BigDecimal ratioPercent) {
        this.ratioPercent = ratioPercent;
    }

    public Integer getBuyDays() {
        return buyDays;
    }

    public void setBuyDays(Integer buyDays) {
        this.buyDays = buyDays;
    }

    public Integer getTopN() {
        return topN;
    }

    public void setTopN(Integer topN) {
        this.topN = topN;
    }

    public Integer getFastPeriod() {
        return fastPeriod;
    }

    public void setFastPeriod(Integer fastPeriod) {
        this.fastPeriod = fastPeriod;
    }

    public Integer getSlowPeriod() {
        return slowPeriod;
    }

    public void setSlowPeriod(Integer slowPeriod) {
        this.slowPeriod = slowPeriod;
    }

    public Integer getSignalPeriod() {
        return signalPeriod;
    }

    public void setSignalPeriod(Integer signalPeriod) {
        this.signalPeriod = signalPeriod;
    }

    // See StrategySelectionDto#getJThreshold for why this name must be pinned explicitly: Jackson's
    // default getter/setter mangling would otherwise serialize this as "jthreshold", not the spec's
    // `jThreshold`.
    @JsonProperty("jThreshold")
    public BigDecimal getJThreshold() {
        return jThreshold;
    }

    @JsonProperty("jThreshold")
    public void setJThreshold(BigDecimal jThreshold) {
        this.jThreshold = jThreshold;
    }

    public LocalDate getDataThroughDate() {
        return dataThroughDate;
    }

    public void setDataThroughDate(LocalDate dataThroughDate) {
        this.dataThroughDate = dataThroughDate;
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
