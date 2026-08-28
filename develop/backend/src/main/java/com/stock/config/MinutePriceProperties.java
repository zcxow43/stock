package com.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the on-demand minute-bar fetch path (GET /api/stocks/{stockId}/minute-bars).
 * Request pacing/backoff intentionally reuse {@link BackfillProperties}'s rate-limit settings
 * rather than a separate config block — see specs/backend/stock-minute-price.md, 速率控制沿用
 * stock-price-ingestion 既有的機制.
 */
@Component
@ConfigurationProperties(prefix = "app.minute-price")
public class MinutePriceProperties {

    /** Retry cap for a FAILED (stock, tradeDate); once reached, only refresh=true fetches again. */
    private int maxAttemptCount = 3;

    public int getMaxAttemptCount() {
        return maxAttemptCount;
    }

    public void setMaxAttemptCount(int maxAttemptCount) {
        this.maxAttemptCount = maxAttemptCount;
    }
}
