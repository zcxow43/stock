package com.stock.service.external;

import com.stock.config.BackfillProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks, independently per source code, whether a {@link PriceHistorySource} is currently
 * blocked (spec: 來源可用性 — 每個來源各自維護一個「封鎖到期時間」). Two sources never share a counter or
 * cooldown: one being blocked never affects the other's availability (spec: 兩個來源各自獨立計算請求
 * 間隔、退避與封鎖狀態).
 *
 * In-memory and process-wide by design: block state doesn't need to survive a restart (spec: 封鎖
 * 到期後的下一檔重新從順位最前的來源開始嘗試，無需重啟或人工介入 already covers the expiry case; a restart happening
 * to also clear a still-active block is an acceptable, not spec-forbidden, side effect).
 */
@Component
public class SourceAvailabilityTracker {

    private final BackfillProperties properties;
    private final ConcurrentHashMap<String, Instant> blockedUntil = new ConcurrentHashMap<>();

    public SourceAvailabilityTracker(BackfillProperties properties) {
        this.properties = properties;
    }

    /** True when this source has no active block (never blocked, or its block has expired). */
    public boolean isAvailable(String sourceCode) {
        Instant until = blockedUntil.get(sourceCode);
        return until == null || !Instant.now().isBefore(until);
    }

    /**
     * Marks a source blocked until {@code retryAfterSeconds} from now, or the configured default
     * cooldown when the response didn't provide one (spec: 到期時間的取得). Continuing to call a
     * blocked source would only extend the effective block in practice; this tracker itself never
     * calls anything — {@link PriceHistoryFetcher} is responsible for actually skipping blocked
     * sources.
     */
    public void markBlocked(String sourceCode, Long retryAfterSeconds) {
        long cooldownSeconds = (retryAfterSeconds != null && retryAfterSeconds > 0)
                ? retryAfterSeconds
                : properties.getRateLimit().getDefaultBlockCooldownSeconds();
        blockedUntil.put(sourceCode, Instant.now().plusSeconds(cooldownSeconds));
    }

    /** Test-only reset hook; never invoked in production code. */
    public void reset() {
        blockedUntil.clear();
    }
}
