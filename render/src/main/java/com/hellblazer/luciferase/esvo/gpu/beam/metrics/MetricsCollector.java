/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

/**
 * Thread-safe interface for rendering system to collect metrics.
 * Bridges render module to portal overlay components.
 * <p>
 * Implementations must be thread-safe for concurrent metric recording
 * and snapshot retrieval.
 *
 * @author hal.hildebrand
 */
public interface MetricsCollector {

    /**
     * Marks the beginning of a new frame.
     * Resets per-frame metrics and starts timing.
     */
    void startFrame();

    /**
     * Marks the end of the current frame.
     * Finalizes frame metrics and adds to rolling window.
     */
    void endFrame();

    /**
     * Records GPU kernel execution timing for current frame.
     *
     * @param kernelExecutionNs Kernel execution time in nanoseconds
     */
    void recordGPUTiming(long kernelExecutionNs);

    /**
     * Records tile dispatch metrics for current frame.
     *
     * @param metrics Dispatch metrics from kernel selector
     */
    void recordDispatchMetrics(DispatchMetrics metrics);

    /**
     * Records per-tile execution metrics during dispatch.
     * Optional - not all implementations may aggregate per-tile data.
     *
     * @param tileX Tile X coordinate
     * @param tileY Tile Y coordinate
     * @param executionNs Tile execution time in nanoseconds
     * @param rayCount Number of rays in tile
     * @param isBatch Whether tile was dispatched as batch
     */
    default void recordTileMetrics(int tileX, int tileY, long executionNs,
                                   int rayCount, boolean isBatch) {
        // Default: no-op - implementations can override for detailed tracking
    }

    /**
     * Records BeamTree coherence statistics for current frame.
     *
     * @param coherence Coherence snapshot from BeamTree
     */
    void recordBeamTreeStats(CoherenceSnapshot coherence);

    /**
     * Gets current aggregated metrics snapshot.
     * Thread-safe - can be called concurrently with metric recording.
     *
     * @return Immutable metrics snapshot
     */
    MetricsSnapshot getSnapshot();

    /**
     * Resets all collected metrics.
     * Clears rolling window and frame-level state.
     */
    void reset();
}
