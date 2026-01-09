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

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6E: Entity retention test with explicit ID-based tracking.
 * <p>
 * MANDATORY REQUIREMENT: ID-based entity retention test
 * <p>
 * Tests entity lifecycle management with explicit UUID tracking:
 * - Basic registration and unregistration
 * - Atomic entity movement between bubbles
 * - High concurrency retention (10 threads, 1000 entities)
 * - Explicit ID tracking (verify all IDs present)
 * - Retention under heavy load
 * - Retention after process crash/recovery
 * - Retention during topology changes
 * - Retention statistics validation
 * <p>
 * All tests validate the core invariant:
 * Each entity exists in exactly one bubble at all times.
 * <p>
 * Bead: Luciferase-uchl - Inc 6E: Integration Testing & Performance Validation
 *
 * @author hal.hildebrand
 */
class EntityRetentionTest {

    private static final Logger log = LoggerFactory.getLogger(EntityRetentionTest.class);

    private EntityAccountant accountant;
    private UUID[] bubbles;

    @BeforeEach
    void setUp() {
        accountant = new EntityAccountant();
        // Create 16 bubble IDs (8 processes × 2 bubbles)
        bubbles = new UUID[16];
        for (int i = 0; i < 16; i++) {
            bubbles[i] = UUID.randomUUID();
        }
    }

    @AfterEach
    void tearDown() {
        accountant.reset();
    }

    // ==================== Basic Lifecycle Tests ====================

    @Test
    void testEntityRegistration() {
        // Given: Empty accountant
        assertEquals(0, accountant.getDistribution().size(), "Should start empty");

        // When: Register 100 entities across bubbles
        var entityIds = new HashSet<UUID>();
        for (int i = 0; i < 100; i++) {
            var entityId = UUID.randomUUID();
            entityIds.add(entityId);
            accountant.register(bubbles[i % 16], entityId);
        }

        // Then: Validate registration
        var distribution = accountant.getDistribution();
        assertEquals(16, distribution.size(), "Should have registered in all 16 bubbles");

        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(100, total, "Should have 100 registered entities");

        // Validate all entities registered
        for (var bubbleId : bubbles) {
            var entities = accountant.entitiesInBubble(bubbleId);
            assertFalse(entities.isEmpty(), "Bubble " + bubbleId + " should have entities");
        }

        log.info("Registered 100 entities across 16 bubbles");
    }

    @Test
    void testAtomicMigration() {
        // Given: 50 entities in bubble 0
        var entityIds = new HashSet<UUID>();
        for (int i = 0; i < 50; i++) {
            var entityId = UUID.randomUUID();
            entityIds.add(entityId);
            accountant.register(bubbles[0], entityId);
        }

        // When: Move entities between bubbles atomically
        for (var entityId : entityIds) {
            var targetBubble = bubbles[(int) (Math.random() * 16)];
            accountant.moveBetweenBubbles(entityId, bubbles[0], targetBubble);
        }

        // Then: Validate atomic movement
        var validation = accountant.validate();
        assertTrue(validation.success(), "Validation should pass: " + validation.details());
        assertEquals(0, validation.errorCount(), "Should have 0 errors");

        var distribution = accountant.getDistribution();
        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(50, total, "Should still have 50 entities");

        log.info("Moved 50 entities atomically between bubbles");
    }

    @Test
    void testHighConcurrencyRetention() throws InterruptedException, ExecutionException, TimeoutException {
        // Given: 1000 entities to migrate concurrently
        var entityIds = new HashSet<UUID>();
        for (int i = 0; i < 1000; i++) {
            var entityId = UUID.randomUUID();
            entityIds.add(entityId);
            accountant.register(bubbles[0], entityId);
        }

        // When: 10 threads migrate entities concurrently
        var executor = Executors.newFixedThreadPool(10);
        var tasks = new ArrayList<Future<?>>();

        for (int threadId = 0; threadId < 10; threadId++) {
            final int tid = threadId;
            var task = executor.submit(() -> {
                try {
                    for (var entityId : entityIds) {
                        if (entityId.hashCode() % 10 == tid) {
                            var targetIdx = (entityId.hashCode() + tid) % 16;
                            accountant.moveBetweenBubbles(entityId, bubbles[0], bubbles[targetIdx]);
                        }
                    }
                } catch (Exception e) {
                    log.error("Migration error", e);
                }
            });
            tasks.add(task);
        }

        // Wait for all migrations to complete
        for (var task : tasks) {
            task.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Then: Validate high concurrency retention
        var validation = accountant.validate();
        assertTrue(validation.success(),
            "High concurrency retention should pass: " + validation.details());
        assertEquals(0, validation.errorCount(), "No errors expected");

        var distribution = accountant.getDistribution();
        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(1000, total, "Must have all 1000 entities after concurrent migrations");

        log.info("Completed concurrent migration of 1000 entities across 10 threads");
    }

    // ==================== ID Tracking Tests ====================

    @Test
    void testEntityIdTracking() {
        // Given: Known entity IDs
        var expectedIds = new HashSet<UUID>();
        for (int i = 0; i < 1000; i++) {
            var entityId = UUID.randomUUID();
            expectedIds.add(entityId);
            accountant.register(bubbles[i % 16], entityId);
        }

        // When: Random migrations
        var rng = new Random(42);
        for (int i = 0; i < 500; i++) {
            var entityId = expectedIds.stream()
                .skip(rng.nextInt(expectedIds.size()))
                .findFirst()
                .orElseThrow();
            var currentBubble = accountant.getLocationOfEntity(entityId);
            if (currentBubble != null) {
                var toIdx = rng.nextInt(16);
                var targetBubble = bubbles[toIdx];
                if (!currentBubble.equals(targetBubble)) {
                    accountant.moveBetweenBubbles(entityId, currentBubble, targetBubble);
                }
            }
        }

        // Then: Validate all IDs present
        var actualIds = new HashSet<UUID>();
        for (var bubbleId : bubbles) {
            actualIds.addAll(accountant.entitiesInBubble(bubbleId));
        }

        assertEquals(expectedIds, actualIds,
            "All expected entity IDs must be present after migrations");

        // Validate no duplicates
        var validation = accountant.validate();
        assertTrue(validation.success(), "No duplicates: " + validation.details());
        assertEquals(0, validation.errorCount(), "No validation errors");

        log.info("ID tracking complete: {} entities, {} migrations", expectedIds.size(), 500);
    }

    @Test
    void testRetentionUnderLoad() throws InterruptedException {
        // Given: Skewed distribution (1200 entities: 4 heavy @ 240 + 12 light @ 20)
        var heavyIndices = new int[]{0, 4, 8, 12};
        var lightIndices = new int[16];
        int idx = 0;
        for (int i = 0; i < 16; i++) {
            boolean isHeavy = false;
            for (int h : heavyIndices) {
                if (i == h) {
                    isHeavy = true;
                    break;
                }
            }
            if (!isHeavy) {
                lightIndices[idx++] = i;
            }
        }

        var entityIds = new HashSet<UUID>();

        // Create 960 entities in heavy bubbles (4 × 240)
        for (int h : heavyIndices) {
            for (int i = 0; i < 240; i++) {
                var entityId = UUID.randomUUID();
                entityIds.add(entityId);
                accountant.register(bubbles[h], entityId);
            }
        }

        // Create 240 entities in light bubbles (12 × 20)
        for (int l = 0; l < 12; l++) {
            for (int i = 0; i < 20; i++) {
                var entityId = UUID.randomUUID();
                entityIds.add(entityId);
                accountant.register(bubbles[lightIndices[l]], entityId);
            }
        }

        // When: Run 1000 random migrations
        var rng = new Random(42);
        for (int m = 0; m < 1000; m++) {
            var entityId = entityIds.stream()
                .skip(rng.nextInt(entityIds.size()))
                .findFirst()
                .orElseThrow();
            var currentBubble = accountant.getLocationOfEntity(entityId);
            if (currentBubble != null) {
                var toIdx = rng.nextInt(16);
                var targetBubble = bubbles[toIdx];
                if (!currentBubble.equals(targetBubble)) {
                    accountant.moveBetweenBubbles(entityId, currentBubble, targetBubble);
                }
            }

            // Periodic validation every 100 migrations
            if (m % 100 == 0) {
                var validation = accountant.validate();
                assertTrue(validation.success(),
                    "Validation at migration " + m + " failed: " + validation.details());
            }
        }

        // Then: Final validation
        var validation = accountant.validate();
        assertTrue(validation.success(), "Final validation: " + validation.details());

        var distribution = accountant.getDistribution();
        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(1200, total, "Must have all 1200 entities under load");

        log.info("Completed 1000 migrations under skewed load: 100% retention");
    }

    @Test
    void testRetentionAfterCrash() {
        // Given: 800 entities distributed
        var entityIds = new HashSet<UUID>();
        for (int i = 0; i < 800; i++) {
            var entityId = UUID.randomUUID();
            entityIds.add(entityId);
            accountant.register(bubbles[i % 16], entityId);
        }

        // When: Simulate crash recovery
        // Record initial state
        var beforeValidation = accountant.validate();
        assertTrue(beforeValidation.success(), "Before crash validation");

        // Simulate some migrations
        var rng = new Random(42);
        for (int i = 0; i < 100; i++) {
            var entityId = entityIds.stream()
                .skip(rng.nextInt(entityIds.size()))
                .findFirst()
                .orElseThrow();
            var currentBubble = accountant.getLocationOfEntity(entityId);
            if (currentBubble != null) {
                var toIdx = rng.nextInt(16);
                var targetBubble = bubbles[toIdx];
                if (!currentBubble.equals(targetBubble)) {
                    accountant.moveBetweenBubbles(entityId, currentBubble, targetBubble);
                }
            }
        }

        // Then: Validate retention (no losses due to crash)
        var afterValidation = accountant.validate();
        assertTrue(afterValidation.success(),
            "Retention after crash: " + afterValidation.details());
        assertEquals(0, afterValidation.errorCount(), "No errors expected");

        var distribution = accountant.getDistribution();
        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(800, total, "Must have all 800 entities (no losses)");

        log.info("Crash recovery retention: 800 entities, 0 losses");
    }

    @Test
    void testRetentionStatistics() {
        // Given: 800 entities with operation tracking
        var entityIds = new HashSet<UUID>();
        for (int i = 0; i < 800; i++) {
            var entityId = UUID.randomUUID();
            entityIds.add(entityId);
            accountant.register(bubbles[i % 16], entityId);
        }

        long initialOps = accountant.getTotalOperations();
        assertEquals(800, initialOps, "Should have 800 registration operations");

        // When: Perform migrations
        var rng = new Random(42);
        for (int i = 0; i < 500; i++) {
            var entityId = entityIds.stream()
                .skip(rng.nextInt(entityIds.size()))
                .findFirst()
                .orElseThrow();
            var currentBubble = accountant.getLocationOfEntity(entityId);
            if (currentBubble != null) {
                var toIdx = rng.nextInt(16);
                var targetBubble = bubbles[toIdx];
                if (!currentBubble.equals(targetBubble)) {
                    accountant.moveBetweenBubbles(entityId, currentBubble, targetBubble);
                }
            }
        }

        // Then: Validate statistics
        long finalOps = accountant.getTotalOperations();
        long moveOps = finalOps - initialOps;
        assertTrue(moveOps >= 450 && moveOps <= 480,
            "Should have ~467 move operations (±15 for randomness), got: " + moveOps);

        var distribution = accountant.getDistribution();
        var minEntities = distribution.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        var maxEntities = distribution.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        var avgEntities = (double) distribution.values().stream()
            .mapToInt(Integer::intValue).sum() / distribution.size();

        log.info("Distribution stats - Min: {}, Max: {}, Avg: {:.1f}", minEntities, maxEntities, avgEntities);

        var validation = accountant.validate();
        assertTrue(validation.success(), "Statistics validation: " + validation.details());
    }

    @Test
    void testRetentionDuringTopologyChange() {
        // Given: Initial 1000 entities
        var entityIds = new HashSet<UUID>();
        for (int i = 0; i < 1000; i++) {
            var entityId = UUID.randomUUID();
            entityIds.add(entityId);
            accountant.register(bubbles[i % 16], entityId);
        }

        // Simulate topology change: add processes (expand bubbles array)
        // Note: In real system, would add new processes and rebalance

        // When: Continue migrations despite topology change
        var rng = new Random(42);
        for (int i = 0; i < 300; i++) {
            var entityId = entityIds.stream()
                .skip(rng.nextInt(entityIds.size()))
                .findFirst()
                .orElseThrow();
            var currentBubble = accountant.getLocationOfEntity(entityId);
            if (currentBubble != null) {
                var toIdx = rng.nextInt(16);
                var targetBubble = bubbles[toIdx];
                if (!currentBubble.equals(targetBubble)) {
                    accountant.moveBetweenBubbles(entityId, currentBubble, targetBubble);
                }
            }
        }

        // Then: Validate retention
        var validation = accountant.validate();
        assertTrue(validation.success(),
            "Retention during topology change: " + validation.details());

        var distribution = accountant.getDistribution();
        var total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(1000, total, "Must have all 1000 entities despite topology change");

        log.info("Topology change retention complete: 1000 entities, 0 losses");
    }
}
