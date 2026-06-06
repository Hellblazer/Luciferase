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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MigrationLogPersistence WAL implementation.
 */
class MigrationLogPersistenceTest {

    @TempDir
    Path tempDir;

    private UUID processId;
    private MigrationLogPersistence persistence;

    @BeforeEach
    void setUp() throws IOException {
        processId = UUID.randomUUID();
        // Use TestMigrationLogPersistence with temp directory
        persistence = new TestMigrationLogPersistence(processId, tempDir);
    }

    @Test
    void testWalDirectoryCreation() throws IOException {
        assertNotNull(persistence.getWalDirectory());
        assertTrue(Files.exists(persistence.getWalDirectory()));
        assertTrue(Files.isDirectory(persistence.getWalDirectory()));
    }

    @Test
    void testRecordAndLoadSingleTransaction() throws IOException {
        var txnId = UUID.randomUUID();
        var entityId = "entity-123";
        var sourceProcess = UUID.randomUUID();
        var destProcess = UUID.randomUUID();
        var sourceBubble = UUID.randomUUID();
        var destBubble = UUID.randomUUID();
        // Snapshot can be null for WAL - recovery restores from source bubble state
        var token = UUID.randomUUID();

        var state = new TransactionState(
            txnId, entityId, sourceProcess, destProcess, sourceBubble, destBubble,
            null, token, TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
        );

        persistence.recordPrepare(state);

        // Create new instance to simulate process restart
        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        var incomplete = recovered.loadIncomplete();

        assertEquals(1, incomplete.size());
        var recovered_state = incomplete.get(0);
        assertEquals(txnId, recovered_state.transactionId());
        assertEquals(entityId, recovered_state.entityId());
        assertEquals(TransactionState.MigrationPhase.PREPARE, recovered_state.phase());
    }

    /**
     * Luciferase-rtffx: every case here passed a null snapshot, so a regression in serializing a non-null
     * TransactionState.snapshot would go undetected in this file. Record a PREPARE carrying a populated
     * SerializedSnapshot and assert it survives recordPrepare -> loadIncomplete (the Point3d-free
     * SerializedSnapshot is Jackson-round-trippable; the old EntitySnapshot/Point3d field was not).
     */
    @Test
    void testRecordAndLoadTransactionWithNonNullSnapshot() throws IOException {
        var txnId = UUID.randomUUID();
        var authority = UUID.randomUUID();
        var snap = new TransactionState.SerializedSnapshot("entity-x", authority, 7L, 3L, 4242L);

        var state = new TransactionState(
            txnId, "entity-x", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            snap, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, 1000L
        );

        persistence.recordPrepare(state);

        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        var incomplete = recovered.loadIncomplete();

        assertEquals(1, incomplete.size(), "the PREPARE record with a non-null snapshot must be recoverable");
        var roundTripped = incomplete.get(0).snapshot();
        assertNotNull(roundTripped, "non-null snapshot must survive recordPrepare -> loadIncomplete");
        assertEquals("entity-x", roundTripped.entityId());
        assertEquals(authority, roundTripped.authorityBubbleId());
        assertEquals(7L, roundTripped.epoch());
        assertEquals(3L, roundTripped.version());
        assertEquals(4242L, roundTripped.timestamp());
    }

    @Test
    void testCommitMarksTransactionComplete() throws IOException {
        var txnId = UUID.randomUUID();
        var entityId = "entity-123";
        var state = new TransactionState(
            txnId, entityId, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
        );

        persistence.recordPrepare(state);
        persistence.recordCommit(txnId);

        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        var incomplete = recovered.loadIncomplete();

        // Should be empty because COMMIT was recorded
        assertEquals(0, incomplete.size());
    }

    @Test
    void testAbortMarksTransactionComplete() throws IOException {
        var txnId = UUID.randomUUID();
        var entityId = "entity-123";
        var state = new TransactionState(
            txnId, entityId, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
        );

        persistence.recordPrepare(state);
        persistence.recordAbort(txnId);

        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        var incomplete = recovered.loadIncomplete();

        // Should be empty because ABORT was recorded
        assertEquals(0, incomplete.size());
    }

    @Test
    void testMultipleTransactionsPartialCompletion() throws IOException {
        var txn1Id = UUID.randomUUID();
        var txn2Id = UUID.randomUUID();
        var txn3Id = UUID.randomUUID();

        // Record 3 transactions
        for (var txnId : new UUID[] { txn1Id, txn2Id, txn3Id }) {
            var state = new TransactionState(
                txnId, "entity-" + txnId.toString().substring(0, 8),
                UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(),
                null, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
            );
            persistence.recordPrepare(state);
        }

        // Complete first two
        persistence.recordCommit(txn1Id);
        persistence.recordAbort(txn2Id);

        // Only txn3 should be incomplete
        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        var incomplete = recovered.loadIncomplete();

        assertEquals(1, incomplete.size());
        assertEquals(txn3Id, incomplete.get(0).transactionId());
    }

    @Test
    void testInteriorMalformedLineThrowsIOException() throws IOException {
        // Write a valid transaction
        var txnId = UUID.randomUUID();
        var state = new TransactionState(
            txnId, "entity", UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
        );
        persistence.recordPrepare(state);

        // Inject a malformed interior line BEFORE a valid trailing line so it is not the final line.
        // This simulates mid-file corruption (not a torn tail). The former bug silently skipped this
        // and returned txnId — encoding RDR-004-class silent data loss.
        var walFile = persistence.getWalFile();
        try (var writer = new PrintWriter(Files.newBufferedWriter(walFile, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND))) {
            writer.println("invalid json {{{");       // interior malformed line
            // append a syntactically valid but semantically incomplete record so the corrupt line
            // is not the final physical line — confirming it is an interior corruption
            writer.println("{\"transactionId\":\"" + UUID.randomUUID() + "\",\"phase\":\"COMMIT\"}");
        }

        // Recovery must FAIL LOUD on the interior malformed line, not silently skip it.
        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        assertThrows(IOException.class, recovered::loadIncomplete,
                     "Interior malformed WAL line must throw IOException, not be silently skipped");
    }

    @Test
    void testTornTailOnFinalLineIsToleratedNotThrowing() throws IOException {
        // Write a valid PREPARE transaction
        var txnId = UUID.randomUUID();
        var state = new TransactionState(
            txnId, "entity", UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
        );
        persistence.recordPrepare(state);

        // Append a truncated/malformed line as the LAST physical line (torn tail after crash-mid-write).
        var walFile = persistence.getWalFile();
        try (var writer = new PrintWriter(Files.newBufferedWriter(walFile, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND))) {
            writer.print("{\"transactionId\":\"partial");  // truncated — no newline, no closing brace
        }

        // Torn tail (final line only) must be tolerated: prior PREPAREs still recovered, no throw.
        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        var incomplete = recovered.loadIncomplete();

        assertEquals(1, incomplete.size(), "PREPARE before torn tail must still be recovered");
        assertEquals(txnId, incomplete.get(0).transactionId());
    }

    // ---- S3 physical-position torn-tail tests (Luciferase-7wzml.198) ----

    /**
     * S3 regression (was silently tolerated before fix): a corrupt non-final physical line followed
     * by trailing blank lines. The corrupt line is the last NON-BLANK entry but NOT the last
     * physical line (blank lines follow). Old code used blank-stripped index and misclassified it
     * as a torn tail. Fixed code checks file-ends-with-newline to detect this.
     */
    @Test
    void testCorruptNonFinalPhysicalLineWithTrailingBlanksThrows() throws IOException {
        var txnId = UUID.randomUUID();
        var state = new TransactionState(
            txnId, "entity", UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
        );
        persistence.recordPrepare(state);
        persistence.close();

        // Append: [CORRUPT]\n\n\n  — corrupt line followed by trailing blank lines.
        // The corrupt line is last NON-BLANK but file ends with '\n' (not a torn tail).
        var walFile = persistence.getWalFile();
        try (var writer = new java.io.PrintWriter(Files.newBufferedWriter(walFile, StandardCharsets.UTF_8,
                                                                          java.nio.file.StandardOpenOption.APPEND))) {
            writer.println("[CORRUPT_LINE_NOT_JSON]");   // corrupt, with newline
            writer.println();                             // trailing blank line 1
            writer.println();                             // trailing blank line 2
        }

        // Must THROW — not silently tolerate as a torn tail (S3 regression test)
        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        assertThrows(IOException.class, recovered::loadIncomplete,
                     "Corrupt non-final physical line (followed by trailing blanks) must throw IOException, "
                     + "not be silently tolerated as a torn tail");
    }

    /**
     * S3: a genuinely truncated final line (file does NOT end with newline) is a legitimate torn
     * tail and must be tolerated without throwing.
     */
    @Test
    void testGenuinelyTruncatedFinalLineNoNewlineTolerated() throws IOException {
        var txnId = UUID.randomUUID();
        var state = new TransactionState(
            txnId, "entity", UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
        );
        persistence.recordPrepare(state);
        persistence.close();

        // Append a truncated line with NO trailing newline — genuine crash-mid-write.
        var walFile = persistence.getWalFile();
        try (var out = Files.newOutputStream(walFile, java.nio.file.StandardOpenOption.APPEND)) {
            out.write("{\"transactionId\":\"truncated-no-newline".getBytes(StandardCharsets.UTF_8));
            // deliberately no newline
        }

        // Must NOT throw — prior PREPARE is still recoverable
        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        var incomplete = recovered.loadIncomplete();
        assertEquals(1, incomplete.size(), "PREPARE before genuinely truncated tail must be recovered");
        assertEquals(txnId, incomplete.get(0).transactionId());
    }

    /**
     * S3: a clean newline-terminated final line whose content is corrupt is NOT a torn tail.
     * The write completed (file ends with '\n'); the content is real corruption → must throw.
     */
    @Test
    void testNewlineTerminatedFinalCorruptLineThrows() throws IOException {
        var txnId = UUID.randomUUID();
        var state = new TransactionState(
            txnId, "entity", UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
        );
        persistence.recordPrepare(state);
        persistence.close();

        // Append a corrupt line WITH a trailing newline — write completed but content is bad.
        var walFile = persistence.getWalFile();
        try (var writer = new java.io.PrintWriter(Files.newBufferedWriter(walFile, StandardCharsets.UTF_8,
                                                                          java.nio.file.StandardOpenOption.APPEND))) {
            writer.println("[CORRUPT_COMPLETE_WRITE]");  // has newline — write completed
        }

        // Must THROW — this is corruption, not a torn tail
        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        assertThrows(IOException.class, recovered::loadIncomplete,
                     "Newline-terminated corrupt final line must throw IOException — the write "
                     + "completed so this is not a torn tail");
    }

    @Test
    void testEmptyWalOnFirstStart() throws IOException {
        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        var incomplete = recovered.loadIncomplete();

        assertEquals(0, incomplete.size());
    }

    @Test
    void testFilePersistenceAcrossInstances() throws IOException {
        var txn1Id = UUID.randomUUID();
        var txn2Id = UUID.randomUUID();

        // First instance
        var state1 = new TransactionState(
            txn1Id, "entity-1", UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
        );
        persistence.recordPrepare(state1);
        persistence.recordCommit(txn1Id);
        persistence.close();

        // Second instance (simulating restart)
        var persistence2 = new TestMigrationLogPersistence(processId, tempDir);
        var state2 = new TransactionState(
            txn2Id, "entity-2", UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
        );
        persistence2.recordPrepare(state2);

        // Only txn2 should be incomplete
        var incomplete = persistence2.loadIncomplete();
        assertEquals(1, incomplete.size());
        assertEquals(txn2Id, incomplete.get(0).transactionId());
        persistence2.close();
    }

    @Test
    void testInvalidPreparePhase() {
        var state = new TransactionState(
            UUID.randomUUID(), "entity", UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID(), TransactionState.MigrationPhase.COMMIT, System.currentTimeMillis()
        );

        assertThrows(IllegalArgumentException.class, () -> persistence.recordPrepare(state));
    }

    /**
     * Test subclass that allows directory override for testing.
     */
    static class TestMigrationLogPersistence extends MigrationLogPersistence {
        TestMigrationLogPersistence(UUID processId, Path baseDir) throws IOException {
            super(processId, baseDir);
        }
    }
}
