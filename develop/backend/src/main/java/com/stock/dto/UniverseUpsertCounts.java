package com.stock.dto;

/** Insert/update split for a single universe-import UPSERT batch (specs/backend/stock-universe-import.md). */
public class UniverseUpsertCounts {

    private final int insertedCount;
    private final int updatedCount;

    public UniverseUpsertCounts(int insertedCount, int updatedCount) {
        this.insertedCount = insertedCount;
        this.updatedCount = updatedCount;
    }

    public int getInsertedCount() {
        return insertedCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }
}
