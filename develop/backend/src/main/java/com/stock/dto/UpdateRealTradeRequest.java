package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;

/**
 * PATCH /api/real-trades/{id} body (specs/backend/real-trade.md). Both fields are optional and an
 * absent field means "leave it alone". {@code targetSellPrice} is tri-state: absent (untouched),
 * explicit {@code null} (clear it) or a number, so the setter records that the key was present.
 * Any other key in the body (stockId, buyDate, buyPrice, shares) is ignored, never applied.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateRealTradeRequest {

    @JsonDeserialize(using = StrictBooleanDeserializer.class)
    private Boolean excluded;
    @JsonDeserialize(using = StrictNumberDeserializer.class)
    private BigDecimal targetSellPrice;
    private boolean targetSellPricePresent;

    public UpdateRealTradeRequest() {
    }

    /** {@code null} when the key was absent. */
    public Boolean getExcluded() {
        return excluded;
    }

    public void setExcluded(Boolean excluded) {
        this.excluded = excluded;
    }

    public BigDecimal getTargetSellPrice() {
        return targetSellPrice;
    }

    public void setTargetSellPrice(BigDecimal targetSellPrice) {
        this.targetSellPrice = targetSellPrice;
        this.targetSellPricePresent = true;
    }

    /** {@code true} when the body carried a {@code targetSellPrice} key, even if its value was {@code null}. */
    public boolean isTargetSellPricePresent() {
        return targetSellPricePresent;
    }
}
