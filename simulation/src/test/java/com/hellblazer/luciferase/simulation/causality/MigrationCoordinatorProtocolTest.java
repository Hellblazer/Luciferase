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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MigrationCoordinator}'s typed 2PC dispatch (Luciferase-4k66e).
 *
 * <p>Verifies the reflection-free dispatch and the failure-propagation/compensation behavior:
 * <ul>
 *   <li>2PC prepare/commit/abort dispatch through {@link CrossProcessMigrationProtocol} (no reflection).</li>
 *   <li>A PrepareRequest dispatch failure rolls the source FSM back to ROLLBACK_OWNED instead of
 *       leaving the entity stranded in MIGRATING_OUT.</li>
 *   <li>A CommitRequest dispatch failure dispatches an AbortRequest so the target releases the
 *       entity (defends the documented "target stuck in MIGRATING_IN" orphan).</li>
 *   <li>Wiring failure (null protocol) surfaces at construction, not at runtime.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class MigrationCoordinatorProtocolTest {

    /**
     * Deterministic in-memory fake of the 2PC transport. Records every dispatched message and
     * allows individual phases to be forced to fail.
     */
    static final class RecordingProtocol implements CrossProcessMigrationProtocol {
        final List<String> prepares = new ArrayList<>();
        final List<String> commits = new ArrayList<>();
        final List<String> aborts = new ArrayList<>();

        boolean failPrepare = false;
        boolean failCommit = false;
        boolean failAbort = false;

        @Override
        public boolean sendPrepareRequest(Object entityId, UUID sourceBubble, UUID targetBubble) {
            prepares.add(entityId + "->" + targetBubble);
            return !failPrepare;
        }

        @Override
        public boolean sendCommitRequest(Object entityId, UUID targetBubble) {
            commits.add(entityId + "->" + targetBubble);
            return !failCommit;
        }

        @Override
        public boolean sendAbortRequest(Object entityId, UUID targetBubble) {
            aborts.add(entityId + "->" + targetBubble);
            return !failAbort;
        }
    }

    private MockFirefliesView<UUID> view;
    private FirefliesViewMonitor monitor;
    private EntityMigrationStateMachine fsm;
    private RecordingProtocol protocol;
    private MigrationCoordinator coordinator;
    private final UUID localBubble = UUID.randomUUID();
    private final UUID targetBubble = UUID.randomUUID();

    @BeforeEach
    void setup() {
        view = new MockFirefliesView<>();
        // No membership changes are applied, so the monitor reports the view as stable
        // (hasChanged == false), allowing the view-stability-gated MIGRATING_OUT -> DEPARTED
        // transition to proceed deterministically.
        monitor = new FirefliesViewMonitor(view, 0);
        fsm = new EntityMigrationStateMachine(monitor);
        protocol = new RecordingProtocol();
        coordinator = new MigrationCoordinator(fsm, protocol, localBubble);
        fsm.addListener(coordinator);
    }

    @Test
    void constructorRejectsNullProtocol() {
        assertThrows(NullPointerException.class,
            () -> new MigrationCoordinator(fsm, null, localBubble),
            "Wiring failure must surface at construction, not at runtime");
    }

    @Test
    void prepareAndCommitDispatchThroughTypedProtocol() {
        var entityId = UUID.randomUUID();
        fsm.initializeOwned(entityId);
        coordinator.setTargetBubble(entityId, targetBubble);

        // OWNED -> MIGRATING_OUT triggers PrepareRequest
        assertTrue(fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT).success);
        assertEquals(1, protocol.prepares.size(), "PrepareRequest must dispatch through the typed protocol");
        assertEquals(1, coordinator.getTotalPrepares());
        assertEquals(0, coordinator.getTotalPrepareFailures());

        // MIGRATING_OUT -> DEPARTED triggers CommitRequest
        assertTrue(fsm.transition(entityId, EntityMigrationState.DEPARTED).success);
        assertEquals(1, protocol.commits.size(), "CommitRequest must dispatch through the typed protocol");
        assertEquals(1, coordinator.getTotalCommits());
        assertEquals(0, coordinator.getTotalCommitFailures());
        assertTrue(protocol.aborts.isEmpty(), "Happy path must not abort");
    }

    @Test
    void prepareFailureRollsBackSourceFsm() {
        var entityId = UUID.randomUUID();
        fsm.initializeOwned(entityId);
        coordinator.setTargetBubble(entityId, targetBubble);
        protocol.failPrepare = true;

        // OWNED -> MIGRATING_OUT: the prepare dispatch fails, coordinator must compensate.
        assertTrue(fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT).success,
            "FSM transition itself succeeds; the 2PC dispatch is what fails");

        assertEquals(1, protocol.prepares.size(), "Prepare was attempted");
        assertEquals(0, coordinator.getTotalPrepares(), "Failed prepare must not count as a successful prepare");
        assertEquals(1, coordinator.getTotalPrepareFailures());

        // Source FSM must be reclaimed to ROLLBACK_OWNED rather than stranded in MIGRATING_OUT.
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId),
            "Prepare failure must roll the source back to ROLLBACK_OWNED");
        // No abort should be sent — the prepare never reached the target.
        assertTrue(protocol.aborts.isEmpty(),
            "No AbortRequest should be sent for a prepare that never reached the target");
    }

    @Test
    void commitFailureDispatchesAbortToReleaseTarget() {
        var entityId = UUID.randomUUID();
        fsm.initializeOwned(entityId);
        coordinator.setTargetBubble(entityId, targetBubble);

        assertTrue(fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT).success);
        assertEquals(1, protocol.prepares.size());

        protocol.failCommit = true;
        assertTrue(fsm.transition(entityId, EntityMigrationState.DEPARTED).success);

        assertEquals(1, protocol.commits.size(), "Commit was attempted");
        assertEquals(1, coordinator.getTotalCommitFailures(), "Commit failure must be recorded");
        // The defining defense: an AbortRequest is dispatched so the target releases the entity
        // instead of being stuck in MIGRATING_IN forever.
        assertEquals(1, protocol.aborts.size(),
            "Commit failure must dispatch an AbortRequest to release the target");
        assertEquals(1, coordinator.getTotalAborts());

        // The source FSM was driven to DEPARTED by the failing commit; compensation must reclaim
        // local ownership so the entity is not lost from the network. The target aborts (never
        // received the entity), so the source must re-own it via ROLLBACK_OWNED.
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId),
            "Commit failure must restore source ownership (ROLLBACK_OWNED), not leave it DEPARTED");
    }

    @Test
    void rollbackTransitionDispatchesAbort() {
        var entityId = UUID.randomUUID();
        fsm.initializeOwned(entityId);
        coordinator.setTargetBubble(entityId, targetBubble);

        assertTrue(fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT).success);
        // MIGRATING_OUT -> ROLLBACK_OWNED triggers AbortRequest dispatch.
        assertTrue(fsm.transition(entityId, EntityMigrationState.ROLLBACK_OWNED).success);

        assertEquals(1, protocol.aborts.size(), "Rollback must dispatch an AbortRequest");
        assertEquals(1, coordinator.getTotalAborts());
    }
}
