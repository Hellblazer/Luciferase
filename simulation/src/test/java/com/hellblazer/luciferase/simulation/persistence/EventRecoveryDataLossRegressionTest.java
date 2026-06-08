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
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RDR-019 P1.1 — TDD failing-first regression reproducing the WAL checkpoint/recovery silent
 * data-loss (driving bug {@code Luciferase-n6jrh.3}).
 *
 * <p><b>The bug.</b> {@link PersistenceManager#checkpoint()} records the checkpoint at the CURRENT
 * {@code eventCounter} sequence. On recovery, {@link EventRecovery#recover} replays only events
 * AFTER the last checkpoint. So an in-flight migration that was written and then checkpointed (but
 * whose state never made it into a durable snapshot — there is none) is silently skipped on the
 * next start: a fresh FSM recovers nothing for that entity. The departure event is durably on disk,
 * yet recovery treats it as already-captured and drops it.
 *
 * <p>This differs from {@link MigrationRecoveryStateSinkTest#endToEndViaManagerReconstructsFsmState}
 * (which passes today) only by the explicit {@code checkpoint()} call between the writes and the
 * crash — that single call is what triggers the loss.
 *
 * <p>Crash is simulated with {@link PersistenceManager#close()} (crash-safe: flush + close, NO
 * truncate), NOT {@link PersistenceManager#closeClean()} (which truncates the WAL).
 *
 * <p>EXPECTED: RED on current code (recovered state is {@code null}); GREEN after RDR-019 P1.2
 * removes the periodic checkpoint as a replay bound and recovery full-replays the retained log.
 *
 * @author hal.hildebrand
 */
class EventRecoveryDataLossRegressionTest {

    private static EntityMigrationStateMachine freshFsm() {
        var view = new MockFirefliesView<>();
        var monitor = new FirefliesViewMonitor(view, 3);
        return new EntityMigrationStateMachine(monitor);
    }

    /**
     * In-flight (uncommitted) departure: DEPARTURE → checkpoint() → crash → recover.
     * Today recovery skips the post-checkpoint tail (empty), so the entity is lost (null).
     */
    @Test
    void inFlightDepartureSurvivesCheckpointThenCrash(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var entityA = UUID.randomUUID();
        var src = UUID.randomUUID();
        var tgt = UUID.randomUUID();
        var clock = new TestClock(1_000L);

        // Write phase: one in-flight departure, then an EXPLICIT checkpoint (mirrors the periodic
        // 5s checkpoint firing mid-migration), then a crash (no clean shutdown, no truncate).
        try (var mgr = new PersistenceManager(nodeId, logDir, RecoveryStateSink.NOOP)) {
            mgr.setClock(clock);
            mgr.logEntityDeparture(entityA, src, tgt);
            mgr.checkpoint();
            // crash: close() flushes + closes but does NOT truncate, retaining the WAL tail
        }

        // Recovery phase: fresh manager + real FSM sink.
        var fsm = freshFsm();
        try (var mgr = new PersistenceManager(nodeId, logDir, new MigrationRecoveryStateSink(fsm))) {
            mgr.setClock(clock);
            mgr.recover();
        }

        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityA.toString()),
                "An in-flight ENTITY_DEPARTURE that was checkpointed (no durable snapshot exists) must "
                + "still reconstruct as MIGRATING_OUT on recovery — pre-fix it is silently dropped (null)");
    }

    /**
     * Committed migration: DEPARTURE → COMMIT → checkpoint() → crash → recover.
     * Both events are durably logged; recovery must reconstruct DEPARTED (harmless in Phase 1 — the
     * pair is not pruned until Phase 2 compaction). Pre-fix the checkpoint-bounded replay drops both.
     */
    @Test
    void committedMigrationSurvivesCheckpointThenCrash(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var entityA = UUID.randomUUID();
        var src = UUID.randomUUID();
        var tgt = UUID.randomUUID();
        var clock = new TestClock(1_000L);

        try (var mgr = new PersistenceManager(nodeId, logDir, RecoveryStateSink.NOOP)) {
            mgr.setClock(clock);
            mgr.logEntityDeparture(entityA, src, tgt);
            mgr.logMigrationCommit(entityA);
            mgr.checkpoint();
            // crash
        }

        var fsm = freshFsm();
        try (var mgr = new PersistenceManager(nodeId, logDir, new MigrationRecoveryStateSink(fsm))) {
            mgr.setClock(clock);
            mgr.recover();
        }

        assertEquals(EntityMigrationState.DEPARTED, fsm.getState(entityA.toString()),
                "A committed migration (DEPARTURE+COMMIT) durably logged before a checkpoint must "
                + "reconstruct as DEPARTED on recovery — pre-fix the checkpoint-bounded replay drops both events");
    }

    /**
     * Re-migration in the same retained log: DEPARTURE → COMMIT (cycle 1 completes) → DEPARTURE again
     * (cycle 2 in flight) → crash → recover. The entity must reconstruct as MIGRATING_OUT (cycle 2's
     * state), not DEPARTED (cycle 1). Pre-fix the migration-key dedup skipped the second ENTITY_DEPARTURE
     * (same {@code ENTITY_DEPARTURE:id} key as the first), silently reconstructing the wrong state.
     * (RDR-019 P1.3 substantive-critic finding.)
     */
    @Test
    void reMigrationAfterCommitReconstructsLatestCycle(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var entityA = UUID.randomUUID();
        var src = UUID.randomUUID();
        var tgt = UUID.randomUUID();
        var clock = new TestClock(1_000L);

        try (var mgr = new PersistenceManager(nodeId, logDir, RecoveryStateSink.NOOP)) {
            mgr.setClock(clock);
            mgr.logEntityDeparture(entityA, src, tgt);   // cycle 1 begins
            mgr.logMigrationCommit(entityA);             // cycle 1 completes (entity later returns)
            mgr.logEntityDeparture(entityA, src, tgt);   // cycle 2 begins, in flight
            // crash before cycle 2 commits
        }

        var fsm = freshFsm();
        try (var mgr = new PersistenceManager(nodeId, logDir, new MigrationRecoveryStateSink(fsm))) {
            mgr.setClock(clock);
            mgr.recover();
        }

        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityA.toString()),
                "Re-migration: the second (in-flight) ENTITY_DEPARTURE must reconstruct as MIGRATING_OUT, "
                + "not be dropped by cycle-blind dedup leaving the stale DEPARTED from cycle 1");
    }
}
