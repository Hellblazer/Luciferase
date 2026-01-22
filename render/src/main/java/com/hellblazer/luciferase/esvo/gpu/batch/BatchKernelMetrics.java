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
package com.hellblazer.luciferase.esvo.gpu.batch;

/**
 * Phase 4.2.2d: Batch Kernel Performance Metrics
 *
 * Tracks performance and correctness metrics for batch kernel optimization.
 * Enables validation that batch kernel produces identical results while improving node traversal efficiency.
 *
 * Metrics include:
 * - Ray count processed
 * - Rays per work item (batch size)
 * - Kernel execution latency (microseconds)
 * - Node accesses (traversal steps)
 * - Unique nodes visited (cache efficiency)
 * - Validation: results match single-ray kernel (true/false)
 * - Node reduction percentage from cache sharing
 *
 * @param rayCount               Number of rays processed
 * @param raysPerItem            Rays processed per work item (1 = single-ray, >1 = batch)
 * @param latencyMicroseconds    Kernel execution time in microseconds
 * @param totalNodeAccesses      Total traversal steps across all rays
 * @param uniqueNodesVisited     Number of unique nodes accessed (cache efficiency)
 * @param resultsMatch           True if batch kernel results match single-ray kernel
 * @param nodeReductionPercent   Percentage reduction in node accesses vs single-ray
 * @param coherenceScore         Ray coherence score that triggered batch kernel
 * @param timestamp              Measurement timestamp (milliseconds since epoch)
 *
 * @author hal.hildebrand
 */
public record BatchKernelMetrics(
    int rayCount,
    int raysPerItem,
    double latencyMicroseconds,
    int totalNodeAccesses,
    int uniqueNodesVisited,
    boolean resultsMatch,
    double nodeReductionPercent,
    double coherenceScore,
    long timestamp
) {

    /**
     * Calculate cache efficiency factor.
     *
     * Lower values (closer to 1.0) indicate better cache reuse.
     * 1.0 = perfect cache (each node accessed once)
     * > 1.0 = cache misses
     *
     * @return ratio of total accesses to unique nodes
     */
    public double cacheEfficiency() {
        if (uniqueNodesVisited == 0) {
            return 0.0;
        }
        return (double) totalNodeAccesses / uniqueNodesVisited;
    }

    /**
     * Calculate throughput in rays per microsecond.
     *
     * @return rays processed per microsecond
     */
    public double throughputRaysPerMicrosecond() {
        if (latencyMicroseconds == 0) {
            return 0.0;
        }
        return rayCount / latencyMicroseconds;
    }

    /**
     * Check if batch kernel achieved target node reduction.
     *
     * Target: >= 30% reduction through cache sharing and coherence exploitation
     *
     * @return true if node reduction meets or exceeds 30% target
     */
    public boolean meetsNodeReductionTarget() {
        return nodeReductionPercent >= 30.0;
    }

    /**
     * Check if batch kernel is valid for use.
     *
     * Valid if:
     * - Results match single-ray kernel (correctness)
     * - Achieves target node reduction (performance)
     *
     * @return true if batch kernel meets validity criteria
     */
    public boolean isValid() {
        return resultsMatch && meetsNodeReductionTarget();
    }

    /**
     * Format metrics as human-readable string.
     *
     * @return formatted metrics string
     */
    @Override
    public String toString() {
        return String.format(
            "BatchMetrics: rays=%d, raysPerItem=%d, latency=%.2fµs, " +
            "nodeAccess=%d/%d (%.2fx cache), reduction=%.1f%%, " +
            "resultsMatch=%s, coherence=%.3f",
            rayCount, raysPerItem, latencyMicroseconds,
            totalNodeAccesses, uniqueNodesVisited, cacheEfficiency(),
            nodeReductionPercent, resultsMatch, coherenceScore
        );
    }
}
