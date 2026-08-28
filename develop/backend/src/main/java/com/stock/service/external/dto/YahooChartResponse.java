package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Top-level shape of Yahoo Finance's `v8/finance/chart/<symbol>` response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YahooChartResponse {

    private YahooChart chart;

    public YahooChart getChart() {
        return chart;
    }

    public void setChart(YahooChart chart) {
        this.chart = chart;
    }
}
