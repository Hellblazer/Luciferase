/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.loop;

import com.hellblazer.luciferase.simulation.behavior.RandomWalkBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SimulationLoop.
 */
class SimulationLoopTest {

    private EnhancedBubble bubble;
    private SimulationLoop simulation;

    @BeforeEach
    void setUp() {
        bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16);

        // Add entities at known positions
        for (int i = 0; i < 10; i++) {
            bubble.addEntity("entity-" + i, new Point3f(100, 100, 100), null);
        }
    }

    @AfterEach
    void tearDown() {
        if (simulation != null) {
            simulation.shutdown();
        }
    }

    @Test
    void testStartStop() {
        var behavior = new RandomWalkBehavior(42);
        simulation = new SimulationLoop(bubble, behavior);

        assertThat(simulation.isRunning()).isFalse();
        assertThat(simulation.getTickCount()).isEqualTo(0);

        simulation.start();
        assertThat(simulation.isRunning()).isTrue();

        simulation.stop();
        assertThat(simulation.isRunning()).isFalse();
    }

    @Test
    void testTicksExecute() throws Exception {
        var behavior = new RandomWalkBehavior(42);
        simulation = new SimulationLoop(bubble, behavior, 10);  // 10ms ticks for faster test

        simulation.start();

        // Wait for some ticks to execute
        TimeUnit.MILLISECONDS.sleep(100);

        simulation.stop();

        // Should have executed at least 5 ticks in 100ms with 10ms interval
        assertThat(simulation.getTickCount()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void testEntitiesMove() throws Exception {
        var behavior = new RandomWalkBehavior(42);
        simulation = new SimulationLoop(bubble, behavior, 10);

        // Record initial positions
        var initialPositions = new HashMap<String, Point3f>();
        for (var entity : bubble.getAllEntityRecords()) {
            initialPositions.put(entity.id(), new Point3f(entity.position()));
        }

        simulation.start();
        TimeUnit.MILLISECONDS.sleep(200);
        simulation.stop();

        // At least some entities should have moved
        int movedCount = 0;
        for (var entity : bubble.getAllEntityRecords()) {
            var initial = initialPositions.get(entity.id());
            var current = entity.position();

            if (!initial.equals(current)) {
                movedCount++;
            }
        }

        assertThat(movedCount).isGreaterThan(0);
    }

    @Test
    void testEntitiesStayInBounds() throws Exception {
        var behavior = new RandomWalkBehavior(42);
        simulation = new SimulationLoop(bubble, behavior, 10);

        simulation.start();
        TimeUnit.MILLISECONDS.sleep(500);  // Run for 500ms
        simulation.stop();

        // All entities should be within world bounds [0, 200]
        for (var entity : bubble.getAllEntityRecords()) {
            var pos = entity.position();
            assertThat(pos.x).isBetween(0f, 200f);
            assertThat(pos.y).isBetween(0f, 200f);
            assertThat(pos.z).isBetween(0f, 200f);
        }
    }

    @Test
    void testGetBubble() {
        var behavior = new RandomWalkBehavior();
        simulation = new SimulationLoop(bubble, behavior);

        assertThat(simulation.getBubble()).isEqualTo(bubble);
    }

    @Test
    void testDoubleStartIsIdempotent() {
        var behavior = new RandomWalkBehavior();
        simulation = new SimulationLoop(bubble, behavior);

        simulation.start();
        assertThat(simulation.isRunning()).isTrue();

        simulation.start();  // Should not throw
        assertThat(simulation.isRunning()).isTrue();
    }

    @Test
    void testDoubleStopIsIdempotent() {
        var behavior = new RandomWalkBehavior();
        simulation = new SimulationLoop(bubble, behavior);

        simulation.start();
        simulation.stop();
        assertThat(simulation.isRunning()).isFalse();

        simulation.stop();  // Should not throw
        assertThat(simulation.isRunning()).isFalse();
    }
}
