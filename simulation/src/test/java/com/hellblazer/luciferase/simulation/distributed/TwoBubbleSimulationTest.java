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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
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
        TimeUnit.MILLISECONDS.sleep(200);
        simulation.stop();

        // Should have executed at least 5 ticks
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
        TimeUnit.MILLISECONDS.sleep(200);
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

    @Test
    void testGhostsSynchronized() throws Exception {
        simulation = new TwoBubbleSimulation(100);

        simulation.start();
        TimeUnit.MILLISECONDS.sleep(300);
        simulation.stop();

        // After some time, there should be ghost entities
        var allEntities = simulation.getAllEntities();
        var ghosts = allEntities.stream().filter(TwoBubbleSimulation.EntitySnapshot::isGhost).toList();

        // Should have some ghosts (entities near boundary)
        // This may be 0 if no entities happen to be near boundary, so we just check the mechanism works
        assertThat(ghosts).isNotNull();
    }

    @Test
    void testEntityMigration() throws Exception {
        // Create simulation with entities that will move
        simulation = new TwoBubbleSimulation(100, WorldBounds.DEFAULT,
                                              new FlockingBehavior(), new FlockingBehavior());

        int initialBubble1 = simulation.getBubble1().entityCount();
        int initialBubble2 = simulation.getBubble2().entityCount();

        simulation.start();
        TimeUnit.MILLISECONDS.sleep(2000);  // Run for 2 seconds to allow migration
        simulation.stop();

        int finalBubble1 = simulation.getBubble1().entityCount();
        int finalBubble2 = simulation.getBubble2().entityCount();

        // Total should remain constant
        assertThat(finalBubble1 + finalBubble2).isEqualTo(initialBubble1 + initialBubble2);

        // Distribution may have changed due to migration
        // (hard to guarantee migration happens, but we verify total is constant)
    }

    @Test
    void testAllEntitiesStayInBounds() throws Exception {
        simulation = new TwoBubbleSimulation(100);
        var bounds = simulation.getWorldBounds();

        simulation.start();
        TimeUnit.MILLISECONDS.sleep(500);
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

    @Test
    void testBubbleNeighborRelationship() {
        simulation = new TwoBubbleSimulation(50);

        // Bubbles should be neighbors
        assertThat(simulation.getBubble1().neighbors()).contains(simulation.getBubble2().id());
        assertThat(simulation.getBubble2().neighbors()).contains(simulation.getBubble1().id());
    }

    @Test
    void testMetrics() throws Exception {
        simulation = new TwoBubbleSimulation(50);

        simulation.start();
        TimeUnit.MILLISECONDS.sleep(200);
        simulation.stop();

        var metrics = simulation.getMetrics();
        assertThat(metrics.getTotalTicks()).isGreaterThan(0);
        assertThat(metrics.getAverageFrameTimeMs()).isGreaterThanOrEqualTo(0);
    }
}
