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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.1 P1: Performance Metrics Test Suite
 *
 * Tests PerformanceMetrics record creation, validation, and comparison methods.
 *
 * @author hal.hildebrand
 */
@DisplayName("PerformanceMetrics Tests")
class PerformanceMetricsTest {

    @Test
    @DisplayName("Create baseline performance metrics")
    void testCreateBaselineMetrics() {
        var metrics = new PerformanceMetrics(
            "baseline",
            100_000,
            850.0,
            117.6,
            75.0f,
            12,
            0.0f,
            System.currentTimeMillis()
        );

        assertEquals("baseline", metrics.scenario());
        assertEquals(100_000, metrics.rayCount());
        assertEquals(850.0, metrics.latencyMicroseconds(), 0.001);
        assertEquals(117.6, metrics.throughputRaysPerMicrosecond(), 0.001);
        assertEquals(75.0f, metrics.gpuOccupancyPercent(), 0.001);
        assertEquals(12, metrics.averageTraversalDepth());
        assertEquals(0.0f, metrics.cacheHitRate(), 0.001);
    }

    @Test
    @DisplayName("Create optimized performance metrics")
    void testCreateOptimizedMetrics() {
        var metrics = new PerformanceMetrics(
            "optimized_A+B",
            100_000,
            450.0,
            222.2,
            85.0f,
            12,
            0.65f,
            System.currentTimeMillis()
        );

        assertEquals("optimized_A+B", metrics.scenario());
        assertEquals(450.0, metrics.latencyMicroseconds(), 0.001);
        assertEquals(222.2, metrics.throughputRaysPerMicrosecond(), 0.001);
        assertEquals(85.0f, metrics.gpuOccupancyPercent(), 0.001);
        assertEquals(0.65f, metrics.cacheHitRate(), 0.001);
    }

    @Test
    @DisplayName("Compare optimized to baseline - show improvement")
    void testCompareToBaseline_Improvement() {
        var baseline = new PerformanceMetrics(
            "baseline",
            100_000,
            850.0,
            117.6,
            75.0f,
            12,
            0.0f,
            System.currentTimeMillis()
        );

        var optimized = new PerformanceMetrics(
            "optimized_A+B",
            100_000,
            450.0,
            222.2,
            85.0f,
            12,
            0.65f,
            System.currentTimeMillis()
        );

        // Improvement = (baseline_latency - optimized_latency) / baseline_latency * 100
        // = (850 - 450) / 850 * 100 = 47.06%
        var improvement = optimized.compareToBaseline(baseline);
        assertEquals(47.06, improvement, 0.01);
    }

    @Test
    @DisplayName("Compare baseline to itself - no change")
    void testCompareToBaseline_NoChange() {
        var baseline = new PerformanceMetrics(
            "baseline",
            100_000,
            850.0,
            117.6,
            75.0f,
            12,
            0.0f,
            System.currentTimeMillis()
        );

        var improvement = baseline.compareToBaseline(baseline);
        assertEquals(0.0, improvement, 0.001);
    }

    @Test
    @DisplayName("Compare with degraded performance - negative improvement")
    void testCompareToBaseline_Degradation() {
        var baseline = new PerformanceMetrics(
            "baseline",
            100_000,
            450.0,
            222.2,
            85.0f,
            12,
            0.65f,
            System.currentTimeMillis()
        );

        var degraded = new PerformanceMetrics(
            "degraded",
            100_000,
            850.0,
            117.6,
            75.0f,
            12,
            0.0f,
            System.currentTimeMillis()
        );

        // Degradation = (450 - 850) / 450 * 100 = -88.89%
        var improvement = degraded.compareToBaseline(baseline);
        assertTrue(improvement < 0, "Should show negative improvement (degradation)");
        assertEquals(-88.89, improvement, 0.01);
    }

    @Test
    @DisplayName("Throughput calculated correctly")
    void testThroughputCalculation() {
        var rayCount = 1_000_000;
        var latencyMicros = 4500.0;

        // Throughput = rayCount / latencyMicros = 1M / 4500µs = 222.2 rays/µs
        var expectedThroughput = rayCount / latencyMicros;

        var metrics = new PerformanceMetrics(
            "test",
            rayCount,
            latencyMicros,
            expectedThroughput,
            80.0f,
            12,
            0.5f,
            System.currentTimeMillis()
        );

        assertEquals(222.2, metrics.throughputRaysPerMicrosecond(), 0.1);
    }

    @Test
    @DisplayName("Large ray count metrics")
    void testLargeRayCount() {
        var metrics = new PerformanceMetrics(
            "large_batch",
            10_000_000,
            45000.0,
            222.2,
            80.0f,
            14,
            0.7f,
            System.currentTimeMillis()
        );

        assertEquals(10_000_000, metrics.rayCount());
        assertEquals(45000.0, metrics.latencyMicroseconds(), 0.001);
    }

    @Test
    @DisplayName("Zero cache hit rate for baseline")
    void testZeroCacheHitRate() {
        var metrics = new PerformanceMetrics(
            "baseline",
            100_000,
            850.0,
            117.6,
            75.0f,
            12,
            0.0f,
            System.currentTimeMillis()
        );

        assertEquals(0.0f, metrics.cacheHitRate(), 0.001);
    }

    @Test
    @DisplayName("High cache hit rate for optimized")
    void testHighCacheHitRate() {
        var metrics = new PerformanceMetrics(
            "optimized_A",
            100_000,
            500.0,
            200.0,
            80.0f,
            12,
            0.85f,
            System.currentTimeMillis()
        );

        assertEquals(0.85f, metrics.cacheHitRate(), 0.001);
    }

    @Test
    @DisplayName("GPU occupancy ranges")
    void testGPUOccupancyRanges() {
        var lowOccupancy = new PerformanceMetrics(
            "low_occupancy",
            100_000,
            1000.0,
            100.0,
            50.0f,
            12,
            0.0f,
            System.currentTimeMillis()
        );

        var highOccupancy = new PerformanceMetrics(
            "high_occupancy",
            100_000,
            400.0,
            250.0,
            95.0f,
            12,
            0.7f,
            System.currentTimeMillis()
        );

        assertEquals(50.0f, lowOccupancy.gpuOccupancyPercent(), 0.001);
        assertEquals(95.0f, highOccupancy.gpuOccupancyPercent(), 0.001);
    }

    @Test
    @DisplayName("Different ray counts comparison")
    void testDifferentRayCountsComparison() {
        var baseline100K = new PerformanceMetrics(
            "baseline_100K",
            100_000,
            850.0,
            117.6,
            75.0f,
            12,
            0.0f,
            System.currentTimeMillis()
        );

        var optimized1M = new PerformanceMetrics(
            "optimized_1M",
            1_000_000,
            4500.0,
            222.2,
            85.0f,
            12,
            0.65f,
            System.currentTimeMillis()
        );

        // Can still compare latency improvement
        var improvement = optimized1M.compareToBaseline(baseline100K);
        assertTrue(improvement < 0, "1M rays should have higher latency than 100K baseline");
    }

    @Test
    @DisplayName("Traversal depth variance")
    void testTraversalDepthVariance() {
        var shallowTraversal = new PerformanceMetrics(
            "shallow",
            100_000,
            300.0,
            333.3,
            80.0f,
            8,
            0.6f,
            System.currentTimeMillis()
        );

        var deepTraversal = new PerformanceMetrics(
            "deep",
            100_000,
            900.0,
            111.1,
            75.0f,
            16,
            0.5f,
            System.currentTimeMillis()
        );

        assertEquals(8, shallowTraversal.averageTraversalDepth());
        assertEquals(16, deepTraversal.averageTraversalDepth());
        assertTrue(shallowTraversal.latencyMicroseconds() < deepTraversal.latencyMicroseconds(),
                   "Shallow traversal should be faster");
    }

    @Test
    @DisplayName("Timestamp preserved")
    void testTimestampPreserved() {
        var timestamp = System.currentTimeMillis();
        var metrics = new PerformanceMetrics(
            "test",
            100_000,
            500.0,
            200.0,
            80.0f,
            12,
            0.5f,
            timestamp
        );

        assertEquals(timestamp, metrics.timestamp());
    }
}
