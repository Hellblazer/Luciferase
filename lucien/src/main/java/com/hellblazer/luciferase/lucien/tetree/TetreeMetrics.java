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
package com.hellblazer.luciferase.lucien.tetree;

/**
 * Performance metrics for Tetree operations. This class captures comprehensive performance data including tree
 * statistics, cache performance, and operation timing.
 *
 * @author hal.hildebrand
 */
public record TetreeMetrics(
/**
 * Structural statistics about the tree (node counts, depth, etc.)
 */
TetreeValidator.TreeStats treeStatistics,

/**
 * Cache hit rate (0.0 to 1.0) for cached operations
 */
float cacheHitRate,

/**
 * Average time in nanoseconds for neighbor queries
 */
float averageNeighborQueryTime,

/**
 * Average time in nanoseconds for tree traversal operations
 */
float averageTraversalTime,

/**
 * Total number of neighbor queries performed
 */
long neighborQueryCount,

/**
 * Total number of traversal operations performed
 */
long traversalCount,

/**
 * Whether performance monitoring is currently enabled
 */
boolean monitoringEnabled) {

    /**
     * Get average neighbor query time in microseconds
     */
    public float getAverageNeighborQueryTimeMicros() {
        return averageNeighborQueryTime / 1000;
    }

    /**
     * Get average traversal time in microseconds
     */
    public float getAverageTraversalTimeMicros() {
        return averageTraversalTime / 1000;
    }

    /**
     * Get cache hit percentage (0-100)
     */
    public float getCacheHitPercentage() {
        return cacheHitRate * 100;
    }

    /**
     * Get a human-readable summary of the metrics
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tetree Performance Metrics:\n");
        sb.append("===========================\n");

        // Tree structure
        sb.append("Tree Structure:\n");
        sb.append("  Total nodes: ").append(treeStatistics.getTotalNodes()).append("\n");
        sb.append("  Max depth: ").append(treeStatistics.getMaxDepth()).append("\n");
        sb.append("  Balance factor: ").append(String.format("%.2f", treeStatistics.getBalanceFactor())).append("\n");

        // Cache performance
        sb.append("\nCache Performance:\n");
        sb.append("  Hit rate: ").append(String.format("%.2f%%", cacheHitRate * 100)).append("\n");

        // Query performance
        sb.append("\nQuery Performance:\n");
        if (neighborQueryCount > 0) {
            sb.append("  Neighbor queries: ").append(neighborQueryCount).append("\n");
            sb.append("  Avg neighbor query time: ")
              .append(String.format("%.2f µs", averageNeighborQueryTime / 1000))
              .append("\n");
        }
        if (traversalCount > 0) {
            sb.append("  Traversals: ").append(traversalCount).append("\n");
            sb.append("  Avg traversal time: ").append(String.format("%.2f µs", averageTraversalTime / 1000)).append(
            "\n");
        }

        sb.append("\nMonitoring: ").append(monitoringEnabled ? "ENABLED" : "DISABLED").append("\n");

        return sb.toString();
    }
}
