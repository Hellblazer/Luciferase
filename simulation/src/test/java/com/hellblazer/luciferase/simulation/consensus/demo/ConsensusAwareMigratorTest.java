/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus.demo;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.consensus.committee.OptimisticMigratorIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConsensusAwareMigrator.
 * <p>
 * Tests consensus-coordinated entity migration with:
 * - Cross-bubble migrations require consensus approval
 * - Intra-bubble migrations use local authority
 * - Approval/rejection callbacks
 * - Timeout handling
 * - View change rollback
 * <p>
 * Phase 8A Day 1: Consensus-Migration Integration
 *
 * @author hal.hildebrand
 */
class ConsensusAwareMigratorTest {

    private ConsensusAwareMigrator migrator;
    private OptimisticMigratorIntegration consensusIntegration;
    private Digest localBubbleId;
    private Digest remoteBubbleId;
    private Digest currentNodeId;

    @BeforeEach
    void setUp() {
        consensusIntegration = mock(OptimisticMigratorIntegration.class);
        localBubbleId = DigestAlgorithm.DEFAULT.digest("local-bubble".getBytes());
        remoteBubbleId = DigestAlgorithm.DEFAULT.digest("remote-bubble".getBytes());
        currentNodeId = DigestAlgorithm.DEFAULT.digest("current-node".getBytes());

        migrator = new ConsensusAwareMigrator(consensusIntegration, localBubbleId, currentNodeId);
    }

    @Test
    void testCrossBubbleMigrationRequiresConsensusApproval() {
        // Given: Entity in local bubble wants to migrate to remote bubble
        var entityId = UUID.randomUUID();

        // Mock consensus approves migration
        when(consensusIntegration.requestMigrationApproval(eq(entityId), eq(currentNodeId), eq(remoteBubbleId)))
            .thenReturn(CompletableFuture.completedFuture(true));

        var approved = new AtomicBoolean(false);

        // When: Request cross-bubble migration
        migrator.requestMigration(entityId, remoteBubbleId, result -> approved.set(result));

        // Then: Consensus was consulted and migration approved
        verify(consensusIntegration).requestMigrationApproval(entityId, currentNodeId, remoteBubbleId);
        assertTrue(approved.get(), "Cross-bubble migration should be approved by consensus");
    }

    @Test
    void testCrossBubbleMigrationRejectedByConsensus() {
        // Given: Entity wants to migrate but consensus rejects
        var entityId = UUID.randomUUID();

        when(consensusIntegration.requestMigrationApproval(eq(entityId), eq(currentNodeId), eq(remoteBubbleId)))
            .thenReturn(CompletableFuture.completedFuture(false));

        var approved = new AtomicBoolean(true); // Start as true to verify it gets set to false

        // When: Request cross-bubble migration
        migrator.requestMigration(entityId, remoteBubbleId, result -> approved.set(result));

        // Then: Migration rejected
        verify(consensusIntegration).requestMigrationApproval(entityId, currentNodeId, remoteBubbleId);
        assertFalse(approved.get(), "Cross-bubble migration should be rejected when consensus denies");
    }

    @Test
    void testIntraBubbleMigrationBypassesConsensus() {
        // Given: Entity migrating within same bubble (local authority)
        var entityId = UUID.randomUUID();
        var approved = new AtomicBoolean(false);

        // When: Request intra-bubble migration (same as localBubbleId)
        migrator.requestMigration(entityId, localBubbleId, result -> approved.set(result));

        // Then: Consensus NOT consulted, approved immediately
        verify(consensusIntegration, never()).requestMigrationApproval(any(), any(), any());
        assertTrue(approved.get(), "Intra-bubble migration should be approved locally without consensus");
    }

    @Test
    void testConsensusTimeoutTriggersRejection() {
        // Given: Entity migration request, but consensus times out
        var entityId = UUID.randomUUID();

        var timeoutFuture = new CompletableFuture<Boolean>();
        timeoutFuture.completeExceptionally(new java.util.concurrent.TimeoutException("Consensus timeout"));

        when(consensusIntegration.requestMigrationApproval(eq(entityId), eq(currentNodeId), eq(remoteBubbleId)))
            .thenReturn(timeoutFuture);

        var approved = new AtomicBoolean(true); // Start as true

        // When: Request migration with timeout
        migrator.requestMigration(entityId, remoteBubbleId, result -> approved.set(result));

        // Then: Migration rejected due to timeout
        assertFalse(approved.get(), "Migration should be rejected on consensus timeout");
    }

    @Test
    void testMultipleConcurrentMigrations() {
        // Given: Multiple entities requesting migrations concurrently
        var entity1 = UUID.randomUUID();
        var entity2 = UUID.randomUUID();
        var entity3 = UUID.randomUUID();

        when(consensusIntegration.requestMigrationApproval(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(true));

        var approvals = new AtomicInteger(0);

        // When: Request multiple migrations concurrently
        migrator.requestMigration(entity1, remoteBubbleId, result -> { if (result) approvals.incrementAndGet(); });
        migrator.requestMigration(entity2, remoteBubbleId, result -> { if (result) approvals.incrementAndGet(); });
        migrator.requestMigration(entity3, remoteBubbleId, result -> { if (result) approvals.incrementAndGet(); });

        // Then: All migrations approved independently
        assertEquals(3, approvals.get(), "All concurrent migrations should be approved");
        verify(consensusIntegration, times(3)).requestMigrationApproval(any(), any(), any());
    }

    @Test
    void testCallbackInvokedOnlyOnce() {
        // Given: Migration request with callback
        var entityId = UUID.randomUUID();

        when(consensusIntegration.requestMigrationApproval(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(true));

        var callbackCount = new AtomicInteger(0);

        // When: Request migration
        migrator.requestMigration(entityId, remoteBubbleId, result -> callbackCount.incrementAndGet());

        // Then: Callback invoked exactly once
        assertEquals(1, callbackCount.get(), "Callback should be invoked exactly once");
    }

    @Test
    void testMigrationProposalTracking() {
        // Given: Entity migration request with non-completed future
        var entityId = UUID.randomUUID();

        var future = new CompletableFuture<Boolean>();
        when(consensusIntegration.requestMigrationApproval(any(), any(), any()))
            .thenReturn(future);

        // When: Request migration (not yet completed)
        migrator.requestMigration(entityId, remoteBubbleId, result -> {});

        // Then: Migration tracked as pending
        assertTrue(migrator.hasPendingMigration(entityId), "Migration should be tracked as pending");

        // Clean up: complete the future
        future.complete(true);
    }

    @Test
    void testPendingMigrationClearedAfterCompletion() {
        // Given: Entity migration request
        var entityId = UUID.randomUUID();

        when(consensusIntegration.requestMigrationApproval(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(true));

        // When: Request and complete migration
        migrator.requestMigration(entityId, remoteBubbleId, result -> {});

        // Give async callback time to complete
        try { Thread.sleep(100); } catch (InterruptedException e) { }

        // Then: Migration no longer pending after completion
        assertFalse(migrator.hasPendingMigration(entityId), "Migration should be cleared after completion");
    }

    @Test
    void testNullEntityIdRejected() {
        // Given: Null entity ID

        // When/Then: Requesting migration with null entity ID should throw
        assertThrows(NullPointerException.class, () -> {
            migrator.requestMigration(null, remoteBubbleId, result -> {});
        }, "Null entity ID should be rejected");
    }

    @Test
    void testNullTargetBubbleIdRejected() {
        // Given: Valid entity, null target
        var entityId = UUID.randomUUID();

        // When/Then: Requesting migration with null target should throw
        assertThrows(NullPointerException.class, () -> {
            migrator.requestMigration(entityId, null, result -> {});
        }, "Null target bubble ID should be rejected");
    }

    @Test
    void testNullCallbackRejected() {
        // Given: Valid entity and target, null callback
        var entityId = UUID.randomUUID();

        // When/Then: Requesting migration with null callback should throw
        assertThrows(NullPointerException.class, () -> {
            migrator.requestMigration(entityId, remoteBubbleId, null);
        }, "Null callback should be rejected");
    }

    @Test
    void testConsensusExceptionHandled() {
        // Given: Consensus throws exception during voting
        var entityId = UUID.randomUUID();

        var failedFuture = new CompletableFuture<Boolean>();
        failedFuture.completeExceptionally(new RuntimeException("Consensus voting failed"));

        when(consensusIntegration.requestMigrationApproval(any(), any(), any()))
            .thenReturn(failedFuture);

        var approved = new AtomicBoolean(true); // Start as true

        // When: Request migration with failing consensus
        migrator.requestMigration(entityId, remoteBubbleId, result -> approved.set(result));

        // Then: Migration rejected gracefully (no exception thrown)
        assertFalse(approved.get(), "Migration should be rejected when consensus fails");
    }

    @Test
    void testIdempotentMigrationRequests() {
        // Given: Same entity requests migration twice
        var entityId = UUID.randomUUID();

        when(consensusIntegration.requestMigrationApproval(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(true));

        var firstCallbackInvoked = new AtomicBoolean(false);
        var secondCallbackInvoked = new AtomicBoolean(false);

        // When: Request same migration twice
        migrator.requestMigration(entityId, remoteBubbleId, result -> firstCallbackInvoked.set(true));
        migrator.requestMigration(entityId, remoteBubbleId, result -> secondCallbackInvoked.set(true));

        // Then: Both requests processed independently (no deduplication at this layer)
        assertTrue(firstCallbackInvoked.get(), "First callback should be invoked");
        assertTrue(secondCallbackInvoked.get(), "Second callback should be invoked");
        verify(consensusIntegration, times(2)).requestMigrationApproval(entityId, currentNodeId, remoteBubbleId);
    }

    @Test
    void testCorrectSourceNodeIdPassedToConsensus() {
        // Given: Entity migration request
        var entityId = UUID.randomUUID();

        when(consensusIntegration.requestMigrationApproval(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(true));

        var sourceCaptor = ArgumentCaptor.forClass(Digest.class);

        // When: Request migration
        migrator.requestMigration(entityId, remoteBubbleId, result -> {});

        // Then: Current node ID passed as source
        verify(consensusIntegration).requestMigrationApproval(
            eq(entityId),
            sourceCaptor.capture(),
            eq(remoteBubbleId)
        );
        assertEquals(currentNodeId, sourceCaptor.getValue(), "Current node ID should be passed as source");
    }

    @Test
    void testIntraBubbleMigrationCompletesImmediately() {
        // Given: Entity migrating within local bubble
        var entityId = UUID.randomUUID();
        var callbackInvoked = new AtomicBoolean(false);

        // When: Request intra-bubble migration
        var startTime = System.currentTimeMillis();
        migrator.requestMigration(entityId, localBubbleId, result -> callbackInvoked.set(true));
        var duration = System.currentTimeMillis() - startTime;

        // Then: Callback invoked immediately (< 50ms)
        assertTrue(callbackInvoked.get(), "Intra-bubble migration should complete immediately");
        assertTrue(duration < 50, "Intra-bubble migration should complete in < 50ms, took " + duration + "ms");
    }
}
