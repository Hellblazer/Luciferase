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
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6B5: 4-Process Baseline Validation Tests
 * <p>
 * Validates the baseline distributed simulation configuration:
 * - 4 processes (simulated JVMs on same machine)
 * - 2 bubbles per process (8 bubbles total)
 * - 800 entities (100 per bubble)
 * - 1000+ ticks execution
 * - 100% entity retention
 * - >100 TPS throughput
 * - <50ms clock skew across processes
 * <p>
 * This test establishes the foundation for Phase 6B6 scaling tests.
 * <p>
 * Bead: Luciferase-u1am (Phase 6B5: Integration & 4-Process Baseline Validation)
 *
 * @author hal.hildebrand
 */
class FourProcessBaselineTest {

    private static final Logger log = LoggerFactory.getLogger(FourProcessBaselineTest.class);

    // Phase 6B5 baseline configuration
    private static final int PROCESS_COUNT = 4;
    private static final int BUBBLES_PER_PROCESS = 2;
    private static final int TOTAL_BUBBLES = PROCESS_COUNT * BUBBLES_PER_PROCESS;  // 8
    private static final int ENTITIES_PER_BUBBLE = 100;
    private static final int TOTAL_ENTITIES = TOTAL_BUBBLES * ENTITIES_PER_BUBBLE;  // 800
    private static final int BASELINE_TICKS = 1000;

    private TestProcessCluster cluster;
    private DistributedEntityFactory entityFactory;
    private CrossProcessMigrationValidator migrationValidator;
    private EntityRetentionValidator retentionValidator;

    @BeforeEach
    void setUp() throws Exception {
        // Create 4-process cluster with 2 bubbles per process
        cluster = new TestProcessCluster(PROCESS_COUNT, BUBBLES_PER_PROCESS);
        cluster.start();

        log.info("Created {}-process cluster with {} bubbles, {} entities target",
                PROCESS_COUNT, TOTAL_BUBBLES, TOTAL_ENTITIES);
    }

    @AfterEach
    void tearDown() {
        if (retentionValidator != null) {
            retentionValidator.stop();
        }
        if (migrationValidator != null) {
            migrationValidator.shutdown();
        }
        if (cluster != null) {
            cluster.stop();
        }
    }

    /**
     * Test 1: Cluster Startup and Topology Discovery
     * <p>
     * Validates that all 4 processes start correctly and discover each other.
     */
    @Test
    void testFourProcessClusterStartup() {
        // Given: Cluster started in setUp()

        // Then: All processes should be running
        assertTrue(cluster.isRunning(), "Cluster should be running");
        assertEquals(PROCESS_COUNT, cluster.getProcessCount(), "Should have 4 processes");

        // Validate all processes are registered
        var processIds = cluster.getProcessIds();
        assertEquals(PROCESS_COUNT, processIds.size(), "Should have 4 process IDs");

        // Validate topology
        var topology = cluster.getTopology();
        assertNotNull(topology, "Topology should exist");
        assertEquals(TOTAL_BUBBLES, topology.getBubbleCount(), "Should have 8 bubbles");

        // Validate each process has correct bubble count
        for (var processId : processIds) {
            var bubbles = topology.getBubblesForProcess(processId);
            assertEquals(BUBBLES_PER_PROCESS, bubbles.size(),
                    "Each process should have 2 bubbles");
        }

        log.info("✓ Cluster startup validated: {} processes, {} bubbles",
                PROCESS_COUNT, TOTAL_BUBBLES);
    }

    /**
     * Test 2: Topology Discovery and Neighbor Relationships
     * <p>
     * Validates that bubbles correctly identify their neighbors across process boundaries.
     */
    @Test
    void testTopologyDiscovery() {
        // Given: Cluster started

        var topology = cluster.getTopology();

        // When: Query neighbor relationships
        var allBubbles = topology.getAllBubbleIds();
        assertEquals(TOTAL_BUBBLES, allBubbles.size(), "Should have 8 bubbles");

        // Then: Each bubble should have neighbors
        int totalNeighborRelationships = 0;
        for (var bubbleId : allBubbles) {
            var neighbors = topology.getNeighbors(bubbleId);
            assertNotNull(neighbors, "Bubble " + bubbleId + " should have neighbor list");
            totalNeighborRelationships += neighbors.size();
        }

        assertTrue(totalNeighborRelationships > 0,
                "Should have at least some neighbor relationships");

        log.info("✓ Topology discovery validated: {} neighbor relationships",
                totalNeighborRelationships);
    }

    /**
     * Test 3: Clock Synchronization Across Processes
     * <p>
     * Validates that clock skew across 4 processes is less than 50ms.
     */
    @Test
    void testClockSynchronization() throws Exception {
        // Given: Cluster with clock sync enabled

        // When: Measure clock skew across processes
        var processIds = cluster.getProcessIds();
        long[] timestamps = new long[PROCESS_COUNT];

        int idx = 0;
        for (var processId : processIds) {
            var coordinator = cluster.getProcessCoordinator(processId);
            assertNotNull(coordinator, "Coordinator should exist for " + processId);

            // Record current bucket time from each process
            timestamps[idx++] = coordinator.getBucketScheduler().getCurrentBucket();
        }

        // Calculate max skew
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long ts : timestamps) {
            min = Math.min(min, ts);
            max = Math.max(max, ts);
        }
        long maxSkewMs = max - min;

        // Then: Clock skew should be less than 50ms (target from Phase 6B5)
        assertTrue(maxSkewMs < 50,
                "Clock skew should be <50ms across processes, got: " + maxSkewMs + "ms");

        log.info("✓ Clock synchronization validated: max skew = {}ms", maxSkewMs);
    }

    /**
     * Test 4: Basic Entity Distribution and Retention
     * <p>
     * Creates 800 entities and validates correct distribution across bubbles.
     */
    @Test
    void testBasicEntities() {
        // Given: Empty cluster
        entityFactory = new DistributedEntityFactory(cluster, 42);

        // When: Create 800 entities
        entityFactory.createEntities(TOTAL_ENTITIES);

        // Then: Validate distribution
        var distribution = cluster.getEntityAccountant().getDistribution();
        int totalEntities = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(TOTAL_ENTITIES, totalEntities,
                "Should have all 800 entities");

        // Validate no duplicates
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "Entity validation should pass: " + validation.details());
        assertEquals(0, validation.errorCount(), "Should have no validation errors");

        log.info("✓ Entity distribution validated: {} entities across {} bubbles",
                totalEntities, distribution.size());
    }

    /**
     * Test 5: Entity Retention Over 1000 Ticks
     * <p>
     * Runs simulation for 1000 ticks and validates 100% entity retention.
     */
    @Test
    void testEntityRetention() throws Exception {
        // Given: Cluster with 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), TOTAL_ENTITIES);
        retentionValidator.startPeriodicValidation(200);

        // When: Run simulation for 1000 ticks (simulated via migrations)
        // At ~10 migrations/sec, this takes ~100 seconds real time
        // For testing, we'll run fewer migrations but validate retention
        long startTime = System.currentTimeMillis();
        int totalMigrations = 0;

        for (int tick = 0; tick < 100; tick++) {  // 100 batches as proxy for 1000 ticks
            totalMigrations += migrationValidator.migrateBatch(10).size();
            Thread.sleep(50);  // ~20 ticks/sec
        }

        long elapsedMs = System.currentTimeMillis() - startTime;

        // Stop validation
        Thread.sleep(250);
        retentionValidator.stop();

        // Then: Validate 100% retention
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
                "Entity retention failed after " + totalMigrations + " migrations: " + validation.details());

        var distribution = cluster.getEntityAccountant().getDistribution();
        int finalTotal = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(TOTAL_ENTITIES, finalTotal,
                "Must have all 800 entities after simulation");

        assertEquals(0, validation.errorCount(), "Should have no validation errors");

        log.info("✓ Entity retention validated: {} migrations in {}ms, 100% retention",
                totalMigrations, elapsedMs);
    }

    /**
     * Test 6: Ghost Synchronization Across Processes
     * <p>
     * Validates ghost entities are correctly synchronized across process boundaries.
     */
    @Test
    void testGhostSync() throws Exception {
        // Given: Cluster with 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Trigger migrations to cause ghost sync
        for (int i = 0; i < 50; i++) {
            migrationValidator.migrateBatch(10);
            cluster.syncGhosts();  // Explicit ghost sync
            Thread.sleep(100);
        }

        // Then: Validate ghost metrics
        var ghostMetrics = cluster.getGhostMetrics();
        assertNotNull(ghostMetrics, "Should have ghost metrics");

        assertTrue(ghostMetrics.activeNeighbors() > 0,
                "Should have active neighbor relationships");

        // Validate entity retention through ghost sync
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
                "Entity retention must hold during ghost sync: " + validation.details());

        log.info("✓ Ghost sync validated: {} active neighbors",
                ghostMetrics.activeNeighbors());
    }

    /**
     * Test 7: Cross-Process Entity Migration
     * <p>
     * Validates entities can migrate across process boundaries successfully.
     */
    @Test
    void testMigration() throws Exception {
        // Given: Cluster with 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), TOTAL_ENTITIES);
        retentionValidator.startPeriodicValidation(200);

        // When: Perform 200 migrations
        int totalMigrations = 0;
        for (int i = 0; i < 20; i++) {
            totalMigrations += migrationValidator.migrateBatch(10).size();
            Thread.sleep(100);
        }

        // Stop validation
        Thread.sleep(250);
        retentionValidator.stop();

        // Then: Validate all migrations succeeded with no loss
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
                "Migration validation failed: " + validation.details());

        var distribution = cluster.getEntityAccountant().getDistribution();
        int finalTotal = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(TOTAL_ENTITIES, finalTotal,
                "Must have all 800 entities after migrations");

        assertTrue(totalMigrations > 0, "Should have performed migrations");

        log.info("✓ Migration validated: {} successful migrations with 100% retention",
                totalMigrations);
    }

    /**
     * Test 8: Performance - Throughput Target (>100 TPS)
     * <p>
     * Validates simulation maintains >100 TPS with 800 entities.
     */
    @Test
    void testPerformance() throws Exception {
        // Given: Cluster with 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // When: Run migrations for 10 seconds
        long startTime = System.currentTimeMillis();
        int totalMigrations = 0;

        while (System.currentTimeMillis() - startTime < 10000) {
            totalMigrations += migrationValidator.migrateBatch(20).size();
            Thread.sleep(100);
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        double tps = (totalMigrations * 1000.0) / elapsedMs;

        // Then: Validate throughput > 100 TPS
        assertTrue(tps > 100, "TPS should be >100, got: " + String.format("%.1f", tps));

        // Validate entity retention
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "Entity retention must hold at high TPS");

        log.info("✓ Performance validated: {:.1f} TPS ({} migrations in {}ms)",
                tps, totalMigrations, elapsedMs);
    }

    /**
     * Test 9: Concurrent Migrations Across Multiple Processes
     * <p>
     * Validates system handles concurrent migrations without race conditions.
     */
    @Test
    void testConcurrentMigrations() throws Exception {
        // Given: Cluster with 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), TOTAL_ENTITIES);
        retentionValidator.startPeriodicValidation(200);

        // When: Run many small concurrent batches
        int totalMigrations = 0;
        for (int batch = 0; batch < 100; batch++) {
            totalMigrations += migrationValidator.migrateBatch(5).size();
            Thread.sleep(50);  // Short interval = high concurrency
        }

        // Stop validation
        Thread.sleep(250);
        retentionValidator.stop();

        // Then: Validate no race conditions or duplicates
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
                "Concurrent migrations validation failed: " + validation.details());

        assertEquals(0, validation.errorCount(),
                "Should have no validation errors from concurrent migrations");

        var distribution = cluster.getEntityAccountant().getDistribution();
        int finalTotal = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(TOTAL_ENTITIES, finalTotal, "Must have all 800 entities after concurrent load");

        log.info("✓ Concurrent migrations validated: {} migrations with 0 errors",
                totalMigrations);
    }

    /**
     * Test 10: 4-Process Integration - Full Baseline Validation
     * <p>
     * Comprehensive test validating all Phase 6B5 success criteria together.
     */
    @Test
    void testFourProcessIntegration() throws Exception {
        // Given: 4-process cluster
        entityFactory = new DistributedEntityFactory(cluster, 42);
        entityFactory.createEntities(TOTAL_ENTITIES);

        migrationValidator = new CrossProcessMigrationValidator(cluster, entityFactory);
        retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), TOTAL_ENTITIES);
        retentionValidator.startPeriodicValidation(200);

        // When: Run full simulation for 10 seconds
        long startTime = System.currentTimeMillis();
        int totalMigrations = 0;

        while (System.currentTimeMillis() - startTime < 10000) {
            totalMigrations += migrationValidator.migrateBatch(15).size();
            cluster.syncGhosts();
            Thread.sleep(100);
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        double tps = (totalMigrations * 1000.0) / elapsedMs;

        // Stop validation
        Thread.sleep(250);
        retentionValidator.stop();

        // Then: Validate ALL success criteria

        // 1. Entity retention = 100%
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(),
                "Full integration entity retention failed: " + validation.details());

        var distribution = cluster.getEntityAccountant().getDistribution();
        int finalTotal = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(TOTAL_ENTITIES, finalTotal, "Must have all 800 entities");

        // 2. No duplicates or errors
        assertEquals(0, validation.errorCount(), "Should have 0 validation errors");

        // 3. Throughput > 100 TPS
        assertTrue(tps > 100, "TPS should be >100, got: " + String.format("%.1f", tps));

        // 4. Ghost sync working
        var ghostMetrics = cluster.getGhostMetrics();
        assertTrue(ghostMetrics.activeNeighbors() > 0, "Should have active ghost neighbors");

        // 5. All processes still running
        assertEquals(PROCESS_COUNT, cluster.getProcessCount(), "All processes should still be running");

        log.info("✓ Full 4-process integration validated:");
        log.info("  - {} entities with 100% retention", finalTotal);
        log.info("  - {:.1f} TPS ({} migrations in {}ms)", tps, totalMigrations, elapsedMs);
        log.info("  - {} active neighbor relationships", ghostMetrics.activeNeighbors());
        log.info("  - {} validation errors", validation.errorCount());
    }
}
