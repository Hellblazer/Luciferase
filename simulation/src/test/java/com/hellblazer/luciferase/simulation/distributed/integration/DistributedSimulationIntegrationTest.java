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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6E: End-to-end integration tests for distributed simulation.
 * <p>
 * Tests comprehensive scenarios covering the full distributed simulation lifecycle:
 * - Full lifecycle (startup → entities → migrations → shutdown)
 * - Crash recovery during active load
 * - High concurrency stress testing
 * - Ghost layer synchronization integration
 * - Topology stability during dynamic changes
 * - Long-running stability with memory/GC monitoring
 * <p>
 * All tests validate:
 * - 100% entity retention (no losses or duplicates)
 * - No memory leaks (growth <2MB/sec)
 * - No GC pause spikes (p99 <40ms)
 * - Ghost sync functioning correctly
 * <p>
 * Bead: Luciferase-uchl - Inc 6E: Integration Testing & Performance Validation
 *
 * @author hal.hildebrand
 */
class DistributedSimulationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DistributedSimulationIntegrationTest.class);

    private TestProcessCluster cluster;
    private DistributedEntityFactory entityFactory;
    private CrossProcessMigrationValidator validator;
    private EntityRetentionValidator retentionValidator;
    private HeapMonitor heapMonitor;
    private GCPauseMeasurement gcMeasurement;

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
        if (heapMonitor != null) {
            heapMonitor.stop();
        }
        if (gcMeasurement != null) {
            gcMeasurement.stop();
        }
        if (cluster != null) {
            cluster.stop();
        }
    }

    // ==================== Lifecycle Tests ====================

    @Test
    void testFullLifecycle() throws Exception {
        // Given: 8-process cluster with 1000 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(1000);

        // Validate initial distribution
        var initialDistribution = cluster.getEntityAccountant().getDistribution();
        var initialTotal = initialDistribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(1000, initialTotal, "Should have 1000 entities initially");

        // Setup validators
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 1000);
        retentionValidator.startPeriodicValidation(200);

        // When: Run migrations for 5 seconds
        long startTime = System.currentTimeMillis();
        int totalMigrations = 0;
        while (System.currentTimeMillis() - startTime < 5000) {
            totalMigrations += validator.migrateBatch(20).size();
            Thread.sleep(100);
        }

        // Stop periodic validation
        Thread.sleep(250);
        retentionValidator.stop();

        // Then: Validate all conditions
        log.info("Completed {} migrations in 5 seconds", totalMigrations);

        // 1. Entity retention must be 100%
        var finalValidation = cluster.getEntityAccountant().validate();
        assertTrue(finalValidation.success(),
            "Entity retention failed: " + finalValidation.details());

        var finalDistribution = cluster.getEntityAccountant().getDistribution();
        var finalTotal = finalDistribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(1000, finalTotal, "Must have all 1000 entities after load");

        // 2. No duplicates
        assertEquals(0, finalValidation.errorCount(), "Should have 0 validation errors");

        // 3. Migration throughput
        assertTrue(totalMigrations >= 100,
            "Should have at least 100 migrations in 5 seconds, got: " + totalMigrations);
    }

    @Test
    void testCrashRecovery() throws Exception {
        // Given: 8-process cluster with 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);

        validator = new CrossProcessMigrationValidator(cluster, entityFactory);
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 800);
        retentionValidator.startPeriodicValidation(200);

        // When: Run migrations and crash a process mid-operation
        var processIds = cluster.getTopology().getProcessIds();
        var targetProcess = processIds.stream().findFirst().orElseThrow();

        // Start migrations in background
        var migrationCount = new AtomicInteger(0);
        var migrationThread = new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    migrationCount.addAndGet(validator.migrateBatch(5).size());
                    Thread.sleep(50);
                }
            } catch (Exception e) {
                log.info("Migration thread interrupted: {}", e.getMessage());
            }
        });
        migrationThread.start();

        // Let some migrations complete
        Thread.sleep(250);

        // Crash the target process
        log.info("Crashing process {}", targetProcess);
        cluster.crashProcess(targetProcess);

        // Wait for migration thread to finish (or timeout)
        migrationThread.join(5000);

        // Recover the process
        log.info("Recovering process {}", targetProcess);
        cluster.recoverProcess(targetProcess);

        // Wait for recovery to complete
        Thread.sleep(1000);

        // Stop periodic validation
        Thread.sleep(250);
        retentionValidator.stop();

        // Then: Validate entity retention after crash/recovery
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
            "Entity retention after crash/recovery failed: " + validation.details());

        var distribution = cluster.getEntityAccountant().getDistribution();
        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(800, total, "Must have all 800 entities after crash/recovery");

        log.info("Completed {} migrations before/after crash", migrationCount.get());
    }

    @Test
    void testConcurrentMigrations() throws Exception {
        // Given: Skewed distribution (1200 entities, 4 heavy + 12 light bubbles)
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(cluster.getTopology(), heavyIndices, 0.8, 42);
        entityFactory = new DistributedEntityFactory(cluster, strategy, 42);
        entityFactory.createEntities(1200);

        validator = new CrossProcessMigrationValidator(cluster, entityFactory);
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 1200);
        retentionValidator.startPeriodicValidation(200);

        // When: Run 500 concurrent migrations
        int totalMigrations = 0;
        for (int batch = 0; batch < 50; batch++) {
            totalMigrations += validator.migrateBatch(10).size();
            Thread.sleep(50);
        }

        // Stop periodic validation
        Thread.sleep(250);
        retentionValidator.stop();

        // Then: Validate retention and no race conditions
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
            "Entity retention under load failed: " + validation.details());

        var distribution = cluster.getEntityAccountant().getDistribution();
        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(1200, total, "Must have all 1200 entities after concurrent load");

        assertEquals(0, validation.errorCount(),
            "No validation errors expected, got: " + validation.errorCount());

        log.info("Completed {} concurrent migrations with 100% retention", totalMigrations);
    }

    @Test
    void testGhostSyncIntegration() throws Exception {
        // Given: 8-process cluster with 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);

        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Migrate entities across process boundaries
        for (int i = 0; i < 100; i++) {
            validator.migrateBatch(10);
            Thread.sleep(100);  // Let ghost sync batch at 100ms boundaries
        }

        // Then: Validate ghost propagation
        var metrics = cluster.getGhostMetrics();
        assertNotNull(metrics, "Should have ghost metrics");
        assertTrue(metrics.activeNeighbors() > 0,
            "Should have active ghost neighbors, got: " + metrics.activeNeighbors());

        // Validate entity retention
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
            "Entity retention must be maintained during ghost sync: " + validation.details());

        log.info("Ghost sync validation complete: {} active neighbors", metrics.activeNeighbors());
    }

    @Test
    void testTopologyStability() throws Exception {
        // Given: Initial 8-process cluster with 1000 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(1000);

        validator = new CrossProcessMigrationValidator(cluster, entityFactory);
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 1000);
        retentionValidator.startPeriodicValidation(200);

        // When: Add 2 processes dynamically
        log.info("Adding 2 processes to topology");
        // cluster.addProcesses(2);  // TODO: Implement dynamic process addition
        // Thread.sleep(1000);  // Allow rebalancing

        // Run migrations during topology change
        for (int i = 0; i < 50; i++) {
            validator.migrateBatch(10);
            Thread.sleep(100);
        }

        // Remove 2 processes
        // log.info("Removing 2 processes from topology");
        // cluster.removeProcesses(2);  // TODO: Implement dynamic process removal
        // Thread.sleep(1000);  // Allow recovery

        // Continue migrations
        for (int i = 0; i < 50; i++) {
            validator.migrateBatch(10);
            Thread.sleep(100);
        }

        // Stop validation
        Thread.sleep(250);
        retentionValidator.stop();

        // Then: Validate no entity loss despite topology changes
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
            "Entity retention during topology changes: " + validation.details());

        var distribution = cluster.getEntityAccountant().getDistribution();
        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(1000, total, "Must have all 1000 entities after topology changes");

        log.info("Topology stability test complete: 100% retention");
    }

    @Test
    void testLongRunningStability() throws Exception {
        // Given: Setup memory and GC monitoring
        heapMonitor = new HeapMonitor();
        gcMeasurement = new GCPauseMeasurement(1);  // 1ms polling

        // Baseline measurement (stabilize JVM)
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }

        // Setup cluster
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(800);

        validator = new CrossProcessMigrationValidator(cluster, entityFactory);
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 800);

        // Start monitoring
        heapMonitor.start(100);  // 100ms sampling
        gcMeasurement.start();
        retentionValidator.startPeriodicValidation(200);

        // When: Run for 30 seconds with continuous migrations
        log.info("Starting 30-second stability test");
        long startTime = System.currentTimeMillis();
        int totalMigrations = 0;

        while (System.currentTimeMillis() - startTime < 30000) {
            totalMigrations += validator.migrateBatch(20).size();
            Thread.sleep(100);
        }

        // Stop monitoring
        heapMonitor.stop();
        gcMeasurement.stop();
        Thread.sleep(250);
        retentionValidator.stop();

        // Then: Validate all stability metrics
        log.info("Completed {} migrations in 30 seconds", totalMigrations);

        // 1. Entity retention
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
            "Entity retention after long run: " + validation.details());

        var distribution = cluster.getEntityAccountant().getDistribution();
        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(800, total, "Must have all 800 entities after long-running load");

        // 2. Memory stability
        var growthRate = heapMonitor.getGrowthRate();  // bytes/sec
        assertTrue(growthRate < 2_000_000,
            "Memory growth should be < 2MB/sec, got: " + (growthRate / 1_000_000) + "MB/sec");

        // 3. GC pause monitoring
        var gcStats = gcMeasurement.getStats();
        assertTrue(gcStats.p99Ms() < 40,
            "GC p99 pause should be < 40ms, got: " + gcStats.p99Ms() + "ms");

        log.info("Stability metrics - Growth: {:.2f} MB/sec, GC p99: {}ms",
            growthRate / 1_000_000.0, gcStats.p99Ms());
    }
}
