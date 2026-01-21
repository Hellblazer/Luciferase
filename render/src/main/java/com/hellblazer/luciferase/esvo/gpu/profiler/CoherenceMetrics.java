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
 * Phase 4.1 P1: Coherence Metrics for Ray Batch Analysis
 *
 * Detailed metrics about ray coherence patterns for evaluating beam optimization viability.
 *
 * @param coherenceScore          Overall coherence score [0.0, 1.0] (higher = more coherent)
 * @param upperLevelSharingPercent Percentage of rays sharing upper-level nodes (depth < 4)
 * @param depthDistribution       Distribution of traversal visits by depth level
 * @param uniqueNodesVisited      Number of unique nodes visited across all rays
 * @param totalNodeVisits         Total node visits (for cache analysis)
 *
 * @author hal.hildebrand
 */
public record CoherenceMetrics(
    double coherenceScore,
    double upperLevelSharingPercent,
    double[] depthDistribution,
    int uniqueNodesVisited,
    int totalNodeVisits
) {

    /**
     * Calculate cache reuse factor.
     *
     * @return average number of times each node was visited
     */
    public double cacheReuseFactor() {
        if (uniqueNodesVisited == 0) {
            return 0.0;
        }
        return (double) totalNodeVisits / uniqueNodesVisited;
    }

    /**
     * Check if coherence meets threshold for beam optimization.
     *
     * @param threshold minimum coherence score (typically 0.5)
     * @return true if coherence score exceeds threshold
     */
    public boolean meetsThreshold(double threshold) {
        return coherenceScore >= threshold;
    }

    /**
     * Format metrics as human-readable string.
     *
     * @return formatted metrics
     */
    @Override
    public String toString() {
        return String.format(
            "Coherence: %.2f (upper-level sharing: %.1f%%, unique nodes: %d, total visits: %d, reuse: %.2fx)",
            coherenceScore,
            upperLevelSharingPercent * 100.0,
            uniqueNodesVisited,
            totalNodeVisits,
            cacheReuseFactor()
        );
    }
}
