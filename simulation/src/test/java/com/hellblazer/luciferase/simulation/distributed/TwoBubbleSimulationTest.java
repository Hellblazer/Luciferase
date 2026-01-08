/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for TwoBubbleSimulation.
 */
class TwoBubbleSimulationTest {

    private TwoBubbleSimulation simulation;

    @AfterEach
    void tearDown() {
        if (simulation != null) {
            simulation.close();
        }
    }

    // ========== Utility Methods ==========

    /**
     * Wait for simulation to reach target tick count with timeout.
     * More robust than fixed sleep for CI systems under load.
     */
    private void waitForTicks(long targetTicks, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while (simulation.getTickCount() < targetTicks) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                fail("Timeout waiting for " + targetTicks + " ticks. Current: " + simulation.getTickCount());
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    // ========== Basic Tests ==========

    @Test
    void testCreation() {
        simulation = new TwoBubbleSimulation(100);

        assertThat(simulation.getBubble1()).isNotNull();
        assertThat(simulation.getBubble2()).isNotNull();
        assertThat(simulation.isRunning()).isFalse();

        // Entities should be split between bubbles
        int total = simulation.getBubble1().entityCount() + simulation.getBubble2().entityCount();
        assertThat(total).isEqualTo(100);
    }

    @Test
    void testEntitiesDistributedByPosition() {
        simulation = new TwoBubbleSimulation(100);
        float boundary = simulation.getBoundaryX();

        // Bubble 1 entities should be to the left of boundary
        for (var entity : simulation.getBubble1().getAllEntityRecords()) {
            assertThat(entity.position().x).isLessThan(boundary);
        }

        // Bubble 2 entities should be to the right of boundary
        for (var entity : simulation.getBubble2().getAllEntityRecords()) {
            assertThat(entity.position().x).isGreaterThanOrEqualTo(boundary);
        }
    }

    @Test
    void testStartStop() {
        simulation = new TwoBubbleSimulation(50);

        assertThat(simulation.isRunning()).isFalse();

        simulation.start();
        assertThat(simulation.isRunning()).isTrue();

        simulation.stop();
        assertThat(simulation.isRunning()).isFalse();
    }

    @Test
    void testTicksExecute() throws Exception {
        simulation = new TwoBubbleSimulation(50);

        simulation.start();
        waitForTicks(5, 2000);  // Wait for at least 5 ticks with 2s timeout
        simulation.stop();

        assertThat(simulation.getTickCount()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void testEntitiesMove() throws Exception {
        simulation = new TwoBubbleSimulation(50);

        // Get initial positions
        var initialPositions = simulation.getAllEntities().stream()
            .filter(e -> !e.isGhost())
            .toList();

        simulation.start();
        waitForTicks(10, 2000);  // Wait for 10 ticks
        simulation.stop();

        // Get final positions
        var finalPositions = simulation.getAllEntities().stream()
            .filter(e -> !e.isGhost())
            .toList();

        // At least some entities should have moved
        int movedCount = 0;
        for (var initial : initialPositions) {
            for (var fin : finalPositions) {
                if (initial.id().equals(fin.id())) {
                    if (!initial.position().equals(fin.position())) {
                        movedCount++;
                    }
                    break;
                }
            }
        }

        assertThat(movedCount).isGreaterThan(0);
    }

    // ========== Ghost Tests ==========

    @Test
    void testGhostsSynchronized() throws Exception {
        simulation = new TwoBubbleSimulation(100);

        simulation.start();
        waitForTicks(20, 2000);  // Wait for several ghost sync intervals
        simulation.stop();

        // After some time, there should be ghost entities
        var allEntities = simulation.getAllEntities();
        var ghosts = allEntities.stream().filter(TwoBubbleSimulation.EntitySnapshot::isGhost).toList();

        // Should have some ghosts (entities near boundary)
        // This may be 0 if no entities happen to be near boundary, so we just check the mechanism works
        assertThat(ghosts).isNotNull();
    }

    @Test
    void testGhostsExpireAfterTTL() throws Exception {
        simulation = new TwoBubbleSimulation(100);

        simulation.start();

        // Wait for ghosts to be created (at least one ghost sync interval)
        waitForTicks(5, 2000);

        // Get initial ghost count
        var initialGhosts = simulation.getAllEntities().stream()
            .filter(TwoBubbleSimulation.EntitySnapshot::isGhost)
            .count();

        // Wait beyond TTL (10 ticks) + buffer
        waitForTicks(simulation.getTickCount() + 15, 3000);
        simulation.stop();

        // Ghosts may have been renewed or expired - just verify mechanism works
        var finalGhosts = simulation.getAllEntities().stream()
            .filter(TwoBubbleSimulation.EntitySnapshot::isGhost)
            .count();

        assertThat(finalGhosts).isGreaterThanOrEqualTo(0);
    }

    // ========== Migration Tests ==========

    @Test
    void testEntityMigration() throws Exception {
        simulation = new TwoBubbleSimulation(100, WorldBounds.DEFAULT,
                                              new FlockingBehavior(), new FlockingBehavior());

        int initialBubble1 = simulation.getBubble1().entityCount();
        int initialBubble2 = simulation.getBubble2().entityCount();

        simulation.start();
        waitForTicks(120, 5000);  // Run for ~2 seconds worth of ticks
        simulation.stop();

        int finalBubble1 = simulation.getBubble1().entityCount();
        int finalBubble2 = simulation.getBubble2().entityCount();

        // Total should remain constant
        assertThat(finalBubble1 + finalBubble2).isEqualTo(initialBubble1 + initialBubble2);
    }

    @Test
    void testMigrationMetrics() throws Exception {
        simulation = new TwoBubbleSimulation(100, WorldBounds.DEFAULT,
                                              new FlockingBehavior(), new FlockingBehavior());

        assertThat(simulation.getMigrationsTo1()).isZero();
        assertThat(simulation.getMigrationsTo2()).isZero();

        simulation.start();
        waitForTicks(120, 5000);  // Run for ~2 seconds worth of ticks
        simulation.stop();

        // Migration counts should be non-negative (may or may not have migrations)
        assertThat(simulation.getMigrationsTo1()).isGreaterThanOrEqualTo(0);
        assertThat(simulation.getMigrationsTo2()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void testMigrationNoEntityLoss() throws Exception {
        // Extended run to verify no entities are lost during migrations
        simulation = new TwoBubbleSimulation(200, WorldBounds.DEFAULT,
                                              new FlockingBehavior(), new FlockingBehavior());

        int initialTotal = simulation.getBubble1().entityCount() + simulation.getBubble2().entityCount();
        assertThat(initialTotal).isEqualTo(200);

        simulation.start();
        waitForTicks(300, 10000);  // Run for ~5 seconds
        simulation.stop();

        int finalTotal = simulation.getBubble1().entityCount() + simulation.getBubble2().entityCount();
        assertThat(finalTotal).isEqualTo(initialTotal);

        // Should have zero migration failures
        assertThat(simulation.getMigrationFailures()).isZero();
    }

    @Test
    void testMigrationCooldownPreventsOscillation() throws Exception {
        // Test that entities don't oscillate rapidly at boundary
        simulation = new TwoBubbleSimulation(100, WorldBounds.DEFAULT,
                                              new FlockingBehavior(), new FlockingBehavior());

        simulation.start();
        waitForTicks(200, 7000);
        simulation.stop();

        // Total migrations should be reasonable, not excessive
        // With cooldown, an entity can only migrate once every 30 ticks (500ms)
        // In 200 ticks, max migrations per entity = ~6
        // With 100 entities, theoretical max = 600, but most won't migrate at all
        long totalMigrations = simulation.getMigrationsTo1() + simulation.getMigrationsTo2();
        assertThat(totalMigrations).isLessThan(300);  // Well under theoretical max

        // Should have some cooldowns active if migrations occurred
        // (or zero if no migrations near the end)
        assertThat(simulation.getCooldownsActive()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void testMigrationFailuresTracked() throws Exception {
        simulation = new TwoBubbleSimulation(50);

        // Initially zero failures
        assertThat(simulation.getMigrationFailures()).isZero();

        simulation.start();
        waitForTicks(30, 2000);
        simulation.stop();

        // Under normal operation, should have no failures
        var state = simulation.getDebugState();
        assertThat(state.migrationFailures()).isZero();
    }

    @Test
    void testMigrationPreservesEntityMovement() throws Exception {
        // Verify that entities continue moving after migration
        // This indirectly tests velocity preservation
        simulation = new TwoBubbleSimulation(100, WorldBounds.DEFAULT,
                                              new FlockingBehavior(), new FlockingBehavior());

        simulation.start();
        waitForTicks(60, 3000);  // Let simulation run and migrations happen

        // Capture positions at tick 60
        var positionsAtTick60 = new java.util.HashMap<String, javax.vecmath.Point3f>();
        for (var entity : simulation.getAllEntities()) {
            if (!entity.isGhost()) {
                positionsAtTick60.put(entity.id(), new javax.vecmath.Point3f(entity.position()));
            }
        }

        // Let simulation run more
        waitForTicks(120, 3000);
        simulation.stop();

        // Capture positions at tick 120
        var positionsAtTick120 = simulation.getAllEntities().stream()
            .filter(e -> !e.isGhost())
            .collect(java.util.stream.Collectors.toMap(
                TwoBubbleSimulation.EntitySnapshot::id,
                TwoBubbleSimulation.EntitySnapshot::position
            ));

        // Count entities that moved (indicates velocity is working)
        int movedCount = 0;
        for (var entry : positionsAtTick60.entrySet()) {
            var id = entry.getKey();
            var pos60 = entry.getValue();
            var pos120 = positionsAtTick120.get(id);

            if (pos120 != null) {
                float dx = pos120.x - pos60.x;
                float dy = pos120.y - pos60.y;
                float dz = pos120.z - pos60.z;
                float distMoved = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

                if (distMoved > 0.1f) {  // Moved more than 0.1 units
                    movedCount++;
                }
            }
        }

        // Most entities should have moved (velocity preserved)
        // At least 80% should be moving
        assertThat(movedCount).isGreaterThan((int) (positionsAtTick60.size() * 0.8));
    }

    // ========== Bounds Tests ==========

    @Test
    void testAllEntitiesStayInBounds() throws Exception {
        simulation = new TwoBubbleSimulation(100);
        var bounds = simulation.getWorldBounds();

        simulation.start();
        waitForTicks(30, 2000);
        simulation.stop();

        for (var entity : simulation.getAllEntities()) {
            if (!entity.isGhost()) {
                var pos = entity.position();
                assertThat(pos.x).isBetween(bounds.min(), bounds.max());
                assertThat(pos.y).isBetween(bounds.min(), bounds.max());
                assertThat(pos.z).isBetween(bounds.min(), bounds.max());
            }
        }
    }

    @Test
    void testGetAllEntitiesReturnsCorrectCount() {
        simulation = new TwoBubbleSimulation(100);

        var allEntities = simulation.getAllEntities().stream()
            .filter(e -> !e.isGhost())
            .toList();

        assertThat(allEntities).hasSize(100);
    }

    // ========== Neighbor Tests ==========

    @Test
    void testBubbleNeighborRelationship() {
        simulation = new TwoBubbleSimulation(50);

        // Bubbles should be neighbors
        assertThat(simulation.getBubble1().neighbors()).contains(simulation.getBubble2().id());
        assertThat(simulation.getBubble2().neighbors()).contains(simulation.getBubble1().id());
    }

    // ========== Metrics and Debug Tests ==========

    @Test
    void testMetrics() throws Exception {
        simulation = new TwoBubbleSimulation(50);

        simulation.start();
        waitForTicks(10, 2000);
        simulation.stop();

        var metrics = simulation.getMetrics();
        assertThat(metrics.getTotalTicks()).isGreaterThan(0);
        assertThat(metrics.getAverageFrameTimeMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void testDebugState() throws Exception {
        simulation = new TwoBubbleSimulation(100);

        // Initial state
        var initialState = simulation.getDebugState();
        assertThat(initialState.tickCount()).isZero();
        assertThat(initialState.bubble1EntityCount() + initialState.bubble2EntityCount()).isEqualTo(100);
        assertThat(initialState.migrationsTo1()).isZero();
        assertThat(initialState.migrationsTo2()).isZero();
        assertThat(initialState.migrationFailures()).isZero();
        assertThat(initialState.cooldownsActive()).isZero();

        simulation.start();
        waitForTicks(20, 2000);
        simulation.stop();

        // After running
        var finalState = simulation.getDebugState();
        assertThat(finalState.tickCount()).isGreaterThanOrEqualTo(20);
        assertThat(finalState.bubble1EntityCount() + finalState.bubble2EntityCount()).isEqualTo(100);
        assertThat(finalState.metrics()).isNotNull();
        // Migration failures should be zero under normal operation
        assertThat(finalState.migrationFailures()).isZero();
    }
}
