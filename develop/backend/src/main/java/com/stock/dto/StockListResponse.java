package com.stock.dto;

import java.util.List;

public class StockListResponse {

    private final int page;
    private final int size;
    private final long total;
    private final int totalPages;
    private final List<StockListItemDto> items;

    public StockListResponse(int page, int size, long total, int totalPages, List<StockListItemDto> items) {
        this.page = page;
        this.size = size;
        this.total = total;
        this.totalPages = totalPages;
        this.items = items;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotal() {
        return total;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public List<StockListItemDto> getItems() {
        return items;
    }
}
