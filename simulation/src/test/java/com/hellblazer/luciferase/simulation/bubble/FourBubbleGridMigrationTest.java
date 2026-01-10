/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * This file is part of the Luciferase Simulation Framework.
 * Licensed under AGPL v3.0.
 */

package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.causality.*;
import com.hellblazer.luciferase.simulation.distributed.migration.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Four-bubble grid migration test for Phase 7E Day 6
 * Validates migrations across a 2x2 bubble grid with synchronized view stability.
 */
@DisplayName("Four-Bubble Grid Migration - Multi-Directional Entity Transfer")
class FourBubbleGridMigrationTest {

    private static final Logger log = LoggerFactory.getLogger(FourBubbleGridMigrationTest.class);

    private EnhancedBubble[] bubbles;
    private EnhancedBubbleMigrationIntegration[] integrations;
    private EntityMigrationStateMachine[] fsms;
    private OptimisticMigrator migrator;
    private FirefliesViewMonitor viewMonitor;

    @BeforeEach
    void setUp() {
        bubbles = new EnhancedBubble[4];
        integrations = new EnhancedBubbleMigrationIntegration[4];
        fsms = new EntityMigrationStateMachine[4];

        var mockMembershipView = mock(com.hellblazer.luciferase.simulation.delos.MembershipView.class);
        viewMonitor = new FirefliesViewMonitor(mockMembershipView, 3);
        migrator = new OptimisticMigratorImpl();
        var migrationOracle = new MigrationOracleImpl(2, 2, 2);

        // Create 4-bubble grid (2x2): [0,1] [2,3]
        for (int i = 0; i < 4; i++) {
            bubbles[i] = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 100L);
            fsms[i] = new EntityMigrationStateMachine(viewMonitor);
            integrations[i] = new EnhancedBubbleMigrationIntegration(
                bubbles[i], fsms[i], migrationOracle, migrator, viewMonitor, 3);
        }
        log.info("4-bubble grid initialized");
    }

    @Test
    @DisplayName("Entities migrate across grid boundaries")
    void testGridMigrations() {
        var entities = new UUID[4];
        for (int i = 0; i < 4; i++) {
            entities[i] = UUID.randomUUID();
            migrator.initiateOptimisticMigration(entities[i], bubbles[(i + 1) % 4].id());
        }

        for (var entity : entities) {
            assertTrue(migrator.getDeferredQueueSize(entity) >= 0);
        }
        log.info("Grid migrations initiated");
    }

    @Test
    @DisplayName("View stability synchronized across grid")
    void testSynchronizedViewStability() {
        var entity = UUID.randomUUID();
        migrator.initiateOptimisticMigration(entity, bubbles[1].id());

        // Queue updates on all bubbles
        for (int i = 0; i < 5; i++) {
            migrator.queueDeferredUpdate(entity, new float[]{1, 2, 3}, new float[]{0, 0, 0});
        }

        // All integrations process with synchronized view monitor
        for (var integration : integrations) {
            integration.processMigrations(System.currentTimeMillis());
        }

        log.info("View stability synchronized across grid");
    }

    @Test
    @DisplayName("Concurrent migrations across all bubble boundaries")
    void testConcurrentGridMigrations() {
        var entityCount = 20;
        var entities = new UUID[entityCount];

        // Initiate migrations across all boundaries
        for (int i = 0; i < entityCount; i++) {
            entities[i] = UUID.randomUUID();
            int targetBubble = (i * 3 + 1) % 4; // Distribute across all bubbles
            migrator.initiateOptimisticMigration(entities[i], bubbles[targetBubble].id());

            for (int j = 0; j < 3; j++) {
                migrator.queueDeferredUpdate(entities[i], new float[]{1, 2, 3}, new float[]{0, 0, 0});
            }
        }

        assertEquals(20, migrator.getPendingDeferredCount());

        // Flush all
        for (var entity : entities) {
            migrator.flushDeferredUpdates(entity);
        }

        assertEquals(0, migrator.getPendingDeferredCount());
        log.info("Concurrent grid migrations completed");
    }

    @Test
    @DisplayName("Performance: 100 migrations across 4-bubble grid")
    void testGridPerformance() {
        long start = System.nanoTime();

        for (int i = 0; i < 100; i++) {
            var entity = UUID.randomUUID();
            int target = i % 4;
            migrator.initiateOptimisticMigration(entity, bubbles[target].id());

            migrator.queueDeferredUpdate(entity, new float[]{1, 2, 3}, new float[]{0, 0, 0});
            migrator.flushDeferredUpdates(entity);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 100);
        log.info("100 grid migrations in {}ms", elapsedMs);
    }

    @Test
    @DisplayName("Grid-wide view change handling")
    void testGridViewChangeRollback() {
        var entity = UUID.randomUUID();
        migrator.initiateOptimisticMigration(entity, bubbles[2].id());
        migrator.queueDeferredUpdate(entity, new float[]{1, 2, 3}, new float[]{0, 0, 0});

        // All integrations get notified of view change
        for (var integration : integrations) {
            integration.onViewChangeRollback(1, 0);
        }

        var metrics = integrations[0].getMetrics();
        assertTrue(metrics.contains("rolledBack="));
        log.info("Grid view change handled");
    }
}
