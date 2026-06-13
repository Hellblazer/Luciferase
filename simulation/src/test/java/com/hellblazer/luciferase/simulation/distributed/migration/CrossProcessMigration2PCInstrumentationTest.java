/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed.migration;

import com.hellblazer.luciferase.common.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.vecmath.Point3d;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Instrumentation regressions for the 2PC orchestrator (Luciferase-0frcy.30/.31/.32).
 *
 * <ul>
 *   <li>.30 — WAL (MigrationLogPersistence) is actually invoked: a successful migration writes a
 *       PREPARE then a COMMIT record (so recovery sees a resolved transaction); an aborted
 *       migration writes a PREPARE then an ABORT record.</li>
 *   <li>.31 — the total-2PC timeout guard in abort() measures from the transaction start, not the
 *       ABORT-phase start.</li>
 *   <li>.32 — the success totalLatency metric measures the full PREPARE→COMMIT span, not just the
 *       COMMIT phase.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class CrossProcessMigration2PCInstrumentationTest {

    /** Test subclass exposing the protected base-dir constructor. */
    static final class TestWal extends MigrationLogPersistence {
        TestWal(UUID processId, Path baseDir) throws IOException {
            super(processId, baseDir);
        }
    }

    /**
     * A clock that advances a fixed delta on every read. Each phase of the state machine reads the
     * clock several times, so a successful 2PC spans many reads — guaranteeing the captured
     * transaction-start time precedes the COMMIT-phase start.
     */
    private static final class TickingClock implements Clock {
        private final AtomicLong now;
        private final long       deltaPerRead;

        TickingClock(long start, long deltaPerRead) {
            this.now = new AtomicLong(start);
            this.deltaPerRead = deltaPerRead;
        }

        @Override
        public long currentTimeMillis() {
            return now.getAndAdd(deltaPerRead);
        }
    }

    private static final class Source implements BubbleReference, TestableEntityStore {
        private final UUID           bubbleId;
        private final EntitySnapshot stored;
        final AtomicReference<EntitySnapshot> restored = new AtomicReference<>();

        Source(UUID bubbleId, EntitySnapshot stored) {
            this.bubbleId = bubbleId;
            this.stored = stored;
        }

        @Override public boolean isLocal() { return true; }
        @Override public LocalBubbleReference asLocal() { return null; }
        @Override public RemoteBubbleProxy asRemote() { throw new IllegalStateException(); }
        @Override public UUID getBubbleId() { return bubbleId; }
        @Override public Point3d getPosition() { return new Point3d(); }
        @Override public Set<UUID> getNeighbors() { return new HashSet<>(); }
        @Override public boolean isReachable() { return true; }
        @Override public EntitySnapshot getEntitySnapshot(String entityId) {
            return stored.entityId().equals(entityId) ? stored : null;
        }
        @Override public boolean removeEntity(String entityId) { return true; }
        @Override public boolean addEntity(EntitySnapshot snapshot) { restored.set(snapshot); return true; }
    }

    private static final class Dest implements BubbleReference, TestableEntityStore {
        private final UUID    bubbleId;
        private final boolean commitSucceeds;

        Dest(UUID bubbleId, boolean commitSucceeds) {
            this.bubbleId = bubbleId;
            this.commitSucceeds = commitSucceeds;
        }

        @Override public boolean isLocal() { return true; }
        @Override public LocalBubbleReference asLocal() { return null; }
        @Override public RemoteBubbleProxy asRemote() { throw new IllegalStateException(); }
        @Override public UUID getBubbleId() { return bubbleId; }
        @Override public Point3d getPosition() { return new Point3d(); }
        @Override public Set<UUID> getNeighbors() { return new HashSet<>(); }
        @Override public boolean isReachable() { return true; }
        @Override public boolean removeEntity(String entityId) { return true; }
        @Override public boolean addEntity(EntitySnapshot snapshot) { return commitSucceeds; }
    }

    private static EntitySnapshot snapshotFor(String entityId, UUID sourceId) {
        return new EntitySnapshot(entityId, new Point3d(1, 2, 3), "payload", sourceId, 5L, 2L, 1000L);
    }

    // ---- .30: WAL is actually wired ----

    @Test
    void successfulMigrationWritesPrepareThenCommitToWal(@TempDir Path tempDir) throws Exception {
        var processId = UUID.randomUUID();
        var wal = new TestWal(processId, tempDir);
        var entityId = UUID.randomUUID().toString();
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var migration = new CrossProcessMigration(new IdempotencyStore(), new MigrationMetrics(),
                                                  MigrationConfig.defaults(), wal);
        // Deterministic clock (constant: delta-per-read 0) so the wall-clock 2PC timeout guards
        // (100ms phase / 300ms total) can never fire under CI load and spuriously short-circuit the
        // PREPARE→COMMIT path before COMMIT is recorded — this test asserts WAL contents, not timing
        // (Luciferase-f5nj7). The total-timeout semantics are exercised separately by the .31 test.
        migration.setClock(new TickingClock(1000L, 0L));
        var source = new Source(sourceId, snapshotFor(entityId, sourceId));
        var dest = new Dest(destId, true);

        var result = migration.migrate(entityId, source, dest).get(5, TimeUnit.SECONDS);
        assertTrue(result.success(), "migration should succeed");
        wal.close();

        // PREPARE was recorded, then COMMIT — so recovery sees a resolved (not incomplete) txn.
        var recovered = new TestWal(processId, tempDir);
        var incomplete = recovered.loadIncomplete();
        recovered.close();
        assertTrue(incomplete.isEmpty(),
                   "A committed migration must leave NO incomplete transaction in the WAL "
                   + "(PREPARE + COMMIT must both have been recorded), got: " + incomplete);
    }

    @Test
    void abortedMigrationWritesPrepareThenAbortToWal(@TempDir Path tempDir) throws Exception {
        var processId = UUID.randomUUID();
        var wal = new TestWal(processId, tempDir);
        var entityId = UUID.randomUUID().toString();
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var migration = new CrossProcessMigration(new IdempotencyStore(), new MigrationMetrics(),
                                                  MigrationConfig.defaults(), wal);
        // Deterministic clock (constant: delta-per-read 0) so the 2PC timeout guards never fire under
        // CI load. Without it, a slow runner blows the 100ms/300ms budget and abort() takes the
        // ABORT_TIMEOUT branch, which (correctly) leaves the txn for recovery WITHOUT writing ABORT —
        // the exact flake this fixes (Luciferase-f5nj7). With the clock pinned, the clean
        // COMMIT_FAILED → successful-rollback → walRecordAbort path is taken deterministically.
        migration.setClock(new TickingClock(1000L, 0L));
        var source = new Source(sourceId, snapshotFor(entityId, sourceId));
        var dest = new Dest(destId, false); // COMMIT fails -> ABORT

        var result = migration.migrate(entityId, source, dest).get(5, TimeUnit.SECONDS);
        assertFalse(result.success(), "migration should fail (commit rejected)");
        wal.close();

        // PREPARE + ABORT both recorded -> recovery treats txn as resolved.
        var recovered = new TestWal(processId, tempDir);
        var incomplete = recovered.loadIncomplete();
        recovered.close();
        assertTrue(incomplete.isEmpty(),
                   "An aborted migration must leave NO incomplete transaction in the WAL "
                   + "(PREPARE + ABORT must both have been recorded), got: " + incomplete);
    }

    @Test
    void timedOutMigrationLeavesPrepareIncompleteForRecovery(@TempDir Path tempDir) throws Exception {
        // Pins the production contract behind the f5nj7 flake: when the 2PC exceeds its timeout budget
        // (forced deterministically here with a fast-advancing clock, mimicking a slow CI runner), the
        // migration fails and DELIBERATELY leaves the PREPARE record incomplete — it does NOT write a
        // false ABORT/COMMIT — so crash recovery (loadIncomplete) resolves the dangling transaction.
        // This is exactly the state abortedMigrationWritesPrepareThenAbortToWal was accidentally landing
        // in under load; making it explicit documents the behavior as intended, not a WAL bug, and proves
        // the root cause (real/fast clock vs the 100ms/300ms budget → a timeout branch that skips ABORT).
        var processId = UUID.randomUUID();
        var wal = new TestWal(processId, tempDir);
        var entityId = UUID.randomUUID().toString();
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var migration = new CrossProcessMigration(new IdempotencyStore(), new MigrationMetrics(),
                                                  MigrationConfig.defaults(), wal);
        // 500ms per clock read >> 100ms phase budget → the PREPARE-phase timeout guard (CrossProcessMigration
        // :768) fires right after PREPARE is durably written (:747) but before COMMIT is entered, completing
        // the future via failAndUnlock("TIMEOUT") with no COMMIT/ABORT record. (The ABORT-phase timeout
        // :891 is a sibling branch with the same PREPARE-only outcome; the contract pinned here — a timed-out
        // 2PC leaves the txn incomplete for recovery — holds for any timeout branch.)
        migration.setClock(new TickingClock(1000L, 500L));
        var source = new Source(sourceId, snapshotFor(entityId, sourceId));
        // dest outcome is never consulted: the PREPARE timeout fires before commit() reads it.
        var dest = new Dest(destId, true);

        var result = migration.migrate(entityId, source, dest).get(5, TimeUnit.SECONDS);
        assertFalse(result.success(), "a migration that blows its timeout budget must fail");
        wal.close();

        var recovered = new TestWal(processId, tempDir);
        var incomplete = recovered.loadIncomplete();
        recovered.close();
        assertFalse(incomplete.isEmpty(),
                    "a timed-out 2PC must leave the PREPARE incomplete for crash recovery (no false ABORT)");
        assertTrue(incomplete.stream().allMatch(t -> t.phase() == TransactionState.MigrationPhase.PREPARE),
                   "the dangling transaction(s) must be in PREPARE phase, got: " + incomplete);
        assertTrue(incomplete.stream().anyMatch(t -> entityId.equals(t.entityId())),
                   "the incomplete transaction must be for our entity, got: " + incomplete);
    }

    @Test
    void noWalConfiguredStillMigratesSuccessfully(@TempDir Path tempDir) throws Exception {
        // Backward-compat: the 3-arg constructor (no WAL) must keep working.
        var entityId = UUID.randomUUID().toString();
        var sourceId = UUID.randomUUID();
        var migration = new CrossProcessMigration(new IdempotencyStore(), new MigrationMetrics());
        var source = new Source(sourceId, snapshotFor(entityId, sourceId));
        var dest = new Dest(UUID.randomUUID(), true);
        var result = migration.migrate(entityId, source, dest).get(5, TimeUnit.SECONDS);
        assertTrue(result.success());
    }

    // ---- .32: totalLatency spans PREPARE->COMMIT ----

    @Test
    void successLatencyMeasuresFullTransactionNotJustCommitPhase() throws Exception {
        var metrics = new MigrationMetrics();
        var migration = new CrossProcessMigration(new IdempotencyStore(), metrics);
        // Each clock read advances 10ms. The full ACQUIRE->PREPARE->COMMIT path reads the clock
        // many times; if latency were measured only from COMMIT-phase start it would be a small
        // fraction of the total. We assert the recorded latency is at least the PREPARE+COMMIT
        // span (strictly more than a single phase's worth of reads).
        migration.setClock(new TickingClock(0, 10));

        var entityId = UUID.randomUUID().toString();
        var sourceId = UUID.randomUUID();
        var source = new Source(sourceId, snapshotFor(entityId, sourceId));
        var dest = new Dest(UUID.randomUUID(), true);

        var result = migration.migrate(entityId, source, dest).get(5, TimeUnit.SECONDS);
        assertTrue(result.success());

        // The success path captures txnStartTime at PREPARE entry and several clock reads occur
        // before COMMIT completes. With a COMMIT-phase-only measurement the latency would be the
        // span across just the commit() reads (<= ~30ms). The end-to-end span is strictly larger.
        var latency = result.latencyMs();
        assertTrue(latency >= 40,
                   "totalLatency must span PREPARE->COMMIT (>=40ms with 10ms/read), got " + latency
                   + "ms — a COMMIT-phase-only measurement would be much smaller");
    }

    // ---- .31: abort total-timeout guard measures from transaction start ----

    @Test
    void abortTotalTimeoutGuardMeasuresFromTransactionStart() throws Exception {
        // A tiny totalTimeoutMs combined with an advancing clock: by the time abort() runs, the
        // transaction has already consumed more than totalTimeoutMs of clock reads since txn start.
        // With the bug (measuring from ABORT-phase start) the guard would never fire because
        // phaseStartTime was reset at the COMMIT->ABORT transition.
        // phase=50ms, total=150ms (honours the total >= 3*phase invariant). With a 50ms-per-read
        // clock, no single phase's couple of reads trips the 50ms per-phase guard, but by the time
        // abort() runs many reads have elapsed since the transaction start (>150ms).
        var config = new MigrationConfig(50, 150, 25, 10_000_000, 20);
        var metrics = new MigrationMetrics();
        var migration = new CrossProcessMigration(new IdempotencyStore(), metrics, config);
        migration.setClock(new TickingClock(0, 50));

        var entityId = UUID.randomUUID().toString();
        var sourceId = UUID.randomUUID();
        var source = new Source(sourceId, snapshotFor(entityId, sourceId));
        var dest = new Dest(UUID.randomUUID(), false); // force COMMIT failure -> ABORT

        var result = migration.migrate(entityId, source, dest).get(5, TimeUnit.SECONDS);
        assertFalse(result.success());

        // The abort total-timeout branch fires only when measuring from txn start. It records a
        // rollback failure and an orphaned entity, and returns ABORT_TIMEOUT.
        assertEquals("ABORT_TIMEOUT", result.reason(),
                     "abort() must report ABORT_TIMEOUT when total 2PC time exceeds totalTimeoutMs "
                     + "measured from the transaction start");
        assertTrue(metrics.getRollbackFailures() >= 1,
                   "total-timeout abort must record a rollback failure");
    }
}
