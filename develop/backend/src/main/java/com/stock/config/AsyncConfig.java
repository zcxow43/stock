package com.stock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Worker pool for the price backfill batch job: up to {@code app.backfill.executor-pool-size}
     * stocks (default 8) are processed concurrently (spec: 逐檔抓取由序列改為 8 檔並行). Concurrency here
     * only bounds how many stocks are in flight at once — it never controls how densely either
     * external source is actually called; that stays a single shared, batch-wide throttle
     * ({@code SourceRateLimiter}) that every worker competes for (spec: 節流的單位是「來源」，不因並行度而
     * 縮成設定值的 1/N). {@link com.stock.service.BackfillRunner#run} itself is dispatched on {@link #backfillDispatchExecutor},
     * never on this pool, so the coordinator never eats one of these N worker slots.
     */
    @Bean
    public Executor backfillExecutor(BackfillProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getExecutorPoolSize());
        executor.setMaxPoolSize(properties.getExecutorPoolSize());
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("price-backfill-worker-");
        executor.initialize();
        return executor;
    }

    /**
     * Single-thread executor used only to dispatch {@code BackfillRunner.run} itself off the
     * calling (HTTP/startup) thread so it can return a {@link java.util.concurrent.CompletableFuture}
     * immediately (spec: 202 Accepted, async job). Deliberately separate from {@link #backfillExecutor}:
     * that pool's N slots are for the per-stock workers the coordinator fans out onto, and running
     * the coordinator itself on the same pool would permanently occupy one of those N slots for the
     * whole batch.
     */
    @Bean
    public Executor backfillDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("price-backfill-dispatch-");
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated executor for the indicator rebuild batch job (POST /api/stocks/indicators/rebuild).
     * No external rate limit applies here — the job only reads/writes our own database — so a small
     * pool is used purely to bound concurrency, not to throttle requests.
     */
    @Bean
    public Executor indicatorRebuildExecutor(IndicatorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getExecutorPoolSize());
        executor.setMaxPoolSize(properties.getExecutorPoolSize());
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("indicator-rebuild-");
        executor.initialize();
        return executor;
    }
}
