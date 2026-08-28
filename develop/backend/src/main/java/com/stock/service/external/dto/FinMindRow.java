package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raw row shape returned by FinMind TaiwanStockPrice dataset, before normalization.
 * Numeric fields are declared as String; Jackson coerces both quoted and unquoted
 * JSON numbers into a String, so this is robust regardless of whether FinMind
 * returns plain numbers or thousands-separated strings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FinMindRow {

    @JsonProperty("date")
    private String date;

    @JsonProperty("stock_id")
    private String stockId;

    @JsonProperty("Trading_Volume")
    private String tradingVolume;

    @JsonProperty("Trading_money")
    private String tradingMoney;

    @JsonProperty("open")
    private String open;

    @JsonProperty("max")
    private String max;

    @JsonProperty("min")
    private String min;

    @JsonProperty("close")
    private String close;

    @JsonProperty("Trading_turnover")
    private String tradingTurnover;

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public String getTradingVolume() {
        return tradingVolume;
    }

    public void setTradingVolume(String tradingVolume) {
        this.tradingVolume = tradingVolume;
    }

    public String getTradingMoney() {
        return tradingMoney;
    }

    public void setTradingMoney(String tradingMoney) {
        this.tradingMoney = tradingMoney;
    }

    public String getOpen() {
        return open;
    }

    public void setOpen(String open) {
        this.open = open;
    }

    public String getMax() {
        return max;
    }

    public void setMax(String max) {
        this.max = max;
    }

    public String getMin() {
        return min;
    }

    public void setMin(String min) {
        this.min = min;
    }

    public String getClose() {
        return close;
    }

    public void setClose(String close) {
        this.close = close;
    }

    public String getTradingTurnover() {
        return tradingTurnover;
    }

    public void setTradingTurnover(String tradingTurnover) {
        this.tradingTurnover = tradingTurnover;
    }
}
