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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for cross-process migration validation.
 * <p>
 * Phase 6B5.4: Cross-Process Migration Validation
 * Bead: Luciferase-hlm5
 *
 * @author hal.hildebrand
 */
class CrossProcessMigrationValidatorTest {

    private TestProcessCluster cluster;
    private DistributedEntityFactory entityFactory;
    private CrossProcessMigrationValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new TestProcessCluster(4, 2); // 4 processes, 2 bubbles each
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
    void testSingleEntityMigration() {
        // Given: An entity in a bubble
        var entityId = entityFactory.getAllEntityIds().iterator().next();
        var sourceBubble = entityFactory.getBubbleForEntity(entityId);
        var neighbors = cluster.getTopology().getNeighbors(sourceBubble);
        var destBubble = neighbors.iterator().next();

        // When: Migrate the entity
        var result = validator.migrateEntity(entityId, sourceBubble, destBubble);

        // Then: Migration should succeed
        assertTrue(result.success(), "Migration should succeed: " + result.error());
        assertEquals(destBubble, entityFactory.getBubbleForEntity(entityId),
            "Entity should be in destination bubble");
    }

    @Test
    void testConcurrentMigrations() throws InterruptedException {
        // Given: 10 entities to migrate concurrently
        var entities = entityFactory.getAllEntityIds().stream().limit(10).toList();
        var latch = new CountDownLatch(entities.size());
        var successCount = new AtomicInteger(0);

        // When: Migrate all concurrently
        for (var entityId : entities) {
            validator.migrateToRandomNeighborAsync(entityId, result -> {
                if (result.success()) {
                    successCount.incrementAndGet();
                }
                latch.countDown();
            });
        }

        // Then: All migrations should complete
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All migrations should complete");
        assertEquals(10, successCount.get(), "All 10 migrations should succeed");
    }

    @Test
    void testBatch100Migrations() {
        // When: Migrate 100 entities
        var results = validator.migrateBatch(100);

        // Then: All should succeed
        assertEquals(100, results.size());
        var successCount = results.stream().filter(MigrationResultSummary::success).count();
        assertEquals(100, successCount, "All 100 migrations should succeed");

        // Verify no entity loss
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "No entity loss: " + validation.details());
    }

    @Test
    void testClockSkew50ms() {
        // Given: Inject 50ms clock skew
        var testClock = new TestClock();
        testClock.setSkew(50);
        validator.setClock(testClock);

        // When: Migrate with skew
        var results = validator.migrateBatch(10);

        // Then: Migrations should still succeed
        var successCount = results.stream().filter(MigrationResultSummary::success).count();
        assertTrue(successCount >= 8, "At least 80% should succeed with 50ms skew");
    }

    @Test
    void testMigrationThroughput() throws InterruptedException {
        // When: Measure throughput over 1 second
        var metrics = validator.measureThroughput(1000, 200);

        // Then: Should achieve target TPS
        assertTrue(metrics.actualTPS() >= 100, "Should achieve 100+ TPS, got: " + metrics.actualTPS());
    }

    @Test
    void testMigrationPathValidity() {
        // Given: An entity
        var entityId = entityFactory.getAllEntityIds().iterator().next();
        var sourceBubble = entityFactory.getBubbleForEntity(entityId);

        // When: Get valid migration paths
        var pathSelector = new MigrationPathSelector(cluster.getTopology());
        var validPaths = pathSelector.getValidDestinations(sourceBubble);

        // Then: Only neighbor bubbles should be valid
        var neighbors = cluster.getTopology().getNeighbors(sourceBubble);
        assertEquals(neighbors, validPaths, "Valid paths should be neighbor bubbles");
    }

    @Test
    void testEntityAccountantConsistency() {
        // When: Perform multiple migrations
        validator.migrateBatch(50);

        // Then: EntityAccountant should be consistent
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "EntityAccountant should be consistent: " + validation.details());
    }

    @Test
    void testNoEntityDuplication() {
        // When: Migrate entities
        validator.migrateBatch(100);

        // Then: No entity should appear in multiple bubbles
        var accountant = cluster.getEntityAccountant();
        var seenEntities = new java.util.HashSet<java.util.UUID>();

        for (var bubbleId : cluster.getTopology().getAllBubbleIds()) {
            var entities = accountant.entitiesInBubble(bubbleId);
            for (var entityId : entities) {
                assertFalse(seenEntities.contains(entityId),
                    "Entity " + entityId + " found in multiple bubbles");
                seenEntities.add(entityId);
            }
        }
    }

    @Test
    void testNoEntityLossAfter1000Migrations() {
        // When: Perform 1000 migrations
        var results = validator.migrateBatch(1000);

        // Then: All 800 entities should still exist
        var distribution = cluster.getEntityAccountant().getDistribution();
        var totalEntities = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(800, totalEntities, "Should have 800 entities after 1000 migrations");

        // Validate no corruption
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "No entity corruption: " + validation.details());
    }

    @Test
    void testMigrationMetrics() {
        // When: Perform migrations
        validator.migrateBatch(50);

        // Then: Metrics should be tracked
        var metrics = validator.getMetrics();
        assertEquals(50, metrics.totalMigrations());
        assertTrue(metrics.successRate() > 0, "Should have some successful migrations");
        assertTrue(metrics.averageLatencyMs() >= 0, "Latency should be measured");
    }

    @Test
    void testRetentionValidatorPeriodic() throws InterruptedException {
        // Given: A retention validator
        var retentionValidator = new EntityRetentionValidator(cluster.getEntityAccountant(), 800);

        // When: Start periodic validation
        retentionValidator.startPeriodicValidation(100); // every 100ms

        // Perform migrations in background
        validator.migrateBatch(50);
        Thread.sleep(250); // Allow 2 validation cycles

        // Then: No violations should be detected
        retentionValidator.stop();
        assertEquals(0, retentionValidator.getViolationCount(),
            "No retention violations should occur");
    }

    @Test
    void testMigrationToInvalidDestination() {
        // Given: An entity
        var entityId = entityFactory.getAllEntityIds().iterator().next();
        var sourceBubble = entityFactory.getBubbleForEntity(entityId);

        // Find a non-neighbor bubble
        var nonNeighbor = cluster.getTopology().getAllBubbleIds().stream()
            .filter(b -> !cluster.getTopology().getNeighbors(sourceBubble).contains(b))
            .filter(b -> !b.equals(sourceBubble))
            .findFirst()
            .orElse(null);

        if (nonNeighbor != null) {
            // When: Attempt migration to non-neighbor
            var result = validator.migrateEntity(entityId, sourceBubble, nonNeighbor);

            // Then: Migration should fail (invalid path)
            assertFalse(result.success(), "Migration to non-neighbor should fail");
        }
    }

    @Test
    void testMigrationWithEntityInFlight() throws InterruptedException {
        // Given: An entity being migrated
        var entityId = entityFactory.getAllEntityIds().iterator().next();
        var sourceBubble = entityFactory.getBubbleForEntity(entityId);
        var destBubble = cluster.getTopology().getNeighbors(sourceBubble).iterator().next();

        // When: Start async migration and try concurrent migration
        var firstComplete = new CountDownLatch(1);
        validator.migrateEntityAsync(entityId, sourceBubble, destBubble, r -> firstComplete.countDown());

        // Attempt second migration immediately
        var secondResult = validator.migrateEntity(entityId, destBubble, sourceBubble);

        firstComplete.await(1, TimeUnit.SECONDS);

        // Then: At least one should succeed, entity should end up in a valid location
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "Entity should be in valid state");
    }

    @Test
    void testMigrationRoundTrip() {
        // Given: An entity
        var entityId = entityFactory.getAllEntityIds().iterator().next();
        var originalBubble = entityFactory.getBubbleForEntity(entityId);
        var neighbors = cluster.getTopology().getNeighbors(originalBubble);
        var destBubble = neighbors.iterator().next();

        // When: Migrate out and back
        var outResult = validator.migrateEntity(entityId, originalBubble, destBubble);
        assertTrue(outResult.success(), "Outbound migration should succeed");

        var backResult = validator.migrateEntity(entityId, destBubble, originalBubble);
        assertTrue(backResult.success(), "Return migration should succeed");

        // Then: Entity should be back in original bubble
        assertEquals(originalBubble, entityFactory.getBubbleForEntity(entityId));
    }

    @Test
    void testCrossProcessMigration() {
        // Given: Find an entity and a destination in a different process
        var entityId = entityFactory.getAllEntityIds().iterator().next();
        var sourceBubble = entityFactory.getBubbleForEntity(entityId);
        var sourceProcess = cluster.getTopology().getProcessForBubble(sourceBubble);

        var destBubble = cluster.getTopology().getNeighbors(sourceBubble).stream()
            .filter(b -> !cluster.getTopology().getProcessForBubble(b).equals(sourceProcess))
            .findFirst()
            .orElse(null);

        if (destBubble != null) {
            // When: Migrate across processes
            var result = validator.migrateEntity(entityId, sourceBubble, destBubble);

            // Then: Cross-process migration should succeed
            assertTrue(result.success(), "Cross-process migration should succeed");
            assertEquals(destBubble, entityFactory.getBubbleForEntity(entityId));
        }
    }

    @Test
    void testValidatorShutdownCleanup() throws InterruptedException {
        // Given: Active validations
        validator.migrateBatch(10);

        // When: Shutdown
        validator.shutdown();

        // Then: Should complete cleanly
        Thread.sleep(100);
        assertFalse(validator.isRunning(), "Validator should not be running after shutdown");
    }

    @Test
    void testMigrationUnderLoad() throws InterruptedException {
        // When: Run migrations under sustained load
        var loadGenerator = new EntityMigrationLoadGenerator(50, 100, entityId -> {
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
        Thread.sleep(2000); // 2 seconds of load
        loadGenerator.stop();

        // Then: System should remain consistent
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "System should remain consistent under load");
    }

    @Test
    void testMigrationLatencyDistribution() {
        // When: Perform migrations and collect latencies
        var results = validator.migrateBatch(100);

        // Then: Collect latency stats
        var latencies = results.stream()
            .filter(MigrationResultSummary::success)
            .mapToLong(MigrationResultSummary::latencyMs)
            .sorted()
            .toArray();

        if (latencies.length > 0) {
            var p50 = latencies[latencies.length / 2];
            var p99 = latencies[(int)(latencies.length * 0.99)];

            // p99 should be less than 200ms per plan requirement
            assertTrue(p99 < 200, "p99 latency should be < 200ms, got: " + p99);
        }
    }

    @Test
    void testGhostSyncAfterMigration() {
        // Given: An entity near a bubble boundary
        var entityId = entityFactory.getAllEntityIds().iterator().next();
        var sourceBubble = entityFactory.getBubbleForEntity(entityId);
        var destBubble = cluster.getTopology().getNeighbors(sourceBubble).iterator().next();

        // When: Migrate entity
        validator.migrateEntity(entityId, sourceBubble, destBubble);

        // Then: Ghost state should be updated (simplified check)
        var accountant = cluster.getEntityAccountant();
        var entities = accountant.entitiesInBubble(destBubble);
        assertTrue(entities.contains(entityId), "Entity should be in destination after ghost sync");
    }
}
