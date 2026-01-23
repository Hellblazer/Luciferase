/*
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.render.benchmark;

/**
 * Results from a single benchmark run.
 *
 * @param sceneName Name of the test scene
 * @param globalNodes Total nodes in baseline global BeamTree
 * @param tiledNodes Total nodes in tiled execution (sum of per-tile trees + virtual nodes)
 * @param reductionRatio Node reduction as fraction (1 - tiledNodes/globalNodes)
 * @param batchTiles Number of tiles routed to batch kernel
 * @param singleRayTiles Number of tiles routed to single-ray kernel
 * @param avgCoherence Average coherence across all tiles
 * @param dispatchTimeMs Dispatch time in milliseconds
 * @param executionTimeMs Total execution time in milliseconds
 */
public record BenchmarkResult(
    String sceneName,
    int globalNodes,
    int tiledNodes,
    double reductionRatio,
    int batchTiles,
    int singleRayTiles,
    double avgCoherence,
    double dispatchTimeMs,
    double executionTimeMs
) {
    /**
     * Validates the benchmark result invariants.
     */
    public BenchmarkResult {
        if (globalNodes < 0) {
            throw new IllegalArgumentException("globalNodes must be non-negative");
        }
        if (tiledNodes < 0) {
            throw new IllegalArgumentException("tiledNodes must be non-negative");
        }
        if (reductionRatio < -0.01 || reductionRatio > 1.01) {
            // Allow small tolerance for floating point
            throw new IllegalArgumentException("reductionRatio must be in [0, 1], got: " + reductionRatio);
        }
        if (batchTiles < 0) {
            throw new IllegalArgumentException("batchTiles must be non-negative");
        }
        if (singleRayTiles < 0) {
            throw new IllegalArgumentException("singleRayTiles must be non-negative");
        }
        if (avgCoherence < 0.0 || avgCoherence > 1.0) {
            throw new IllegalArgumentException("avgCoherence must be in [0, 1]");
        }
        if (dispatchTimeMs < 0) {
            throw new IllegalArgumentException("dispatchTimeMs must be non-negative");
        }
        if (executionTimeMs < 0) {
            throw new IllegalArgumentException("executionTimeMs must be non-negative");
        }
    }

    /**
     * Get total tiles processed.
     */
    public int totalTiles() {
        return batchTiles + singleRayTiles;
    }

    /**
     * Get batch ratio (0.0 to 1.0).
     */
    public double batchRatio() {
        int total = totalTiles();
        return total > 0 ? (double) batchTiles / total : 0.0;
    }

    /**
     * Check if reduction meets or exceeds target.
     */
    public boolean meetsTarget(double targetReduction) {
        return reductionRatio >= (targetReduction - 0.01);
    }
}
