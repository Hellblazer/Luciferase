package com.hellblazer.luciferase.render.tile;

/**
 * Performance metrics for tile-based dispatch.
 *
 * @param totalTiles     number of tiles processed
 * @param batchTiles     count of tiles using batch kernel
 * @param singleRayTiles count of tiles using single-ray kernel
 * @param batchRatio     fraction of tiles using batch kernel (0.0-1.0)
 * @param avgCoherence   average coherence across all tiles
 * @param dispatchTimeNs total dispatch time in nanoseconds
 */
public record DispatchMetrics(
    int totalTiles,
    int batchTiles,
    int singleRayTiles,
    double batchRatio,
    double avgCoherence,
    long dispatchTimeNs
) {
    /**
     * Validates the metrics invariants.
     */
    public DispatchMetrics {
        if (totalTiles < 0) {
            throw new IllegalArgumentException("Total tiles must be non-negative");
        }
        if (batchTiles < 0) {
            throw new IllegalArgumentException("Batch tiles must be non-negative");
        }
        if (singleRayTiles < 0) {
            throw new IllegalArgumentException("Single-ray tiles must be non-negative");
        }
        if (batchRatio < 0.0 || batchRatio > 1.0) {
            throw new IllegalArgumentException("Batch ratio must be in [0, 1]");
        }
        if (avgCoherence < 0.0 || avgCoherence > 1.0) {
            throw new IllegalArgumentException("Average coherence must be in [0, 1]");
        }
        if (dispatchTimeNs < 0) {
            throw new IllegalArgumentException("Dispatch time must be non-negative");
        }
        if (batchTiles + singleRayTiles != totalTiles) {
            throw new IllegalArgumentException("Batch tiles + single-ray tiles must equal total tiles");
        }
    }
}
