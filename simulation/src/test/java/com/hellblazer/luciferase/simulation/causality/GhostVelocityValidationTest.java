/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.causality;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import com.hellblazer.luciferase.simulation.ghost.GhostStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.90: GhostStateListener must pass the ghost's ACTUAL
 * tracked velocity (not a hard-coded zero vector) to the consistency validator, so that
 * position validation scales with entity speed instead of collapsing to the static 0.1-unit
 * tolerance used for stationary objects.
 *
 * @author hal.hildebrand
 */
class GhostVelocityValidationTest {

    /** Deterministic fixed clock. */
    private static final class FixedClock implements Clock {
        volatile long millis;

        FixedClock(long millis) {
            this.millis = millis;
        }

        @Override
        public long currentTimeMillis() {
            return millis;
        }

        @Override
        public long nanoTime() {
            return millis * 1_000_000L;
        }
    }

    private GhostStateManager ghostStateManager;
    private EntityMigrationStateMachine fsm;
    private GhostStateListener listener;
    private FixedClock clock;

    @BeforeEach
    void setUp() {
        var mockView = new MockFirefliesView<>();
        var viewMonitor = new FirefliesViewMonitor(mockView, 3);
        fsm = new EntityMigrationStateMachine(viewMonitor, EntityMigrationStateMachine.Configuration.defaultConfig());

        var positions = List.of(new Point3f(0, 0, 0), new Point3f(1000, 1000, 1000));
        var bounds = BubbleBounds.fromEntityPositions(positions);
        ghostStateManager = new GhostStateManager(bounds, 1000);

        listener = new GhostStateListener(ghostStateManager, fsm);
        fsm.addListener(listener);

        clock = new FixedClock(0L);
        listener.setClock(clock);
    }

    /**
     * A fast-moving ghost whose dead-reckoned extrapolation diverges from its last
     * authoritative position by 0.4 units. With the fix (actual velocity = 10 units/s), the
     * velocity-scaled tolerance is 10 × 1.0s × 5% = 0.5 units, so 0.4 is VALID. Pre-fix (zero
     * velocity), the static tolerance was 0.1 units, so 0.4 would be (incorrectly) INVALID and,
     * worse, validation was effectively bypassed for all faster entities. This test fails
     * pre-fix (positionValid() == false) and passes post-fix.
     */
    @Test
    void movingGhostValidationScalesWithActualVelocity() {
        var entityId = new StringEntityID("mover");
        var sourceBubble = UUID.randomUUID();
        var p0 = new Point3f(20, 20, 20);
        var velocity = new Point3f(10, 0, 0); // 10 units/sec along x

        // Drive FSM to GHOST.
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);

        // Authoritative ghost update at t=0.
        var event = new EntityUpdateEvent(entityId, p0, velocity, 0L, 100L);
        ghostStateManager.updateGhost(sourceBubble, event);

        fsm.transition(entityId, EntityMigrationState.GHOST);

        // Advance the clock by 40ms: dead reckoning extrapolates x by 10 * 0.04 = 0.4 units,
        // while the listener passes ghost.position() (p0) as expectedPosition → delta = 0.4.
        clock.millis = 40L;

        // GHOST → MIGRATING_IN triggers validation.
        var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);
        assertTrue(result.success, "transition should succeed");

        var report = listener.getLastConsistencyReport();
        assertNotNull(report, "validation report captured");

        // Sanity: divergence is well beyond the old static 0.1 tolerance...
        assertTrue(report.positionDelta() > 0.1f,
                   "position delta (" + report.positionDelta() + ") exceeds old static tolerance");
        // ...yet within the velocity-scaled tolerance, so the fix reports VALID.
        assertTrue(report.positionValid(),
                   "moving ghost within velocity-scaled tolerance must validate; delta="
                   + report.positionDelta());
    }
}
