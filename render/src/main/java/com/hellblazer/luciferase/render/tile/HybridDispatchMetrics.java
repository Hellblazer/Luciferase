/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.render.tile;

/**
 * Performance metrics for hybrid CPU/GPU tile-based dispatch.
 *
 * <p>Extends the basic dispatch metrics with CPU execution tracking
 * and GPU saturation information.
 *
 * @param totalTiles       total number of tiles processed
 * @param gpuBatchTiles    count of tiles using GPU batch kernel
 * @param gpuSingleTiles   count of tiles using GPU single-ray kernel
 * @param cpuTiles         count of tiles using CPU traversal
 * @param gpuRatio         fraction of tiles using GPU (batch + single)
 * @param cpuRatio         fraction of tiles using CPU
 * @param avgCoherence     average coherence across all tiles
 * @param gpuSaturation    GPU saturation level at dispatch time
 * @param dispatchTimeNs   total dispatch time in nanoseconds
 * @param gpuTimeNs        time spent on GPU execution
 * @param cpuTimeNs        time spent on CPU execution
 */
public record HybridDispatchMetrics(
    int totalTiles,
    int gpuBatchTiles,
    int gpuSingleTiles,
    int cpuTiles,
    double gpuRatio,
    double cpuRatio,
    double avgCoherence,
    double gpuSaturation,
    long dispatchTimeNs,
    long gpuTimeNs,
    long cpuTimeNs
) {
    /**
     * Validates the metrics invariants.
     */
    public HybridDispatchMetrics {
        if (totalTiles < 0) {
            throw new IllegalArgumentException("Total tiles must be non-negative");
        }
        if (gpuBatchTiles < 0) {
            throw new IllegalArgumentException("GPU batch tiles must be non-negative");
        }
        if (gpuSingleTiles < 0) {
            throw new IllegalArgumentException("GPU single tiles must be non-negative");
        }
        if (cpuTiles < 0) {
            throw new IllegalArgumentException("CPU tiles must be non-negative");
        }
        if (gpuRatio < 0.0 || gpuRatio > 1.0) {
            throw new IllegalArgumentException("GPU ratio must be in [0, 1]");
        }
        if (cpuRatio < 0.0 || cpuRatio > 1.0) {
            throw new IllegalArgumentException("CPU ratio must be in [0, 1]");
        }
        if (avgCoherence < 0.0 || avgCoherence > 1.0) {
            throw new IllegalArgumentException("Average coherence must be in [0, 1]");
        }
        if (gpuSaturation < 0.0 || gpuSaturation > 1.0) {
            throw new IllegalArgumentException("GPU saturation must be in [0, 1]");
        }
        if (dispatchTimeNs < 0) {
            throw new IllegalArgumentException("Dispatch time must be non-negative");
        }
        if (gpuTimeNs < 0) {
            throw new IllegalArgumentException("GPU time must be non-negative");
        }
        if (cpuTimeNs < 0) {
            throw new IllegalArgumentException("CPU time must be non-negative");
        }
        if (gpuBatchTiles + gpuSingleTiles + cpuTiles != totalTiles) {
            throw new IllegalArgumentException("GPU batch + GPU single + CPU tiles must equal total tiles");
        }
    }

    /**
     * Creates metrics from a standard DispatchMetrics with no CPU tiles.
     *
     * @param base base dispatch metrics
     * @return hybrid metrics with zero CPU usage
     */
    public static HybridDispatchMetrics fromBase(DispatchMetrics base) {
        return new HybridDispatchMetrics(
            base.totalTiles(),
            base.batchTiles(),
            base.singleRayTiles(),
            0,  // no CPU tiles
            1.0,  // 100% GPU
            0.0,  // 0% CPU
            base.avgCoherence(),
            0.0,  // unknown saturation
            base.dispatchTimeNs(),
            base.dispatchTimeNs(),  // assume all GPU time
            0L  // no CPU time
        );
    }

    /**
     * Returns total GPU tiles (batch + single).
     */
    public int totalGpuTiles() {
        return gpuBatchTiles + gpuSingleTiles;
    }

    /**
     * Returns GPU batch ratio relative to all GPU tiles.
     */
    public double gpuBatchRatio() {
        int gpuTotal = totalGpuTiles();
        return gpuTotal > 0 ? (double) gpuBatchTiles / gpuTotal : 0.0;
    }

    /**
     * Returns efficiency estimate based on optimal work distribution.
     * Higher is better (1.0 = optimal).
     */
    public double efficiency() {
        // Simple heuristic: efficiency drops if we're using CPU when GPU isn't saturated
        if (gpuSaturation < 0.8 && cpuRatio > 0.2) {
            return 1.0 - (cpuRatio - 0.2) * 0.5;  // Penalty for excess CPU use
        }
        return 1.0;
    }
}
