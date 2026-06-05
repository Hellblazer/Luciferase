/*
 * Copyright (C) 2024 Hellblazer. All rights reserved.
 * This software is subject to the terms of the AGPL v3.0 license.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostEntityHalo;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.ghost.GhostChannel;
import com.hellblazer.luciferase.simulation.ghost.SimulationGhostEntity;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD test for Luciferase-7wzml.186: ghost dead-reckoning is fed real velocity,
 * not hardcoded zero. A ghost with a non-zero velocity must be extrapolated to
 * a position that differs from its reception position after a time delta.
 */
class BubbleGhostCoordinatorDeadReckoningTest {

    /**
     * Capturing GhostChannel: stores the handler registered via onReceive()
     * so the test can fire ghost-reception events on demand.
     */
    private static final class CapturingGhostChannel
        implements GhostChannel<StringEntityID, EntityData> {

        private BiConsumer<UUID, List<SimulationGhostEntity<StringEntityID, EntityData>>> handler;

        @Override
        public void queueGhost(UUID targetBubbleId,
                               SimulationGhostEntity<StringEntityID, EntityData> ghost) {}

        @Override
        public void sendBatch(UUID targetBubbleId,
                              List<SimulationGhostEntity<StringEntityID, EntityData>> ghosts) {}

        @Override
        public void flush(long bucket) {}

        @Override
        public void onReceive(
            BiConsumer<UUID, List<SimulationGhostEntity<StringEntityID, EntityData>>> h) {
            this.handler = h;
        }

        @Override
        public boolean isConnected(UUID targetBubbleId) { return true; }

        @Override
        public int getPendingCount(UUID targetBubbleId) { return 0; }

        @Override
        public void close() {}

        void deliver(UUID source, List<SimulationGhostEntity<StringEntityID, EntityData>> ghosts) {
            if (handler != null) handler.accept(source, ghosts);
        }
    }

    /**
     * A ghost with velocity (1,0,0) m/s received at t=0 must be extrapolated to
     * x > receptionX after a positive time delta. With zero velocity the old code
     * returned the exact reception position every time, making dead-reckoning a
     * silent no-op.
     */
    @Test
    void ghostWithNonZeroVelocityDeadReckonsToMovedPosition() {
        var controller = new RealTimeController(UUID.randomUUID(), "dr-test", 100);
        var channel = new CapturingGhostChannel();
        var bounds = BubbleBounds.fromTetreeKey(TetreeKey.create((byte) 10, 0L, 0L));

        try (var coordinator = new BubbleGhostCoordinator(channel, () -> bounds, controller)) {

            var sourceBubble = UUID.randomUUID();
            var entityId = new StringEntityID("tank-01");
            var receptionPosition = new Point3f(10f, 20f, 30f);
            // Non-zero velocity: 1 unit/s in X direction
            var velocity = new Vector3f(1f, 0f, 0f);
            long receptionTime = 1000L; // 1 second

            // Build a SimulationGhostEntity with real velocity
            var halo = new GhostEntityHalo<StringEntityID, EntityData>(
                entityId,
                null,
                receptionPosition,
                new EntityBounds(receptionPosition, 0.5f),
                "source-tree",
                receptionTime
            );
            var simGhost = new SimulationGhostEntity<>(
                halo, sourceBubble, 1L, 0L, 1L, velocity
            );

            // Deliver ghost to coordinator (fires the onReceive lambda)
            channel.deliver(sourceBubble, List.of(simGhost));

            // Query dead-reckoned position 2 seconds later
            long queryTime = receptionTime + 2000L; // 2 s after reception
            var ghostState = coordinator.getGhostStateManager();
            var deadReckonedPosition = ghostState.getGhostPosition(entityId, queryTime);

            assertNotNull(deadReckonedPosition,
                "Ghost must exist after reception");

            // With velocity (1,0,0) over 2 s, x should be > receptionX.
            // The exact delta depends on the estimator's first-update seed path,
            // but the position must have advanced positively in X.
            assertTrue(deadReckonedPosition.x > receptionPosition.x,
                "Dead-reckoned x=" + deadReckonedPosition.x
                + " must exceed reception x=" + receptionPosition.x
                + " for velocity=(1,0,0) over 2 s");
        }
    }

    /**
     * Regression guard: a ghost with zero velocity must NOT move between ticks —
     * confirming the non-zero test above is meaningful and the estimator actually uses velocity.
     */
    @Test
    void ghostWithZeroVelocityStaysAtReceptionPosition() {
        var controller = new RealTimeController(UUID.randomUUID(), "dr-test-zero", 100);
        var channel = new CapturingGhostChannel();
        var bounds = BubbleBounds.fromTetreeKey(TetreeKey.create((byte) 10, 0L, 0L));

        try (var coordinator = new BubbleGhostCoordinator(channel, () -> bounds, controller)) {

            var sourceBubble = UUID.randomUUID();
            var entityId = new StringEntityID("static-01");
            var receptionPosition = new Point3f(5f, 5f, 5f);
            long receptionTime = 500L;

            var halo = new GhostEntityHalo<StringEntityID, EntityData>(
                entityId,
                null,
                receptionPosition,
                new EntityBounds(receptionPosition, 0.5f),
                "source-tree",
                receptionTime
            );
            // Explicit zero velocity — should stay put
            var simGhost = new SimulationGhostEntity<>(
                halo, sourceBubble, 1L, 0L, 1L, new Vector3f(0f, 0f, 0f)
            );

            channel.deliver(sourceBubble, List.of(simGhost));

            long queryTime = receptionTime + 2000L;
            var pos = coordinator.getGhostStateManager().getGhostPosition(entityId, queryTime);

            assertNotNull(pos, "Ghost must exist after reception");
            // With zero velocity the estimator returns the reception position (clamped to bounds)
            assertTrue(Math.abs(pos.x - receptionPosition.x) < 0.01f,
                "Zero-velocity ghost x=" + pos.x + " must stay at reception x=" + receptionPosition.x);
        }
    }
}
