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
package com.hellblazer.luciferase.esvt.optimization;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Memory layout optimization for ESVT tetrahedral data.
 *
 * <p>Analyzes cache efficiency and reorders data for better memory access patterns,
 * optimized for the 8-byte ESVT node structure.
 *
 * <p>Key optimizations:
 * <ul>
 *   <li>Cache line alignment (8 nodes per 64-byte cache line)</li>
 *   <li>Spatial locality for tetrahedral traversal patterns</li>
 *   <li>Child grouping by tetrahedron type (S0-S5)</li>
 *   <li>Breadth-first vs depth-first layout analysis</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTMemoryOptimizer {
    private static final Logger log = LoggerFactory.getLogger(ESVTMemoryOptimizer.class);

    private static final int CACHE_LINE_SIZE = 64; // bytes
    private static final int NODE_SIZE = ESVTNodeUnified.SIZE_BYTES; // 8 bytes
    private static final int NODES_PER_CACHE_LINE = CACHE_LINE_SIZE / NODE_SIZE; // 8 nodes

    /**
     * Memory layout analysis result.
     */
    public static class MemoryLayoutProfile {
        private final float cacheEfficiency;
        private final float spatialLocality;
        private final float fragmentation;
        private final long memoryFootprint;
        private final float tetTypeLocality;

        public MemoryLayoutProfile(float cacheEfficiency, float spatialLocality,
                                   float fragmentation, long memoryFootprint,
                                   float tetTypeLocality) {
            this.cacheEfficiency = Math.max(0.0f, Math.min(1.0f, cacheEfficiency));
            this.spatialLocality = Math.max(0.0f, Math.min(1.0f, spatialLocality));
            this.fragmentation = Math.max(0.0f, Math.min(1.0f, fragmentation));
            this.memoryFootprint = memoryFootprint;
            this.tetTypeLocality = Math.max(0.0f, Math.min(1.0f, tetTypeLocality));
        }

        public float getCacheEfficiency() { return cacheEfficiency; }
        public float getSpatialLocality() { return spatialLocality; }
        public float getFragmentation() { return fragmentation; }
        public long getMemoryFootprint() { return memoryFootprint; }
        public float getTetTypeLocality() { return tetTypeLocality; }

        @Override
        public String toString() {
            return String.format("MemoryLayoutProfile{cacheEff=%.2f, spatial=%.2f, " +
                               "frag=%.2f, tetType=%.2f, memory=%d bytes}",
                               cacheEfficiency, spatialLocality, fragmentation,
                               tetTypeLocality, memoryFootprint);
        }
    }

    /**
     * Analyze memory layout for cache efficiency.
     */
    public MemoryLayoutProfile analyzeMemoryLayout(ESVTData data) {
        var nodes = data.nodes();
        if (nodes.length == 0) {
            return new MemoryLayoutProfile(1.0f, 1.0f, 0.0f, 0, 1.0f);
        }

        var cacheEfficiency = calculateCacheEfficiency(nodes);
        var spatialLocality = calculateSpatialLocality(nodes);
        var fragmentation = calculateFragmentation(data);
        var memoryFootprint = (long) nodes.length * NODE_SIZE;
        var tetTypeLocality = calculateTetTypeLocality(nodes);

        return new MemoryLayoutProfile(cacheEfficiency, spatialLocality,
                                       fragmentation, memoryFootprint, tetTypeLocality);
    }

    /**
     * Optimize memory layout for better cache performance.
     */
    public ESVTData optimizeMemoryLayout(ESVTData originalData) {
        var nodes = originalData.nodes();
        if (nodes.length == 0) {
            return originalData;
        }

        // Group nodes by type for better type-locality
        var nodesByType = groupNodesByType(nodes);

        // Reorder nodes within each type group for spatial locality
        var optimizedNodes = new ESVTNodeUnified[nodes.length];
        var currentIndex = 0;

        // Process types in order (S0-S5)
        for (int type = 0; type < 6; type++) {
            var typeNodes = nodesByType.get(type);
            if (typeNodes != null && !typeNodes.isEmpty()) {
                // Sort by child pointer for spatial locality
                typeNodes.sort(Comparator.comparingInt(ESVTNodeUnified::getChildPtr));

                for (var node : typeNodes) {
                    optimizedNodes[currentIndex++] = node;
                }
            }
        }

        // Copy any remaining nodes (invalid type)
        var otherNodes = nodesByType.get(-1);
        if (otherNodes != null) {
            for (var node : otherNodes) {
                if (currentIndex < optimizedNodes.length) {
                    optimizedNodes[currentIndex++] = node;
                }
            }
        }

        // Pad to cache line alignment if beneficial
        padToCacheLineAlignment(optimizedNodes);

        log.debug("Optimized memory layout: {} nodes, type-grouped ordering",
                optimizedNodes.length);

        return new ESVTData(
            optimizedNodes,
            originalData.contours(),
            originalData.farPointers(),
            originalData.rootType(),
            originalData.maxDepth(),
            originalData.leafCount(),
            originalData.internalCount(),
            originalData.gridResolution(),
            originalData.leafVoxelCoords()
        );
    }

    /**
     * Optimize for breadth-first traversal pattern.
     */
    public ESVTData optimizeBreadthFirst(ESVTData originalData) {
        var nodes = originalData.nodes();
        if (nodes.length <= 1) {
            return originalData;
        }

        // Build level-order traversal
        var levelOrder = new ArrayList<ESVTNodeUnified>();
        var queue = new ArrayDeque<Integer>();
        var visited = new boolean[nodes.length];

        // Start from root
        if (nodes.length > 0) {
            queue.add(0);
            visited[0] = true;
        }

        while (!queue.isEmpty()) {
            var nodeIndex = queue.poll();
            if (nodeIndex < nodes.length) {
                var node = nodes[nodeIndex];
                levelOrder.add(node);

                // Add children to queue (node has children if childMask != 0)
                if (node.isValid() && node.getChildMask() != 0) {
                    var childPtr = node.getChildPtr();
                    var childMask = node.getChildMask();

                    for (int i = 0; i < 8; i++) {
                        if ((childMask & (1 << i)) != 0) {
                            var childIndex = childPtr + Integer.bitCount(childMask & ((1 << i) - 1));
                            if (childIndex < nodes.length && !visited[childIndex]) {
                                visited[childIndex] = true;
                                queue.add(childIndex);
                            }
                        }
                    }
                }
            }
        }

        // Add any unvisited nodes
        for (int i = 0; i < nodes.length; i++) {
            if (!visited[i]) {
                levelOrder.add(nodes[i]);
            }
        }

        return new ESVTData(
            levelOrder.toArray(new ESVTNodeUnified[0]),
            originalData.contours(),
            originalData.farPointers(),
            originalData.rootType(),
            originalData.maxDepth(),
            originalData.leafCount(),
            originalData.internalCount(),
            originalData.gridResolution(),
            originalData.leafVoxelCoords()
        );
    }

    private float calculateCacheEfficiency(ESVTNodeUnified[] nodes) {
        if (nodes.length <= NODES_PER_CACHE_LINE) {
            return 1.0f;
        }

        int cacheHits = 0;
        int currentCacheLine = -1;

        for (int i = 0; i < nodes.length; i++) {
            int cacheLine = i / NODES_PER_CACHE_LINE;
            if (cacheLine == currentCacheLine) {
                cacheHits++;
            } else {
                currentCacheLine = cacheLine;
            }
        }

        return (float) cacheHits / nodes.length;
    }

    private float calculateSpatialLocality(ESVTNodeUnified[] nodes) {
        if (nodes.length <= 1) {
            return 1.0f;
        }

        long totalDistance = 0;
        int comparisons = 0;

        for (int i = 0; i < nodes.length - 1; i++) {
            var curr = nodes[i];
            var next = nodes[i + 1];

            if (curr.isValid() && next.isValid()) {
                // Distance based on child pointer difference
                int distance = Math.abs(curr.getChildPtr() - next.getChildPtr());
                totalDistance += distance;
                comparisons++;
            }
        }

        if (comparisons == 0) return 1.0f;

        double avgDistance = (double) totalDistance / comparisons;
        double maxPossibleDistance = nodes.length;

        return (float) Math.max(0.0, 1.0 - (avgDistance / maxPossibleDistance));
    }

    private float calculateFragmentation(ESVTData data) {
        int validNodes = 0;
        int invalidNodes = 0;

        for (var node : data.nodes()) {
            if (node.isValid()) {
                validNodes++;
            } else {
                invalidNodes++;
            }
        }

        int total = validNodes + invalidNodes;
        if (total == 0) return 0.0f;

        return (float) invalidNodes / total;
    }

    private float calculateTetTypeLocality(ESVTNodeUnified[] nodes) {
        if (nodes.length <= 1) {
            return 1.0f;
        }

        int sameTypeTransitions = 0;
        int totalTransitions = 0;

        for (int i = 0; i < nodes.length - 1; i++) {
            var curr = nodes[i];
            var next = nodes[i + 1];

            if (curr.isValid() && next.isValid()) {
                totalTransitions++;
                if (curr.getTetType() == next.getTetType()) {
                    sameTypeTransitions++;
                }
            }
        }

        if (totalTransitions == 0) return 1.0f;

        return (float) sameTypeTransitions / totalTransitions;
    }

    private Map<Integer, List<ESVTNodeUnified>> groupNodesByType(ESVTNodeUnified[] nodes) {
        var grouped = new HashMap<Integer, List<ESVTNodeUnified>>();

        for (var node : nodes) {
            int type = node.isValid() ? node.getTetType() : -1;
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(node);
        }

        return grouped;
    }

    private void padToCacheLineAlignment(ESVTNodeUnified[] nodes) {
        // This is a placeholder - actual implementation would ensure
        // cache line alignment for optimal performance
        // With 8-byte nodes, 8 nodes fit per 64-byte cache line
    }
}
