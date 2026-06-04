/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.metrics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Luciferase-43dc2: {@link LatencyTracker#getStats()} must be an atomically-consistent snapshot of the
 * sliding window. The fix advances the ring-buffer index and tracks a {@code filled} counter under the
 * {@code window} lock (instead of incrementing the observable {@code totalSamples} before the slot is
 * written), and reads the count under that same lock, so getStats() never reports a non-zero sampleCount
 * paired with a min/percentile of 0 pulled from an uninitialized slot — the same publication-order race
 * that was fixed in {@code MigrationMetrics.LatencyStats}.
 */
class LatencyTrackerStatsConsistencyTest {

    /**
     * With fewer than WINDOW_SIZE samples, all of value 50ns, every window statistic must be 50 — never 0
     * (which would mean an uninitialized slot was pulled into the sort).
     */
    @Test
    void statsNeverIncludeUninitializedSlots() {
        var tracker = new LatencyTracker();
        for (int i = 0; i < 100; i++) {
            tracker.record(50L);
        }
        var stats = tracker.getStats();
        assertEquals(100L, stats.sampleCount());
        assertEquals(50L, stats.minLatencyNs());
        assertEquals(50L, stats.maxLatencyNs());
        assertEquals(50L, stats.p50LatencyNs());
        assertEquals(50L, stats.p99LatencyNs(),
                     "P99 over 100 identical samples must be the sample, not a zero slot");
    }

    /**
     * Concurrent record() vs getStats() must never expose a torn snapshot: with all positive samples, a
     * non-empty snapshot must report strictly positive min and p99 (a 0 would indicate totalSamples outran
     * the window write and an uninitialized slot was sorted in).
     */
    @Test
    void concurrentRecordAndStatsIsConsistent() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var tracker = new LatencyTracker();
            var start = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();

            var writer = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200_000 && error.get() == null; i++) {
                        tracker.record(10L + (i % 90)); // always >= 10, never 0
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            }, "latency-writer");

            var reader = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200_000 && error.get() == null; i++) {
                        var stats = tracker.getStats();
                        if (stats.sampleCount() > 0 && (stats.minLatencyNs() <= 0 || stats.p99LatencyNs() <= 0)) {
                            throw new AssertionError("torn snapshot: count=" + stats.sampleCount()
                                                     + " min=" + stats.minLatencyNs()
                                                     + " p99=" + stats.p99LatencyNs());
                        }
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            }, "latency-reader");

            writer.start();
            reader.start();
            start.countDown();
            writer.join(TimeUnit.SECONDS.toMillis(25));
            reader.join(TimeUnit.SECONDS.toMillis(25));

            if (error.get() != null) {
                fail("concurrent record/getStats inconsistency: " + error.get(), error.get());
            }
        });
    }
}
