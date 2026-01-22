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
package com.hellblazer.luciferase.esvo.gpu.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.2.2c: Batch Kernel Metrics - Unit Tests
 *
 * Validates that BatchKernelMetrics correctly:
 * - Stores and retrieves performance metrics
 * - Calculates cache efficiency and throughput
 * - Validates node reduction targets (>=30%)
 * - Marks metrics as valid when both correctness and performance criteria met
 *
 * @author hal.hildebrand
 */
@DisplayName("Phase 4.2.2c: Batch Kernel Metrics")
class BatchKernelMetricsTest {

    private BatchKernelMetrics metrics;
    private static final long TIMESTAMP = System.currentTimeMillis();

    @BeforeEach
    void setUp() {
        // Create metrics with typical batch kernel performance:
        // - 100,000 rays
        // - 4 rays per item (raysPerItem)
        // - 150µs latency (faster than 450µs baseline)
        // - 250,000 total node accesses
        // - 50,000 unique nodes (cache efficiency 5.0x)
        // - Results match single-ray kernel
        // - 40% node reduction (exceeds 30% target)
        metrics = new BatchKernelMetrics(
            100_000,           // rayCount
            4,                 // raysPerItem
            150.0,             // latencyMicroseconds
            250_000,           // totalNodeAccesses
            50_000,            // uniqueNodesVisited
            true,              // resultsMatch
            40.0,              // nodeReductionPercent
            0.75,              // coherenceScore
            TIMESTAMP          // timestamp
        );
    }

    /**
     * Test that metrics stores all parameters correctly
     */
    @Test
    @DisplayName("Metrics stores all parameters correctly")
    void testMetricsStorage() {
        assertEquals(100_000, metrics.rayCount());
        assertEquals(4, metrics.raysPerItem());
        assertEquals(150.0, metrics.latencyMicroseconds());
        assertEquals(250_000, metrics.totalNodeAccesses());
        assertEquals(50_000, metrics.uniqueNodesVisited());
        assertTrue(metrics.resultsMatch());
        assertEquals(40.0, metrics.nodeReductionPercent());
        assertEquals(0.75, metrics.coherenceScore());
        assertEquals(TIMESTAMP, metrics.timestamp());
    }

    /**
     * Test cache efficiency calculation
     */
    @Test
    @DisplayName("Cache efficiency calculated correctly")
    void testCacheEfficiency() {
        // Cache efficiency = totalAccesses / uniqueNodes = 250,000 / 50,000 = 5.0x
        double efficiency = metrics.cacheEfficiency();
        assertEquals(5.0, efficiency, 0.01, "Cache efficiency should be 5.0x");
    }

    /**
     * Test throughput calculation
     */
    @Test
    @DisplayName("Throughput calculated correctly")
    void testThroughput() {
        // Throughput = rayCount / latency = 100,000 / 150 = 666.67 rays/µs
        double throughput = metrics.throughputRaysPerMicrosecond();
        assertEquals(100_000.0 / 150.0, throughput, 0.01, "Throughput should be 666.67 rays/µs");
    }

    /**
     * Test node reduction target validation
     */
    @Test
    @DisplayName("Node reduction target validation")
    void testNodeReductionTarget() {
        // Test with 40% reduction (exceeds 30% target)
        assertTrue(metrics.meetsNodeReductionTarget(), "40% reduction should meet 30% target");

        // Test with exactly 30% reduction
        var borderlineMetrics = new BatchKernelMetrics(
            100_000, 4, 150.0, 250_000, 50_000, true, 30.0, 0.75, TIMESTAMP
        );
        assertTrue(borderlineMetrics.meetsNodeReductionTarget(), "30% reduction should meet target");

        // Test with 29% reduction (fails target)
        var failMetrics = new BatchKernelMetrics(
            100_000, 4, 150.0, 250_000, 50_000, true, 29.0, 0.75, TIMESTAMP
        );
        assertFalse(failMetrics.meetsNodeReductionTarget(), "29% reduction should not meet target");
    }

    /**
     * Test validity check (correctness AND performance)
     */
    @Test
    @DisplayName("Validity requires both correctness and performance")
    void testValidityCheck() {
        // Valid: results match AND node reduction >= 30%
        assertTrue(metrics.isValid(), "Should be valid: matches results and >=30% reduction");

        // Invalid: results don't match (even with good reduction)
        var mismatchMetrics = new BatchKernelMetrics(
            100_000, 4, 150.0, 250_000, 50_000, false, 40.0, 0.75, TIMESTAMP
        );
        assertFalse(mismatchMetrics.isValid(), "Should be invalid: results mismatch");

        // Invalid: results match but insufficient reduction
        var insufficientReductionMetrics = new BatchKernelMetrics(
            100_000, 4, 150.0, 250_000, 50_000, true, 25.0, 0.75, TIMESTAMP
        );
        assertFalse(insufficientReductionMetrics.isValid(), "Should be invalid: insufficient reduction");

        // Invalid: both criteria fail
        var bothFailMetrics = new BatchKernelMetrics(
            100_000, 4, 150.0, 250_000, 50_000, false, 25.0, 0.75, TIMESTAMP
        );
        assertFalse(bothFailMetrics.isValid(), "Should be invalid: both criteria fail");
    }

    /**
     * Test cache efficiency with different access patterns
     */
    @Test
    @DisplayName("Cache efficiency scales with access patterns")
    void testCacheEfficiencyVariations() {
        var testCases = new Object[][] {
            // {totalAccesses, uniqueNodes, expectedEfficiency}
            {1_000, 1_000, 1.0},      // No cache benefit
            {2_000, 1_000, 2.0},      // 2x cache hits
            {10_000, 1_000, 10.0},    // 10x cache hits
            {100_000, 50_000, 2.0},   // 2x cache efficiency
            {500_000, 50_000, 10.0},  // 10x cache efficiency
        };

        for (Object[] testCase : testCases) {
            int totalAccesses = (int) testCase[0];
            int uniqueNodes = (int) testCase[1];
            double expectedEfficiency = (double) testCase[2];

            var testMetrics = new BatchKernelMetrics(
                100_000, 4, 150.0, totalAccesses, uniqueNodes, true, 40.0, 0.75, TIMESTAMP
            );

            double efficiency = testMetrics.cacheEfficiency();
            assertEquals(expectedEfficiency, efficiency, 0.01,
                String.format("Efficiency should be %.1f for %d accesses / %d unique nodes",
                    expectedEfficiency, totalAccesses, uniqueNodes));
        }
    }

    /**
     * Test throughput with different latencies
     */
    @Test
    @DisplayName("Throughput scales inversely with latency")
    void testThroughputVariations() {
        var testCases = new Object[][] {
            // {latency, expectedThroughput}
            {100.0, 1_000.0},   // 100_000 / 100 = 1,000 rays/µs
            {200.0, 500.0},     // 100_000 / 200 = 500 rays/µs
            {400.0, 250.0},     // 100_000 / 400 = 250 rays/µs
            {50.0, 2_000.0},    // 100_000 / 50 = 2,000 rays/µs
        };

        for (Object[] testCase : testCases) {
            double latency = (double) testCase[0];
            double expectedThroughput = (double) testCase[1];

            var testMetrics = new BatchKernelMetrics(
                100_000, 4, latency, 250_000, 50_000, true, 40.0, 0.75, TIMESTAMP
            );

            double throughput = testMetrics.throughputRaysPerMicrosecond();
            assertEquals(expectedThroughput, throughput, 1.0,
                String.format("Throughput should be %.0f for latency %.0fµs",
                    expectedThroughput, latency));
        }
    }

    /**
     * Test node reduction percentage boundaries
     */
    @Test
    @DisplayName("Node reduction percentage boundary values")
    void testNodeReductionBoundaries() {
        var testCases = new Object[][] {
            // {reduction%, meetsTarget}
            {0.0, false},       // No improvement
            {15.0, false},      // Below target
            {29.9, false},      // Just below target
            {30.0, true},       // Exactly at target
            {30.1, true},       // Just above target
            {50.0, true},       // Significant improvement
            {100.0, true},      // Maximum possible (no batch accesses)
        };

        for (Object[] testCase : testCases) {
            double reduction = (double) testCase[0];
            boolean expectedToMeet = (boolean) testCase[1];

            var testMetrics = new BatchKernelMetrics(
                100_000, 4, 150.0, 250_000, 50_000, true, reduction, 0.75, TIMESTAMP
            );

            boolean meetsTarget = testMetrics.meetsNodeReductionTarget();
            assertEquals(expectedToMeet, meetsTarget,
                String.format("%.1f%% reduction should %s meet target",
                    reduction, expectedToMeet ? "" : "not"));
        }
    }

    /**
     * Test string representation
     */
    @Test
    @DisplayName("Metrics toString includes all key information")
    void testStringRepresentation() {
        String str = metrics.toString();

        assertNotNull(str);
        assertFalse(str.isEmpty());
        assertTrue(str.contains("100000") || str.contains("100,000"), "Should contain ray count");
        assertTrue(str.contains("raysPerItem=4"), "Should contain raysPerItem");
        assertTrue(str.contains("150"), "Should contain latency");
        assertTrue(str.contains("40"), "Should contain node reduction");
        assertTrue(str.contains("0.75"), "Should contain coherence");
    }

    /**
     * Test with zero latency handling
     */
    @Test
    @DisplayName("Handles zero latency gracefully")
    void testZeroLatencyHandling() {
        var zeroLatencyMetrics = new BatchKernelMetrics(
            100_000, 4, 0.0, 250_000, 50_000, true, 40.0, 0.75, TIMESTAMP
        );

        // Throughput with zero latency should be Infinity or handled gracefully
        double throughput = zeroLatencyMetrics.throughputRaysPerMicrosecond();
        assertTrue(Double.isInfinite(throughput) || throughput == 0,
            "Should handle zero latency gracefully");
    }

    /**
     * Test with zero unique nodes handling
     */
    @Test
    @DisplayName("Handles zero unique nodes gracefully")
    void testZeroUniqueNodesHandling() {
        var zeroNodesMetrics = new BatchKernelMetrics(
            100_000, 4, 150.0, 0, 0, true, 40.0, 0.75, TIMESTAMP
        );

        // Cache efficiency with zero nodes should be 0 or infinity
        double efficiency = zeroNodesMetrics.cacheEfficiency();
        assertTrue(efficiency == 0 || Double.isInfinite(efficiency) || Double.isNaN(efficiency),
            "Should handle zero nodes gracefully");
    }

    /**
     * Test high coherence scenario
     */
    @Test
    @DisplayName("High coherence with optimal node reduction")
    void testHighCoherenceScenario() {
        // High coherence (0.9) with excellent cache efficiency (10x) and 50% node reduction
        var highCoherenceMetrics = new BatchKernelMetrics(
            100_000, 10, 100.0, 100_000, 10_000, true, 50.0, 0.9, TIMESTAMP
        );

        assertTrue(highCoherenceMetrics.isValid(), "Should be valid");
        assertTrue(highCoherenceMetrics.meetsNodeReductionTarget());
        assertEquals(10.0, highCoherenceMetrics.cacheEfficiency(), 0.01);
        assertEquals(1_000.0, highCoherenceMetrics.throughputRaysPerMicrosecond(), 1.0);
    }

    /**
     * Test low coherence scenario
     */
    @Test
    @DisplayName("Low coherence with minimal improvement")
    void testLowCoherenceScenario() {
        // Low coherence (0.3) with 1x cache efficiency and 30% node reduction (borderline)
        var lowCoherenceMetrics = new BatchKernelMetrics(
            100_000, 1, 150.0, 350_000, 350_000, true, 30.0, 0.3, TIMESTAMP
        );

        assertTrue(lowCoherenceMetrics.isValid(), "Should be valid: meets target exactly");
        assertTrue(lowCoherenceMetrics.meetsNodeReductionTarget());
        assertEquals(1.0, lowCoherenceMetrics.cacheEfficiency(), 0.01);
    }
}
