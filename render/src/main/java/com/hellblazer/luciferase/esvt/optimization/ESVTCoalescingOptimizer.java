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
import com.hellblazer.luciferase.sparse.optimization.Optimizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * GPU memory coalescing optimization for ESVT tetrahedral data structures.
 *
 * <p>Analyzes and optimizes memory access patterns for maximum GPU memory throughput,
 * with specific optimizations for the 8-byte ESVT node format and tetrahedral
 * subdivision patterns (S0-S5).
 *
 * <p>Key differences from ESVO coalescing:
 * <ul>
 *   <li>8-byte nodes (vs 12-16 byte ESVO) allow 4 nodes per 32-byte transaction</li>
 *   <li>6 tetrahedron types create different access patterns than octree's 8 children</li>
 *   <li>Moller-Trumbore intersection has different memory locality needs</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTCoalescingOptimizer implements Optimizer<ESVTData> {
    private static final Logger log = LoggerFactory.getLogger(ESVTCoalescingOptimizer.class);

    private static final int WARP_SIZE = 32; // GPU warp size
    private static final int CACHE_LINE_SIZE = 128; // bytes (GPU L2 cache line)
    private static final int NODE_SIZE = ESVTNodeUnified.SIZE_BYTES; // 8 bytes
    private static final int NODES_PER_TRANSACTION = 4; // 32-byte transaction / 8-byte node

    /** Implements {@link Optimizer#optimize} by delegating to {@link #optimizeForCoalescing}. */
    @Override
    public ESVTData optimize(ESVTData input) {
        return optimizeForCoalescing(input);
    }

    /**
     * Coalescing efficiency analysis result.
     */
    public static class CoalescingProfile {
        private final float coalescingEfficiency;
        private final int totalTransactions;
        private final int optimalTransactions;
        private final float tetTypeCoherence;
        private final Map<String, Float> metrics;

        public CoalescingProfile(float coalescingEfficiency, int totalTransactions,
                                 int optimalTransactions, float tetTypeCoherence,
                                 Map<String, Float> metrics) {
            this.coalescingEfficiency = Math.max(0.0f, Math.min(1.0f, coalescingEfficiency));
            this.totalTransactions = totalTransactions;
            this.optimalTransactions = optimalTransactions;
            this.tetTypeCoherence = tetTypeCoherence;
            this.metrics = new HashMap<>(metrics);
        }

        public float getCoalescingEfficiency() { return coalescingEfficiency; }
        public int getTotalTransactions() { return totalTransactions; }
        public int getOptimalTransactions() { return optimalTransactions; }
        public float getTetTypeCoherence() { return tetTypeCoherence; }
        public Map<String, Float> getMetrics() { return Collections.unmodifiableMap(metrics); }

        public float getWastedBandwidth() {
            return totalTransactions > optimalTransactions ?
                1.0f - ((float) optimalTransactions / totalTransactions) : 0.0f;
        }
    }

    /**
     * Memory access pattern analysis.
     */
    public static class AccessPattern {
        private final int[] accessIndices;
        private final String patternType;
        private final float predictability;
        private final int[] tetTypeDistribution;

        public AccessPattern(int[] accessIndices, String patternType,
                             float predictability, int[] tetTypeDistribution) {
            this.accessIndices = Arrays.copyOf(accessIndices, accessIndices.length);
            this.patternType = patternType;
            this.predictability = predictability;
            this.tetTypeDistribution = Arrays.copyOf(tetTypeDistribution, tetTypeDistribution.length);
        }

        public int[] getAccessIndices() { return Arrays.copyOf(accessIndices, accessIndices.length); }
        public String getPatternType() { return patternType; }
        public float getPredictability() { return predictability; }
        public int[] getTetTypeDistribution() { return Arrays.copyOf(tetTypeDistribution, tetTypeDistribution.length); }
    }

    /**
     * Analyze coalescing opportunities in ESVT data.
     */
    public CoalescingProfile analyzeCoalescingOpportunities(ESVTData data) {
        var nodes = data.nodes();
        if (nodes.length == 0) {
            return new CoalescingProfile(1.0f, 0, 0, 1.0f, Map.of());
        }

        // Generate access pattern (simulated traversal)
        var accessPattern = generateTypicalAccessPattern(nodes);

        // Analyze warp-level coalescing
        var warpGroups = groupByWarps(accessPattern);
        var totalTransactions = 0;
        var optimalTransactions = 0;
        var coalescedWarps = 0;

        for (var warpAccesses : warpGroups) {
            var warpAnalysis = analyzeWarpCoalescing(warpAccesses);
            totalTransactions += warpAnalysis.actualTransactions;
            optimalTransactions += warpAnalysis.optimalTransactions;

            if (warpAnalysis.isCoalesced) {
                coalescedWarps++;
            }
        }

        // Calculate tetrahedron type coherence
        var tetTypeCoherence = calculateTetTypeCoherence(nodes, accessPattern);

        // Calculate metrics
        var metrics = new HashMap<String, Float>();
        metrics.put("accessCount", (float) accessPattern.length);
        metrics.put("nodeCount", (float) nodes.length);
        metrics.put("totalWarps", (float) warpGroups.size());
        metrics.put("coalescedWarps", (float) coalescedWarps);
        metrics.put("warpCoalescingRatio", warpGroups.size() > 0 ?
            (float) coalescedWarps / warpGroups.size() : 1.0f);
        metrics.put("averageStride", calculateAverageStride(accessPattern));
        metrics.put("nodesPerCacheLine", (float) (CACHE_LINE_SIZE / NODE_SIZE));

        var efficiency = totalTransactions > 0 ?
            (float) optimalTransactions / totalTransactions : 1.0f;

        log.debug("Coalescing analysis: efficiency={}, tetTypeCoherence={}, warps={}",
                String.format("%.2f", efficiency), String.format("%.2f", tetTypeCoherence),
                warpGroups.size());

        return new CoalescingProfile(efficiency, totalTransactions,
                                     optimalTransactions, tetTypeCoherence, metrics);
    }

    /**
     * Optimize ESVT data layout for better GPU coalescing.
     */
    public ESVTData optimizeForCoalescing(ESVTData originalData) {
        var nodes = originalData.nodes();
        if (nodes.length <= NODES_PER_TRANSACTION) {
            return originalData;
        }

        // Group nodes by tetrahedron type for better type coherence
        var nodesByType = groupNodesByTetType(nodes);

        // Reorder within each type group for sequential access
        var optimizedNodes = new ESVTNodeUnified[nodes.length];
        var indexMapping = new int[nodes.length]; // Original -> new index
        var currentIndex = 0;

        // Process types in order (S0-S5) to maximize type locality
        for (int type = 0; type < 6; type++) {
            var typeNodes = nodesByType.get(type);
            if (typeNodes != null && !typeNodes.isEmpty()) {
                // Sort by child pointer for spatial locality
                typeNodes.sort(Comparator.comparingInt(entry -> entry.node.getChildPtr()));

                for (var entry : typeNodes) {
                    optimizedNodes[currentIndex] = entry.node;
                    indexMapping[entry.originalIndex] = currentIndex;
                    currentIndex++;
                }
            }
        }

        // Handle invalid nodes
        var invalidNodes = nodesByType.get(-1);
        if (invalidNodes != null) {
            for (var entry : invalidNodes) {
                if (currentIndex < optimizedNodes.length) {
                    optimizedNodes[currentIndex] = entry.node;
                    indexMapping[entry.originalIndex] = currentIndex;
                    currentIndex++;
                }
            }
        }

        // Pad to cache line boundaries where beneficial
        alignToCacheLines(optimizedNodes);

        log.debug("Optimized for coalescing: {} nodes reorganized by tetrahedron type",
                nodes.length);

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
     * Analyze access pattern for coalescing characteristics.
     */
    public AccessPattern analyzeAccessPattern(int[] accessIndices, ESVTData data) {
        if (accessIndices.length == 0) {
            return new AccessPattern(accessIndices, "empty", 1.0f, new int[6]);
        }

        var nodes = data.nodes();
        var tetTypeDistribution = new int[6];

        // Count accesses by tetrahedron type
        for (int index : accessIndices) {
            if (index >= 0 && index < nodes.length) {
                var node = nodes[index];
                if (node.isValid()) {
                    var type = node.getTetType();
                    if (type >= 0 && type < 6) {
                        tetTypeDistribution[type]++;
                    }
                }
            }
        }

        // Classify pattern
        var patternType = classifyAccessPattern(accessIndices);
        var predictability = calculatePredictability(accessIndices);

        return new AccessPattern(accessIndices, patternType, predictability, tetTypeDistribution);
    }

    /**
     * Estimate bandwidth improvement from coalescing optimization.
     */
    public Map<String, Float> estimateBandwidthImprovement(ESVTData originalData) {
        var improvement = new HashMap<String, Float>();

        var originalProfile = analyzeCoalescingOpportunities(originalData);
        var optimizedData = optimizeForCoalescing(originalData);
        var optimizedProfile = analyzeCoalescingOpportunities(optimizedData);

        var efficiencyGain = optimizedProfile.getCoalescingEfficiency() -
                             originalProfile.getCoalescingEfficiency();
        var bandwidthReduction = originalProfile.getWastedBandwidth() -
                                 optimizedProfile.getWastedBandwidth();

        improvement.put("originalEfficiency", originalProfile.getCoalescingEfficiency());
        improvement.put("optimizedEfficiency", optimizedProfile.getCoalescingEfficiency());
        improvement.put("efficiencyGain", efficiencyGain);
        improvement.put("bandwidthReduction", bandwidthReduction);
        improvement.put("tetTypeCoherenceGain",
            optimizedProfile.getTetTypeCoherence() - originalProfile.getTetTypeCoherence());

        return improvement;
    }

    // Private helper classes and methods

    private static class NodeEntry {
        final ESVTNodeUnified node;
        final int originalIndex;

        NodeEntry(ESVTNodeUnified node, int originalIndex) {
            this.node = node;
            this.originalIndex = originalIndex;
        }
    }

    private static class WarpCoalescingAnalysis {
        final boolean isCoalesced;
        final int actualTransactions;
        final int optimalTransactions;

        WarpCoalescingAnalysis(boolean isCoalesced, int actualTransactions, int optimalTransactions) {
            this.isCoalesced = isCoalesced;
            this.actualTransactions = actualTransactions;
            this.optimalTransactions = optimalTransactions;
        }
    }

    private int[] generateTypicalAccessPattern(ESVTNodeUnified[] nodes) {
        // Simulate typical traversal pattern
        var pattern = new ArrayList<Integer>();
        var visited = new boolean[nodes.length];
        var queue = new ArrayDeque<Integer>();

        if (nodes.length > 0) {
            queue.add(0);
            visited[0] = true;
        }

        while (!queue.isEmpty() && pattern.size() < nodes.length) {
            var nodeIndex = queue.poll();
            pattern.add(nodeIndex);

            if (nodeIndex < nodes.length) {
                var node = nodes[nodeIndex];
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

        return pattern.stream().mapToInt(Integer::intValue).toArray();
    }

    private List<int[]> groupByWarps(int[] accessPattern) {
        var warpGroups = new ArrayList<int[]>();

        for (int i = 0; i < accessPattern.length; i += WARP_SIZE) {
            var endIndex = Math.min(i + WARP_SIZE, accessPattern.length);
            var warpAccesses = Arrays.copyOfRange(accessPattern, i, endIndex);
            warpGroups.add(warpAccesses);
        }

        return warpGroups;
    }

    private WarpCoalescingAnalysis analyzeWarpCoalescing(int[] warpAccesses) {
        if (warpAccesses.length == 0) {
            return new WarpCoalescingAnalysis(true, 0, 0);
        }

        // Calculate memory addresses (8 bytes per node)
        var addresses = new long[warpAccesses.length];
        for (int i = 0; i < warpAccesses.length; i++) {
            addresses[i] = (long) warpAccesses[i] * NODE_SIZE;
        }

        // Find address range
        var minAddr = Arrays.stream(addresses).min().orElse(0L);
        var maxAddr = Arrays.stream(addresses).max().orElse(0L);
        var addressRange = maxAddr - minAddr + NODE_SIZE;

        // Calculate cache lines spanned
        var firstCacheLine = minAddr / CACHE_LINE_SIZE;
        var lastCacheLine = maxAddr / CACHE_LINE_SIZE;
        var cacheLineSpan = (int) (lastCacheLine - firstCacheLine + 1);

        // Optimal transactions: sequential access within minimal cache lines
        var optimalTransactions = Math.max(1, (int) ((addressRange + CACHE_LINE_SIZE - 1) / CACHE_LINE_SIZE));

        // Actual transactions based on cache line distribution
        var cacheLineAccesses = new HashSet<Long>();
        for (var addr : addresses) {
            cacheLineAccesses.add(addr / CACHE_LINE_SIZE);
        }
        var actualTransactions = cacheLineAccesses.size();

        // Consider coalesced if mostly sequential
        var isCoalesced = isSequentialAccess(warpAccesses) || (cacheLineSpan <= 2);

        return new WarpCoalescingAnalysis(isCoalesced, actualTransactions, optimalTransactions);
    }

    private float calculateTetTypeCoherence(ESVTNodeUnified[] nodes, int[] accessPattern) {
        if (accessPattern.length < 2) {
            return 1.0f;
        }

        var sameTypeTransitions = 0;
        var totalTransitions = 0;

        for (int i = 0; i < accessPattern.length - 1; i++) {
            var currIndex = accessPattern[i];
            var nextIndex = accessPattern[i + 1];

            if (currIndex >= 0 && currIndex < nodes.length &&
                nextIndex >= 0 && nextIndex < nodes.length) {
                var curr = nodes[currIndex];
                var next = nodes[nextIndex];

                if (curr.isValid() && next.isValid()) {
                    totalTransitions++;
                    if (curr.getTetType() == next.getTetType()) {
                        sameTypeTransitions++;
                    }
                }
            }
        }

        return totalTransitions > 0 ? (float) sameTypeTransitions / totalTransitions : 1.0f;
    }

    private Map<Integer, List<NodeEntry>> groupNodesByTetType(ESVTNodeUnified[] nodes) {
        var grouped = new HashMap<Integer, List<NodeEntry>>();

        for (int i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            int type = node.isValid() ? node.getTetType() : -1;
            grouped.computeIfAbsent(type, k -> new ArrayList<>())
                   .add(new NodeEntry(node, i));
        }

        return grouped;
    }

    private boolean isSequentialAccess(int[] accesses) {
        if (accesses.length <= 1) return true;

        var sortedAccesses = Arrays.stream(accesses).sorted().toArray();
        var sequentialCount = 0;

        for (int i = 1; i < sortedAccesses.length; i++) {
            if (sortedAccesses[i] - sortedAccesses[i-1] <= 2) {
                sequentialCount++;
            }
        }

        return (float) sequentialCount / (sortedAccesses.length - 1) > 0.8f;
    }

    private String classifyAccessPattern(int[] accessIndices) {
        if (accessIndices.length <= 1) return "single";

        if (isSequentialAccess(accessIndices)) {
            return "sequential";
        }

        // Check for strided pattern
        var sortedAccesses = Arrays.stream(accessIndices).sorted().toArray();
        if (isStridedAccess(sortedAccesses)) {
            return "strided";
        }

        // Check for random pattern
        var uniqueAccesses = Arrays.stream(accessIndices).distinct().count();
        var spreadFactor = (sortedAccesses[sortedAccesses.length - 1] - sortedAccesses[0]) /
                          (double) uniqueAccesses;

        return spreadFactor > 10.0 ? "random" : "clustered";
    }

    private boolean isStridedAccess(int[] sortedAccesses) {
        if (sortedAccesses.length < 3) return false;

        var strides = new int[sortedAccesses.length - 1];
        for (int i = 1; i < sortedAccesses.length; i++) {
            strides[i-1] = sortedAccesses[i] - sortedAccesses[i-1];
        }

        var firstStride = strides[0];
        var consistentStrides = 0;

        for (var stride : strides) {
            if (Math.abs(stride - firstStride) <= 1) {
                consistentStrides++;
            }
        }

        return (float) consistentStrides / strides.length > 0.7f;
    }

    private float calculatePredictability(int[] accessIndices) {
        if (accessIndices.length <= 1) return 1.0f;

        var accessCounts = new HashMap<Integer, Integer>();
        for (int access : accessIndices) {
            accessCounts.merge(access, 1, Integer::sum);
        }

        var entropy = 0.0;
        var totalAccesses = accessIndices.length;

        for (var count : accessCounts.values()) {
            var probability = (double) count / totalAccesses;
            if (probability > 0) {
                entropy -= probability * Math.log(probability) / Math.log(2);
            }
        }

        var maxEntropy = Math.log(accessCounts.size()) / Math.log(2);
        return maxEntropy > 0 ? (float) (1.0 - entropy / maxEntropy) : 1.0f;
    }

    private float calculateAverageStride(int[] accessPattern) {
        if (accessPattern.length <= 1) return 0.0f;

        var totalStride = 0L;
        for (int i = 1; i < accessPattern.length; i++) {
            totalStride += Math.abs(accessPattern[i] - accessPattern[i-1]);
        }

        return (float) totalStride / (accessPattern.length - 1);
    }

    private void alignToCacheLines(ESVTNodeUnified[] nodes) {
        // Cache line alignment is handled implicitly through type grouping
        // With 8-byte nodes, 16 nodes fit per 128-byte cache line
    }
}
