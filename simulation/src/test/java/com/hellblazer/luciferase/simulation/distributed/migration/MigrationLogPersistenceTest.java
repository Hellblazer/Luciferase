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
    void testMalformedLineHandling() throws IOException {
        // Write a valid transaction
        var txnId = UUID.randomUUID();
        var state = new TransactionState(
            txnId, "entity", UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            null, UUID.randomUUID(), TransactionState.MigrationPhase.PREPARE, System.currentTimeMillis()
        );
        persistence.recordPrepare(state);

        // Manually append malformed lines
        var walFile = persistence.getWalFile();
        try (var writer = new PrintWriter(Files.newBufferedWriter(walFile, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND))) {
            writer.println("invalid json {{{");
            writer.println("{ \"incomplete\": true }");
            writer.println("");  // Empty line
        }

        // Recovery should skip malformed lines and return valid transaction
        var recovered = new TestMigrationLogPersistence(processId, tempDir);
        var incomplete = recovered.loadIncomplete();

        assertEquals(1, incomplete.size());
        assertEquals(txnId, incomplete.get(0).transactionId());
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
