package com.stock.dto;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/stocks/sync/backfill request body. {@code commonStocksOnly} is nullable on the wire
 * so omission (-> {@code true}) can be distinguished from an explicit {@code false}, mirroring
 * {@code ScanRequestDto}/{@code MomentumController}'s handling of the same flag. It is ignored
 * entirely whenever {@code stockIds} names an explicit list — a named target is always fetched,
 * common stock or not (spec: 提供 stockIds 時本參數不生效).
 */
public class BackfillRequest {

    private List<String> stockIds;

    private Boolean commonStocksOnly;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    private boolean resume = false;

    private boolean catchUp = false;

    public List<String> getStockIds() {
        return stockIds;
    }

    public void setStockIds(List<String> stockIds) {
        this.stockIds = stockIds;
    }

    public Boolean getCommonStocksOnly() {
        return commonStocksOnly;
    }

    public void setCommonStocksOnly(Boolean commonStocksOnly) {
        this.commonStocksOnly = commonStocksOnly;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public boolean isResume() {
        return resume;
    }

    public void setResume(boolean resume) {
        this.resume = resume;
    }

    public boolean isCatchUp() {
        return catchUp;
    }

    public void setCatchUp(boolean catchUp) {
        this.catchUp = catchUp;
    }
}
