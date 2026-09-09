package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One table within a TWSE MI_INDEX response's {@code tables} array. The response carries several
 * of these (price index, return index, trade statistics, advance/decline counts, per-stock
 * quotes...); the per-stock quote table is identified by its {@code fields[0]} being exactly
 * {@code "證券代號"} — see {@link com.stock.service.external.TwseMiIndexClient} for why this must
 * be located by that condition and never by array index (spec: 逐日全市場快照（交易所 MI_INDEX）—
 * 已測事實).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MiIndexTable {

    private List<String> fields;
    private List<List<String>> data;

    public List<String> getFields() {
        return fields;
    }

    public void setFields(List<String> fields) {
        this.fields = fields;
    }

    public List<List<String>> getData() {
        return data;
    }

    public void setData(List<List<String>> data) {
        this.data = data;
    }
}
