package com.hellblazer.luciferase.simulation.viz.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.39: RegionBuilder.build() queue-admission TOCTOU.
 * <p>
 * Under concurrent submission the check (queueSize &gt;= maxQueueDepth) and the offer+increment
 * must be atomic; otherwise N threads all pass the check and all enqueue, overshooting
 * maxQueueDepth. We hammer the queue from many threads and assert the observed depth never
 * exceeds the configured maximum.
 */
class RegionBuilderBackpressureRemediationWave2Test {

    private RegionBuilder builder;

    @AfterEach
    void tearDown() {
        if (builder != null) {
            builder.close();
        }
    }

    @Test
    void concurrentBuildsNeverExceedMaxQueueDepth() throws Exception {
        int maxQueueDepth = 4;
        // Single build thread so the queue stays under pressure while submitters race.
        builder = new RegionBuilder(1, maxQueueDepth, 10, 64);

        int threads = 16;
        int perThread = 50;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var pool = Executors.newFixedThreadPool(threads);
        var maxObservedDepth = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        var request = invisibleRequest(seed * perThread + i);
                        try {
                            builder.build(request);
                            // Sample immediately after a successful admission; the queue must
                            // never hold more than maxQueueDepth pending builds.
                            maxObservedDepth.accumulateAndGet(builder.getQueueDepth(), Math::max);
                        } catch (RegionBuilder.BuildQueueFullException
                                 | RegionBuilder.CircuitBreakerOpenException expected) {
                            // Rejection under backpressure is correct behavior.
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "submitters must finish");
        pool.shutdownNow();

        assertTrue(maxObservedDepth.get() <= maxQueueDepth,
                   "queue depth must never exceed maxQueueDepth (" + maxQueueDepth
                   + "); observed peak " + maxObservedDepth.get());
    }

    private RegionBuilder.BuildRequest invisibleRequest(int n) {
        var regionId = new RegionId(n, 4);
        var bounds = new RegionBounds(0f, 0f, 0f, 1f, 1f, 1f);
        var positions = List.of(new Point3f(0.5f, 0.5f, 0.5f));
        return new RegionBuilder.BuildRequest(
                regionId, positions, bounds, 4, /*visible=*/false,
                RegionBuilder.BuildType.ESVO, n);
    }
}
