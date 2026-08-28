package com.stock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day's MACD/KD indicator row, including the recursive intermediate state
 * (ema_fast, ema_slow) needed to advance the sequence one day at a time.
 * See specs/dba/stock-daily-indicator.md for the field-by-field contract.
 */
public class StockDailyIndicator {

    /** The only param_key used in this phase: KD (9,3,3, RMA) + MACD (12,26,9). */
    public static final String PARAM_KEY = "MACD_12_26_9__KD_9_3_3";

    /** Trading days of lookback required before the output start date for the recursion to converge. */
    public static final int WARMUP_TRADING_DAYS = 250;

    private String stockId;
    private LocalDate tradeDate;
    private String paramKey;

    private BigDecimal emaFast;
    private BigDecimal emaSlow;
    private BigDecimal dif;
    private BigDecimal dea;
    private BigDecimal osc;

    private BigDecimal rsv;
    private BigDecimal kValue;
    private BigDecimal dValue;
    private BigDecimal jValue;

    private boolean isWarmup;

    public String getStockId() {
        return stockId;
    }

    public void setStockId(String stockId) {
        this.stockId = stockId;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public String getParamKey() {
        return paramKey;
    }

    public void setParamKey(String paramKey) {
        this.paramKey = paramKey;
    }

    public BigDecimal getEmaFast() {
        return emaFast;
    }

    public void setEmaFast(BigDecimal emaFast) {
        this.emaFast = emaFast;
    }

    public BigDecimal getEmaSlow() {
        return emaSlow;
    }

    public void setEmaSlow(BigDecimal emaSlow) {
        this.emaSlow = emaSlow;
    }

    public BigDecimal getDif() {
        return dif;
    }

    public void setDif(BigDecimal dif) {
        this.dif = dif;
    }

    public BigDecimal getDea() {
        return dea;
    }

    public void setDea(BigDecimal dea) {
        this.dea = dea;
    }

    public BigDecimal getOsc() {
        return osc;
    }

    public void setOsc(BigDecimal osc) {
        this.osc = osc;
    }

    public BigDecimal getRsv() {
        return rsv;
    }

    public void setRsv(BigDecimal rsv) {
        this.rsv = rsv;
    }

    public BigDecimal getKValue() {
        return kValue;
    }

    public void setKValue(BigDecimal kValue) {
        this.kValue = kValue;
    }

    public BigDecimal getDValue() {
        return dValue;
    }

    public void setDValue(BigDecimal dValue) {
        this.dValue = dValue;
    }

    public BigDecimal getJValue() {
        return jValue;
    }

    public void setJValue(BigDecimal jValue) {
        this.jValue = jValue;
    }

    public boolean isWarmup() {
        return isWarmup;
    }

    public void setWarmup(boolean warmup) {
        isWarmup = warmup;
    }
}
