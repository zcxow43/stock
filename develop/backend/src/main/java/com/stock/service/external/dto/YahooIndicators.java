package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class YahooIndicators {

    private List<YahooQuote> quote;

    public List<YahooQuote> getQuote() {
        return quote;
    }

    public void setQuote(List<YahooQuote> quote) {
        this.quote = quote;
    }
}
