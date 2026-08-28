package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FinMindResponse {

    private String msg;
    private int status;
    private List<FinMindRow> data;

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public List<FinMindRow> getData() {
        return data;
    }

    public void setData(List<FinMindRow> data) {
        this.data = data;
    }
}
