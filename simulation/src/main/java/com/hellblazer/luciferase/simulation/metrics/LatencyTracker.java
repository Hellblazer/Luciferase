/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
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
     * Current index in ring buffer
     */
    private final AtomicInteger windowIndex;

    /**
     * Create a new latency tracker.
     */
    public LatencyTracker() {
        this.totalSamples = new LongAdder();
        this.totalLatency = new LongAdder();
        this.window = new long[WINDOW_SIZE];
        this.windowIndex = new AtomicInteger(0);
    }

    /**
     * Record a latency measurement.
     * <p>
     * Thread-safe: can be called concurrently from multiple threads.
     *
     * @param latencyNs Latency in nanoseconds
     */
    public void record(long latencyNs) {
        // Update counters
        totalSamples.increment();
        totalLatency.add(latencyNs);

        // Add to ring buffer
        var idx = windowIndex.getAndUpdate(i -> (i + 1) % WINDOW_SIZE);
        synchronized (window) {
            window[idx] = latencyNs;
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
        var count = totalSamples.sum();

        // Handle empty case
        if (count == 0) {
            return new LatencyStats(Long.MAX_VALUE, 0, 0.0, 0, 0, 0);
        }

        var avg = (double) totalLatency.sum() / count;

        // Calculate min, max, and percentiles from window
        long min;
        long max;
        long p50;
        long p99;

        synchronized (window) {
            // Determine how many samples are in the window
            var windowSamples = (int) Math.min(count, WINDOW_SIZE);

            // Copy and sort only the valid samples
            var sorted = new long[windowSamples];
            if (count <= WINDOW_SIZE) {
                // Haven't wrapped yet - copy first windowSamples entries
                System.arraycopy(window, 0, sorted, 0, windowSamples);
            } else {
                // Have wrapped - copy entire window
                System.arraycopy(window, 0, sorted, 0, WINDOW_SIZE);
            }

            Arrays.sort(sorted);

            // Calculate min/max from window
            min = sorted[0];
            max = sorted[windowSamples - 1];

            // Calculate percentiles
            p50 = calculatePercentile(sorted, 50);
            p99 = calculatePercentile(sorted, 99);
        }

        return new LatencyStats(min, max, avg, p50, p99, count);
    }

    /**
     * Reset all statistics.
     * <p>
     * Clears all recorded samples and resets counters to initial state.
     */
    public void reset() {
        totalSamples.reset();
        totalLatency.reset();
        windowIndex.set(0);
        synchronized (window) {
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
