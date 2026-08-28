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
     * Dedicated executor for the price backfill batch job. Pool size defaults to 1
     * (sequential) because the external data source's rate limit must be respected
     * per-request, not per-thread; see specs/backend/stock-price-ingestion.md.
     */
    @Bean
    public Executor backfillExecutor(BackfillProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getExecutorPoolSize());
        executor.setMaxPoolSize(properties.getExecutorPoolSize());
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("price-backfill-");
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
