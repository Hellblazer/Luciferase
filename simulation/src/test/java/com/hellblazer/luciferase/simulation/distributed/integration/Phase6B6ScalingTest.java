/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6B6 8-Process Scaling & GC Benchmarking Integration Tests.
 * <p>
 * Tests distributed simulation at 8 processes, 2 bubbles per process (16 total bubbles),
 * with both balanced and skewed entity distribution scenarios.
 * <p>
 * Test Structure:
 * - Baseline Tests (800 entities, ~50/bubble)
 * - Skewed Tests (1200 entities, 4 heavy @ 200 + 12 light @ 50)
 * - GC Tests (pause time and frequency)
 * - Performance Tests (latency and concurrency)
 * - Topology Tests (8-process grid structure)
 * <p>
 * Phase 6B6: 8-Process Scaling & GC Benchmarking
 * Bead: Luciferase-TBD
 *
 * @author hal.hildebrand
 */
class Phase6B6ScalingTest {

    private TestProcessCluster cluster;
    private DistributedEntityFactory entityFactory;
    private CrossProcessMigrationValidator validator;
    private EntityRetentionValidator retentionValidator;

    @BeforeEach
    void setUp() throws Exception {
        // Create 8-process cluster with 2 bubbles per process
        cluster = new TestProcessCluster(8, 2);
        cluster.start();
    }

    @AfterEach
    void tearDown() {
        if (retentionValidator != null) {
            retentionValidator.stop();
        }
        if (validator != null) {
            validator.shutdown();
        }
        if (cluster != null) {
            cluster.stop();
        }
    }

    // ==================== Baseline Tests (800 entities, ~50/bubble) ====================

    @Test
    void test8ProcessBaseline_800Entities() {
        // Given: 800 entities with round-robin distribution
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Check topology and distribution
        var topology = cluster.getTopology();
        var distribution = entityFactory.getDistribution();

        // Then: Verify 8 processes, 16 bubbles
        assertEquals(8, topology.getProcessCount(), "Should have 8 processes");
        assertEquals(16, topology.getBubbleCount(), "Should have 16 bubbles");

        // Verify distribution (~50 entities per bubble)
        var minEntities = distribution.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        var maxEntities = distribution.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(minEntities >= 45, "Min entities should be >= 45, got: " + minEntities);
        assertTrue(maxEntities <= 55, "Max entities should be <= 55, got: " + maxEntities);

        // Verify total
        var totalEntities = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(800, totalEntities, "Should have 800 total entities");
    }

    @Test
    void test8ProcessBaseline_MigrationThroughput() throws InterruptedException {
        // Given: 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Measure throughput for 100 migrations
        var metrics = validator.measureThroughput(2000, 100);

        // Then: Should achieve 100+ TPS
        assertTrue(metrics.actualTPS() >= 100,
            "Should achieve 100+ TPS in baseline, got: " + metrics.actualTPS());
        assertEquals(100, metrics.completedCount(),
            "All 100 migrations should complete");
    }

    @Test
    void test8ProcessBaseline_EntityRetention() throws InterruptedException {
        // Given: 800 entities with retention validator
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 800);
        retentionValidator.startPeriodicValidation(200);

        // When: Run high load
        for (int i = 0; i < 10; i++) {
            validator.migrateBatch(100);
            Thread.sleep(50);
        }

        // Wait for pending validations
        Thread.sleep(250);
        retentionValidator.stop();

        // Then: Verify 100% entity retention
        var finalValidation = cluster.getEntityAccountant().validate();
        assertTrue(finalValidation.success(), "Final validation should pass: " + finalValidation.details());

        var distribution = cluster.getEntityAccountant().getDistribution();
        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(800, total, "Should have all 800 entities after load");
    }

    // ==================== Skewed Tests (1200 entities, 4H@240 + 12L@20) ====================

    @Test
    void test8ProcessSkewed_4Heavy12Light() {
        // Given: Heavy bubbles at indices 0, 4, 8, 12 (one every ~2.6 processes)
        // With 80% weight: 4 heavy get 960 entities (240 each), 12 light get 240 entities (20 each)
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(cluster.getTopology(), heavyIndices, 0.8, 42);
        entityFactory = new DistributedEntityFactory(cluster, strategy, 42);
        entityFactory.createEntities(1200);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Check distribution
        var stats = strategy.getStats();

        // Then: Verify skewed distribution
        assertEquals(4, strategy.getHeavyBubbleCount(), "Should have 4 heavy bubbles");
        assertEquals(12, strategy.getLightBubbleCount(), "Should have 12 light bubbles");
        assertEquals(1200, stats.total(), "Should have 1200 total entities");

        // Heavy bubbles should average ~240 entities (±15% for variance)
        assertTrue(stats.heavyAverage() >= 200 && stats.heavyAverage() <= 280,
            "Heavy bubbles should average ~240 entities, got: " + stats.heavyAverage());

        // Light bubbles should average ~20 entities (±30% for variance)
        assertTrue(stats.lightAverage() >= 14 && stats.lightAverage() <= 26,
            "Light bubbles should average ~20 entities, got: " + stats.lightAverage());
    }

    @Test
    void test8ProcessSkewed_MigrationPerformance() throws InterruptedException {
        // Given: Skewed load
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(cluster.getTopology(), heavyIndices, 0.8, 42);
        entityFactory = new DistributedEntityFactory(cluster, strategy, 42);
        entityFactory.createEntities(1200);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Measure throughput
        var metrics = validator.measureThroughput(2500, 100);

        // Then: Should achieve 80+ TPS (allow for skewed load overhead)
        assertTrue(metrics.actualTPS() >= 80,
            "Should achieve 80+ TPS with skewed load, got: " + metrics.actualTPS());
        assertTrue(metrics.completedCount() >= 90,
            "Should complete at least 90% of migrations, got: " + metrics.completedCount());
    }

    @Test
    void test8ProcessSkewed_LoadBalancing() {
        // Given: Skewed load
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(cluster.getTopology(), heavyIndices, 0.8, 42);
        entityFactory = new DistributedEntityFactory(cluster, strategy, 42);
        entityFactory.createEntities(1200);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Perform migrations
        var results = validator.migrateBatch(200);

        // Then: Should observe more migrations from heavy bubbles
        var successRate = results.stream().filter(MigrationResultSummary::success).count() * 100.0 / results.size();
        assertTrue(successRate >= 70,
            "Should have 70%+ success rate with skewed load, got: " + successRate);

        // Verify entity retention
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "Entity accounting should remain consistent");
    }

    // ==================== GC Tests ====================

    @Test
    void testGCPause_BaselineUnder40ms() throws InterruptedException {
        // Given: 800 entities, aggressive GC stabilization
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // Aggressive heap stabilization
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Thread.sleep(500);

        // When: Measure GC pauses during baseline load
        var gcMeasurement = new GCPauseMeasurement(1);
        gcMeasurement.start();

        // Run load for 2 seconds
        for (int i = 0; i < 20; i++) {
            validator.migrateBatch(20);
            Thread.sleep(100);
        }

        gcMeasurement.stop();

        // Then: p99 pause should be < 40ms
        var stats = gcMeasurement.getStats();
        assertTrue(stats.p99Ms() < 40,
            "Baseline p99 GC pause should be < 40ms, got: " + stats.p99Ms() + "ms");
    }

    @Test
    void testGCPause_SkewedLoadUnder40ms() throws InterruptedException {
        // Given: Skewed load, aggressive GC stabilization
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(cluster.getTopology(), heavyIndices, 0.8, 42);
        entityFactory = new DistributedEntityFactory(cluster, strategy, 42);
        entityFactory.createEntities(1200);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // Aggressive heap stabilization
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Thread.sleep(500);

        // When: Measure GC pauses during skewed load
        var gcMeasurement = new GCPauseMeasurement(1);
        gcMeasurement.start();

        // Run load for 2 seconds
        for (int i = 0; i < 20; i++) {
            validator.migrateBatch(30);
            Thread.sleep(100);
        }

        gcMeasurement.stop();

        // Then: p99 pause should be < 40ms even with skewed load
        var stats = gcMeasurement.getStats();
        assertTrue(stats.p99Ms() < 40,
            "Skewed load p99 GC pause should be < 40ms, got: " + stats.p99Ms() + "ms");
    }

    @Test
    void testGCPause_PauseFrequency() throws InterruptedException {
        // Given: 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // GC stabilization
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Thread.sleep(500);

        // When: Measure GC pause frequency
        var gcMeasurement = new GCPauseMeasurement(1);
        gcMeasurement.start();

        // Run load for 3 seconds
        for (int i = 0; i < 30; i++) {
            validator.migrateBatch(20);
            Thread.sleep(100);
        }

        gcMeasurement.stop();

        // Then: Pause frequency should be < 5 per second
        var stats = gcMeasurement.getStats();
        assertTrue(stats.pauseFrequency() < 5.0,
            "GC pause frequency should be < 5/sec, got: " + stats.pauseFrequency());
    }

    // ==================== Performance Tests ====================

    @Test
    void test8ProcessLatency_P99Under200ms() {
        // Given: 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Perform many migrations
        var results = validator.migrateBatch(200);

        // Then: p99 latency should be < 200ms
        var latencies = results.stream()
            .filter(MigrationResultSummary::success)
            .mapToLong(MigrationResultSummary::latencyMs)
            .sorted()
            .toArray();

        if (latencies.length > 0) {
            var p99Index = (int)(latencies.length * 0.99);
            var p99 = latencies[Math.min(p99Index, latencies.length - 1)];
            assertTrue(p99 < 200, "p99 latency should be < 200ms, got: " + p99);
        }
    }

    @Test
    void test8ProcessConcurrency_16BubbleStress() throws InterruptedException {
        // Given: 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Run concurrent migrations with 10 batches
        for (int i = 0; i < 10; i++) {
            validator.migrateBatch(50);
            Thread.sleep(50);
        }

        // Then: System should remain stable
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "System should remain stable under concurrent stress");

        var metrics = validator.getMetrics();
        assertTrue(metrics.totalMigrations() >= 500,
            "Should have completed at least 500 migrations, got: " + metrics.totalMigrations());
    }

    @Test
    void test8ProcessHeapStability_SustainedLoad() throws InterruptedException {
        // Given: 800 entities, aggressive heap stabilization
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // Aggressive heap stabilization
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Thread.sleep(500);

        // Measure baseline growth rate
        var baselineMonitor = new HeapMonitor();
        baselineMonitor.start(100);
        Thread.sleep(2000);
        baselineMonitor.stop();
        var baselineGrowthRate = baselineMonitor.getGrowthRate();

        // Warmup
        validator.migrateBatch(50);
        Thread.sleep(200);
        System.gc();
        Thread.sleep(200);

        // When: Monitor heap during sustained load
        var heapMonitor = new HeapMonitor();
        heapMonitor.start(100);

        for (int i = 0; i < 20; i++) {
            validator.migrateBatch(50);
            Thread.sleep(100);
        }

        heapMonitor.stop();

        // Then: Delta growth should be < 2MB/sec
        var loadGrowthRate = heapMonitor.getGrowthRate();
        var deltaGrowth = Math.max(0, loadGrowthRate - baselineGrowthRate);

        assertTrue(deltaGrowth < 2_000_000,
            "Migration work should not cause significant growth, baseline: " + baselineGrowthRate
            + " bytes/sec, load: " + loadGrowthRate + " bytes/sec, delta: " + deltaGrowth + " bytes/sec");
    }

    // ==================== Topology Tests ====================

    @Test
    void test8ProcessTopology_GridStructure() {
        // Given: 8-process topology
        var topology = cluster.getTopology();

        // When: Check structure
        var processCount = topology.getProcessCount();
        var bubbleCount = topology.getBubbleCount();

        // Then: Verify grid structure (4x2)
        assertEquals(8, processCount, "Should have 8 processes in 4x2 grid");
        assertEquals(16, bubbleCount, "Should have 16 bubbles (2 per process)");

        // Verify each process has 2 bubbles
        for (int i = 0; i < processCount; i++) {
            var processId = topology.getProcessId(i);
            var bubbles = topology.getBubblesForProcess(processId);
            assertEquals(2, bubbles.size(),
                "Process " + i + " should have 2 bubbles, got: " + bubbles.size());

            // Verify each bubble has neighbors
            for (var bubbleId : bubbles) {
                var neighbors = topology.getNeighbors(bubbleId);
                assertTrue(neighbors.size() >= 1,
                    "Bubble should have at least 1 neighbor, got: " + neighbors.size());
            }
        }
    }

    @Test
    void test8ProcessTopology_CrossProcessMigrationPaths() {
        // Given: 8-process topology
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);

        var topology = cluster.getTopology();

        // When: Check that all processes have cross-process migration paths
        var allProcessesHavePaths = true;
        for (int i = 0; i < topology.getProcessCount(); i++) {
            var processId = topology.getProcessId(i);
            var bubbles = topology.getBubblesForProcess(processId);

            var hasCrossProcessPath = false;
            for (var bubbleId : bubbles) {
                var neighbors = topology.getNeighbors(bubbleId);
                for (var neighborId : neighbors) {
                    var neighborProcess = topology.getProcessForBubble(neighborId);
                    if (!neighborProcess.equals(processId)) {
                        hasCrossProcessPath = true;
                        break;
                    }
                }
                if (hasCrossProcessPath) break;
            }

            if (!hasCrossProcessPath) {
                allProcessesHavePaths = false;
                break;
            }
        }

        // Then: All processes should have cross-process migration paths
        assertTrue(allProcessesHavePaths,
            "All processes should have cross-process migration paths");
    }
}
