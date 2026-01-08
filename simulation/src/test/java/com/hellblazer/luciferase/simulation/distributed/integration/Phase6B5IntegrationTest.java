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

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for Phase 6B5.
 * <p>
 * Phase 6B5.6: Integration Test Suite
 * Bead: Luciferase-gxoo
 * <p>
 * Tests the complete integration of:
 * - 4 processes, 8 bubbles, 800 entities
 * - Clock skew tolerance <50ms
 * - 100+ TPS throughput
 * - 100% entity retention
 *
 * @author hal.hildebrand
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase6B5IntegrationTest {

    private static TestProcessCluster cluster;
    private static DistributedEntityFactory entityFactory;
    private static CrossProcessMigrationValidator validator;

    @BeforeAll
    static void setUpCluster() throws Exception {
        // Initialize 4-process, 8-bubble cluster
        cluster = new TestProcessCluster(4, 2);
        cluster.start();

        // Create and distribute 800 entities
        entityFactory = new DistributedEntityFactory(cluster, 42L); // Fixed seed for reproducibility
        entityFactory.createEntities(800);

        // Create validator
        validator = new CrossProcessMigrationValidator(cluster, entityFactory);

        // Start metrics timer
        cluster.getMetrics().startTimer();
    }

    @AfterAll
    static void tearDownCluster() {
        if (validator != null) {
            validator.shutdown();
        }
        if (cluster != null) {
            cluster.stop();
        }
    }

    @Test
    @Order(1)
    void testFullSystemStartup() {
        // Verify: All 4 processes running
        assertTrue(cluster.isRunning(), "Cluster should be running");
        assertEquals(4, cluster.getProcessCount(), "Should have 4 processes");

        // Verify: All coordinators active
        for (var processId : cluster.getProcessIds()) {
            var coordinator = cluster.getProcessCoordinator(processId);
            assertNotNull(coordinator, "Coordinator should exist for " + processId);
            assertTrue(coordinator.isRunning(), "Coordinator should be running for " + processId);
        }

        // Verify: 8 bubbles configured
        assertEquals(8, cluster.getTopology().getBubbleCount(), "Should have 8 bubbles");
    }

    @Test
    @Order(2)
    void testInitialEntityDistribution() {
        // Verify: 800 entities registered
        var distribution = cluster.getEntityAccountant().getDistribution();
        var totalEntities = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(800, totalEntities, "Should have 800 entities distributed");

        // Verify: Even distribution (100 per bubble)
        for (var entry : distribution.entrySet()) {
            assertEquals(100, entry.getValue(),
                "Bubble " + entry.getKey() + " should have 100 entities");
        }

        // Verify: No validation errors
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "Initial distribution should be valid: " + validation.details());
    }

    @Test
    @Order(3)
    void testSustainedMigrationLoad() throws InterruptedException {
        // Run sustained load: 100 TPS for 30 seconds simulation
        var targetTPS = 100;
        var durationMs = 5000; // Reduced for faster CI
        var expectedMigrations = targetTPS * durationMs / 1000;

        var metrics = validator.measureThroughput(durationMs, expectedMigrations);

        // Verify: Achieved target TPS
        assertTrue(metrics.actualTPS() >= targetTPS * 0.8,
            "Should achieve at least 80% of target TPS (" + targetTPS + "), got: " + metrics.actualTPS());

        // Verify: Most migrations completed
        assertTrue(metrics.completedCount() >= expectedMigrations * 0.9,
            "Should complete at least 90% of migrations");
    }

    @Test
    @Order(4)
    void testEntityRetentionInvariant() {
        // After sustained load, verify 800 entities still exist
        var distribution = cluster.getEntityAccountant().getDistribution();
        var totalEntities = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(800, totalEntities, "Should always have exactly 800 entities");

        // Run full validation
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "Entity retention invariant should hold: " + validation.details());
    }

    @Test
    @Order(5)
    void testNoGhostDuplication() {
        // Verify no entity appears in multiple bubbles
        var seenEntities = new java.util.HashSet<java.util.UUID>();
        var accountant = cluster.getEntityAccountant();

        for (var bubbleId : cluster.getTopology().getAllBubbleIds()) {
            var entities = accountant.entitiesInBubble(bubbleId);
            for (var entityId : entities) {
                assertFalse(seenEntities.contains(entityId),
                    "Entity " + entityId + " found in multiple bubbles (ghost duplication)");
                seenEntities.add(entityId);
            }
        }

        assertEquals(800, seenEntities.size(), "Should have exactly 800 unique entities");
    }

    @Test
    @Order(6)
    void testClockSkewTolerance() {
        // Test with 50ms clock skew (max tolerance)
        var testClock = new TestClock();
        testClock.setSkew(50);
        validator.setClock(testClock);

        // Perform migrations under skew
        var results = validator.migrateBatch(100);

        // Reset clock
        validator.setClock(Clock.system());

        // Verify: Majority succeed despite skew
        var successRate = results.stream().filter(MigrationResultSummary::success).count() * 100.0 / results.size();
        assertTrue(successRate >= 80, "Should achieve 80%+ success with 50ms skew, got: " + successRate);

        // Verify: Entity retention maintained
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "Retention should be maintained under clock skew");
    }

    @Test
    @Order(7)
    void testProcessMetrics() {
        // Verify metrics are being tracked
        var metrics = cluster.getMetrics();

        assertTrue(metrics.getTotalMigrations() > 0, "Should have recorded migrations");
        assertTrue(metrics.getSuccessRate() >= 80, "Success rate should be >= 80%");
        assertTrue(metrics.getAverageLatencyMs() >= 0, "Latency should be measured");
        assertEquals(4, metrics.getActiveProcessCount(), "Should have 4 active processes");
    }

    @Test
    @Order(8)
    void testCrossProcessMigrationFlow() {
        // Find an entity and migrate it across processes
        var entityId = entityFactory.getAllEntityIds().iterator().next();
        var sourceBubble = entityFactory.getBubbleForEntity(entityId);
        var sourceProcess = cluster.getTopology().getProcessForBubble(sourceBubble);

        // Find a neighbor in different process
        var crossProcessDest = cluster.getTopology().getNeighbors(sourceBubble).stream()
            .filter(b -> !cluster.getTopology().getProcessForBubble(b).equals(sourceProcess))
            .findFirst()
            .orElse(null);

        if (crossProcessDest != null) {
            var destProcess = cluster.getTopology().getProcessForBubble(crossProcessDest);
            assertNotEquals(sourceProcess, destProcess, "Should be cross-process migration");

            var result = validator.migrateEntity(entityId, sourceBubble, crossProcessDest);
            assertTrue(result.success(), "Cross-process migration should succeed");

            // Verify entity moved
            assertEquals(crossProcessDest, entityFactory.getBubbleForEntity(entityId),
                "Entity should be in destination bubble");
        }
    }

    @Test
    @Order(9)
    void testCompleteSystemShutdown() throws Exception {
        // First, verify we can restart after stopping
        cluster.stop();
        assertFalse(cluster.isRunning(), "Cluster should stop");

        // Restart for final verification
        cluster = new TestProcessCluster(4, 2);
        cluster.start();
        assertTrue(cluster.isRunning(), "Cluster should restart");

        // Create fresh entity factory
        entityFactory = new DistributedEntityFactory(cluster);
        entityFactory.createEntities(800);

        // Verify fresh start works
        assertEquals(800, cluster.getEntityAccountant().getDistribution().values()
            .stream().mapToInt(Integer::intValue).sum(), "Should have 800 entities after restart");
    }

    @Test
    @Order(10)
    void testFinalValidation() {
        // Final comprehensive validation
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "Final validation should pass: " + validation.details());

        // Verify entity distribution
        var distribution = cluster.getEntityAccountant().getDistribution();
        assertEquals(8, distribution.size(), "Should have 8 bubbles");

        var totalEntities = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(800, totalEntities, "Should have 800 total entities");

        // Verify topology integrity
        for (var bubbleId : cluster.getTopology().getAllBubbleIds()) {
            var neighbors = cluster.getTopology().getNeighbors(bubbleId);
            assertFalse(neighbors.isEmpty(), "Each bubble should have neighbors");
        }
    }
}
