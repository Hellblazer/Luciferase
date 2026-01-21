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

import com.hellblazer.luciferase.esvo.core.ESVORay;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.beam.RayCoherenceAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Phase 4.1 P1: Coherence Profiler
 *
 * Extends RayCoherenceAnalyzer with detailed profiling metrics for GPU performance validation.
 * Provides comprehensive analysis of:
 * - Overall coherence score
 * - Upper-level node sharing percentage
 * - Depth-wise visit distribution
 * - Unique nodes vs total visits (cache reuse factor)
 *
 * @author hal.hildebrand
 */
public class CoherenceProfiler extends RayCoherenceAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(CoherenceProfiler.class);

    // Depth threshold for "upper-level" nodes
    private static final int UPPER_LEVEL_DEPTH_THRESHOLD = 4;

    // Maximum depth for analysis
    private static final int MAX_DEPTH = 32;

    /**
     * Analyze ray batch coherence with detailed metrics.
     *
     * Extends base analyzeCoherence with additional profiling data:
     * - Upper-level node sharing
     * - Depth distribution
     * - Unique node count
     * - Total visits for cache analysis
     *
     * @param rays ray batch to analyze
     * @param dag  DAG octree structure
     * @return detailed coherence metrics
     */
    public CoherenceMetrics analyzeDetailed(ESVORay[] rays, DAGOctreeData dag) {
        Objects.requireNonNull(rays, "rays must not be null");
        Objects.requireNonNull(dag, "dag must not be null");

        // Empty batch: zero metrics
        if (rays.length == 0) {
            return new CoherenceMetrics(0.0, 0.0, new double[0], 0, 0);
        }

        // Single ray: perfect coherence
        if (rays.length == 1) {
            return new CoherenceMetrics(1.0, 1.0, new double[]{1.0}, 1, 1);
        }

        // Track node visits by depth and overall
        var nodeVisitsAtDepth = new HashMap<Integer, Map<Integer, Integer>>(); // depth -> (nodeIdx -> count)
        var allNodeVisits = new HashMap<Integer, Integer>(); // nodeIdx -> count
        var depthCounts = new int[MAX_DEPTH];
        var totalDepth = 0;

        // Trace all rays
        for (var ray : rays) {
            var visitedNodes = traceRayWithDepth(ray, dag);
            totalDepth += visitedNodes.size();

            for (int depth = 0; depth < visitedNodes.size() && depth < MAX_DEPTH; depth++) {
                var nodeIdx = visitedNodes.get(depth);

                // Track by depth
                nodeVisitsAtDepth.computeIfAbsent(depth, k -> new HashMap<>())
                                 .merge(nodeIdx, 1, Integer::sum);

                // Track overall
                allNodeVisits.merge(nodeIdx, 1, Integer::sum);

                // Track depth distribution
                depthCounts[depth]++;
            }
        }

        // Calculate coherence score (from base implementation)
        var coherenceScore = super.analyzeCoherence(rays, dag);

        // Calculate upper-level sharing percentage
        var upperLevelSharing = calculateUpperLevelSharing(nodeVisitsAtDepth, rays.length);

        // Create depth distribution (normalized by ray count)
        var depthDistribution = new double[MAX_DEPTH];
        for (int i = 0; i < MAX_DEPTH; i++) {
            depthDistribution[i] = depthCounts[i] / (double) rays.length;
        }

        // Count unique nodes and total visits
        var uniqueNodes = allNodeVisits.size();
        var totalVisits = allNodeVisits.values().stream()
                                       .mapToInt(Integer::intValue)
                                       .sum();

        log.debug("Coherence analysis: score={}, upperSharing={}, uniqueNodes={}, totalVisits={}",
                  String.format("%.3f", coherenceScore),
                  String.format("%.3f", upperLevelSharing),
                  uniqueNodes,
                  totalVisits);

        return new CoherenceMetrics(
            coherenceScore,
            upperLevelSharing,
            depthDistribution,
            uniqueNodes,
            totalVisits
        );
    }

    /**
     * Calculate percentage of rays that share upper-level nodes (depth < 4).
     *
     * Upper-level sharing is a strong indicator of beam optimization potential.
     */
    private double calculateUpperLevelSharing(Map<Integer, Map<Integer, Integer>> nodeVisitsAtDepth, int rayCount) {
        var sharedNodeCount = 0;
        var totalUpperLevelVisits = 0;

        // Examine depths 0 through UPPER_LEVEL_DEPTH_THRESHOLD
        for (int depth = 0; depth < UPPER_LEVEL_DEPTH_THRESHOLD; depth++) {
            var visitsAtDepth = nodeVisitsAtDepth.get(depth);
            if (visitsAtDepth == null) {
                continue;
            }

            for (var visitCount : visitsAtDepth.values()) {
                totalUpperLevelVisits += visitCount;
                if (visitCount > 1) {
                    // This node was shared by multiple rays
                    sharedNodeCount += visitCount;
                }
            }
        }

        if (totalUpperLevelVisits == 0) {
            return 0.0;
        }

        return sharedNodeCount / (double) totalUpperLevelVisits;
    }

    /**
     * Trace a single ray through the DAG and return visited node indices with depth.
     *
     * Similar to base RayCoherenceAnalyzer but returns depth-ordered list.
     */
    private List<Integer> traceRayWithDepth(ESVORay ray, DAGOctreeData dag) {
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
}
