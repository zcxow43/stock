package com.stock.service.external;

import com.stock.config.BackfillProperties;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests (no Spring context) for {@link SourceRateLimiter} — the shared, batch-wide
 * per-source throttle (spec: 節流的單位是「來源」...每個來源各自維持至少 0.5 秒的請求間隔; 並行執行下的封鎖處置's
 * third bullet: 間隔若做成每個工作者各自計時，實際請求密度會變成設定值的 8 倍). These assert the actual spacing
 * between recorded call times, never elapsed-total-time-only, and specifically drive it from
 * MULTIPLE concurrent threads to prove the interval is shared batch-wide rather than reset per
 * caller/thread.
 */
class SourceRateLimiterTest {

    private static final String SOURCE_A = "YAHOO";
    private static final String SOURCE_B = "FINMIND";

    @Test
    void firstCall_neverWaits() {
        SourceRateLimiter limiter = newLimiter(500);
        long start = System.nanoTime();
        limiter.acquire(SOURCE_A);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 100, "first-ever call to a source must not wait; took " + elapsedMs + "ms");
    }

    @Test
    void secondCallToSameSource_waitsAtLeastTheConfiguredInterval() {
        long intervalMs = 200;
        SourceRateLimiter limiter = newLimiter(intervalMs);

        limiter.acquire(SOURCE_A);
        long start = System.nanoTime();
        limiter.acquire(SOURCE_A);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= intervalMs - 20,
                "second call to the same source should wait ~" + intervalMs + "ms, only waited " + elapsedMs + "ms");
    }

    @Test
    void differentSources_neverBlockEachOther() {
        long intervalMs = 500; // deliberately large so a false "blocked" would be obvious
        SourceRateLimiter limiter = newLimiter(intervalMs);

        limiter.acquire(SOURCE_A); // starts SOURCE_A's cooldown

        long start = System.nanoTime();
        limiter.acquire(SOURCE_B); // must NOT be delayed by SOURCE_A's interval
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 100,
                "acquiring a DIFFERENT source must not wait on the first source's interval; took " + elapsedMs + "ms");
    }

    /**
     * The property that actually matters under concurrency (spec: 節流的單位是「來源」...不因並行度而縮成
     * 設定值的 1/8): N worker threads racing to call the SAME source must still be spaced at least
     * intervalMs apart from each other — a per-thread/per-worker limiter would let all N through
     * immediately since each would see itself as "the first caller".
     */
    @Test
    void concurrentWorkersOnSameSource_staySpacedByAtLeastTheInterval() throws InterruptedException {
        long intervalMs = 80;
        int workerCount = 6;
        SourceRateLimiter limiter = newLimiter(intervalMs);

        List<Long> callTimes = new CopyOnWriteArrayList<>();
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        try {
            for (int i = 0; i < workerCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    limiter.acquire(SOURCE_A);
                    callTimes.add(System.nanoTime());
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown(); // release every worker at (as close to) the same instant as possible

            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers did not finish in time");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(callTimes.size() == workerCount, "expected every worker to have called acquire()");
        List<Long> sorted = new CopyOnWriteArrayList<>(callTimes);
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            long gapMs = (sorted.get(i) - sorted.get(i - 1)) / 1_000_000;
            assertTrue(gapMs >= intervalMs - 20,
                    "consecutive concurrent acquire() calls to the SAME source must stay >= " + intervalMs
                            + "ms apart (shared, batch-wide throttle); got a " + gapMs + "ms gap at index " + i);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private SourceRateLimiter newLimiter(long intervalMs) {
        BackfillProperties properties = new BackfillProperties();
        properties.getRateLimit().setIntervalMs(intervalMs);
        return new SourceRateLimiter(properties);
    }
}
