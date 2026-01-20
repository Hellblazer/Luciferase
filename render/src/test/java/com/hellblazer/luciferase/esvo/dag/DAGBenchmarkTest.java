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
package com.hellblazer.luciferase.esvo.dag;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmark tests for DAG compression.
 *
 * <p>These tests measure:
 * <ul>
 * <li>Compression time for large octrees</li>
 * <li>Traversal speed comparison (DAG vs SVO)</li>
 * <li>Memory reduction and compression ratios</li>
 * <li>Hash algorithm performance</li>
 * <li>Concurrent compression throughput</li>
 * </ul>
 *
 * <p><b>Important</b>: All benchmarks are disabled in CI as they are system-dependent.
 * Run locally with {@code mvn test -Dtest=DAGBenchmarkTest} to measure performance.
 *
 * @author hal.hildebrand
 */
class DAGBenchmarkTest {

    // ==================== Compression Time Benchmarks ====================

    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "Benchmark: system-dependent performance")
    @Test
    void benchmarkCompressionTime() {
        var svo = createLargeOctree(8);  // 8 subtrees = ~40 nodes

        var start = System.nanoTime();
        var dag = DAGBuilder.from(svo).build();
        var elapsed = System.nanoTime() - start;

        // Expect compression in reasonable time
        var elapsedMs = elapsed / 1_000_000;
        assertTrue(elapsed < 5_000_000_000L,  // 5 seconds max
            "Compression took " + elapsedMs + "ms, expected < 5000ms");

        // Verify DAG was actually built
        assertNotNull(dag);
        assertTrue(dag.nodes().length > 0);
        System.out.printf("Compression time: %dms for %d nodes%n", elapsedMs, svo.getNodeCount());
    }

    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "Benchmark: performance varies with system load")
    @Test
    void benchmarkTraversalSpeedDAGvsSVO() {
        var svo = createLargeOctree(8);  // 8 subtrees
        var dag = DAGBuilder.from(svo).build();

        var svoTime = measureTraversalTime(svo, 10000);
        var dagTime = measureTraversalTime(dag, 10000);

        // DAG should be comparable (allow 50% variance for system variation)
        assertTrue(dagTime <= svoTime * 1.5f,
            "DAG traversal slower: DAG=" + dagTime + "ms, SVO=" + svoTime + "ms");

        System.out.printf("Traversal comparison: SVO=%dms, DAG=%dms (%.2fx)%n",
            svoTime, dagTime, (float) svoTime / dagTime);
    }

    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "Benchmark: memory usage system-dependent")
    @Test
    void benchmarkMemoryReduction() {
        var svo = createLargeOctree(8);  // 8 subtrees
        var dag = DAGBuilder.from(svo).build();

        var compressionRatio = dag.getCompressionRatio();
        assertTrue(compressionRatio >= 1.0f,
            "Expected compression ratio >= 1.0, got " + compressionRatio);

        var metadata = dag.getMetadata();
        var memorySavedBytes = metadata.memorySavedBytes();
        var memorySavedKB = memorySavedBytes / 1024.0;

        System.out.printf("Memory reduction: %.2fx compression, %.1f KB saved%n",
            compressionRatio, memorySavedKB);
    }

    // ==================== Hash Algorithm Benchmarks ====================

    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "Benchmark: timing sensitive")
    @Test
    void benchmarkHashingPerformance() {
        // Benchmark hashing overhead by comparing with and without validation
        var svo = createLargeOctree(8);  // 8 subtrees

        // With validation (includes hash computation)
        var start = System.nanoTime();
        var dag = DAGBuilder.from(svo)
            .withHashAlgorithm(HashAlgorithm.SHA256)
            .withValidation(true)
            .build();
        var withValidationTime = System.nanoTime() - start;

        // Without validation (still does hashing for deduplication)
        var svo2 = createLargeOctree(8);  // Need fresh copy
        var start2 = System.nanoTime();
        var dag2 = DAGBuilder.from(svo2)
            .withHashAlgorithm(HashAlgorithm.SHA256)
            .withValidation(false)
            .build();
        var withoutValidationTime = System.nanoTime() - start2;

        // Both should succeed and produce same compression
        assertNotNull(dag);
        assertNotNull(dag2);
        assertEquals(dag.getCompressionRatio(), dag2.getCompressionRatio(), 0.01f,
            "Validation flag should not affect compression ratio");

        System.out.printf("Hash performance: with validation=%dms, without=%dms (%.2f%% overhead)%n",
            withValidationTime / 1_000_000, withoutValidationTime / 1_000_000,
            100.0f * (withValidationTime - withoutValidationTime) / withoutValidationTime);
    }

    // ==================== Concurrent Compression Benchmarks ====================

    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "Benchmark: concurrent access timing")
    @Test
    void benchmarkConcurrentCompressionThroughput() throws InterruptedException {
        var svo = createLargeOctree(8);  // 8 subtrees
        var executor = Executors.newFixedThreadPool(4);
        var latch = new CountDownLatch(100);

        var start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    DAGBuilder.from(svo).build();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS),
            "Concurrent compression timed out after 30 seconds");
        var elapsed = System.nanoTime() - start;
        executor.shutdown();

        // Measure throughput
        var elapsedMs = elapsed / 1_000_000;
        var compressionsPer1000ms = (100.0 * 1000.0) / elapsedMs;
        assertTrue(compressionsPer1000ms > 0);

        System.out.printf("Concurrent throughput: 100 compressions in %dms (%.2f ops/sec)%n",
            elapsedMs, compressionsPer1000ms);
    }

    // ==================== Large Dataset Benchmarks ====================

    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "Benchmark: large dataset processing")
    @Test
    void benchmarkLargeOctreeCompression() {
        // Large octree to stress compression (8 subtrees max from our helper)
        var svo = createLargeOctree(8);

        var start = System.nanoTime();
        var dag = DAGBuilder.from(svo)
            .withCompressionStrategy(CompressionStrategy.BALANCED)
            .build();
        var elapsed = System.nanoTime() - start;

        assertNotNull(dag);
        assertTrue(dag.getCompressionRatio() >= 1.0f);

        var elapsedMs = elapsed / 1_000_000;
        System.out.printf("Large dataset: %d nodes compressed in %dms (%.2fx ratio)%n",
            svo.getNodeCount(), elapsedMs, dag.getCompressionRatio());
    }

    // ==================== Strategy Comparison Benchmarks ====================

    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "Benchmark: strategy comparison timing")
    @Test
    void benchmarkStrategyComparison() {
        var svo = createLargeOctree(8);  // 8 subtrees

        // Aggressive
        var start1 = System.nanoTime();
        var aggressive = DAGBuilder.from(svo)
            .withCompressionStrategy(CompressionStrategy.AGGRESSIVE)
            .build();
        var aggressiveTime = System.nanoTime() - start1;

        // Balanced
        var svo2 = createLargeOctree(8);
        var start2 = System.nanoTime();
        var balanced = DAGBuilder.from(svo2)
            .withCompressionStrategy(CompressionStrategy.BALANCED)
            .build();
        var balancedTime = System.nanoTime() - start2;

        // Conservative
        var svo3 = createLargeOctree(8);
        var start3 = System.nanoTime();
        var conservative = DAGBuilder.from(svo3)
            .withCompressionStrategy(CompressionStrategy.CONSERVATIVE)
            .build();
        var conservativeTime = System.nanoTime() - start3;

        System.out.printf("Strategy timing:%n");
        System.out.printf("  AGGRESSIVE:   %dms (%.2fx ratio)%n",
            aggressiveTime / 1_000_000, aggressive.getCompressionRatio());
        System.out.printf("  BALANCED:     %dms (%.2fx ratio)%n",
            balancedTime / 1_000_000, balanced.getCompressionRatio());
        System.out.printf("  CONSERVATIVE: %dms (%.2fx ratio)%n",
            conservativeTime / 1_000_000, conservative.getCompressionRatio());
    }

    // ==================== Cache Efficiency Benchmarks ====================

    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "Benchmark: cache efficiency system-dependent")
    @Test
    void benchmarkCacheEfficiency() {
        // Create octree with high duplicate rate (8 subtrees max)
        var svo = createOctreeWithMaximalDuplication(8);

        var dag = DAGBuilder.from(svo).build();

        var compressionRatio = dag.getCompressionRatio();
        var metadata = dag.getMetadata();

        // With maximal duplication, should achieve high compression
        assertTrue(compressionRatio > 1.5f,
            "Expected > 1.5x compression with high duplication, got " + compressionRatio);

        System.out.printf("Cache efficiency: %.2fx compression, %d shared subtrees%n",
            compressionRatio, metadata.sharedSubtreeCount());
    }

    // ==================== Helper Methods ====================

    /**
     * Create large octree with specified number of duplicate subtrees.
     * Uses simple pattern similar to DAGBuilderTest to ensure correct structure.
     */
    private ESVOOctreeData createLargeOctree(int subtreeCount) {
        var octree = new ESVOOctreeData(subtreeCount * 16);

        // Root with 8 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0xFF); // All 8 children
        root.setChildPtr(1);
        octree.setNode(0, root);

        var nodeIdx = 1;

        // Create subtreeCount subtrees, with duplication
        for (int i = 0; i < Math.min(8, subtreeCount); i++) {
            var subtreeRoot = new ESVONodeUnified();
            subtreeRoot.setValid(true);
            subtreeRoot.setChildMask(0x0F); // 4 children
            subtreeRoot.setChildPtr(1);
            octree.setNode(nodeIdx++, subtreeRoot);

            // Add 4 leaf children (use same pattern for duplication)
            for (int j = 0; j < 4; j++) {
                var leaf = new ESVONodeUnified(0, (i % 4)); // Pattern creates duplication
                leaf.setValid(true);
                leaf.setChildMask(0); // Leaf
                octree.setNode(nodeIdx++, leaf);
            }
        }

        return octree;
    }

    /**
     * Create octree with maximal duplication for cache efficiency testing.
     */
    private ESVOOctreeData createOctreeWithMaximalDuplication(int subtreeCount) {
        var octree = new ESVOOctreeData(subtreeCount * 16);

        // Root with 8 children
        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0xFF);
        root.setChildPtr(1);
        octree.setNode(0, root);

        var nodeIdx = 1;

        // Create identical subtrees
        for (int i = 0; i < Math.min(8, subtreeCount); i++) {
            var subtreeRoot = new ESVONodeUnified();
            subtreeRoot.setValid(true);
            subtreeRoot.setChildMask(0x0F); // 4 children
            subtreeRoot.setChildPtr(1);
            octree.setNode(nodeIdx++, subtreeRoot);

            // All leaves identical (maximum duplication)
            for (int j = 0; j < 4; j++) {
                var leaf = new ESVONodeUnified(0, 0); // All same
                leaf.setValid(true);
                leaf.setChildMask(0); // Leaf
                octree.setNode(nodeIdx++, leaf);
            }
        }

        return octree;
    }

    /**
     * Measure traversal time for SVO.
     */
    private long measureTraversalTime(ESVOOctreeData svo, int iterations) {
        var start = System.nanoTime();

        for (int iter = 0; iter < iterations; iter++) {
            // Simulate traversal: visit all nodes
            var indices = svo.getNodeIndices();
            var sum = 0;  // Prevent optimization
            for (var idx : indices) {
                var node = svo.getNode(idx);
                if (node != null) {
                    sum += node.getChildMask();
                }
            }
            // Use sum to prevent dead code elimination
            if (sum < 0) System.out.println("Impossible");
        }

        var elapsed = System.nanoTime() - start;
        return elapsed / 1_000_000; // Convert to milliseconds
    }

    /**
     * Measure traversal time for DAG.
     */
    private long measureTraversalTime(DAGOctreeData dag, int iterations) {
        var start = System.nanoTime();

        for (int iter = 0; iter < iterations; iter++) {
            // Simulate traversal: visit all nodes
            var nodes = dag.nodes();
            var sum = 0;  // Prevent optimization
            for (var node : nodes) {
                if (node != null) {
                    sum += node.getChildMask();
                }
            }
            // Use sum to prevent dead code elimination
            if (sum < 0) System.out.println("Impossible");
        }

        var elapsed = System.nanoTime() - start;
        return elapsed / 1_000_000; // Convert to milliseconds
    }
}
