package com.stock.dto;

/** Response of {@code POST /api/stocks/universe/import} (specs/backend/stock-universe-import.md). */
public class UniverseImportResponse {

    private final int fetchedCount;
    private final int eligibleCount;
    private final int skippedCount;
    private final int insertedCount;
    private final int updatedCount;
    private final int totalActiveCount;

    public UniverseImportResponse(int fetchedCount, int eligibleCount, int skippedCount,
                                   int insertedCount, int updatedCount, int totalActiveCount) {
        this.fetchedCount = fetchedCount;
        this.eligibleCount = eligibleCount;
        this.skippedCount = skippedCount;
        this.insertedCount = insertedCount;
        this.updatedCount = updatedCount;
        this.totalActiveCount = totalActiveCount;
    }

    public int getFetchedCount() {
        return fetchedCount;
    }

    public int getEligibleCount() {
        return eligibleCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public int getInsertedCount() {
        return insertedCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getTotalActiveCount() {
        return totalActiveCount;
    }
}
