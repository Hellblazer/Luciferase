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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.vecmath.Point3d;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fault-injection test for Luciferase-s06p8 (Wave-2 .30 review M3): the WAL crash-safety ordering
 * invariant — a PREPARE record must be durably written BEFORE the source entity is removed. This
 * test fails the WAL append (recordPrepare throws) and asserts the source still holds the entity:
 * the migration aborts before removeEntity is ever invoked, so a crash at this point can never
 * leave a removed-but-unrecorded entity.
 *
 * @author hal.hildebrand
 */
class CrossProcessMigrationWalOrderingFaultTest {

    /** WAL whose PREPARE append fails, simulating a durable-write failure (e.g. disk full). */
    private static final class FailingPrepareWal extends MigrationLogPersistence {
        FailingPrepareWal(UUID processId, Path baseDir) throws IOException {
            super(processId, baseDir);
        }

        @Override
        public void recordPrepare(TransactionState state) throws IOException {
            throw new IOException("injected WAL PREPARE append failure");
        }
    }

    /** Source store that records whether removeEntity was ever invoked / succeeded. */
    private static final class Source implements BubbleReference, EntityStoreOperations {
        private final UUID bubbleId;
        private final String heldEntity;
        final AtomicBoolean removeInvoked = new AtomicBoolean(false);
        private final Set<String> held = new HashSet<>();

        Source(UUID bubbleId, String heldEntity) {
            this.bubbleId = bubbleId;
            this.heldEntity = heldEntity;
            this.held.add(heldEntity);
        }

        boolean stillHolds(String entityId) {
            return held.contains(entityId);
        }

        @Override public boolean isLocal() { return true; }
        @Override public LocalBubbleReference asLocal() { return null; }
        @Override public RemoteBubbleProxy asRemote() { throw new IllegalStateException(); }
        @Override public UUID getBubbleId() { return bubbleId; }
        @Override public Point3d getPosition() { return new Point3d(); }
        @Override public Set<UUID> getNeighbors() { return new HashSet<>(); }
        @Override public boolean isReachable() { return true; }

        @Override
        public EntitySnapshot getEntitySnapshot(String entityId) {
            return heldEntity.equals(entityId)
                   ? new EntitySnapshot(entityId, new Point3d(1, 2, 3), "payload", bubbleId, 5L, 2L, 1000L)
                   : null;
        }

        @Override
        public boolean removeEntity(String entityId) {
            removeInvoked.set(true);
            held.remove(entityId);
            return true;
        }

        @Override public boolean addEntity(EntitySnapshot snapshot) {
            held.add(snapshot.entityId());
            return true;
        }
    }

    private static final class Dest implements BubbleReference, EntityStoreOperations {
        private final UUID bubbleId;

        Dest(UUID bubbleId) {
            this.bubbleId = bubbleId;
        }

        @Override public boolean isLocal() { return true; }
        @Override public LocalBubbleReference asLocal() { return null; }
        @Override public RemoteBubbleProxy asRemote() { throw new IllegalStateException(); }
        @Override public UUID getBubbleId() { return bubbleId; }
        @Override public Point3d getPosition() { return new Point3d(); }
        @Override public Set<UUID> getNeighbors() { return new HashSet<>(); }
        @Override public boolean isReachable() { return true; }
        @Override public boolean removeEntity(String entityId) { return true; }
        @Override public boolean addEntity(EntitySnapshot snapshot) { return true; }
    }

    @Test
    void walPrepareFailureLeavesEntityOnSource(@TempDir Path tempDir) throws Exception {
        var processId = UUID.randomUUID();
        var wal = new FailingPrepareWal(processId, tempDir);
        var entityId = UUID.randomUUID().toString();
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var migration = new CrossProcessMigration(new IdempotencyStore(), new MigrationMetrics(),
                                                  MigrationConfig.defaults(), wal);
        var source = new Source(sourceId, entityId);
        var dest = new Dest(destId);

        var result = migration.migrate(entityId, source, dest).get(5, TimeUnit.SECONDS);

        // Migration must fail because PREPARE could not be durably recorded.
        assertFalse(result.success(), "migration must fail when WAL PREPARE append fails");

        // CRASH-SAFETY ORDERING INVARIANT: removeEntity must never have run, so the source still
        // holds the entity. A crash here can never produce a removed-but-unrecorded entity.
        assertFalse(source.removeInvoked.get(),
                    "source.removeEntity must NOT be called when WAL PREPARE append fails "
                    + "(PREPARE must be durable before removal)");
        assertTrue(source.stillHolds(entityId), "source must still hold the entity after failed PREPARE");

        // And the WAL must hold no incomplete transaction (PREPARE never durably landed).
        wal.close();
        var recovered = new MigrationLogPersistence(processId, tempDir) {
        };
        assertTrue(recovered.loadIncomplete().isEmpty(),
                   "no incomplete transaction may persist when PREPARE append failed");
        recovered.close();
    }
}
