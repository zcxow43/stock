package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Top-level shape of Fugle's `GET .../stock/historical/candles/<symbol>` response — confirmed
 * live against the real API on 2026-09-15 for both 2330 (TWSE) and 6488 (TPEx): top-level `data`
 * is a flat array of bars, sibling to `symbol`/`exchange`/`market`/`timeframe`/`sort` metadata
 * (see specs/backend/stock-minute-price.md, 富果 Fugle). Metadata fields are intentionally not
 * modeled — this client only ever queries one symbol/date it already knows, so `exchange` etc.
 * carry no information it doesn't already have.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FugleCandlesResponse {

    private List<FugleCandle> data;

    public List<FugleCandle> getData() {
        return data;
    }

    public void setData(List<FugleCandle> data) {
        this.data = data;
    }
}
