package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raw row shape returned by TWSE openapi STOCK_DAY_ALL, before normalization.
 * All fields arrive as strings, including numeric ones (with possible thousands
 * separators) and the trade date (ROC calendar, compact "1150827" form).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TwseDailyRow {

    @JsonProperty("Date")
    private String date;

    @JsonProperty("Code")
    private String code;

    @JsonProperty("Name")
    private String name;

    @JsonProperty("TradeVolume")
    private String tradeVolume;

    @JsonProperty("TradeValue")
    private String tradeValue;

    @JsonProperty("OpeningPrice")
    private String openingPrice;

    @JsonProperty("HighestPrice")
    private String highestPrice;

    @JsonProperty("LowestPrice")
    private String lowestPrice;

    @JsonProperty("ClosingPrice")
    private String closingPrice;

    @JsonProperty("Transaction")
    private String transaction;

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
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

    public String getTradeVolume() {
        return tradeVolume;
    }

    public void setTradeVolume(String tradeVolume) {
        this.tradeVolume = tradeVolume;
    }

    public String getTradeValue() {
        return tradeValue;
    }

    public void setTradeValue(String tradeValue) {
        this.tradeValue = tradeValue;
    }

    public String getOpeningPrice() {
        return openingPrice;
    }

    public void setOpeningPrice(String openingPrice) {
        this.openingPrice = openingPrice;
    }

    public String getHighestPrice() {
        return highestPrice;
    }

    public void setHighestPrice(String highestPrice) {
        this.highestPrice = highestPrice;
    }

    public String getLowestPrice() {
        return lowestPrice;
    }

    public void setLowestPrice(String lowestPrice) {
        this.lowestPrice = lowestPrice;
    }

    public String getClosingPrice() {
        return closingPrice;
    }

    public void setClosingPrice(String closingPrice) {
        this.closingPrice = closingPrice;
    }

    public String getTransaction() {
        return transaction;
    }

    public void setTransaction(String transaction) {
        this.transaction = transaction;
    }
}
