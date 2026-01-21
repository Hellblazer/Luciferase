/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvo.gpu.profiler;

/**
 * Phase 4.1 P1: GPU Performance Metrics
 *
 * Captures comprehensive GPU performance measurements for baseline vs optimized comparison.
 *
 * Metrics include:
 * - Latency (microseconds)
 * - Throughput (rays per microsecond)
 * - GPU occupancy (percent)
 * - Average traversal depth
 * - Cache hit rate (Stream A optimization)
 * - Timestamp for temporal analysis
 *
 * @param scenario                       Measurement scenario identifier (e.g., "baseline", "optimized_A+B")
 * @param rayCount                       Number of rays traced
 * @param latencyMicroseconds            Measured latency in microseconds
 * @param throughputRaysPerMicrosecond   Throughput (rays/µs)
 * @param gpuOccupancyPercent            GPU occupancy from Stream B profiling
 * @param averageTraversalDepth          Average DAG traversal depth
 * @param cacheHitRate                   Shared memory cache hit rate (0.0-1.0)
 * @param timestamp                      Measurement timestamp (milliseconds)
 *
 * @author hal.hildebrand
 */
public record PerformanceMetrics(
    String scenario,
    int rayCount,
    double latencyMicroseconds,
    double throughputRaysPerMicrosecond,
    float gpuOccupancyPercent,
    int averageTraversalDepth,
    float cacheHitRate,
    long timestamp
) {

    /**
     * Compare this metrics to a baseline and calculate improvement percentage.
     *
     * Positive value = improvement (this is faster than baseline)
     * Negative value = degradation (this is slower than baseline)
     *
     * Formula: (baseline_latency - this_latency) / baseline_latency * 100
     *
     * @param baseline baseline metrics for comparison
     * @return improvement percentage (positive = faster, negative = slower)
     */
    public double compareToBaseline(PerformanceMetrics baseline) {
        if (baseline.latencyMicroseconds == 0) {
            return 0.0;
        }

        return (baseline.latencyMicroseconds - this.latencyMicroseconds)
            / baseline.latencyMicroseconds * 100.0;
    }

    /**
     * Calculate speedup factor relative to baseline.
     *
     * @param baseline baseline metrics for comparison
     * @return speedup factor (e.g., 1.89 = 1.89x faster)
     */
    public double speedupFactor(PerformanceMetrics baseline) {
        if (this.latencyMicroseconds == 0) {
            return 0.0;
        }

        return baseline.latencyMicroseconds / this.latencyMicroseconds;
    }

    /**
     * Check if performance meets target threshold.
     *
     * @param targetLatencyMicros target latency threshold
     * @return true if latency is below target
     */
    public boolean meetsTarget(double targetLatencyMicros) {
        return this.latencyMicroseconds < targetLatencyMicros;
    }

    /**
     * Format metrics as human-readable string.
     *
     * @return formatted metrics string
     */
    @Override
    public String toString() {
        return String.format(
            "%s: %,d rays in %.2fµs (%.2f Kray/ms, %.1f%% occupancy, depth=%d, cache=%.1f%%)",
            scenario,
            rayCount,
            latencyMicroseconds,
            throughputRaysPerMicrosecond,
            gpuOccupancyPercent,
            averageTraversalDepth,
            cacheHitRate * 100.0f
        );
    }
}
