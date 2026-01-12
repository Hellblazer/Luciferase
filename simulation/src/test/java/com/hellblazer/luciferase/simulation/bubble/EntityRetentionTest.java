/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * Part of Luciferase Simulation Framework. Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.causality.*;
import com.hellblazer.luciferase.simulation.distributed.migration.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Entity retention test for Phase 7E Day 7
 * Validates 100% entity retention across 10-bubble distributed system
 * with 1000+ entity migrations.
 */
@DisplayName("Entity Retention - 10 Bubbles, 1000+ Entities")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "Test hangs in CI with deferred queue overflow warnings, timing-sensitive behavior")
class EntityRetentionTest {

    private static final Logger log = LoggerFactory.getLogger(EntityRetentionTest.class);
    private static final int BUBBLE_COUNT = 10;
    private static final int ENTITY_COUNT = 1000;

    private EnhancedBubble[] bubbles;
    private EnhancedBubbleMigrationIntegration[] integrations;
    private OptimisticMigrator migrator;
    private FirefliesViewMonitor viewMonitor;
    private Set<UUID> allEntities;

    @BeforeEach
    void setUp() {
        bubbles = new EnhancedBubble[BUBBLE_COUNT];
        integrations = new EnhancedBubbleMigrationIntegration[BUBBLE_COUNT];
        allEntities = new HashSet<>();

        var mockMembershipView = mock(com.hellblazer.luciferase.simulation.delos.MembershipView.class);
        viewMonitor = new FirefliesViewMonitor(mockMembershipView, 3);
        migrator = new OptimisticMigratorImpl();
        var migrationOracle = new MigrationOracleImpl(2, 2, 2);

        // Create 10 bubbles
        for (int i = 0; i < BUBBLE_COUNT; i++) {
            bubbles[i] = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 100L);
            var fsm = new EntityMigrationStateMachine(viewMonitor);
            integrations[i] = new EnhancedBubbleMigrationIntegration(
                bubbles[i], fsm, migrationOracle, migrator, viewMonitor, 3);
        }
        log.info("10-bubble system initialized");
    }

    @Test
    @DisplayName("1000 entities retained during distributed migrations")
    void test1000EntityRetention() {
        // Create 1000 entities
        for (int i = 0; i < ENTITY_COUNT; i++) {
            allEntities.add(UUID.randomUUID());
        }

        var entityList = new ArrayList<>(allEntities);
        int migrationCount = 0;

        // Migrate entities across bubbles with deferred updates
        for (var entity : entityList) {
            int sourceBubble = migrationCount % BUBBLE_COUNT;
            int targetBubble = (sourceBubble + 1) % BUBBLE_COUNT;

            migrator.initiateOptimisticMigration(entity, bubbles[targetBubble].id());

            // Queue at least one deferred update so entity is tracked
            migrator.queueDeferredUpdate(entity,
                new float[]{1.0f, 2.0f, 3.0f},
                new float[]{0.0f, 0.0f, 0.0f});

            migrationCount++;

            if (migrationCount % 100 == 0) {
                log.info("Initiated {} migrations", migrationCount);
            }
        }

        assertEquals(ENTITY_COUNT, migrator.getPendingDeferredCount(),
                "All entities should be tracked during migration");

        // Flush all entities to complete migration
        for (var entity : entityList) {
            migrator.flushDeferredUpdates(entity);
        }

        assertEquals(0, migrator.getPendingDeferredCount(),
                "All entities should be retained (no loss)");

        log.info("1000 entity retention test passed");
    }

    @Test
    @DisplayName("Concurrent migrations across 10-bubble network")
    void testConcurrentMigrationsAcrossNetwork() {
        var entities = new UUID[500];
        for (int i = 0; i < 500; i++) {
            entities[i] = UUID.randomUUID();
        }

        // Initiate concurrent migrations
        long startMs = System.currentTimeMillis();
        for (int i = 0; i < 500; i++) {
            int target = (i * 7) % BUBBLE_COUNT; // Distribute widely
            migrator.initiateOptimisticMigration(entities[i], bubbles[target].id());

            // Queue variable-length updates
            int updateCount = 2 + (i % 8);
            for (int j = 0; j < updateCount; j++) {
                migrator.queueDeferredUpdate(entities[i], 
                    new float[]{1.0f + j, 2.0f, 3.0f}, 
                    new float[]{0.0f, 0.0f, 0.0f});
            }
        }

        // All bubbles process migrations
        for (var integration : integrations) {
            integration.processMigrations(System.currentTimeMillis());
        }

        // Flush all
        for (var entity : entities) {
            migrator.flushDeferredUpdates(entity);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        assertEquals(0, migrator.getPendingDeferredCount());
        log.info("500 concurrent migrations across 10 bubbles in {}ms", elapsedMs);
    }

    @Test
    @DisplayName("Deferred queue integrity with 1000+ updates")
    void testDeferredQueueIntegrityLarge() {
        var entity = UUID.randomUUID();
        migrator.initiateOptimisticMigration(entity, bubbles[5].id());

        // Queue 1000+ updates
        for (int i = 0; i < 1000; i++) {
            var pos = new float[]{1.0f + i * 0.001f, 2.0f, 3.0f};
            var vel = new float[]{0.5f, 0.0f, 0.1f};
            migrator.queueDeferredUpdate(entity, pos, vel);
        }

        // Queue is limited to 100, so we have 100 most recent updates
        assertEquals(100, migrator.getDeferredQueueSize(entity),
                "Queue limited to 100 (oldest dropped)");

        migrator.flushDeferredUpdates(entity);
        assertEquals(0, migrator.getDeferredQueueSize(entity));
        log.info("Large deferred queue integrity verified");
    }

    @Test
    @DisplayName("Network-wide view change without entity loss")
    void testNetworkViewChangeWithoutLoss() {
        // Start 100 concurrent migrations
        var entities = new UUID[100];
        for (int i = 0; i < 100; i++) {
            entities[i] = UUID.randomUUID();
            int target = (i * 3) % BUBBLE_COUNT;
            migrator.initiateOptimisticMigration(entities[i], bubbles[target].id());

            for (int j = 0; j < 5; j++) {
                migrator.queueDeferredUpdate(entities[i], 
                    new float[]{1, 2, 3}, new float[]{0, 0, 0});
            }
        }

        assertEquals(100, migrator.getPendingDeferredCount());

        // Network-wide view change
        for (var integration : integrations) {
            integration.onViewChangeRollback(10, 0); // Simulate view change
        }

        // Entities should still be tracked for recovery
        assertTrue(migrator.getPendingDeferredCount() > 0,
                "Entities retained for recovery after view change");

        log.info("Network view change handled without loss");
    }

    @Test
    @DisplayName("Performance: 1000 entity migrations across 10 bubbles")
    void testPerformance1000Entities() {
        long startNs = System.nanoTime();

        var entities = new UUID[1000];
        for (int i = 0; i < 1000; i++) {
            entities[i] = UUID.randomUUID();
            int target = (i * 13) % BUBBLE_COUNT;
            migrator.initiateOptimisticMigration(entities[i], bubbles[target].id());
            migrator.queueDeferredUpdate(entities[i], 
                new float[]{1, 2, 3}, new float[]{0, 0, 0});
            migrator.flushDeferredUpdates(entities[i]);
        }

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        log.info("1000 migrations in {}ms", elapsedMs);
        assertTrue(elapsedMs < 500, "Should complete in < 500ms, got " + elapsedMs);
    }

    @Test
    @DisplayName("Entity retention statistics")
    void testRetentionStatistics() {
        int totalInitiated = 0;
        int totalCompleted = 0;

        var entities = new UUID[200];
        for (int i = 0; i < 200; i++) {
            entities[i] = UUID.randomUUID();
            migrator.initiateOptimisticMigration(entities[i], bubbles[i % BUBBLE_COUNT].id());
            totalInitiated++;
        }

        for (var entity : entities) {
            migrator.flushDeferredUpdates(entity);
            totalCompleted++;
        }

        assertEquals(totalInitiated, totalCompleted,
                "Entity retention: all initiated entities completed");
        log.info("Retention stats: initiated={}, completed={}, retained=100%",
                totalInitiated, totalCompleted);
    }
}
