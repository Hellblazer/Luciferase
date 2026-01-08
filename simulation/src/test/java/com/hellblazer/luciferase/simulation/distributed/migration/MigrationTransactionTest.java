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

import com.hellblazer.luciferase.simulation.distributed.BubbleReference;
import javafx.geometry.Point3D;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MigrationTransaction and related classes.
 * <p>
 * Verifies:
 * - Transaction creation and validation
 * - Phase transitions
 * - Timeout detection
 * - EntitySnapshot immutability
 * - MigrationResult success/failure cases
 * - MigrationMetrics accuracy
 * - MigrationState enum transitions
 * - MigrationPhase enum usage
 *
 * @author hal.hildebrand
 */
class MigrationTransactionTest {

    @Test
    void testTransactionCreation() {
        var txnId = UUID.randomUUID();
        var token = new IdempotencyToken(
            "entity-1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            System.currentTimeMillis(),
            UUID.randomUUID()
        );

        var mockSource = createMockBubbleReference(UUID.randomUUID());
        var mockDest = createMockBubbleReference(UUID.randomUUID());

        var snapshot = new EntitySnapshot(
            "entity-1",
            new Point3D(10, 20, 30),
            "TestContent",
            mockSource.getBubbleId(),
            42L, // epoch
            100L, // version
            System.currentTimeMillis()
        );

        var txn = new MigrationTransaction(txnId, token, snapshot, mockSource, mockDest);

        assertNotNull(txn);
        assertEquals(txnId, txn.transactionId());
        assertEquals(token, txn.idempotencyToken());
        assertEquals(snapshot, txn.entitySnapshot());
        assertEquals(mockSource, txn.sourceRef());
        assertEquals(mockDest, txn.destRef());
        assertEquals(MigrationPhase.PREPARE, txn.phase());
        assertFalse(txn.isTimedOut(300)); // Should not be timed out immediately
    }

    @Test
    void testPhaseTransitions() {
        var txnId = UUID.randomUUID();
        var token = new IdempotencyToken("entity-1", UUID.randomUUID(), UUID.randomUUID(),
                                         System.currentTimeMillis(), UUID.randomUUID());
        var mockSource = createMockBubbleReference(UUID.randomUUID());
        var mockDest = createMockBubbleReference(UUID.randomUUID());
        var snapshot = new EntitySnapshot("entity-1", new Point3D(0, 0, 0), "Content",
                                          mockSource.getBubbleId(), 1L, 1L, System.currentTimeMillis());

        var txn = new MigrationTransaction(txnId, token, snapshot, mockSource, mockDest);

        // Initially PREPARE
        assertEquals(MigrationPhase.PREPARE, txn.phase());

        // Advance to COMMIT
        var committed = txn.advancePhase(MigrationPhase.COMMIT);
        assertEquals(MigrationPhase.COMMIT, committed.phase());

        // Verify original unchanged (immutable)
        assertEquals(MigrationPhase.PREPARE, txn.phase());
    }

    @Test
    void testPhaseAbort() {
        var txnId = UUID.randomUUID();
        var token = new IdempotencyToken("entity-1", UUID.randomUUID(), UUID.randomUUID(),
                                         System.currentTimeMillis(), UUID.randomUUID());
        var mockSource = createMockBubbleReference(UUID.randomUUID());
        var mockDest = createMockBubbleReference(UUID.randomUUID());
        var snapshot = new EntitySnapshot("entity-1", new Point3D(0, 0, 0), "Content",
                                          mockSource.getBubbleId(), 1L, 1L, System.currentTimeMillis());

        var txn = new MigrationTransaction(txnId, token, snapshot, mockSource, mockDest);

        // Can abort from PREPARE
        var aborted = txn.advancePhase(MigrationPhase.ABORT);
        assertEquals(MigrationPhase.ABORT, aborted.phase());
    }

    @Test
    void testTimeoutDetection() throws InterruptedException {
        var txnId = UUID.randomUUID();
        var token = new IdempotencyToken("entity-1", UUID.randomUUID(), UUID.randomUUID(),
                                         System.currentTimeMillis(), UUID.randomUUID());
        var mockSource = createMockBubbleReference(UUID.randomUUID());
        var mockDest = createMockBubbleReference(UUID.randomUUID());
        var snapshot = new EntitySnapshot("entity-1", new Point3D(0, 0, 0), "Content",
                                          mockSource.getBubbleId(), 1L, 1L, System.currentTimeMillis());

        var txn = new MigrationTransaction(txnId, token, snapshot, mockSource, mockDest);

        // Not timed out immediately
        assertFalse(txn.isTimedOut(100));

        // Wait for timeout
        Thread.sleep(150);

        // Should be timed out now
        assertTrue(txn.isTimedOut(100));
    }

    @Test
    void testEntitySnapshotImmutability() {
        var position = new Point3D(10, 20, 30);
        var snapshot = new EntitySnapshot(
            "entity-1",
            position,
            "TestContent",
            UUID.randomUUID(),
            42L,
            100L,
            System.currentTimeMillis()
        );

        // Verify all fields
        assertEquals("entity-1", snapshot.entityId());
        assertEquals(position, snapshot.position());
        assertEquals("TestContent", snapshot.content());
        assertEquals(42L, snapshot.epoch());
        assertEquals(100L, snapshot.version());

        // Snapshot is immutable (record type)
        var snapshot2 = new EntitySnapshot(
            snapshot.entityId(),
            snapshot.position(),
            snapshot.content(),
            snapshot.authorityBubbleId(),
            snapshot.epoch(),
            snapshot.version(),
            snapshot.timestamp()
        );

        assertEquals(snapshot, snapshot2);
    }

    @Test
    void testMigrationResultSuccess() {
        var result = MigrationResult.success("entity-1", UUID.randomUUID(), 42L);

        assertTrue(result.success());
        assertEquals("entity-1", result.entityId());
        assertEquals(42L, result.latencyMs());
        assertNull(result.reason());
    }

    @Test
    void testMigrationResultFailure() {
        var result = MigrationResult.failure("entity-1", "Destination unreachable");

        assertFalse(result.success());
        assertEquals("entity-1", result.entityId());
        assertEquals("Destination unreachable", result.reason());
    }

    @Test
    void testMigrationMetrics() {
        var metrics = new MigrationMetrics();

        // Initially zero
        assertEquals(0, metrics.getSuccessfulMigrations());
        assertEquals(0, metrics.getFailedMigrations());
        assertEquals(0, metrics.getDuplicatesRejected());
        assertEquals(0, metrics.getAborts());
        assertEquals(0, metrics.getConcurrentMigrations());

        // Record success
        metrics.recordSuccess(50);
        assertEquals(1, metrics.getSuccessfulMigrations());
        assertEquals(50.0, metrics.getPrepareLatency().mean(), 0.001);

        // Record failure
        metrics.recordFailure("timeout");
        assertEquals(1, metrics.getFailedMigrations());

        // Record abort
        metrics.recordAbort("network partition");
        assertEquals(1, metrics.getAborts());

        // Concurrent migrations gauge
        metrics.incrementConcurrent();
        assertEquals(1, metrics.getConcurrentMigrations());

        metrics.decrementConcurrent();
        assertEquals(0, metrics.getConcurrentMigrations());
    }

    /**
     * Create a mock BubbleReference for testing.
     */
    private BubbleReference createMockBubbleReference(UUID bubbleId) {
        return new BubbleReference() {
            @Override
            public boolean isLocal() {
                return true;
            }

            @Override
            public com.hellblazer.luciferase.simulation.distributed.LocalBubbleReference asLocal() {
                return null; // Not needed for these tests
            }

            @Override
            public com.hellblazer.luciferase.simulation.distributed.RemoteBubbleProxy asRemote() {
                throw new IllegalStateException("Not a remote reference");
            }

            @Override
            public UUID getBubbleId() {
                return bubbleId;
            }

            @Override
            public Point3D getPosition() {
                return new Point3D(0, 0, 0);
            }

            @Override
            public java.util.Set<UUID> getNeighbors() {
                return new HashSet<>();
            }
        };
    }
}
