package com.stock.dto;

/** Response of {@code POST /api/stocks/universe/import} (specs/backend/stock-universe-import.md). */
public class UniverseImportResponse {

    /** industrySourceStatus: source 2 fetched and applied normally. */
    public static final String INDUSTRY_STATUS_OK = "OK";
    /** industrySourceStatus: source 2 connection failed / timed out / non-2xx. */
    public static final String INDUSTRY_STATUS_UNAVAILABLE = "UNAVAILABLE";
    /** industrySourceStatus: source 2 returned an empty array. */
    public static final String INDUSTRY_STATUS_EMPTY = "EMPTY";
    /** industrySourceStatus: source 2's response could not be parsed. */
    public static final String INDUSTRY_STATUS_MALFORMED = "MALFORMED";

    private final int fetchedCount;
    private final int eligibleCount;
    private final int skippedCount;
    private final int insertedCount;
    private final int updatedCount;
    private final int totalActiveCount;
    private final String industrySourceStatus;
    private final int industryCount;
    private final int industryLinkedStockCount;
    private final int uncategorizedStockCount;

    public UniverseImportResponse(int fetchedCount, int eligibleCount, int skippedCount,
                                   int insertedCount, int updatedCount, int totalActiveCount,
                                   String industrySourceStatus, int industryCount,
                                   int industryLinkedStockCount, int uncategorizedStockCount) {
        this.fetchedCount = fetchedCount;
        this.eligibleCount = eligibleCount;
        this.skippedCount = skippedCount;
        this.insertedCount = insertedCount;
        this.updatedCount = updatedCount;
        this.totalActiveCount = totalActiveCount;
        this.industrySourceStatus = industrySourceStatus;
        this.industryCount = industryCount;
        this.industryLinkedStockCount = industryLinkedStockCount;
        this.uncategorizedStockCount = uncategorizedStockCount;
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

    public String getIndustrySourceStatus() {
        return industrySourceStatus;
    }

    public int getIndustryCount() {
        return industryCount;
    }

    public int getIndustryLinkedStockCount() {
        return industryLinkedStockCount;
    }

    public int getUncategorizedStockCount() {
        return uncategorizedStockCount;
    }
}
