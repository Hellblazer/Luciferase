/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.behavior;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.SimulationBubble;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for FlockingBehavior.
 */
class FlockingBehaviorTest {

    private EnhancedBubble bubble;
    private FlockingBehavior behavior;
    private SimulationBubble simulation;

    @BeforeEach
    void setUp() {
        bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16);
        behavior = new FlockingBehavior();
    }

    @AfterEach
    void tearDown() {
        if (simulation != null) {
            simulation.shutdown();
        }
    }

    @Test
    void testSeparation_entitiesSpreadOut() throws Exception {
        // Place entities very close together
        for (int i = 0; i < 10; i++) {
            bubble.addEntity("entity-" + i, new Point3f(100, 100, 100), null);
        }

        // Run simulation with high separation weight
        behavior = new FlockingBehavior(3.0f, 0.5f, 0.5f);
        simulation = new SimulationBubble(bubble, behavior, 10);

        simulation.start();
        TimeUnit.MILLISECONDS.sleep(500);
        simulation.stop();

        // Entities should have spread out - check variance
        float sumX = 0, sumY = 0, sumZ = 0;
        var entities = bubble.getAllEntityRecords();
        for (var e : entities) {
            sumX += e.position().x;
            sumY += e.position().y;
            sumZ += e.position().z;
        }
        float meanX = sumX / entities.size();
        float meanY = sumY / entities.size();
        float meanZ = sumZ / entities.size();

        float variance = 0;
        for (var e : entities) {
            float dx = e.position().x - meanX;
            float dy = e.position().y - meanY;
            float dz = e.position().z - meanZ;
            variance += dx * dx + dy * dy + dz * dz;
        }
        variance /= entities.size();

        // Variance should be > 0 (entities spread out from initial cluster)
        assertThat(variance).isGreaterThan(1.0f);
    }

    @Test
    void testCohesion_entitiesStayTogether() throws Exception {
        // Place entities spread out
        var random = new Random(42);
        for (int i = 0; i < 20; i++) {
            bubble.addEntity("entity-" + i, new Point3f(
                50 + random.nextFloat() * 100,
                50 + random.nextFloat() * 100,
                50 + random.nextFloat() * 100
            ), null);
        }

        // Run simulation with high cohesion weight
        behavior = new FlockingBehavior(0.5f, 0.5f, 2.0f);
        simulation = new SimulationBubble(bubble, behavior, 10);

        // Measure initial spread
        float initialSpread = measureSpread(bubble);

        simulation.start();
        TimeUnit.MILLISECONDS.sleep(1000);
        simulation.stop();

        // Entities should not spread too far (cohesion keeps them together)
        float finalSpread = measureSpread(bubble);

        // Final spread shouldn't be dramatically larger than initial
        // (cohesion counteracts random spread)
        assertThat(finalSpread).isLessThan(initialSpread * 3);
    }

    @Test
    void testEntitiesStayInBounds() throws Exception {
        // Place entities near edges
        bubble.addEntity("entity-0", new Point3f(5, 100, 100), null);
        bubble.addEntity("entity-1", new Point3f(195, 100, 100), null);
        bubble.addEntity("entity-2", new Point3f(100, 5, 100), null);
        bubble.addEntity("entity-3", new Point3f(100, 195, 100), null);
        bubble.addEntity("entity-4", new Point3f(100, 100, 5), null);
        bubble.addEntity("entity-5", new Point3f(100, 100, 195), null);

        simulation = new SimulationBubble(bubble, behavior, 10);

        simulation.start();
        TimeUnit.MILLISECONDS.sleep(500);
        simulation.stop();

        // All entities should be within bounds
        for (var entity : bubble.getAllEntityRecords()) {
            var pos = entity.position();
            assertThat(pos.x).isBetween(0f, 200f);
            assertThat(pos.y).isBetween(0f, 200f);
            assertThat(pos.z).isBetween(0f, 200f);
        }
    }

    @Test
    void testAoiRadius() {
        assertThat(behavior.getAoiRadius()).isEqualTo(30.0f);
    }

    @Test
    void testMaxSpeed() {
        assertThat(behavior.getMaxSpeed()).isEqualTo(15.0f);
    }

    @Test
    void testSingleEntityWanders() throws Exception {
        // Single entity should still move (wander behavior)
        bubble.addEntity("lonely", new Point3f(100, 100, 100), null);

        simulation = new SimulationBubble(bubble, behavior, 10);
        simulation.start();
        TimeUnit.MILLISECONDS.sleep(200);
        simulation.stop();

        var entity = bubble.getAllEntityRecords().get(0);
        var pos = entity.position();

        // Should have moved from initial position
        boolean moved = Math.abs(pos.x - 100) > 0.1f ||
                        Math.abs(pos.y - 100) > 0.1f ||
                        Math.abs(pos.z - 100) > 0.1f;
        assertThat(moved).isTrue();
    }

    @Test
    void testVelocityComputation() {
        bubble.addEntity("test", new Point3f(100, 100, 100), null);

        var velocity = new Vector3f(5, 0, 0);
        var newVelocity = behavior.computeVelocity(
            "test", new Point3f(100, 100, 100), velocity, bubble, 0.016f
        );

        // Should return a valid velocity
        assertThat(newVelocity).isNotNull();
        assertThat(newVelocity.length()).isLessThanOrEqualTo(behavior.getMaxSpeed() + 0.01f);
    }

    @Test
    void testCustomWeights() {
        var customBehavior = new FlockingBehavior(2.0f, 1.5f, 1.0f);

        assertThat(customBehavior.getAoiRadius()).isEqualTo(30.0f);
        assertThat(customBehavior.getMaxSpeed()).isEqualTo(15.0f);
    }

    private float measureSpread(EnhancedBubble bubble) {
        var entities = bubble.getAllEntityRecords();
        if (entities.isEmpty()) return 0;

        float sumX = 0, sumY = 0, sumZ = 0;
        for (var e : entities) {
            sumX += e.position().x;
            sumY += e.position().y;
            sumZ += e.position().z;
        }
        float meanX = sumX / entities.size();
        float meanY = sumY / entities.size();
        float meanZ = sumZ / entities.size();

        float variance = 0;
        for (var e : entities) {
            float dx = e.position().x - meanX;
            float dy = e.position().y - meanY;
            float dz = e.position().z - meanZ;
            variance += dx * dx + dy * dy + dz * dz;
        }

        return (float) Math.sqrt(variance / entities.size());
    }
}
