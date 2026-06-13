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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-019 gate-S1 (Luciferase-gg28h) — characterization + regression pin for the ACCEPTED GAP (decision B).
 *
 * <p>After WAL compaction prunes a committed {@code ENTITY_DEPARTURE}+{@code MIGRATION_COMMIT} pair, the
 * source node's migration FSM has {@code getState(id)==null} on restart. A subsequent {@code DEPARTED→GHOST}
 * ghost notification's underlying {@code null→GHOST} transition is therefore rejected
 * ({@link EntityMigrationStateMachine#isValidTransition} has no {@code null→GHOST} case, by the single-owner
 * invariant), so source-side ghost adjacency is not re-established via the FSM.
 *
 * <p>RDR-019 decided (decision B) to <b>accept</b> this gap rather than add a {@code null→GHOST} transition,
 * because it is <b>benign</b>: the entity is owned at the TARGET (the source FSM's {@code null} is the
 * correct state — it does <i>not</i> claim ownership), so there is no ownership-invariant violation; only
 * source-side ghost <i>adjacency</i> tracking is lost, and that is re-derivable by the neighbor layer
 * (not load-bearing on FSM bookkeeping). This test pins all three of those facts so the accepted gap is
 * <i>characterized and defended</i>, not silently assumed.
 *
 * <p>This is a characterization test of EXISTING accepted behavior — it is GREEN by design. It turns RED
 * only if someone changes the gap's shape (e.g. adds a {@code null→GHOST} transition, or makes the source
 * FSM wrongly retain ownership of a compacted-away entity). The day a workload needs the source-side ghost
 * re-derived, the fix is a neighbor-layer re-derivation hook on recovery (see
 * {@code GhostStateListener.reconcileGhostState}) — NOT a {@code null→GHOST} FSM transition; this test then
 * documents the contract that re-derivation must not violate (no stale source ownership).
 *
 * <p>Seal-boundary protection of <i>recently</i>-departed entities (the active segment, holding the most
 * recent events, is never compacted — so a recent pair survives and recovers as {@code DEPARTED}, making
 * {@code DEPARTED→GHOST} work normally) is a property of {@link PersistenceManager#compact()} already
 * covered by the gate-S2 tests (WalCompactionConcurrencyTest); it is referenced here, not re-tested.
 *
 * @author hal.hildebrand
 */
class WalCompactionSourceGhostGapTest {

    private static EntityMigrationStateMachine freshFsm() {
        return new EntityMigrationStateMachine(new FirefliesViewMonitor(new MockFirefliesView<>(), 3));
    }

    /** Sum of all WAL segment (.log) byte sizes in the directory — the "retained log" footprint. */
    private static long walBytes(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".log"))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .sum();
        }
    }

    /**
     * Compaction prunes a committed pair → on restart the source FSM does not track the entity, and the
     * {@code DEPARTED→GHOST} forward path is rejected — but benignly (no stale source ownership).
     */
    @Test
    void compactedDepartedEntityYieldsBenignSourceGhostGap(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var committed = UUID.randomUUID();  // departs + commits — eligible for compaction pruning
        var src = UUID.randomUUID();
        var tgt = UUID.randomUUID();
        var clock = new TestClock(1_000L);

        // session 1: a completed migration (DEPARTURE + COMMIT); crash (no clean shutdown).
        try (var mgr = new PersistenceManager(nodeId, logDir, RecoveryStateSink.NOOP)) {
            mgr.setClock(clock);
            mgr.logEntityDeparture(committed, src, tgt);
            mgr.logMigrationCommit(committed);
            // crash
        }
        long bytesBeforeCompaction = walBytes(logDir);

        // session 2: recover, then compact (seals the active segment, prunes the committed pair), crash.
        try (var mgr = new PersistenceManager(nodeId, logDir, RecoveryStateSink.NOOP)) {
            mgr.setClock(clock);
            mgr.recover();
            mgr.compact();
            // crash
        }
        long bytesAfterCompaction = walBytes(logDir);

        // Precondition for the gap: the committed pair really was pruned (otherwise the entity would
        // recover as DEPARTED and the gap would not exist — making the assertions below vacuous).
        assertTrue(bytesAfterCompaction < bytesBeforeCompaction,
                "compaction must prune the committed DEPARTURE+COMMIT pair (retained bytes "
                + bytesAfterCompaction + " must be < pre-compaction " + bytesBeforeCompaction
                + ") — otherwise this test does not exercise the gap");

        // session 3: recover from the compacted log into a fresh FSM — the source-side gap manifests here.
        var fsm3 = freshFsm();
        try (var mgr = new PersistenceManager(nodeId, logDir, new MigrationRecoveryStateSink(fsm3))) {
            mgr.setClock(clock);
            mgr.recover();
        }
        var entityKey = committed.toString();

        // (a) THE GAP: the pruned committed migration leaves the source FSM with no record of the entity.
        assertNull(fsm3.getState(entityKey),
                "after compaction prunes the committed pair, the source FSM must not track the departed "
                + "entity (getState==null) — this is the RDR-019 gate-S1 gap");

        // (b) FORWARD PATH REJECTED: a post-restart DEPARTED→GHOST notification cannot be reconstructed via
        // the FSM — null→GHOST is deliberately not a valid transition (single-owner invariant).
        var ghostAttempt = fsm3.transition(entityKey, EntityMigrationState.GHOST);
        assertFalse(ghostAttempt.success,
                "null→GHOST must be rejected (RDR-019 decision B: deliberately no null→GHOST transition)");
        assertNull(ghostAttempt.fromState,
                "rejection must be the not-found path (fromState==null), not a state-machine-tracked from-state");

        // (c) BENIGN: the gap costs only source-side ghost adjacency tracking, NOT ownership correctness.
        // The source FSM must NOT believe it still owns (or is migrating out) the entity that committed at
        // the target — null is the correct, non-owning state. This is the load-bearing benignity guarantee.
        var sourceState = fsm3.getState(entityKey);
        assertNotEquals(EntityMigrationState.OWNED, sourceState,
                "benign-gap invariant: source must not re-own a committed-away entity (no ownership violation)");
        assertNotEquals(EntityMigrationState.MIGRATING_OUT, sourceState,
                "benign-gap invariant: source must not resurrect a stale in-flight migration for the entity");

        // The rejected GHOST attempt must not have mutated FSM state (the entity stays untracked).
        assertNull(fsm3.getState(entityKey),
                "a rejected null→GHOST transition must leave the entity untracked (no partial state created)");
    }

    /**
     * Contrast / liveness anchor: when the committed pair is NOT compacted away, recovery reconstructs the
     * entity as {@code DEPARTED}, and the {@code DEPARTED→GHOST} forward path is valid — i.e. the gap is
     * specific to the post-compaction case, and the normal (pre-compaction) ghost path is unaffected.
     */
    @Test
    void uncompactedDepartedEntitySupportsDepartedToGhost(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var departed = UUID.randomUUID();
        var src = UUID.randomUUID();
        var tgt = UUID.randomUUID();
        var clock = new TestClock(1_000L);

        // session 1: a completed migration; crash. No compaction runs.
        try (var mgr = new PersistenceManager(nodeId, logDir, RecoveryStateSink.NOOP)) {
            mgr.setClock(clock);
            mgr.logEntityDeparture(departed, src, tgt);
            mgr.logMigrationCommit(departed);
            // crash — no compact()
        }

        // session 2: recover from the full (uncompacted) log — the pair replays to DEPARTED.
        var fsm2 = freshFsm();
        try (var mgr = new PersistenceManager(nodeId, logDir, new MigrationRecoveryStateSink(fsm2))) {
            mgr.setClock(clock);
            mgr.recover();
        }
        var entityKey = departed.toString();

        assertEquals(EntityMigrationState.DEPARTED, fsm2.getState(entityKey),
                "without compaction, the committed pair replays and the entity recovers as DEPARTED");

        // DEPARTED→GHOST is a valid forward transition — the normal source-side ghost path works.
        var ghost = fsm2.transition(entityKey, EntityMigrationState.GHOST);
        assertTrue(ghost.success,
                "DEPARTED→GHOST must succeed for a recovered-as-DEPARTED entity (normal ghost path)");
        assertEquals(EntityMigrationState.GHOST, fsm2.getState(entityKey),
                "the entity must be tracked as GHOST after a successful DEPARTED→GHOST transition");
    }
}
