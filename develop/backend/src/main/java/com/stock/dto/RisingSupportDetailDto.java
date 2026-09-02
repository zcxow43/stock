package com.stock.dto;

import java.math.BigDecimal;
import java.util.List;

/** detail payload for a RISING_SUPPORT hit. */
public class RisingSupportDetailDto {

    private BigDecimal supportClose;
    private BigDecimal riseClose;
    private BigDecimal risePercent;
    private BigDecimal priorHighClose;
    private List<ConfirmCloseDto> confirmCloses;

    public RisingSupportDetailDto() {
    }

    public RisingSupportDetailDto(BigDecimal supportClose, BigDecimal riseClose, BigDecimal risePercent,
                                   BigDecimal priorHighClose, List<ConfirmCloseDto> confirmCloses) {
        this.supportClose = supportClose;
        this.riseClose = riseClose;
        this.risePercent = risePercent;
        this.priorHighClose = priorHighClose;
        this.confirmCloses = confirmCloses;
    }

    public BigDecimal getSupportClose() {
        return supportClose;
    }

    public void setSupportClose(BigDecimal supportClose) {
        this.supportClose = supportClose;
    }

    public BigDecimal getRiseClose() {
        return riseClose;
    }

    public void setRiseClose(BigDecimal riseClose) {
        this.riseClose = riseClose;
    }

    public BigDecimal getRisePercent() {
        return risePercent;
    }

    public void setRisePercent(BigDecimal risePercent) {
        this.risePercent = risePercent;
    }

    public BigDecimal getPriorHighClose() {
        return priorHighClose;
    }

    public void setPriorHighClose(BigDecimal priorHighClose) {
        this.priorHighClose = priorHighClose;
    }

    public List<ConfirmCloseDto> getConfirmCloses() {
        return confirmCloses;
    }

    public void setConfirmCloses(List<ConfirmCloseDto> confirmCloses) {
        this.confirmCloses = confirmCloses;
    }
}
