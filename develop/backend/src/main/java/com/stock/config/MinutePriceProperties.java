package com.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

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

    /**
     * Earliest trade date minute bars are available for, from either source (spec: availableFrom
     * 為設定值，預設 2023-05-23...程式中不寫死此日期). Drives BOTH the OUT_OF_WINDOW boundary and the
     * response's `availableFrom` field from the SAME value, so changing this one setting moves
     * both together — never hardcode 2023-05-23 anywhere else.
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate availableFrom = LocalDate.of(2023, 5, 23);

    private final Fugle fugle = new Fugle();

    public int getMaxAttemptCount() {
        return maxAttemptCount;
    }

    public void setMaxAttemptCount(int maxAttemptCount) {
        this.maxAttemptCount = maxAttemptCount;
    }

    public LocalDate getAvailableFrom() {
        return availableFrom;
    }

    public void setAvailableFrom(LocalDate availableFrom) {
        this.availableFrom = availableFrom;
    }

    public Fugle getFugle() {
        return fugle;
    }

    /** Settings specific to the Fugle source (dates older than 30 days, not earlier than availableFrom). */
    public static class Fugle {

        /**
         * From environment variable FUGLE_API_KEY via `app.minute-price.fugle.api-key:
         * ${FUGLE_API_KEY:}` — never a real value in a version-controlled file (spec: 富果 API Key
         * 由環境變數 FUGLE_API_KEY 注入，不寫入版控中的設定檔).
         */
        private String apiKey = "";

        /**
         * Minimum spacing between consecutive Fugle requests. Fugle's free-tier historical-candle
         * quota is 60 requests/minute, so this must not go below 1000ms (spec: 富果相鄰兩次請求的間隔
         * 不小於 1 秒，數值取自設定). Independent of Yahoo's own pacing — see {@code
         * com.stock.service.external.SourceRateLimiter}.
         */
        private long intervalMs = 1000;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }
}
