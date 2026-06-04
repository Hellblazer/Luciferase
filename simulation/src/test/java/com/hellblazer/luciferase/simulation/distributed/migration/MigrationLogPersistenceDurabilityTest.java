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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAL durability and deserialization regressions (Luciferase-0frcy.33/.34).
 *
 * <ul>
 *   <li>.33 — records are written through a {@link java.nio.channels.FileChannel} and fsync'd via
 *       {@code force(true)} on every record, so a record is on stable storage (visible to an
 *       independent reader) <em>before</em> the write call returns — without relying on
 *       {@code close()}.</li>
 *   <li>.34 — a PREPARE record carrying a non-null {@link TransactionState.SerializedSnapshot}
 *       round-trips through {@code recordPrepare} + {@code loadIncomplete}. Pre-fix the field was
 *       typed {@code EntitySnapshot} (contains a {@code Point3d} Jackson cannot deserialize) and
 *       recovery silently skipped every PREPARE record.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class MigrationLogPersistenceDurabilityTest {

    static final class TestWal extends MigrationLogPersistence {
        TestWal(UUID processId, Path baseDir) throws IOException {
            super(processId, baseDir);
        }
    }

    private static TransactionState prepareState(UUID txnId, TransactionState.SerializedSnapshot snap) {
        return new TransactionState(
            txnId, "entity-" + txnId, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            snap, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, 1000L);
    }

    // ---- .33: each record is durable (flushed to disk) without close() ----

    @Test
    void recordPrepareIsVisibleToIndependentReaderWithoutClose(@TempDir Path tempDir) throws IOException {
        var processId = UUID.randomUUID();
        var wal = new TestWal(processId, tempDir);
        try {
            var txnId = UUID.randomUUID();
            wal.recordPrepare(prepareState(txnId, null));

            // Without calling close(): the bytes must already be on disk (force(true) fsync'd them).
            // A fresh reader opened on the same file sees a non-empty WAL and recovers the txn.
            var walFile = wal.getWalFile();
            assertTrue(Files.exists(walFile), "WAL file must exist after recordPrepare");
            assertTrue(Files.size(walFile) > 0,
                       "recordPrepare must durably write bytes to disk before returning "
                       + "(no reliance on close()/flush-to-cache)");

            var reader = new TestWal(processId, tempDir);
            var incomplete = reader.loadIncomplete();
            reader.close();
            assertEquals(1, incomplete.size(), "Independent reader must see the durably-written PREPARE");
            assertEquals(txnId, incomplete.get(0).transactionId());
        } finally {
            wal.close();
        }
    }

    // ---- .34: SerializedSnapshot round-trips through recovery ----

    @Test
    void prepareWithSerializedSnapshotRoundTripsThroughRecovery(@TempDir Path tempDir) throws IOException {
        var processId = UUID.randomUUID();
        var wal = new TestWal(processId, tempDir);
        var txnId = UUID.randomUUID();
        var authority = UUID.randomUUID();
        var snap = new TransactionState.SerializedSnapshot("entity-x", authority, 7L, 3L, 4242L);

        wal.recordPrepare(prepareState(txnId, snap));
        wal.close();

        var recovered = new TestWal(processId, tempDir);
        var incomplete = recovered.loadIncomplete();
        recovered.close();

        assertEquals(1, incomplete.size(),
                     "A PREPARE record with a non-null snapshot must be recoverable — pre-fix the "
                     + "EntitySnapshot/Point3d field made Jackson skip it, yielding 0 recovered txns");
        var roundTripped = incomplete.get(0).snapshot();
        assertNotNull(roundTripped, "snapshot must survive the WAL round-trip");
        assertEquals("entity-x", roundTripped.entityId());
        assertEquals(authority, roundTripped.authorityBubbleId());
        assertEquals(7L, roundTripped.epoch());
        assertEquals(3L, roundTripped.version());
        assertEquals(4242L, roundTripped.timestamp());
    }

    @Test
    void serializedSnapshotProjectsFromFullEntitySnapshot() {
        var authority = UUID.randomUUID();
        var full = new EntitySnapshot("e1", new javax.vecmath.Point3d(9, 9, 9), "content",
                                      authority, 11L, 5L, 7777L);
        var projected = TransactionState.SerializedSnapshot.from(full);
        assertEquals("e1", projected.entityId());
        assertEquals(authority, projected.authorityBubbleId());
        assertEquals(11L, projected.epoch());
        assertEquals(5L, projected.version());
        assertEquals(7777L, projected.timestamp());
    }
}
