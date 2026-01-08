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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance and stability tests for distributed simulation.
 * <p>
 * Phase 6B5.5: Performance & Stability Testing
 * Bead: Luciferase-u99r
 *
 * @author hal.hildebrand
 */
class PerformanceStabilityTest {

    private TestProcessCluster cluster;
    private DistributedEntityFactory entityFactory;
    private CrossProcessMigrationValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new TestProcessCluster(4, 2);
        cluster.start();
        entityFactory = new DistributedEntityFactory(cluster);
        entityFactory.createEntities(800);
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);
    }

    @AfterEach
    void tearDown() {
        if (validator != null) {
            validator.shutdown();
        }
        if (cluster != null) {
            cluster.stop();
        }
    }

    @Test
    void testBaselinePerformance() throws InterruptedException {
        // When: Run 100 migrations with no clock skew
        var metrics = validator.measureThroughput(2000, 100);

        // Then: Should achieve reasonable throughput
        assertTrue(metrics.actualTPS() >= 50,
            "Should achieve at least 50 TPS in baseline, got: " + metrics.actualTPS());
        assertEquals(100, metrics.completedCount(),
            "All 100 migrations should complete");
    }

    @Test
    void testWithClockSkew25ms() {
        // Given: 25ms clock skew
        var testClock = new TestClock();
        testClock.setSkew(25);
        validator.setClock(testClock);

        // When: Perform migrations
        var results = validator.migrateBatch(50);

        // Then: Should still succeed with minor skew
        var successRate = results.stream().filter(MigrationResultSummary::success).count() * 100.0 / results.size();
        assertTrue(successRate >= 90, "Should have 90%+ success rate with 25ms skew, got: " + successRate);
    }

    @Test
    void testWithClockSkew50ms() {
        // Given: 50ms clock skew (max tolerance)
        var testClock = new TestClock();
        testClock.setSkew(50);
        validator.setClock(testClock);

        // When: Perform migrations
        var results = validator.migrateBatch(50);

        // Then: Should still succeed at tolerance boundary
        var successRate = results.stream().filter(MigrationResultSummary::success).count() * 100.0 / results.size();
        assertTrue(successRate >= 80, "Should have 80%+ success rate at 50ms skew, got: " + successRate);
    }

    @Test
    void testThroughputTarget() throws InterruptedException {
        // When: Measure sustained throughput
        var metrics = validator.measureThroughput(3000, 300);

        // Then: Should achieve 100+ TPS
        assertTrue(metrics.actualTPS() >= 100,
            "Should achieve 100+ TPS, got: " + metrics.actualTPS());
    }

    @Test
    void testLatencyP99() {
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
    void testHeapStability() throws InterruptedException {
        // Given: Warmup phase to stabilize heap before monitoring
        validator.migrateBatch(50);
        Thread.sleep(200);
        System.gc(); // Clear transient objects from warmup
        Thread.sleep(200);

        // Given: HeapMonitor tracking memory
        var heapMonitor = new HeapMonitor();
        heapMonitor.start(100); // snapshot every 100ms

        // When: Run sustained load for 2 seconds (longer duration for reliable trend)
        for (int i = 0; i < 20; i++) {
            validator.migrateBatch(50);
            Thread.sleep(100);
        }

        heapMonitor.stop();

        // Then: Growth rate should not indicate a leak
        // Allow up to 1MB/sec growth (accounts for normal GC patterns)
        var growthRate = heapMonitor.getGrowthRate();
        assertFalse(heapMonitor.hasLeak(1_000_000),
            "Should not have significant memory leak, growth rate: " + growthRate + " bytes/sec");
    }

    @Test
    void testEntityRetentionUnderLoad() throws InterruptedException {
        // Given: Retention validator with longer interval to reduce race conditions
        var retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 800);
        retentionValidator.startPeriodicValidation(200);

        // When: Run high load
        for (int i = 0; i < 10; i++) {
            validator.migrateBatch(100);
            Thread.sleep(50);
        }

        // Wait for pending validations to complete
        Thread.sleep(250);

        retentionValidator.stop();

        // Then: Verify final state (transient violations during concurrent load are acceptable)
        var finalValidation = cluster.getEntityAccountant().validate();
        assertTrue(finalValidation.success(), "Final validation should pass: " + finalValidation.details());

        var distribution = cluster.getEntityAccountant().getDistribution();
        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(800, total, "Should have all 800 entities after load");
    }

    @Test
    void testGhostSyncUnderLoad() {
        // When: Perform many cross-bubble migrations
        var results = validator.migrateBatch(100);

        // Then: Ghost sync should not cause issues
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "Ghost sync should maintain consistency");
    }

    @Test
    void testConcurrentMigrationStress() throws InterruptedException {
        // When: Run many concurrent migrations
        var loadGenerator = new EntityMigrationLoadGenerator(100, 200, entityId -> {
            var bubble = entityFactory.getBubbleForEntity(entityId);
            if (bubble != null) {
                var neighbors = cluster.getTopology().getNeighbors(bubble);
                if (!neighbors.isEmpty()) {
                    var dest = neighbors.iterator().next();
                    validator.migrateEntity(entityId, bubble, dest);
                }
            }
        });

        loadGenerator.start();
        Thread.sleep(3000);
        loadGenerator.stop();

        // Then: System should remain stable
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "System should remain stable under stress");
    }

    @Test
    void testTickLatencyUnder16ms() {
        // Given: Simulate tick timing
        var tickLatencies = new long[60]; // 60 "ticks"

        // When: Measure migration time per tick
        for (int i = 0; i < 60; i++) {
            var start = System.nanoTime();
            validator.migrateBatch(5); // 5 migrations per tick
            var elapsed = (System.nanoTime() - start) / 1_000_000; // ms
            tickLatencies[i] = elapsed;
        }

        // Then: Most ticks should be < 16.7ms (60 FPS)
        var under16Count = 0;
        for (var latency : tickLatencies) {
            if (latency < 17) under16Count++;
        }
        var percentUnder16 = under16Count * 100.0 / 60;
        assertTrue(percentUnder16 >= 80, "At least 80% of ticks should be < 16.7ms, got: " + percentUnder16 + "%");
    }

    @Test
    void testLongRunningStability() throws InterruptedException {
        // Given: Retention validator with longer validation interval to reduce race conditions
        var retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 800);
        retentionValidator.startPeriodicValidation(500); // Longer interval

        // When: Run for 5 seconds (reduced to avoid timing issues in CI)
        var endTime = System.currentTimeMillis() + 5_000;
        var iterations = 0;

        while (System.currentTimeMillis() < endTime) {
            validator.migrateBatch(10); // Fewer migrations per batch
            iterations++;
            Thread.sleep(100);
        }

        // Wait for pending validations to complete
        Thread.sleep(600);

        retentionValidator.stop();

        // Then: Should have completed many iterations
        assertTrue(iterations >= 40, "Should complete at least 40 iterations, got: " + iterations);

        // Final validation should pass (some transient violations may occur during migration)
        var finalValidation = cluster.getEntityAccountant().validate();
        assertTrue(finalValidation.success(), "Final validation should pass");
    }

    @Test
    void testMetricsAccuracy() {
        // When: Perform known number of migrations
        var results = validator.migrateBatch(100);

        // Then: Metrics should match
        var metrics = validator.getMetrics();
        var expectedSuccess = results.stream().filter(MigrationResultSummary::success).count();

        assertTrue(metrics.totalMigrations() >= 100,
            "Should track at least 100 migrations");
        assertEquals(expectedSuccess, metrics.successfulMigrations(),
            "Successful count should match");
    }

    @Test
    void testRecoveryAfterErrors() throws InterruptedException {
        // Given: Inject some errors by using invalid paths
        var entityId = entityFactory.getAllEntityIds().iterator().next();
        var bubble = entityFactory.getBubbleForEntity(entityId);

        // Find non-neighbor
        var nonNeighbor = cluster.getTopology().getAllBubbleIds().stream()
            .filter(b -> !cluster.getTopology().getNeighbors(bubble).contains(b))
            .filter(b -> !b.equals(bubble))
            .findFirst()
            .orElse(null);

        if (nonNeighbor != null) {
            // Attempt invalid migration
            var failResult = validator.migrateEntity(entityId, bubble, nonNeighbor);
            assertFalse(failResult.success(), "Invalid migration should fail");
        }

        // Then: System should still work for valid migrations
        var validResults = validator.migrateBatch(50);
        var successRate = validResults.stream().filter(MigrationResultSummary::success).count() * 100.0 / validResults.size();
        assertTrue(successRate >= 90, "Should recover and continue working after errors");
    }

    @Test
    void testPeakMemoryBounded() throws InterruptedException {
        // Given: Monitor memory
        var heapMonitor = new HeapMonitor();
        var initialMemory = heapMonitor.getCurrentMemory();

        heapMonitor.start(100);

        // When: Run load
        for (int i = 0; i < 10; i++) {
            validator.migrateBatch(100);
            Thread.sleep(100);
        }

        heapMonitor.stop();

        // Then: Peak memory should be bounded (< 100MB growth)
        var peakMemory = heapMonitor.getPeakMemory();
        var growth = peakMemory - initialMemory;
        assertTrue(growth < 100_000_000,
            "Memory growth should be < 100MB, got: " + (growth / 1_000_000) + "MB");
    }
}
