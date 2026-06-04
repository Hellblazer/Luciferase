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

import javax.vecmath.Point3d;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rollback-fidelity test for {@link CrossProcessMigration#createEntitySnapshot} (Luciferase-x8pwi).
 *
 * <p>Regression: createEntitySnapshot used to return a hardcoded {@code (0,0,0)}/{@code "MockContent"}
 * snapshot, so an aborted migration restored garbage to the source — silent data loss. This test
 * migrates an entity whose source store holds real state, forces a COMMIT failure (which triggers
 * ABORT/rollback), and asserts the source is restored with the <em>original</em> position and
 * content, not the fabricated origin/mock values.
 *
 * @author hal.hildebrand
 */
class CrossProcessMigrationRollbackFidelityTest {

    /**
     * Source store that holds one real entity and records the snapshot restored to it on rollback.
     */
    private static final class StatefulSource implements BubbleReference, TestableEntityStore {
        private final UUID bubbleId;
        private final EntitySnapshot stored;
        private final boolean provideSnapshot;
        final AtomicReference<EntitySnapshot> restored = new AtomicReference<>();

        StatefulSource(UUID bubbleId, EntitySnapshot stored) {
            this(bubbleId, stored, true);
        }

        StatefulSource(UUID bubbleId, EntitySnapshot stored, boolean provideSnapshot) {
            this.bubbleId = bubbleId;
            this.stored = stored;
            this.provideSnapshot = provideSnapshot;
        }

        @Override public boolean isLocal() { return true; }
        @Override public LocalBubbleReference asLocal() { return null; }
        @Override public RemoteBubbleProxy asRemote() { throw new IllegalStateException("Not remote"); }
        @Override public UUID getBubbleId() { return bubbleId; }
        @Override public Point3d getPosition() { return new Point3d(0, 0, 0); }
        @Override public Set<UUID> getNeighbors() { return new HashSet<>(); }
        @Override public boolean isReachable() { return true; }

        @Override
        public EntitySnapshot getEntitySnapshot(String entityId) {
            // Real entity state captured during PREPARE (when this store tracks full state).
            if (!provideSnapshot) {
                return null;
            }
            return stored.entityId().equals(entityId) ? stored : null;
        }

        @Override
        public boolean removeEntity(String entityId) {
            return true;
        }

        @Override
        public boolean addEntity(EntitySnapshot snapshot) {
            // Rollback restores the snapshot to the source.
            restored.set(snapshot);
            return true;
        }
    }

    /**
     * Destination that always fails the COMMIT (addEntity), forcing an ABORT/rollback.
     */
    private static final class FailingCommitDest implements BubbleReference, TestableEntityStore {
        private final UUID bubbleId;

        FailingCommitDest(UUID bubbleId) { this.bubbleId = bubbleId; }

        @Override public boolean isLocal() { return true; }
        @Override public LocalBubbleReference asLocal() { return null; }
        @Override public RemoteBubbleProxy asRemote() { throw new IllegalStateException("Not remote"); }
        @Override public UUID getBubbleId() { return bubbleId; }
        @Override public Point3d getPosition() { return new Point3d(0, 0, 0); }
        @Override public Set<UUID> getNeighbors() { return new HashSet<>(); }
        @Override public boolean isReachable() { return true; }
        @Override public boolean removeEntity(String entityId) { return true; }

        @Override
        public boolean addEntity(EntitySnapshot snapshot) {
            return false; // COMMIT fails -> migration aborts
        }
    }

    @Test
    void abortRestoresRealEntityStateNotGarbage() throws Exception {
        var entityId = UUID.randomUUID().toString();
        var realPosition = new Point3d(12.5, -7.25, 33.0);
        var realContent = "real-payload-" + UUID.randomUUID();
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        // The entity's real state, as held by the source before migration.
        var original = new EntitySnapshot(entityId, realPosition, realContent, sourceId, 7L, 3L, 1000L);

        var source = new StatefulSource(sourceId, original);
        var dest = new FailingCommitDest(destId);

        var migration = new CrossProcessMigration(new IdempotencyStore(), new MigrationMetrics());

        var result = migration.migrate(entityId, source, dest).get(5, TimeUnit.SECONDS);

        // Migration must have failed (COMMIT could not complete).
        assertFalse(result.success(), "Migration must fail when COMMIT is rejected");

        // The source must have had the entity restored with its REAL state.
        var restored = source.restored.get();
        assertNotNull(restored, "Rollback must restore the entity to the source");

        assertEquals(entityId, restored.entityId());
        assertEquals(realPosition, restored.position(),
            "Rollback must restore the REAL position, not fabricated (0,0,0)");
        assertEquals(realContent, restored.content(),
            "Rollback must restore the REAL content, not 'MockContent'");
        assertEquals(7L, restored.epoch(), "Rollback must restore the real epoch");
        assertEquals(3L, restored.version(), "Rollback must restore the real version");

        // Explicitly guard against the old garbage values.
        assertNotEquals(new Point3d(0, 0, 0), restored.position(), "Must not restore fabricated origin");
        assertNotEquals("MockContent", restored.content(), "Must not restore fabricated content");
    }

    @Test
    void snapshotWithoutEntityStoreIsHonestIdentityOnly() throws Exception {
        // A source that does NOT provide getEntitySnapshot (default returns null): the migration
        // must not fabricate (0,0,0)/MockContent; the restored snapshot is identity-only.
        var entityId = UUID.randomUUID().toString();
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var dest = new FailingCommitDest(destId);

        // A TestableEntityStore source that records the rollback but provides no full state.
        var recordingSource = new StatefulSource(sourceId,
            new EntitySnapshot(entityId, new Point3d(1, 1, 1), "x", sourceId, 1L, 1L, 0L),
            false);

        var migration = new CrossProcessMigration(new IdempotencyStore(), new MigrationMetrics());
        var result = migration.migrate(entityId, recordingSource, dest).get(5, TimeUnit.SECONDS);

        assertFalse(result.success());
        var restored = recordingSource.restored.get();
        assertNotNull(restored);
        assertEquals(entityId, restored.entityId());
        // Honest: null position/content, NOT fabricated garbage.
        assertNull(restored.position(), "Identity-only snapshot must not fabricate a position");
        assertNull(restored.content(), "Identity-only snapshot must not fabricate content");
        assertNotEquals("MockContent", restored.content());
    }

    @Test
    void identityOnlySnapshotRollbackDoesNotNpe() {
        // Explicit guard (wave-1 review FIX 5): the ABORT/rollback call site must tolerate an
        // identity-only snapshot (null position AND null content, per Luciferase-x8pwi) without
        // throwing an NPE. The rollback path only logs the snapshot fields (SLF4J null-safe) and
        // hands the snapshot to source.addEntity; neither dereferences position/content.
        var entityId = UUID.randomUUID().toString();
        var sourceId = UUID.randomUUID();
        var destId = UUID.randomUUID();

        var dest = new FailingCommitDest(destId);
        // provideSnapshot=false -> createEntitySnapshot returns the null-position/null-content
        // identity-only snapshot, which is what gets restored on rollback.
        var recordingSource = new StatefulSource(sourceId,
            new EntitySnapshot(entityId, new Point3d(2, 2, 2), "y", sourceId, 1L, 1L, 0L),
            false);

        var migration = new CrossProcessMigration(new IdempotencyStore(), new MigrationMetrics());

        assertDoesNotThrow(() -> {
            var result = migration.migrate(entityId, recordingSource, dest).get(5, TimeUnit.SECONDS);
            assertFalse(result.success(), "Migration aborts when COMMIT is rejected");
        }, "Rollback of an identity-only (null position/content) snapshot must not NPE");

        var restored = recordingSource.restored.get();
        assertNotNull(restored, "Rollback must still restore the identity-only snapshot");
        assertNull(restored.position());
        assertNull(restored.content());
    }
}
