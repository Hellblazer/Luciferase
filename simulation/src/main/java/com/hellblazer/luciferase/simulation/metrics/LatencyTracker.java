/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks latency measurements with sliding window for percentile calculation.
 * <p>
 * Uses a ring buffer to maintain the last 1000 samples for efficient percentile calculation.
 * Thread-safe for concurrent latency recording.
 * <p>
 * <strong>Features:</strong>
 * <ul>
 *   <li>Ring buffer with 1000-sample sliding window</li>
 *   <li>P50 and P99 percentile calculation</li>
 *   <li>Min, max, and average tracking</li>
 *   <li>Thread-safe concurrent recording</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class LatencyTracker {

    /**
     * Ring buffer size - keep last 1000 samples for percentile calculation
     */
    private static final int WINDOW_SIZE = 1000;

    /**
     * Total samples recorded (never decreases, even with sliding window)
     */
    private final LongAdder totalSamples;

    /**
     * Total latency (sum of all samples, for average calculation)
     */
    private final LongAdder totalLatency;

    /**
     * Ring buffer for percentile calculation (last WINDOW_SIZE samples)
     */
    private final long[] window;

    /**
     * Current index in ring buffer. Guarded by {@code synchronized(window)} (Luciferase-43dc2): the
     * advance must be co-located with the slot write so a reader never observes a half-published sample.
     */
    private int windowIndex;

    /**
     * Number of ring-buffer slots actually written, capped at {@link #WINDOW_SIZE} (Luciferase-43dc2).
     * Guarded by {@code synchronized(window)}. {@link #getStats()} derives its window sample count from
     * {@code filled} (not the unbounded {@code totalSamples}) so it never sorts uninitialized zero slots.
     */
    private int filled;

    /**
     * Create a new latency tracker.
     */
    public LatencyTracker() {
        this.totalSamples = new LongAdder();
        this.totalLatency = new LongAdder();
        this.window = new long[WINDOW_SIZE];
        this.windowIndex = 0;
        this.filled = 0;
    }

    /**
     * Record a latency measurement.
     * <p>
     * Thread-safe: can be called concurrently from multiple threads.
     *
     * @param latencyNs Latency in nanoseconds
     */
    public void record(long latencyNs) {
        // Luciferase-43dc2: publish the ring-buffer slot, the index advance, and `filled` BEFORE the
        // observable counters, all within the same critical section. A reader that sees totalSamples > 0
        // is therefore guaranteed to also see filled > 0 (the slot write and filled++ happened-before the
        // count increment, and `filled` is monotonic once non-zero), so getStats() can never return
        // min/percentiles of 0 backed by an uninitialized slot for a non-empty tracker. The counters are
        // updated LAST so they are never observable ahead of the sample that backs them.
        synchronized (window) {
            window[windowIndex] = latencyNs;
            windowIndex = (windowIndex + 1) % WINDOW_SIZE;
            if (filled < WINDOW_SIZE) {
                filled++;
            }
            totalLatency.add(latencyNs);
            totalSamples.increment();
        }
    }

    /**
     * Get current latency statistics.
     * <p>
     * Calculates percentiles, min, and max from the sliding window (last 1000 samples).
     * Average is calculated from all samples (for overall trend).
     *
     * @return Current latency statistics
     */
    public LatencyStats getStats() {
        // Luciferase-43dc2: read the count and compute window stats under the SAME `window` monitor that
        // record() holds when it publishes the slot, `filled`, and the counters. This makes getStats() an
        // atomically-consistent snapshot — the returned sampleCount and min/percentiles always reflect the
        // same set of samples, so a concurrent reader can never observe count > 0 paired with a torn
        // min/p99 of 0 from an uninitialized slot.
        synchronized (window) {
            var count = totalSamples.sum();

            // Handle empty case: return min=0 (not Long.MAX_VALUE) so the natural min <= max
            // invariant holds for consumers that compare the two without first checking
            // sampleCount (Luciferase-0frcy.113). sampleCount=0 remains the authoritative
            // "no data" signal.
            if (count == 0) {
                return new LatencyStats(0, 0, 0.0, 0, 0, 0);
            }

            var avg = (double) totalLatency.sum() / count;

            // Determine how many samples are populated in the window from `filled` (slots actually written
            // under this lock), never the unbounded `count` — they only differ transiently across calls,
            // but `filled` is the authoritative count of initialized slots.
            var windowSamples = filled;

            // Copy and sort only the valid samples
            var sorted = new long[windowSamples];
            System.arraycopy(window, 0, sorted, 0, windowSamples);
            Arrays.sort(sorted);

            // Calculate min/max from window
            var min = sorted[0];
            var max = sorted[windowSamples - 1];

            // Calculate percentiles
            var p50 = calculatePercentile(sorted, 50);
            var p99 = calculatePercentile(sorted, 99);

            return new LatencyStats(min, max, avg, p50, p99, count);
        }
    }

    /**
     * Reset all statistics.
     * <p>
     * Clears all recorded samples and resets counters to initial state.
     */
    public void reset() {
        synchronized (window) {
            totalSamples.reset();
            totalLatency.reset();
            windowIndex = 0;
            filled = 0;
            Arrays.fill(window, 0);
        }
    }

    /**
     * Calculate percentile from sorted array.
     *
     * @param sorted     Sorted array of latency values
     * @param percentile Percentile to calculate (0-100)
     * @return Percentile value in nanoseconds
     */
    private long calculatePercentile(long[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0;
        }

        // Use nearest-rank method for percentile calculation
        var rank = (int) Math.ceil((percentile / 100.0) * sorted.length);
        var index = Math.max(0, Math.min(rank - 1, sorted.length - 1));

        return sorted[index];
    }
}
