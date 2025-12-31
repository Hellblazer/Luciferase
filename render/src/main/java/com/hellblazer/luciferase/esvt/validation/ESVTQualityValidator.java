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
package com.hellblazer.luciferase.esvt.validation;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.esvt.traversal.ESVTTraversal;
import com.hellblazer.luciferase.esvt.traversal.ESVTRay;
import com.hellblazer.luciferase.esvt.traversal.ESVTResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;

/**
 * Quality validation and metrics for ESVT traversal results.
 *
 * <p>Provides metrics including:
 * <ul>
 *   <li>Hit rate analysis</li>
 *   <li>Depth distribution statistics</li>
 *   <li>Memory efficiency metrics</li>
 *   <li>Traversal consistency validation</li>
 *   <li>Coverage analysis</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTQualityValidator {
    private static final Logger log = LoggerFactory.getLogger(ESVTQualityValidator.class);

    /**
     * Quality report containing detailed metrics.
     */
    public record QualityReport(
        int totalRays,
        int hits,
        int misses,
        double hitRate,
        double avgTraversalDepth,
        double maxTraversalDepth,
        double minTraversalDepth,
        double depthStdDev,
        double memoryEfficiency,
        double nodeUtilization,
        double coverageRatio,
        List<String> warnings
    ) {
        public boolean meetsThreshold(QualityThresholds thresholds) {
            return hitRate >= thresholds.minHitRate &&
                   memoryEfficiency >= thresholds.minMemoryEfficiency &&
                   nodeUtilization >= thresholds.minNodeUtilization;
        }
    }

    /**
     * Configurable quality thresholds.
     */
    public record QualityThresholds(
        double minHitRate,
        double minMemoryEfficiency,
        double minNodeUtilization,
        double maxAvgDepth,
        double maxDepthStdDev
    ) {
        public static QualityThresholds defaultThresholds() {
            return new QualityThresholds(0.0, 0.5, 0.3, 20.0, 5.0);
        }

        public static QualityThresholds strictThresholds() {
            return new QualityThresholds(0.1, 0.7, 0.5, 15.0, 3.0);
        }
    }

    /**
     * Depth histogram for analysis.
     */
    public record DepthHistogram(int[] counts, int maxDepth) {
        public double percentile(double p) {
            int total = Arrays.stream(counts).sum();
            if (total == 0) return 0;
            int target = (int) (total * p / 100.0);
            int cumulative = 0;
            for (int i = 0; i < counts.length; i++) {
                cumulative += counts[i];
                if (cumulative >= target) {
                    return i;
                }
            }
            return maxDepth;
        }
    }

    private final QualityThresholds thresholds;
    private final ESVTTraversal traversal;

    public ESVTQualityValidator() {
        this(QualityThresholds.defaultThresholds());
    }

    public ESVTQualityValidator(QualityThresholds thresholds) {
        this.thresholds = thresholds;
        this.traversal = new ESVTTraversal();
    }

    /**
     * Analyze ESVT data quality with random ray tests.
     *
     * @param data The ESVT data to analyze
     * @param numRays Number of random rays to cast
     * @param seed Random seed for reproducibility
     * @return Quality report with metrics
     */
    public QualityReport analyze(ESVTData data, int numRays, long seed) {
        var warnings = new ArrayList<String>();

        if (data == null || data.nodes() == null || data.nodeCount() == 0) {
            return new QualityReport(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                List.of("Empty or null ESVT data"));
        }

        var random = new Random(seed);
        var depthSamples = new ArrayList<Double>();
        int hits = 0;
        int misses = 0;

        // Generate and cast rays
        for (int i = 0; i < numRays; i++) {
            var ray = generateRandomRay(random);
            var result = traversal.castRay(ray, data.nodes(), data.contours(), data.farPointers(), 0);

            if (result.hit) {
                hits++;
                depthSamples.add((double) result.scale);
            } else {
                misses++;
            }
        }

        // Calculate statistics
        double hitRate = numRays > 0 ? (double) hits / numRays : 0.0;
        double avgDepth = calculateMean(depthSamples);
        double maxDepth = depthSamples.isEmpty() ? 0.0 : Collections.max(depthSamples);
        double minDepth = depthSamples.isEmpty() ? 0.0 : Collections.min(depthSamples);
        double stdDev = calculateStdDev(depthSamples, avgDepth);

        // Memory efficiency: ratio of leaves to total nodes
        double memoryEfficiency = data.nodeCount() > 0 ?
            (double) data.leafCount() / data.nodeCount() : 0.0;

        // Node utilization: average child count per internal node
        double nodeUtilization = calculateNodeUtilization(data);

        // Coverage: estimate based on hit distribution
        double coverage = estimateCoverage(data, numRays, seed);

        // Add warnings for threshold violations
        if (hitRate < thresholds.minHitRate) {
            warnings.add(String.format("Hit rate %.2f%% below threshold %.2f%%",
                hitRate * 100, thresholds.minHitRate * 100));
        }
        if (avgDepth > thresholds.maxAvgDepth) {
            warnings.add(String.format("Average depth %.2f exceeds threshold %.2f",
                avgDepth, thresholds.maxAvgDepth));
        }
        if (stdDev > thresholds.maxDepthStdDev) {
            warnings.add(String.format("Depth std dev %.2f exceeds threshold %.2f",
                stdDev, thresholds.maxDepthStdDev));
        }

        return new QualityReport(numRays, hits, misses, hitRate, avgDepth, maxDepth,
            minDepth, stdDev, memoryEfficiency, nodeUtilization, coverage, warnings);
    }

    /**
     * Analyze traversal depth distribution.
     */
    public DepthHistogram analyzeDepthDistribution(ESVTData data, int numRays, long seed) {
        if (data == null || data.nodeCount() == 0) {
            return new DepthHistogram(new int[1], 0);
        }

        int maxDepth = data.maxDepth() + 1;
        int[] counts = new int[maxDepth + 1];
        var random = new Random(seed);

        for (int i = 0; i < numRays; i++) {
            var ray = generateRandomRay(random);
            var result = traversal.castRay(ray, data.nodes(), data.contours(), data.farPointers(), 0);

            if (result.hit) {
                int depth = Math.min(result.scale, maxDepth);
                counts[depth]++;
            }
        }

        return new DepthHistogram(counts, maxDepth);
    }

    /**
     * Validate traversal consistency by casting same ray multiple times.
     */
    public boolean validateConsistency(ESVTData data, int numRays, long seed) {
        if (data == null || data.nodeCount() == 0) {
            return true; // Empty tree is consistent
        }

        var random = new Random(seed);

        for (int i = 0; i < numRays; i++) {
            var ray = generateRandomRay(random);

            // Cast same ray twice
            var result1 = traversal.castRay(ray, data.nodes(), data.contours(), data.farPointers(), 0);
            var result2 = traversal.castRay(ray, data.nodes(), data.contours(), data.farPointers(), 0);

            // Results should be identical
            if (result1.hit != result2.hit ||
                (result1.hit && !resultsEqual(result1, result2))) {
                log.warn("Inconsistent traversal result for ray {}", i);
                return false;
            }
        }

        return true;
    }

    /**
     * Calculate memory efficiency breakdown.
     */
    public record MemoryBreakdown(
        long nodeBytes,
        long contourBytes,
        long farPointerBytes,
        long totalBytes,
        double bytesPerNode,
        double bytesPerLeaf
    ) {}

    public MemoryBreakdown analyzeMemory(ESVTData data) {
        if (data == null) {
            return new MemoryBreakdown(0, 0, 0, 0, 0, 0);
        }

        long nodeBytes = (long) data.nodeCount() * ESVTNodeUnified.SIZE_BYTES;
        long contourBytes = (long) data.contourCount() * 4;
        long farPtrBytes = (long) data.farPointerCount() * 4;
        long total = nodeBytes + contourBytes + farPtrBytes;

        double perNode = data.nodeCount() > 0 ? (double) total / data.nodeCount() : 0;
        double perLeaf = data.leafCount() > 0 ? (double) total / data.leafCount() : 0;

        return new MemoryBreakdown(nodeBytes, contourBytes, farPtrBytes, total, perNode, perLeaf);
    }

    /**
     * Analyze tree balance.
     */
    public record BalanceMetrics(
        double avgChildrenPerNode,
        double leafDepthVariance,
        double branchingFactor,
        boolean isBalanced
    ) {}

    public BalanceMetrics analyzeBalance(ESVTData data) {
        if (data == null || data.nodeCount() == 0) {
            return new BalanceMetrics(0, 0, 0, true);
        }

        var nodes = data.nodes();
        var leafDepths = new ArrayList<Integer>();
        int totalChildren = 0;
        int internalCount = 0;

        // BFS to find all leaf depths
        var queue = new ArrayDeque<int[]>(); // [nodeIdx, depth]
        queue.add(new int[]{0, 0});
        var visited = new BitSet(nodes.length);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            int idx = current[0];
            int depth = current[1];

            if (idx < 0 || idx >= nodes.length || visited.get(idx)) {
                continue;
            }
            visited.set(idx);

            var node = nodes[idx];
            int childMask = node.getChildMask();

            if (childMask == 0) {
                leafDepths.add(depth);
            } else {
                internalCount++;
                int childCount = node.getChildCount();
                totalChildren += childCount;

                // Queue children
                for (int mortonIdx = 0; mortonIdx < 8; mortonIdx++) {
                    if ((childMask & (1 << mortonIdx)) != 0) {
                        int leafMask = node.getLeafMask();
                        if ((leafMask & (1 << mortonIdx)) != 0) {
                            leafDepths.add(depth + 1);
                        } else {
                            try {
                                int childIdx = node.getChildIndex(mortonIdx, idx, data.farPointers());
                                queue.add(new int[]{childIdx, depth + 1});
                            } catch (Exception e) {
                                // Skip invalid pointers
                            }
                        }
                    }
                }
            }
        }

        double avgChildren = internalCount > 0 ? (double) totalChildren / internalCount : 0;
        double leafDepthMean = calculateMean(leafDepths.stream().mapToDouble(d -> d).boxed().toList());
        double leafDepthVar = calculateVariance(leafDepths.stream().mapToDouble(d -> d).boxed().toList(), leafDepthMean);

        // Tree is balanced if leaf depth variance is low
        boolean isBalanced = leafDepthVar < 2.0;

        return new BalanceMetrics(avgChildren, leafDepthVar, avgChildren, isBalanced);
    }

    // ========== Helper Methods ==========

    private ESVTRay generateRandomRay(Random random) {
        // Generate ray origin outside unit cube
        float ox = random.nextFloat() * 2 - 1.5f;
        float oy = random.nextFloat() * 2 - 1.5f;
        float oz = random.nextFloat() * 2 - 1.5f;

        // Direction toward center
        float dx = 0.5f - ox + (random.nextFloat() - 0.5f) * 0.5f;
        float dy = 0.5f - oy + (random.nextFloat() - 0.5f) * 0.5f;
        float dz = 0.5f - oz + (random.nextFloat() - 0.5f) * 0.5f;

        // Normalize direction
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0) {
            dx /= len;
            dy /= len;
            dz /= len;
        } else {
            dx = 1.0f;
            dy = 0.0f;
            dz = 0.0f;
        }

        return new ESVTRay(new Point3f(ox, oy, oz), new Vector3f(dx, dy, dz));
    }

    private double calculateMean(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        return values.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    private double calculateStdDev(List<Double> values, double mean) {
        if (values.size() < 2) return 0.0;
        double variance = values.stream()
            .mapToDouble(d -> (d - mean) * (d - mean))
            .sum() / (values.size() - 1);
        return Math.sqrt(variance);
    }

    private double calculateVariance(List<Double> values, double mean) {
        if (values.size() < 2) return 0.0;
        return values.stream()
            .mapToDouble(d -> (d - mean) * (d - mean))
            .sum() / (values.size() - 1);
    }

    private double calculateNodeUtilization(ESVTData data) {
        var nodes = data.nodes();
        int totalChildren = 0;
        int internalCount = 0;

        for (var node : nodes) {
            if (node.getChildMask() != 0) {
                internalCount++;
                totalChildren += node.getChildCount();
            }
        }

        // Max is 8 children per internal node
        return internalCount > 0 ? (double) totalChildren / (internalCount * 8) : 0.0;
    }

    private double estimateCoverage(ESVTData data, int numRays, long seed) {
        // Grid-based coverage estimation
        int gridSize = 10;
        var hitGrid = new boolean[gridSize][gridSize][gridSize];
        var random = new Random(seed);

        for (int i = 0; i < numRays; i++) {
            var ray = generateRandomRay(random);
            var result = traversal.castRay(ray, data.nodes(), data.contours(), data.farPointers(), 0);

            if (result.hit) {
                // Estimate hit position based on ray and t
                // This is approximate since we don't have exact hit position
                int gx = Math.min(gridSize - 1, Math.max(0, (int) (ray.originX * gridSize)));
                int gy = Math.min(gridSize - 1, Math.max(0, (int) (ray.originY * gridSize)));
                int gz = Math.min(gridSize - 1, Math.max(0, (int) (ray.originZ * gridSize)));
                hitGrid[gx][gy][gz] = true;
            }
        }

        // Count covered cells
        int covered = 0;
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    if (hitGrid[x][y][z]) covered++;
                }
            }
        }

        return (double) covered / (gridSize * gridSize * gridSize);
    }

    private boolean resultsEqual(ESVTResult a, ESVTResult b) {
        return a.scale == b.scale;
    }
}
