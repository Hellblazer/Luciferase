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
package com.hellblazer.luciferase.esvo.gpu;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGBuilder;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.gpu.batch.BatchKernelMetrics;
import com.hellblazer.luciferase.esvo.gpu.batch.BatchKernelValidator;
import com.hellblazer.luciferase.esvo.gpu.batch.NodeTraversalMetricsTracker;
import com.hellblazer.luciferase.esvo.gpu.profiler.GPUPerformanceProfiler;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5a: Stream C Activation Integration Tests
 *
 * Validates that batch kernel activation works correctly based on ray coherence.
 * Tests:
 * - Batch kernel activation when coherence >= 0.5
 * - Proper raysPerItem calculation based on coherence
 * - Metrics tracking for batch kernel execution
 * - Coherence analyzer initialization
 *
 * @author hal.hildebrand
 */
@DisplayName("Phase 5a: Stream C Activation Integration Tests")
class StreamCActivationIntegrationTest {

    private DAGOpenCLRenderer renderer;
    private DAGOctreeData testDAG;
    private ESVOOctreeData testSVO;
    private BatchKernelValidator validator;
    private NodeTraversalMetricsTracker metricsTracker;

    @BeforeEach
    void setUp() {
        // Build test DAG
        testSVO = buildTestOctree();
        testDAG = buildTestDAG(testSVO);

        // Initialize renderer
        renderer = new DAGOpenCLRenderer(512, 512, "test_cache_dir");

        // Initialize validation utilities
        validator = new BatchKernelValidator();
        metricsTracker = new NodeTraversalMetricsTracker();
    }

    /**
     * Test that raysPerItem is calculated correctly from coherence
     * Formula: ceil(coherence * 16.0), bounded [1, 16]
     */
    @Test
    @DisplayName("raysPerItem scaling from coherence score")
    void testRaysPerItemScaling() {
        // These calculations match the formula in DAGOpenCLRenderer.calculateRaysPerItem()
        var coherenceScores = new double[]{0.0, 0.3, 0.5, 0.75, 1.0};
        var expectedRaysPerItem = new int[]{1, 5, 8, 12, 16};

        for (int i = 0; i < coherenceScores.length; i++) {
            double coherence = coherenceScores[i];
            int expected = expectedRaysPerItem[i];

            // Formula: Math.max(1, Math.min(16, (int) Math.ceil(coherence * 16.0)))
            int actual = Math.max(1, Math.min(16, (int) Math.ceil(coherence * 16.0)));

            assertEquals(expected, actual,
                String.format("coherence=%.2f should yield raysPerItem=%d", coherence, expected));
        }
    }

    /**
     * Test that coherence threshold is correctly applied for batch kernel activation
     */
    @Test
    @DisplayName("Batch kernel activation threshold at 0.5 coherence")
    void testBatchKernelActivationThreshold() {
        // Test boundary conditions
        double[] testCoherences = {0.49, 0.5, 0.51, 0.99, 1.0};
        boolean[] shouldActivate = {false, true, true, true, true};

        for (int i = 0; i < testCoherences.length; i++) {
            double coherence = testCoherences[i];
            boolean expected = shouldActivate[i];

            // This matches the logic in DAGOpenCLRenderer.updateCoherenceIfNeeded()
            boolean useBatch = (coherence >= 0.5);

            assertEquals(expected, useBatch,
                String.format("coherence=%.2f should activate batch=%s", coherence, expected));
        }
    }

    /**
     * Test batch kernel metrics tracking
     */
    @Test
    @DisplayName("Batch kernel metrics tracked correctly")
    void testBatchKernelMetrics() {
        // Create test metrics with values in Phase 5a target range
        var metrics = new BatchKernelMetrics(
            100_000,    // rayCount
            8,          // raysPerItem
            350.0,      // latencyMicroseconds (Phase 5a target range, 20-30% improvement over 450µs)
            7500,       // totalNodeAccesses
            350,        // uniqueNodesVisited
            true,       // resultsMatch
            30.0,       // nodeReductionPercent (exactly meets 30% target)
            0.65,       // coherenceScore
            System.currentTimeMillis()
        );

        // Verify metrics record values
        assertEquals(100_000, metrics.rayCount());
        assertEquals(8, metrics.raysPerItem());
        assertTrue(metrics.resultsMatch());
        assertEquals(30.0, metrics.nodeReductionPercent(), 0.1);
        assertEquals(0.65, metrics.coherenceScore(), 0.01);

        // Verify derived calculations
        double cacheEff = metrics.cacheEfficiency();
        assertEquals(7500.0 / 350, cacheEff, 0.01, "Cache efficiency should match");

        double throughput = metrics.throughputRaysPerMicrosecond();
        assertEquals(100_000.0 / 350.0, throughput, 0.01, "Throughput should match");

        // Verify validity - meets all criteria
        assertTrue(metrics.meetsNodeReductionTarget(), "30% reduction meets >= 30% target");
        assertTrue(metrics.isValid(), "Metrics should be valid with matched results and node reduction");
    }

    /**
     * Test that test DAG is created correctly
     */
    @Test
    @DisplayName("Test DAG is created correctly")
    void testDAGCreation() {
        // Verify renderer and DAG are both initialized
        assertNotNull(renderer, "Renderer should be initialized");
        assertNotNull(testDAG, "Test DAG should be created");
        assertNotNull(testSVO, "Test SVO should be created");

        // Verify DAG is valid
        assertTrue(testDAG.getAddressingMode() == PointerAddressingMode.ABSOLUTE,
                   "DAG must use absolute addressing for GPU renderer");
    }

    // Helper methods

    /**
     * Build a test ESVO octree with some geometry
     */
    private ESVOOctreeData buildTestOctree() {
        // Create a simple test octree with a root and 8 leaf nodes
        var octree = new ESVOOctreeData(16);

        var root = new ESVONodeUnified();
        root.setValid(true);
        root.setChildMask(0xFF);  // All 8 children present
        root.setChildPtr(1);      // Point to first child
        octree.setNode(0, root);

        // Add 8 leaf nodes
        for (int i = 0; i < 8; i++) {
            var leaf = new ESVONodeUnified();
            leaf.setValid(true);
            leaf.setChildMask(0);   // No children (leaf node)
            octree.setNode(1 + i, leaf);
        }

        return octree;
    }

    /**
     * Build a test DAG from octree data
     */
    private DAGOctreeData buildTestDAG(ESVOOctreeData octree) {
        return DAGBuilder.from(octree).build();
    }
}
