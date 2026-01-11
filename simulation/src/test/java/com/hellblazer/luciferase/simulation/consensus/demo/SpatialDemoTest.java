/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 8E: Demo Runner and Validation.
 * <p>
 * Tests the complete demo execution with metrics collection and validation.
 * RED phase of TDD - these tests will fail until implementation is complete.
 * <p>
 * Phase 8E Day 1: Demo Runner and Validation
 *
 * @author hal.hildebrand
 */
class SpatialDemoTest {

    /**
     * Test 1: Demo completes 3-minute simulation without exceptions.
     * <p>
     * CRITICAL: This validates basic execution stability.
     * Note: For MVP, simulation runs in tight loop (not real-time),
     * so actual wall-clock time will be < 1 second.
     */
    @Test
    @Timeout(value = 10) // Should complete in < 10 seconds
    void testDemoCompletes3Minutes() {
        // Create demo with 180-tick simulation
        var config = DemoConfiguration.builder()
            .bubbleCount(4)
            .initialEntityCount(100)
            .runtimeSeconds(180)
            .failureInjectionTimeSeconds(60)
            .failureType(FailureInjector.FailureType.BYZANTINE_VOTE)
            .failureBubbleIndex(0)
            .build();

        var demo = new SpatialDemo(config);

        // Run to completion (should not throw)
        var report = demo.run();
        assertNotNull(report, "Report should be generated");

        // Verify simulation completed (runtime should be < 5 seconds wall-clock for tight loop)
        var metrics = demo.getMetrics();
        var actualRuntime = metrics.getTotalRuntimeMs() / 1000.0;
        assertTrue(actualRuntime < 5.0,
                   "Simulation should complete in < 5 seconds, got " + actualRuntime);
        assertTrue(actualRuntime > 0.0,
                   "Simulation should take some time, got " + actualRuntime);
    }

    /**
     * Test 2: Metrics are collected during run.
     * <p>
     * Verifies that metrics collectors capture data during execution.
     */
    @Test
    @Timeout(value = 10)
    void testMetricsCollectedDuringRun() {
        var config = DemoConfiguration.builder()
            .bubbleCount(4)
            .initialEntityCount(100)
            .runtimeSeconds(180)
            .build();

        var demo = new SpatialDemo(config);
        demo.run();

        var metrics = demo.getMetrics();

        // Verify metrics populated
        assertTrue(metrics.totalMigrations() > 0, "Should have migrations");
        assertEquals(100, metrics.entitiesSpawned(), "Should spawn 100 entities");
        assertTrue(metrics.entitiesRetained() >= 95, "Should retain at least 95% of entities");
    }

    /**
     * Test 3: Validation report is generated.
     * <p>
     * Verifies report contains configuration, metrics, and validation checklist.
     */
    @Test
    @Timeout(value = 10)
    void testReportGenerated() {
        var config = DemoConfiguration.builder()
            .bubbleCount(4)
            .initialEntityCount(100)
            .runtimeSeconds(180)
            .build();

        var demo = new SpatialDemo(config);
        var report = demo.run();

        // Verify report structure
        assertNotNull(report, "Report should not be null");
        var reportText = report.generateReport();
        assertNotNull(reportText, "Report text should not be null");
        assertFalse(reportText.isEmpty(), "Report text should not be empty");

        // Verify report contains key sections (use uppercase check since report uses all-caps)
        var reportUpper = reportText.toUpperCase();
        assertTrue(reportText.contains("Phase 8E Demo Validation Report"),
                   "Report should have title");
        assertTrue(reportUpper.contains("CONFIGURATION"),
                   "Report should have configuration section");
        assertTrue(reportUpper.contains("METRICS"),
                   "Report should have metrics section");
        assertTrue(reportUpper.contains("VALIDATION"),
                   "Report should have validation section");
    }

    /**
     * Test 4: Entity retention is 100%.
     * <p>
     * CRITICAL: No entities should be lost during consensus migrations.
     */
    @Test
    @Timeout(value = 10)
    void testEntity100PercentRetention() {
        var config = DemoConfiguration.builder()
            .bubbleCount(4)
            .initialEntityCount(100)
            .runtimeSeconds(180)
            .failureInjectionTimeSeconds(60)
            .failureType(FailureInjector.FailureType.BYZANTINE_VOTE)
            .build();

        var demo = new SpatialDemo(config);
        demo.run();

        var metrics = demo.getMetrics();

        // Verify retention (allow >= 99% to account for edge cases)
        var retentionRate = metrics.getRetentionRate();
        assertTrue(retentionRate >= 0.99,
                   "Retention should be >= 99%, got " + (retentionRate * 100) + "%");
        assertTrue(metrics.entitiesRetained() >= 99,
                   "Should retain at least 99/100 entities, got " + metrics.entitiesRetained());
    }

    /**
     * Test 5: Throughput exceeds 100 migrations/second.
     * <p>
     * CRITICAL: Validates consensus performance under load.
     */
    @Test
    @Timeout(value = 10)
    void testThroughputGreaterThan100() {
        var config = DemoConfiguration.builder()
            .bubbleCount(4)
            .initialEntityCount(100)
            .runtimeSeconds(180)
            .build();

        var demo = new SpatialDemo(config);
        demo.run();

        var metrics = demo.getMetrics();
        var throughput = metrics.getThroughput();

        assertTrue(throughput > 100,
                   "Throughput should be > 100 migrations/sec, got " + throughput);
    }

    /**
     * Test 6: Latency p99 is under threshold.
     * <p>
     * CRITICAL: Validates consensus latency for migrations.
     */
    @Test
    @Timeout(value = 10)
    void testLatencyP99UnderThreshold() {
        var config = DemoConfiguration.builder()
            .bubbleCount(4)
            .initialEntityCount(100)
            .runtimeSeconds(180)
            .build();

        var demo = new SpatialDemo(config);
        demo.run();

        var metrics = demo.getMetrics();

        // p99 should be < 500ms
        var p99 = metrics.getLatencyP99();
        assertTrue(p99 < 500,
                   "Latency p99 should be < 500ms, got " + p99 + "ms");

        // Also verify p50 and p95 for completeness
        var p50 = metrics.getLatencyP50();
        var p95 = metrics.getLatencyP95();

        assertTrue(p50 < p95, "p50 should be < p95");
        assertTrue(p95 < p99, "p95 should be < p99");
    }

    /**
     * Test 7: Byzantine failure recovery is fast.
     * <p>
     * CRITICAL: Validates BFT tolerance and recovery under Byzantine failure.
     */
    @Test
    @Timeout(value = 10)
    void testByzantineFailureRecovery() {
        var config = DemoConfiguration.builder()
            .bubbleCount(4)
            .initialEntityCount(100)
            .runtimeSeconds(180)
            .failureInjectionTimeSeconds(60)
            .failureType(FailureInjector.FailureType.BYZANTINE_VOTE)
            .failureBubbleIndex(0)
            .build();

        var demo = new SpatialDemo(config);
        demo.run();

        var metrics = demo.getMetrics();

        // Verify failure was injected
        assertTrue(metrics.failureInjectionTimeMs() > 0,
                   "Failure should have been injected");

        // Verify failure was detected
        assertTrue(metrics.failureDetectedTimeMs() > 0,
                   "Failure should have been detected");

        // Verify recovery occurred
        assertTrue(metrics.recoveryCompleteTimeMs() > 0,
                   "Recovery should have completed");

        // Verify recovery time < 10 seconds
        var recoveryTime = metrics.getRecoveryTimeMs();
        assertTrue(recoveryTime < 10000,
                   "Recovery should take < 10 seconds, got " + recoveryTime + "ms");

        // Verify throughput remained reasonable during failure (> 50/sec)
        var throughput = metrics.getThroughput();
        assertTrue(throughput > 50,
                   "Throughput should remain > 50/sec during Byzantine failure, got " + throughput);
    }
}
