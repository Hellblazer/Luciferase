/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.persistence;

import com.hellblazer.luciferase.simulation.causality.EntityMigrationState;
import com.hellblazer.luciferase.simulation.causality.EntityMigrationStateMachine;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link MigrationRecoveryStateSink}.
 *
 * <p>Verifies that crash recovery — using a real {@link EntityMigrationStateMachine} wired via
 * {@link MigrationRecoveryStateSink} — genuinely reconstructs FSM state. Tests fail if the sink
 * is replaced with {@link RecoveryStateSink#NOOP}.
 */
class MigrationRecoveryStateSinkTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Create a minimal valid WAL event map (version + type + timestamp required by isValidEvent). */
    private static Map<String, Object> base(String type) {
        var e = new java.util.LinkedHashMap<String, Object>();
        e.put("version", 1);
        e.put("type", type);
        e.put("timestamp", System.currentTimeMillis());
        return e;
    }

    private static Map<String, Object> departureEvent(String entityId, String src, String tgt) {
        var e = base("ENTITY_DEPARTURE");
        e.put("entityId", entityId);
        e.put("sourceBubble", src);
        e.put("targetBubble", tgt);
        return e;
    }

    private static Map<String, Object> commitEvent(String entityId) {
        var e = base("MIGRATION_COMMIT");
        e.put("entityId", entityId);
        return e;
    }

    private static Map<String, Object> deferredUpdateEvent(String entityId, List<Number> pos) {
        var e = base("DEFERRED_UPDATE");
        e.put("entityId", entityId);
        e.put("position", pos);
        return e;
    }

    private static Map<String, Object> viewSyncAckEvent(String entityId, boolean success) {
        var e = base("VIEW_SYNC_ACK");
        e.put("entityId", entityId);
        e.put("success", success);
        return e;
    }

    private static EntityMigrationStateMachine freshFsm() {
        var view = new MockFirefliesView<>();
        var monitor = new FirefliesViewMonitor(view, 3);
        return new EntityMigrationStateMachine(monitor);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * ENTITY_DEPARTURE replayed → entity lands in MIGRATING_OUT in the FSM.
     * The test FAILS with NOOP sink because getState() returns null.
     */
    @Test
    void departureSetsEntityToMigratingOut(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var entityId = UUID.randomUUID().toString();
        var src = UUID.randomUUID().toString();
        var tgt = UUID.randomUUID().toString();

        // Write departure WAL; simulate crash (no checkpoint).
        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            wal.append(departureEvent(entityId, src, tgt));
            wal.flush();
        }

        var fsm  = freshFsm();
        var sink = new MigrationRecoveryStateSink(fsm);
        var recovery = new EventRecovery(logDir, sink);
        recovery.recover(nodeId);

        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId),
                "ENTITY_DEPARTURE must set FSM to MIGRATING_OUT, not be silently discarded");
    }

    /**
     * ENTITY_DEPARTURE followed by MIGRATION_COMMIT → entity lands in DEPARTED.
     * The test FAILS with NOOP sink.
     */
    @Test
    void departureFollowedByCommitSetsEntityToDeparted(@TempDir Path logDir) throws IOException {
        var nodeId   = UUID.randomUUID();
        var entityId = UUID.randomUUID().toString();
        var src      = UUID.randomUUID().toString();
        var tgt      = UUID.randomUUID().toString();

        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            wal.append(departureEvent(entityId, src, tgt));
            wal.append(commitEvent(entityId));
            wal.flush();
        }

        var fsm  = freshFsm();
        var sink = new MigrationRecoveryStateSink(fsm);
        var recovery = new EventRecovery(logDir, sink);
        var state = recovery.recover(nodeId);

        assertEquals(2, state.totalEventsReplayed(), "Both events must be replayed");
        assertEquals(EntityMigrationState.DEPARTED, fsm.getState(entityId),
                "MIGRATION_COMMIT after ENTITY_DEPARTURE must promote entity to DEPARTED");
    }

    /**
     * End-to-end through PersistenceManager: write events, crash, recover via a NEW
     * PersistenceManager+MigrationRecoveryStateSink wired to a fresh FSM. Asserts FSM state.
     *
     * <p>This is the PRODUCTION seam test: it exercises the path
     * {@code PersistenceManager.recover() → new EventRecovery(logDir, recoverySink) → stateSink.*}.
     * Fails if PersistenceManager reverts to the single-arg EventRecovery constructor.
     */
    @Test
    void endToEndViaManagerReconstructsFsmState(@TempDir Path logDir) throws IOException {
        var nodeId   = UUID.randomUUID();
        var entityA  = UUID.randomUUID();
        var entityB  = UUID.randomUUID();
        var src      = UUID.randomUUID();
        var tgt      = UUID.randomUUID();

        var fsm  = freshFsm();
        var sink = new MigrationRecoveryStateSink(fsm);

        // Write phase: two entities; A departs and commits, B departs but crashes before commit.
        try (var mgr = new PersistenceManager(nodeId, logDir, RecoveryStateSink.NOOP)) {
            mgr.logEntityDeparture(entityA, src, tgt);
            mgr.logMigrationCommit(entityA);
            mgr.logEntityDeparture(entityB, src, tgt);
            // Crash: no checkpoint, B's commit never written
        }

        // Recovery phase: fresh manager with real FSM sink.
        try (var mgr = new PersistenceManager(nodeId, logDir, sink)) {
            var recovered = mgr.recover();
            assertTrue(recovered.totalEventsReplayed() >= 3,
                    "At least 3 WAL events must be replayed");
        }

        // A completed migration → DEPARTED
        assertEquals(EntityMigrationState.DEPARTED, fsm.getState(entityA.toString()),
                "Committed migration must reconstruct as DEPARTED");

        // B started but did not commit → MIGRATING_OUT
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityB.toString()),
                "Uncommitted departure must reconstruct as MIGRATING_OUT");
    }

    /**
     * Proves that {@code enforceRecoveryCaller} uses a class-filter (not a fixed skip depth) and
     * is therefore robust to synthetic lambda frames injected by the JVM.
     *
     * <p>When {@code MigrationRecoveryStateSink.recoverViaLambdaForTest()} dispatches
     * {@code recoverEntityState} via a lambda, the JVM inserts a synthetic bridge frame between
     * the {@code EntityMigrationStateMachine} frame and the {@code MigrationRecoveryStateSink}
     * frame. The old {@code skip(2).findFirst()} implementation would have placed the synthetic
     * frame at depth 2, hiding the real caller one level deeper and causing a false
     * {@link IllegalCallerException} on the legitimate caller.
     *
     * <p>The filter-based implementation strips all {@code EntityMigrationStateMachine} frames
     * (regardless of how many are present), so the first remaining frame — the lambda's
     * declaring class, which is {@code MigrationRecoveryStateSink} — is correctly identified as
     * the permitted caller.
     */
    @Test
    void recoverEntityState_viaLambdaDoesNotThrow() {
        var fsm  = freshFsm();
        var sink = new MigrationRecoveryStateSink(fsm);
        var entityId = UUID.randomUUID().toString();

        // Must NOT throw: the lambda's declaring class is MigrationRecoveryStateSink, which
        // is the permitted RECOVERY_CALLER. With skip(2) this would have thrown.
        assertDoesNotThrow(
            () -> sink.recoverViaLambdaForTest(entityId, EntityMigrationState.MIGRATING_OUT),
            "enforceRecoveryCaller must not throw when recoverEntityState is dispatched via "
            + "a lambda inside MigrationRecoveryStateSink — filter-based guard is lambda-robust"
        );
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId),
            "state must be applied when the permitted caller dispatches via lambda");
    }

    /**
     * VIEW_SYNC_ACK and DEFERRED_UPDATE are handled without throwing; FSM state is unaffected.
     */
    @Test
    void viewSyncAckAndDeferredUpdateDoNotThrowOrMutateFsm(@TempDir Path logDir) throws IOException {
        var nodeId   = UUID.randomUUID();
        var entityId = UUID.randomUUID().toString();

        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            wal.append(viewSyncAckEvent(entityId, true));
            wal.append(deferredUpdateEvent(entityId, List.of(1.0, 2.0, 3.0)));
            wal.flush();
        }

        var fsm  = freshFsm();
        var sink = new MigrationRecoveryStateSink(fsm);
        var recovery = new EventRecovery(logDir, sink);
        var state = recovery.recover(nodeId);

        assertEquals(2, state.totalEventsReplayed());
        assertNull(fsm.getState(entityId),
                "VIEW_SYNC_ACK and DEFERRED_UPDATE must not create FSM entries");
    }
}
