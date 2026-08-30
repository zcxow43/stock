package com.stock.dto;

import java.util.List;

/** detail payload for a HIGHER_LOWS hit: the winning run of swing lows, oldest first. */
public class HigherLowsDetailDto {

    private List<LowPointDto> lows;

    public HigherLowsDetailDto() {
    }

    public HigherLowsDetailDto(List<LowPointDto> lows) {
        this.lows = lows;
    }

    public List<LowPointDto> getLows() {
        return lows;
    }

    public void setLows(List<LowPointDto> lows) {
        this.lows = lows;
    }
}
