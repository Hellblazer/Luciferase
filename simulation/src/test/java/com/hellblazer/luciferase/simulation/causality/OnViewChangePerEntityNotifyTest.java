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

import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.61: onViewChange() must fire per-entity listener
 * notifications (with precise old/new state identity) rather than relying solely on the
 * aggregate onViewChangeRollback() count.
 *
 * @author hal.hildebrand
 */
class OnViewChangePerEntityNotifyTest {

    private record Captured(Object entityId, EntityMigrationState from, EntityMigrationState to) {
    }

    private MockFirefliesView<String> view;
    private FirefliesViewMonitor viewMonitor;
    private EntityMigrationStateMachine fsm;

    @BeforeEach
    void setUp() {
        view = new MockFirefliesView<>();
        viewMonitor = new FirefliesViewMonitor(view, 3);
        fsm = new EntityMigrationStateMachine(viewMonitor);
    }

    private void makeViewStable() {
        view.addMember("bubble1");
        for (int i = 1; i <= 3; i++) {
            viewMonitor.onTick(i);
        }
    }

    @Test
    void onViewChangeNotifiesPerEntityWithIdentity() {
        var captured = new ArrayList<Captured>();
        var rollbackCounts = new int[2]; // [rolledBack, ghost]
        fsm.addListener(new MigrationStateListener() {
            @Override
            public void onEntityStateTransition(Object entityId, EntityMigrationState fromState,
                                                EntityMigrationState toState,
                                                EntityMigrationStateMachine.TransitionResult result) {
                captured.add(new Captured(entityId, fromState, toState));
            }

            @Override
            public void onViewChangeRollback(int rolledBackCount, int ghostCount) {
                rollbackCounts[0] = rolledBackCount;
                rollbackCounts[1] = ghostCount;
            }
        });

        makeViewStable();

        // Drive "out" to MIGRATING_OUT.
        fsm.initializeOwned("out");
        fsm.transition("out", EntityMigrationState.MIGRATING_OUT);

        // Drive "in" to MIGRATING_IN (OWNED->MIGRATING_OUT->DEPARTED->GHOST->MIGRATING_IN).
        fsm.initializeOwned("in");
        fsm.transition("in", EntityMigrationState.MIGRATING_OUT);
        fsm.transition("in", EntityMigrationState.DEPARTED);
        fsm.transition("in", EntityMigrationState.GHOST);
        fsm.transition("in", EntityMigrationState.MIGRATING_IN);

        captured.clear(); // ignore the setup transitions; focus on the view-change ones

        fsm.onViewChange();

        // Pre-fix: captured would be empty (replaceAll fired no per-entity notifications).
        assertEquals(2, captured.size(), "one per-entity notification per affected entity");

        var out = captured.stream().filter(c -> c.entityId().equals("out")).findFirst().orElseThrow();
        assertEquals(EntityMigrationState.MIGRATING_OUT, out.from(), "out: from MIGRATING_OUT");
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, out.to(), "out: to ROLLBACK_OWNED");

        var in = captured.stream().filter(c -> c.entityId().equals("in")).findFirst().orElseThrow();
        assertEquals(EntityMigrationState.MIGRATING_IN, in.from(), "in: from MIGRATING_IN");
        assertEquals(EntityMigrationState.GHOST, in.to(), "in: to GHOST");

        // Aggregate notification still fires with correct counts.
        assertEquals(1, rollbackCounts[0], "one rolled back");
        assertEquals(1, rollbackCounts[1], "one ghost");
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState("out"));
        assertEquals(EntityMigrationState.GHOST, fsm.getState("in"));
    }
}
