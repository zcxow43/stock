package com.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * latestDif..latestJ and the four cross-count fields are null (not 0) whenever the interval
 * has no computed indicator row at all; 0 means "computed, no crossing occurred" (see spec's
 * "指標尚未運算時的行為").
 */
public class StatisticsSummaryDto {

    private BigDecimal firstOpen;
    private BigDecimal lastClose;
    private BigDecimal highest;
    private LocalDate highestDate;
    private BigDecimal lowest;
    private LocalDate lowestDate;
    private BigDecimal changeAmount;
    private BigDecimal changePercent;
    private long totalVolume;
    private long avgVolume;
    private BigDecimal latestDif;
    private BigDecimal latestDea;
    private BigDecimal latestOsc;
    private BigDecimal latestK;
    private BigDecimal latestD;
    private BigDecimal latestJ;
    private Integer macdGoldenCross;
    private Integer macdDeathCross;
    private Integer kdGoldenCross;
    private Integer kdDeathCross;

    public BigDecimal getFirstOpen() {
        return firstOpen;
    }

    public void setFirstOpen(BigDecimal firstOpen) {
        this.firstOpen = firstOpen;
    }

    public BigDecimal getLastClose() {
        return lastClose;
    }

    public void setLastClose(BigDecimal lastClose) {
        this.lastClose = lastClose;
    }

    public BigDecimal getHighest() {
        return highest;
    }

    public void setHighest(BigDecimal highest) {
        this.highest = highest;
    }

    public LocalDate getHighestDate() {
        return highestDate;
    }

    public void setHighestDate(LocalDate highestDate) {
        this.highestDate = highestDate;
    }

    public BigDecimal getLowest() {
        return lowest;
    }

    public void setLowest(BigDecimal lowest) {
        this.lowest = lowest;
    }

    public LocalDate getLowestDate() {
        return lowestDate;
    }

    public void setLowestDate(LocalDate lowestDate) {
        this.lowestDate = lowestDate;
    }

    public BigDecimal getChangeAmount() {
        return changeAmount;
    }

    public void setChangeAmount(BigDecimal changeAmount) {
        this.changeAmount = changeAmount;
    }

    public BigDecimal getChangePercent() {
        return changePercent;
    }

    public void setChangePercent(BigDecimal changePercent) {
        this.changePercent = changePercent;
    }

    public long getTotalVolume() {
        return totalVolume;
    }

    public void setTotalVolume(long totalVolume) {
        this.totalVolume = totalVolume;
    }

    public long getAvgVolume() {
        return avgVolume;
    }

    public void setAvgVolume(long avgVolume) {
        this.avgVolume = avgVolume;
    }

    public BigDecimal getLatestDif() {
        return latestDif;
    }

    public void setLatestDif(BigDecimal latestDif) {
        this.latestDif = latestDif;
    }

    public BigDecimal getLatestDea() {
        return latestDea;
    }

    public void setLatestDea(BigDecimal latestDea) {
        this.latestDea = latestDea;
    }

    public BigDecimal getLatestOsc() {
        return latestOsc;
    }

    public void setLatestOsc(BigDecimal latestOsc) {
        this.latestOsc = latestOsc;
    }

    public BigDecimal getLatestK() {
        return latestK;
    }

    public void setLatestK(BigDecimal latestK) {
        this.latestK = latestK;
    }

    public BigDecimal getLatestD() {
        return latestD;
    }

    public void setLatestD(BigDecimal latestD) {
        this.latestD = latestD;
    }

    public BigDecimal getLatestJ() {
        return latestJ;
    }

    public void setLatestJ(BigDecimal latestJ) {
        this.latestJ = latestJ;
    }

    public Integer getMacdGoldenCross() {
        return macdGoldenCross;
    }

    public void setMacdGoldenCross(Integer macdGoldenCross) {
        this.macdGoldenCross = macdGoldenCross;
    }

    public Integer getMacdDeathCross() {
        return macdDeathCross;
    }

    public void setMacdDeathCross(Integer macdDeathCross) {
        this.macdDeathCross = macdDeathCross;
    }

    public Integer getKdGoldenCross() {
        return kdGoldenCross;
    }

    public void setKdGoldenCross(Integer kdGoldenCross) {
        this.kdGoldenCross = kdGoldenCross;
    }

    public Integer getKdDeathCross() {
        return kdDeathCross;
    }

    public void setKdDeathCross(Integer kdDeathCross) {
        this.kdDeathCross = kdDeathCross;
    }
}
