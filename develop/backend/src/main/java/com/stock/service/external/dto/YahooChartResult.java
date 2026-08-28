package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * `chart.result[0]`: parallel-array bar data. `timestamp[i]` (epoch seconds, bar start) lines up
 * index-for-index with `indicators.quote[0].open/high/low/close/volume[i]` — see
 * specs/backend/stock-minute-price.md, 資料源與其限制.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YahooChartResult {

    private long[] timestamp;
    private YahooIndicators indicators;

    public long[] getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long[] timestamp) {
        this.timestamp = timestamp;
    }

    public YahooIndicators getIndicators() {
        return indicators;
    }

    public void setIndicators(YahooIndicators indicators) {
        this.indicators = indicators;
    }
}
