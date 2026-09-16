package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Raw shape of TWSE's T86 three-major-institutional-investors daily report:
 * {@code {"stat":"OK","fields":[...],"data":[[...]]}} on a trading day, or
 * {@code {"stat":"很抱歉，沒有符合條件的資料!","total":0}} (no {@code fields}/{@code data} at all) on a
 * non-trading day — both shapes are handled by leaving the missing fields {@code null}
 * (spec: 未實測：交易日收盤後、日報尚未發布時請求的回應形狀... 下方「失敗處置」以「stat 不為 OK、或沒有 data」涵蓋兩者).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class T86Response {

    private String stat;
    private List<String> fields;
    private List<List<String>> data;

    public String getStat() {
        return stat;
    }

    public void setStat(String stat) {
        this.stat = stat;
    }

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
