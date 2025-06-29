/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.performance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simple test to verify performance test infrastructure is working
 *
 * @author hal.hildebrand
 */
public class PerformanceTestRunner {

    @Test
    @DisplayName("Verify performance test environment variable")
    void testEnvironmentVariable() {
        boolean runPerfTests = Boolean.parseBoolean(
        System.getenv().getOrDefault("RUN_SPATIAL_INDEX_PERF_TESTS", "false"));

        System.out.println("RUN_SPATIAL_INDEX_PERF_TESTS = " + runPerfTests);

        if (runPerfTests) {
            System.out.println("Performance tests are ENABLED");
        } else {
            System.out.println("Performance tests are DISABLED (default)");
            System.out.println("To enable, set environment variable: RUN_SPATIAL_INDEX_PERF_TESTS=true");
        }

        // This test always passes - it's just informational
        assertTrue(true);
    }

    @Test
    @DisplayName("Test PerformanceMetrics calculations")
    void testPerformanceMetrics() {
        PerformanceMetrics metrics = new PerformanceMetrics("test_operation", 1000, // entity count
                                                            5_000_000_000L, // 5 seconds in nanos
                                                            10_485_760L, // 10 MB in bytes
                                                            null);

        assertEquals(5000.0, metrics.getElapsedMillis(), 0.01);
        assertEquals(5.0, metrics.getElapsedSeconds(), 0.01);
        assertEquals(200.0, metrics.getOperationsPerSecond(), 0.01);
        assertEquals(5_000_000.0, metrics.getNanosPerOperation(), 0.01);
        assertEquals(10485.76, metrics.getMemoryPerEntity(), 0.01);
        assertEquals(10.0, metrics.getMemoryUsedMB(), 0.01);
    }

    @Test
    @DisplayName("Test SpatialDistribution generation")
    void testSpatialDistributions() {
        com.hellblazer.luciferase.lucien.VolumeBounds bounds = new com.hellblazer.luciferase.lucien.VolumeBounds(0, 0,
                                                                                                                 0, 100,
                                                                                                                 100,
                                                                                                                 100);

        for (SpatialDistribution dist : SpatialDistribution.values()) {
            var points = dist.generate(100, bounds);
            assertEquals(100, points.size(), "Should generate exact number of points");

            // Verify all points are within bounds
            for (var point : points) {
                assertTrue(point.x >= bounds.minX() && point.x <= bounds.maxX(),
                           "X coordinate should be within bounds");
                assertTrue(point.y >= bounds.minY() && point.y <= bounds.maxY(),
                           "Y coordinate should be within bounds");
                assertTrue(point.z >= bounds.minZ() && point.z <= bounds.maxZ(),
                           "Z coordinate should be within bounds");
            }

            System.out.println(dist + " distribution generated " + points.size() + " points successfully");
        }
    }
}
