/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.metrics;

/**
 * Latency statistics for monitoring ghost sync performance.
 * <p>
 * Provides comprehensive latency metrics including min, max, average, and percentiles (P50, P99).
 *
 * @param minLatencyNs  Minimum latency observed in nanoseconds
 * @param maxLatencyNs  Maximum latency observed in nanoseconds
 * @param avgLatencyNs  Average latency in nanoseconds
 * @param p50LatencyNs  50th percentile (median) latency in nanoseconds
 * @param p99LatencyNs  99th percentile latency in nanoseconds
 * @param sampleCount   Total number of samples recorded
 * @author hal.hildebrand
 */
public record LatencyStats(long minLatencyNs, long maxLatencyNs, double avgLatencyNs, long p50LatencyNs,
                           long p99LatencyNs, long sampleCount) {

    /**
     * Check if P99 latency exceeds the given threshold.
     *
     * @param thresholdNs Threshold in nanoseconds
     * @return true if P99 latency exceeds threshold
     */
    public boolean exceedsThreshold(long thresholdNs) {
        return p99LatencyNs > thresholdNs;
    }

    /**
     * Get average latency in milliseconds.
     *
     * @return Average latency in milliseconds
     */
    public double avgLatencyMs() {
        return avgLatencyNs / 1_000_000.0;
    }

    /**
     * Get P50 latency in milliseconds.
     *
     * @return P50 latency in milliseconds
     */
    public double p50LatencyMs() {
        return p50LatencyNs / 1_000_000.0;
    }

    /**
     * Get P99 latency in milliseconds.
     *
     * @return P99 latency in milliseconds
     */
    public double p99LatencyMs() {
        return p99LatencyNs / 1_000_000.0;
    }

    /**
     * Get minimum latency in milliseconds.
     *
     * @return Minimum latency in milliseconds
     */
    public double minLatencyMs() {
        return minLatencyNs / 1_000_000.0;
    }

    /**
     * Get maximum latency in milliseconds.
     *
     * @return Maximum latency in milliseconds
     */
    public double maxLatencyMs() {
        return maxLatencyNs / 1_000_000.0;
    }
}
