package com.stock.service.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Raw shape of TWSE's MI_INDEX whole-market-for-one-date snapshot: {@code {"tables":[...]}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MiIndexResponse {

    private List<MiIndexTable> tables;

    public List<MiIndexTable> getTables() {
        return tables;
    }

    public void setTables(List<MiIndexTable> tables) {
        this.tables = tables;
    }
}
