/**
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-entity update-failure observability (Luciferase-nqk13).
 *
 * <p>{@code updateBubbleEntities} isolates a throwing entity — it logs and continues to the next entity so
 * one bad entity cannot abort a whole bubble's tick. That swallow was previously invisible. These tests pin
 * the fix: per-entity failures are counted ({@link GridMultiBubbleSimulation#getEntityUpdateFailureCount()})
 * AND the isolation holds (the tick still succeeds, the simulation stays healthy, and tickCount advances —
 * a per-entity failure does NOT trip the tick-level circuit-breaker).
 *
 * @author hal.hildebrand
 */
class GridMultiBubbleSimulationEntityFailureTest {

    /** FlockingBehavior whose per-entity velocity computation always throws — every entity update fails. */
    private static final class ThrowingComputeVelocity extends FlockingBehavior {
        @Override
        public Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                                        com.hellblazer.luciferase.simulation.bubble.EnhancedBubble bubble,
                                        float deltaTime) {
            throw new IllegalStateException("test-injected per-entity update failure for " + entityId);
        }
    }

    @Test
    void perEntityUpdateFailuresAreCountedAndIsolated() {
        var config = GridConfiguration.DEFAULT_2X2;
        try (var sim = new GridMultiBubbleSimulation(config, 20, WorldBounds.DEFAULT, new ThrowingComputeVelocity())) {
            sim.start();

            // Every entity's computeVelocity throws → the per-entity catch counts each and continues.
            await("per-entity failures are surfaced")
                .atMost(Duration.ofSeconds(10))
                .until(() -> sim.getEntityUpdateFailureCount() > 0);

            // Isolation held: a per-entity failure must NOT trip the tick-level breaker — the tick itself
            // completes (the catch is inside the per-entity loop), so the sim stays healthy and ticks advance.
            assertFalse(sim.isFailed(),
                        "a per-entity update failure must be isolated — it must NOT circuit-break the tick");
            assertTrue(sim.isHealthy(), "the simulation must remain healthy despite per-entity failures");
            await("ticks keep advancing despite per-entity failures")
                .atMost(Duration.ofSeconds(10))
                .until(() -> sim.getTickCount() > 0);
            assertEquals(0L, sim.getTickFailureCount(),
                         "per-entity failures must not be counted as tick failures (distinct surfaces)");
        }
    }

    @Test
    void healthyEntitiesProduceNoUpdateFailures() {
        var config = GridConfiguration.DEFAULT_2X2;
        // Default FlockingBehavior — no injected failures.
        try (var sim = new GridMultiBubbleSimulation(config, 20, WorldBounds.DEFAULT)) {
            sim.start();
            await("simulation ticks").atMost(Duration.ofSeconds(10)).until(() -> sim.getTickCount() > 2);
            assertEquals(0L, sim.getEntityUpdateFailureCount(),
                         "a healthy simulation must report zero per-entity update failures");
        }
    }
}
