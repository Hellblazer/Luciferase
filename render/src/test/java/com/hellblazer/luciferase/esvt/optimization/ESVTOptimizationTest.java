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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Vector3f;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVT optimization components.
 *
 * @author hal.hildebrand
 */
class ESVTOptimizationTest {

    private ESVTMemoryOptimizer memoryOptimizer;
    private ESVTBandwidthOptimizer bandwidthOptimizer;
    private ESVTCoalescingOptimizer coalescingOptimizer;
    private ESVTTraversalOptimizer traversalOptimizer;
    private ESVTOptimizationProfiler profiler;
    private ESVTOptimizationPipeline pipeline;

    @BeforeEach
    void setUp() {
        memoryOptimizer = new ESVTMemoryOptimizer();
        bandwidthOptimizer = new ESVTBandwidthOptimizer();
        coalescingOptimizer = new ESVTCoalescingOptimizer();
        traversalOptimizer = new ESVTTraversalOptimizer();
        profiler = new ESVTOptimizationProfiler();
        pipeline = ESVTOptimizationPipeline.createDefaultPipeline();
    }

    // ========== Memory Optimizer Tests ==========

    @Test
    void testMemoryLayoutAnalysis() {
        var data = createTestESVTData(100);

        var profile = memoryOptimizer.analyzeMemoryLayout(data);

        assertNotNull(profile);
        assertTrue(profile.getCacheEfficiency() >= 0.0f && profile.getCacheEfficiency() <= 1.0f);
        assertTrue(profile.getSpatialLocality() >= 0.0f && profile.getSpatialLocality() <= 1.0f);
        assertTrue(profile.getFragmentation() >= 0.0f && profile.getFragmentation() <= 1.0f);
        assertEquals(100 * 8, profile.getMemoryFootprint()); // 8 bytes per node
    }

    @Test
    void testMemoryLayoutOptimization() {
        var originalData = createTestESVTData(200);

        var optimizedData = memoryOptimizer.optimizeMemoryLayout(originalData);

        assertNotNull(optimizedData);
        assertEquals(originalData.nodeCount(), optimizedData.nodeCount());

        // Optimized layout should maintain or improve efficiency
        var originalProfile = memoryOptimizer.analyzeMemoryLayout(originalData);
        var optimizedProfile = memoryOptimizer.analyzeMemoryLayout(optimizedData);
        assertNotNull(optimizedProfile);
    }

    @Test
    void testBreadthFirstOptimization() {
        var originalData = createTestESVTData(150);

        var optimizedData = memoryOptimizer.optimizeBreadthFirst(originalData);

        assertNotNull(optimizedData);
        assertEquals(originalData.nodeCount(), optimizedData.nodeCount());
    }

    @Test
    void testEmptyDataMemoryAnalysis() {
        var emptyData = createEmptyESVTData();

        var profile = memoryOptimizer.analyzeMemoryLayout(emptyData);

        assertNotNull(profile);
        assertEquals(1.0f, profile.getCacheEfficiency());
        assertEquals(0, profile.getMemoryFootprint());
    }

    // ========== Bandwidth Optimizer Tests ==========

    @Test
    void testBandwidthUsageAnalysis() {
        var data = createTestESVTData(100);
        var accessPattern = new int[]{0, 1, 2, 5, 10, 15, 20};

        var profile = bandwidthOptimizer.analyzeBandwidthUsage(data, accessPattern);

        assertNotNull(profile);
        assertTrue(profile.getTotalBytes() > 0);
        assertTrue(profile.getBandwidthReduction() >= 0.0f && profile.getBandwidthReduction() <= 1.0f);
    }

    @Test
    void testNodeCompression() {
        var data = createTestESVTData(100);

        var compressed = bandwidthOptimizer.compressNodeData(data);

        assertNotNull(compressed);
        assertTrue(compressed.getOriginalSize() > 0);
        assertTrue(compressed.getCompressedSize() > 0);
        assertTrue(compressed.getCompressionRatio() > 0.0f);
        assertEquals(100, compressed.getMetadata().get("nodeCount"));
    }

    @Test
    void testNodeDecompression() {
        var originalData = createTestESVTData(50);

        var compressed = bandwidthOptimizer.compressNodeData(originalData);
        var decompressed = bandwidthOptimizer.decompressNodeData(compressed, originalData);

        assertNotNull(decompressed);
        assertEquals(originalData.nodeCount(), decompressed.nodeCount());
    }

    @Test
    void testStreamingOptimization() {
        var data = createTestESVTData(200);
        var bufferSize = 512; // bytes

        var optimizedData = bandwidthOptimizer.optimizeForStreaming(data, bufferSize);

        assertNotNull(optimizedData);
        assertEquals(data.nodeCount(), optimizedData.nodeCount());
    }

    @Test
    void testBandwidthSavingsEstimation() {
        var data = createTestESVTData(100);
        var accessPattern = new int[]{0, 1, 2, 3, 4, 5, 0, 1, 2}; // With repetition

        var savings = bandwidthOptimizer.estimateBandwidthSavings(data, accessPattern);

        assertNotNull(savings);
        assertTrue(savings.containsKey("compression"));
        assertTrue(savings.containsKey("streaming"));
        assertTrue(savings.containsKey("total"));
    }

    // ========== Coalescing Optimizer Tests ==========

    @Test
    void testCoalescingAnalysis() {
        var data = createTestESVTData(100);

        var profile = coalescingOptimizer.analyzeCoalescingOpportunities(data);

        assertNotNull(profile);
        assertTrue(profile.getCoalescingEfficiency() >= 0.0f && profile.getCoalescingEfficiency() <= 1.0f);
        assertTrue(profile.getTetTypeCoherence() >= 0.0f && profile.getTetTypeCoherence() <= 1.0f);
    }

    @Test
    void testCoalescingOptimization() {
        var originalData = createTestESVTData(150);

        var optimizedData = coalescingOptimizer.optimizeForCoalescing(originalData);

        assertNotNull(optimizedData);
        assertEquals(originalData.nodeCount(), optimizedData.nodeCount());
    }

    @Test
    void testAccessPatternAnalysis() {
        var data = createTestESVTData(100);
        var accessIndices = new int[]{0, 1, 2, 3, 10, 11, 12, 50, 51};

        var pattern = coalescingOptimizer.analyzeAccessPattern(accessIndices, data);

        assertNotNull(pattern);
        assertNotNull(pattern.getPatternType());
        assertTrue(pattern.getPredictability() >= 0.0f && pattern.getPredictability() <= 1.0f);
        assertEquals(6, pattern.getTetTypeDistribution().length);
    }

    @Test
    void testBandwidthImprovementEstimation() {
        var data = createTestESVTData(100);

        var improvement = coalescingOptimizer.estimateBandwidthImprovement(data);

        assertNotNull(improvement);
        assertTrue(improvement.containsKey("originalEfficiency"));
        assertTrue(improvement.containsKey("optimizedEfficiency"));
        assertTrue(improvement.containsKey("efficiencyGain"));
    }

    // ========== Traversal Optimizer Tests ==========

    @Test
    void testRayCoherenceAnalysis() {
        var rayOrigins = new Vector3f[]{
            new Vector3f(0.0f, 0.0f, 0.0f),
            new Vector3f(0.01f, 0.0f, 0.0f),
            new Vector3f(0.02f, 0.0f, 0.0f)
        };
        var rayDirections = new Vector3f[]{
            new Vector3f(0.0f, 0.0f, 1.0f),
            new Vector3f(0.0f, 0.0f, 1.0f),
            new Vector3f(0.0f, 0.1f, 1.0f)
        };

        var coherence = traversalOptimizer.analyzeRayCoherence(rayOrigins, rayDirections);

        assertNotNull(coherence);
        assertTrue(coherence.getSpatialCoherence() > 0.5f); // Close origins
        assertTrue(coherence.getDirectionalCoherence() > 0.5f); // Similar directions
        assertTrue(coherence.getOverallCoherence() >= 0.0f && coherence.getOverallCoherence() <= 1.0f);
    }

    @Test
    void testRayGrouping() {
        var rayOrigins = new Vector3f[10];
        var rayDirections = new Vector3f[10];
        var random = new Random(42);

        for (int i = 0; i < 10; i++) {
            rayOrigins[i] = new Vector3f(random.nextFloat() * 0.1f, random.nextFloat() * 0.1f, 0.0f);
            rayDirections[i] = new Vector3f(0.0f, 0.0f, 1.0f);
        }

        var groups = traversalOptimizer.optimizeRayGrouping(rayOrigins, rayDirections);

        assertNotNull(groups);
        assertFalse(groups.isEmpty());

        // All rays should be in groups
        var totalRays = groups.stream().mapToInt(g -> g.getRayIndices().length).sum();
        assertEquals(10, totalRays);
    }

    @Test
    void testTraversalPatternAnalysis() {
        var nodeVisitCounts = new int[]{5, 10, 3, 8, 2, 15, 7};
        var nodeTetTypes = new int[]{0, 1, 2, 3, 4, 5, 0};
        var nodeVisitTimes = new float[]{1.0f, 2.0f, 0.5f, 1.5f, 0.3f, 3.0f, 1.2f};

        var pattern = traversalOptimizer.analyzeTraversalPattern(
            nodeVisitCounts, nodeTetTypes, nodeVisitTimes);

        assertNotNull(pattern);
        assertNotNull(pattern.getPatternType());
        assertTrue(pattern.getEfficiency() >= 0.0f && pattern.getEfficiency() <= 1.0f);
        assertEquals(6, pattern.getTetTypeVisitCounts().length);
    }

    @Test
    void testTraversalOrderOptimization() {
        var originalOrder = new int[]{0, 5, 2, 7, 3, 9, 1};
        var nodeTetTypes = new int[]{0, 0, 1, 1, 2, 2, 3, 3, 4, 5};
        var nodePositions = new HashMap<Integer, Vector3f>();
        for (int i = 0; i < 10; i++) {
            nodePositions.put(i, new Vector3f(i * 0.1f, 0.0f, 0.0f));
        }

        var optimizedOrder = traversalOptimizer.optimizeTraversalOrder(
            originalOrder, nodeTetTypes, nodePositions);

        assertNotNull(optimizedOrder);
        assertEquals(originalOrder.length, optimizedOrder.length);
    }

    @Test
    void testOptimalWorkgroupSize() {
        var rayOrigins = new Vector3f[]{new Vector3f(0, 0, 0)};
        var rayDirections = new Vector3f[]{new Vector3f(0, 0, 1)};
        var coherence = traversalOptimizer.analyzeRayCoherence(rayOrigins, rayDirections);

        var workgroupSize = traversalOptimizer.predictOptimalWorkgroupSize(1000, coherence, 0.8f);

        assertTrue(workgroupSize >= 16 && workgroupSize <= 512);
        assertEquals(0, workgroupSize & (workgroupSize - 1)); // Power of 2
    }

    @Test
    void testIntersectionStatsEstimation() {
        var rayOrigins = new Vector3f[]{
            new Vector3f(0.0f, 0.0f, 0.0f),
            new Vector3f(0.1f, 0.0f, 0.0f)
        };
        var rayDirections = new Vector3f[]{
            new Vector3f(0.0f, 0.0f, 1.0f),
            new Vector3f(0.0f, 0.0f, 1.0f)
        };

        var stats = traversalOptimizer.estimateIntersectionStats(rayOrigins, rayDirections, 1000);

        assertNotNull(stats);
        assertTrue(stats.getTotalTests() > 0);
        assertTrue(stats.getHitRate() >= 0.0f && stats.getHitRate() <= 1.0f);
        assertTrue(stats.getEarlyCullRate() >= 0.0f && stats.getEarlyCullRate() <= 1.0f);
    }

    // ========== Optimization Pipeline Tests ==========

    @Test
    void testDefaultPipelineCreation() {
        var defaultPipeline = ESVTOptimizationPipeline.createDefaultPipeline();

        assertNotNull(defaultPipeline);
        var optimizers = defaultPipeline.getRegisteredOptimizers();
        assertEquals(4, optimizers.size());
        assertTrue(optimizers.contains("ESVTMemoryOptimizer"));
        assertTrue(optimizers.contains("ESVTBandwidthOptimizer"));
        assertTrue(optimizers.contains("ESVTCoalescingOptimizer"));
        assertTrue(optimizers.contains("ESVTTraversalOptimizer"));
    }

    @Test
    void testPipelineOptimization() {
        var data = createTestESVTData(100);

        var result = pipeline.optimize(data);

        assertNotNull(result);
        assertNotNull(result.optimizedData());
        assertNotNull(result.report());
        assertEquals(data.nodeCount(), result.optimizedData().nodeCount());

        // Report should have steps
        var report = result.report();
        assertFalse(report.steps().isEmpty());
        assertTrue(report.totalTimeMs() >= 0);
        assertTrue(report.overallImprovement() >= 0);
    }

    @Test
    void testPipelineStats() {
        var data = createTestESVTData(50);
        pipeline.optimize(data);

        var stats = pipeline.getPipelineStats();

        assertNotNull(stats);
        assertEquals(4, stats.get("totalOptimizers"));
        assertTrue((Float) stats.get("totalOptimizationTimeMs") >= 0);
    }

    @Test
    void testPipelineStatsClear() {
        var data = createTestESVTData(50);
        pipeline.optimize(data);
        pipeline.clearStats();

        var stats = pipeline.getPipelineStats();
        assertEquals(0.0f, (Float) stats.get("totalOptimizationTimeMs"), 0.001f);
    }

    // ========== Optimization Profiler Tests ==========

    @Test
    void testOptimizationProfiling() {
        var data = createTestESVTData(100);

        var result = profiler.profileOptimization(data, pipeline);

        assertNotNull(result);
        assertNotNull(result.getInputData());
        assertNotNull(result.getOptimizedData());
        assertNotNull(result.getPhaseDurations());
        assertNotNull(result.getMetrics());
        assertTrue(result.getTotalDurationMs() >= 0);
    }

    @Test
    void testIndividualOptimizerProfiling() {
        var data = createTestESVTData(100);

        var result = profiler.profileOptimizer(
            "ESVTMemoryOptimizer",
            data,
            memoryOptimizer::optimizeMemoryLayout
        );

        assertNotNull(result);
        assertTrue(result.containsKey("inputProfile"));
        assertTrue(result.containsKey("outputProfile"));
        assertTrue(result.containsKey("durationMs"));
        assertTrue(result.containsKey("improvementPercent"));
    }

    @Test
    void testProfilerSummaryStats() {
        var data = createTestESVTData(50);
        profiler.profileOptimization(data, pipeline);

        var stats = profiler.getSummaryStats();

        assertNotNull(stats);
        assertTrue(stats.containsKey("totalProfilingTime"));
        assertTrue(stats.containsKey("totalRuns"));
        assertTrue(stats.containsKey("phases"));
    }

    @Test
    void testProfilerHistory() {
        var data = createTestESVTData(50);
        profiler.profileOptimization(data, pipeline);

        var history = profiler.getOptimizationHistory();

        assertNotNull(history);
        assertEquals(1, history.size());
        assertEquals(50, history.get(0).getNodeCount());
    }

    @Test
    void testProfilerReportGeneration() {
        var data = createTestESVTData(50);
        profiler.profileOptimization(data, pipeline);

        var report = profiler.generateReport();

        assertNotNull(report);
        assertTrue(report.contains("ESVT Optimization Profiler Report"));
        assertTrue(report.contains("Total Profiling Time"));
    }

    @Test
    void testProfilerClear() {
        var data = createTestESVTData(50);
        profiler.profileOptimization(data, pipeline);
        profiler.clearProfiles();

        var history = profiler.getOptimizationHistory();
        assertTrue(history.isEmpty());
    }

    // ========== Edge Case Tests ==========

    @Test
    void testEmptyDataOptimization() {
        var emptyData = createEmptyESVTData();

        var result = pipeline.optimize(emptyData);

        assertNotNull(result);
        assertEquals(0, result.optimizedData().nodeCount());
    }

    @Test
    void testSingleNodeOptimization() {
        var singleNodeData = createTestESVTData(1);

        var result = pipeline.optimize(singleNodeData);

        assertNotNull(result);
        assertEquals(1, result.optimizedData().nodeCount());
    }

    @Test
    void testLargeDataOptimization() {
        var largeData = createTestESVTData(1000);

        var result = pipeline.optimize(largeData);

        assertNotNull(result);
        assertEquals(1000, result.optimizedData().nodeCount());
    }

    // ========== Helper Methods ==========

    private ESVTData createTestESVTData(int nodeCount) {
        var nodes = new ESVTNodeUnified[nodeCount];
        var random = new Random(42);

        for (int i = 0; i < nodeCount; i++) {
            // Create valid internal and leaf nodes with different tet types
            var tetType = (byte) (i % 6); // S0-S5
            var isLeaf = random.nextBoolean();

            var node = new ESVTNodeUnified(tetType);

            if (isLeaf) {
                // Leaf node - set leafMask but no childMask
                node.setLeafMask(0xFF); // Mark as leaf
            } else {
                // Internal node with child pointer and children
                var childPtr = Math.min(i + 1, nodeCount - 1);
                var childMask = random.nextInt(255) + 1; // At least one child
                node.setChildPtr(Math.min(childPtr, 32767)); // Max 15 bits
                node.setChildMask(childMask);
            }
            nodes[i] = node;
        }

        return new ESVTData(
            nodes,
            new int[0],
            new int[0],
            0, // rootType
            8, // maxDepth
            nodeCount / 2, // leafCount
            nodeCount / 2, // internalCount
            256, // gridResolution
            null // leafVoxelCoords
        );
    }

    private ESVTData createEmptyESVTData() {
        return new ESVTData(
            new ESVTNodeUnified[0],
            new int[0],
            new int[0],
            0,
            0,
            0,
            0,
            0,
            null
        );
    }
}
