package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class YahooChart {

    private List<YahooChartResult> result;

    public List<YahooChartResult> getResult() {
        return result;
    }

    public void setResult(List<YahooChartResult> result) {
        this.result = result;
    }
}
