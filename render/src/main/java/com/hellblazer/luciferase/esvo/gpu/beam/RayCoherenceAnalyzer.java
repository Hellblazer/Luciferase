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
package com.hellblazer.luciferase.esvo.gpu.beam;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVORay;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Stream C Phase 1: Ray Coherence Detection
 *
 * Analyzes ray batches to determine coherence at upper tree levels.
 * Coherence = (shared_upper_node_count) / (total_rays × average_depth)
 *
 * High coherence (>0.5) indicates potential for beam optimization.
 *
 * @author hal.hildebrand
 */
public class RayCoherenceAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(RayCoherenceAnalyzer.class);

    // Maximum traversal depth for analysis
    private static final int MAX_DEPTH = 32;

    // Recent coherence measurements for logging
    private final List<Double> recentCoherenceValues = new ArrayList<>();
    private final Map<String, CoherenceStats> sceneStats = new HashMap<>();

    /**
     * Analyze coherence of a ray batch against a DAG structure.
     *
     * Algorithm:
     * 1. Trace each ray through the DAG, recording visited nodes
     * 2. Count how many nodes are shared across rays (weighted by depth)
     * 3. Compute coherence = shared_nodes / (total_rays × avg_depth)
     *
     * @param rays ray batch to analyze
     * @param dag DAG octree structure
     * @return coherence score [0.0, 1.0] where 1.0 = perfect coherence
     * @throws NullPointerException if rays or dag is null
     */
    public double analyzeCoherence(ESVORay[] rays, DAGOctreeData dag) {
        Objects.requireNonNull(rays, "rays must not be null");
        Objects.requireNonNull(dag, "dag must not be null");

        // Empty batch: zero coherence
        if (rays.length == 0) {
            return 0.0;
        }

        // Single ray: perfect coherence
        if (rays.length == 1) {
            return 1.0;
        }

        // Trace all rays and collect visited nodes with depth weighting
        var nodeVisitCounts = new HashMap<Integer, Integer>();
        var totalDepth = 0;

        for (var ray : rays) {
            var visitedNodes = traceRay(ray, dag);
            totalDepth += visitedNodes.size();

            // Count visits with depth weighting (upper nodes weighted more)
            for (int i = 0; i < visitedNodes.size(); i++) {
                var nodeIdx = visitedNodes.get(i);
                var depth = i;

                // Weight upper nodes more heavily (exponential decay with depth)
                // Upper levels (0-3): weight 1.0
                // Middle levels (4-7): weight 0.5
                // Lower levels (8+): weight 0.25
                var weight = depth < 4 ? 1.0 : (depth < 8 ? 0.5 : 0.25);

                nodeVisitCounts.merge(nodeIdx, 1, Integer::sum);
            }
        }

        // Calculate average depth
        var avgDepth = totalDepth / (double) rays.length;

        // Count shared nodes (visited by multiple rays)
        var sharedNodeCount = 0;
        for (var visitCount : nodeVisitCounts.values()) {
            if (visitCount > 1) {
                sharedNodeCount += visitCount - 1; // Count extra visits beyond first
            }
        }

        // Coherence = shared node count / (total rays × average depth)
        var coherence = sharedNodeCount / (rays.length * avgDepth);

        // Normalize to [0, 1] range
        coherence = Math.min(1.0, Math.max(0.0, coherence));

        // Store for logging
        recentCoherenceValues.add(coherence);
        if (recentCoherenceValues.size() > 100) {
            recentCoherenceValues.remove(0);
        }

        return coherence;
    }

    /**
     * Trace a single ray through the DAG and return visited node indices.
     *
     * Uses simplified traversal for coherence analysis (full accuracy not required).
     */
    private List<Integer> traceRay(ESVORay ray, DAGOctreeData dag) {
        var visitedNodes = new ArrayList<Integer>();

        // Prepare ray for traversal
        ray.prepareForTraversal();

        // Start from root
        var stack = new ArrayDeque<Integer>();
        stack.push(0);

        var depth = 0;

        while (!stack.isEmpty() && depth < MAX_DEPTH) {
            var nodeIdx = stack.pop();
            depth++;

            // Bounds check
            if (nodeIdx < 0 || nodeIdx >= dag.nodeCount()) {
                continue;
            }

            var node = dag.getNode(nodeIdx);
            if (node == null || !node.isValid()) {
                continue;
            }

            // Record visit
            visitedNodes.add(nodeIdx);

            // If leaf, stop
            var childMask = node.getChildMask();
            if (childMask == 0) {
                break;
            }

            // Otherwise, find children that ray intersects
            var childPtr = node.getChildPtr();

            // Simplified: add all children to stack (detailed ray-AABB test not needed for coherence)
            for (int octant = 0; octant < 8; octant++) {
                if ((childMask & (1 << octant)) != 0) {
                    var childIdx = dag.resolveChildIndex(nodeIdx, node, octant);
                    if (childIdx >= 0 && childIdx < dag.nodeCount()) {
                        stack.push(childIdx);
                    }
                }
            }
        }

        return visitedNodes;
    }

    /**
     * Log coherence metrics for profiling.
     *
     * @param sceneName name of the scene for logging context
     */
    public void logCoherenceMetrics(String sceneName) {
        if (recentCoherenceValues.isEmpty()) {
            log.info("No coherence data for scene: {}", sceneName);
            return;
        }

        // Compute statistics
        var avg = recentCoherenceValues.stream()
                                        .mapToDouble(Double::doubleValue)
                                        .average()
                                        .orElse(0.0);

        var min = recentCoherenceValues.stream()
                                        .mapToDouble(Double::doubleValue)
                                        .min()
                                        .orElse(0.0);

        var max = recentCoherenceValues.stream()
                                        .mapToDouble(Double::doubleValue)
                                        .max()
                                        .orElse(0.0);

        // Store stats
        sceneStats.put(sceneName, new CoherenceStats(avg, min, max, recentCoherenceValues.size()));

        log.info("Coherence metrics for {}: avg={}, min={}, max={}, samples={}",
                 sceneName, String.format("%.3f", avg), String.format("%.3f", min),
                 String.format("%.3f", max), recentCoherenceValues.size());
    }

    /**
     * Get recent coherence statistics for a scene.
     *
     * @param sceneName scene name
     * @return statistics or null if not available
     */
    public CoherenceStats getStats(String sceneName) {
        return sceneStats.get(sceneName);
    }

    /**
     * Clear all stored statistics.
     */
    public void clearStats() {
        recentCoherenceValues.clear();
        sceneStats.clear();
    }

    /**
     * Statistics for coherence analysis.
     */
    public record CoherenceStats(double average, double min, double max, int sampleCount) {
    }
}
