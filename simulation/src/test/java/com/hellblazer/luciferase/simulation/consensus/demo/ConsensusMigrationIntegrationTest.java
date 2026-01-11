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
import com.hellblazer.luciferase.simulation.consensus.committee.MigrationProposal;
import com.hellblazer.luciferase.simulation.consensus.committee.ViewCommitteeConsensus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConsensusMigrationIntegration.
 * <p>
 * Tests proposal lifecycle, view change handling, and timeout scenarios.
 * <p>
 * Phase 8A Day 1: Consensus-Migration Integration
 *
 * @author hal.hildebrand
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class ConsensusMigrationIntegrationTest {

    private ConsensusMigrationIntegration integration;
    private ViewCommitteeConsensus consensus;
    private Digest currentViewId;

    @BeforeEach
    void setUp() {
        consensus = mock(ViewCommitteeConsensus.class);
        currentViewId = DigestAlgorithm.DEFAULT.digest("view-1".getBytes());
        integration = new ConsensusMigrationIntegration(consensus, currentViewId);
    }

    @Test
    void testProposalCreatedWithCorrectViewId() {
        // Given: Migration request
        var entityId = UUID.randomUUID();
        var sourceId = DigestAlgorithm.DEFAULT.digest("source".getBytes());
        var targetId = DigestAlgorithm.DEFAULT.digest("target".getBytes());

        when(consensus.requestConsensus(any(MigrationProposal.class)))
            .thenReturn(CompletableFuture.completedFuture(true));

        // When: Create migration proposal
        integration.createMigrationProposal(entityId, sourceId, targetId);

        // Then: Proposal created with current view ID
        verify(consensus).requestConsensus(argThat(proposal ->
            proposal.entityId().equals(entityId) &&
            proposal.sourceNodeId().equals(sourceId) &&
            proposal.targetNodeId().equals(targetId) &&
            proposal.viewId().equals(currentViewId)
        ));
    }

    @Test
    void testProposalApprovalTriggersCallback() {
        // Given: Migration proposal that will be approved
        var entityId = UUID.randomUUID();
        var sourceId = DigestAlgorithm.DEFAULT.digest("source".getBytes());
        var targetId = DigestAlgorithm.DEFAULT.digest("target".getBytes());

        when(consensus.requestConsensus(any(MigrationProposal.class)))
            .thenReturn(CompletableFuture.completedFuture(true));

        var callbackInvoked = new AtomicBoolean(false);

        // When: Create proposal with approval callback
        integration.createMigrationProposal(entityId, sourceId, targetId, approved -> {
            if (approved) callbackInvoked.set(true);
        });

        // Then: Approval callback invoked
        assertTrue(callbackInvoked.get(), "Approval callback should be invoked");
    }

    @Test
    void testProposalRejectionTriggersCallback() {
        // Given: Migration proposal that will be rejected
        var entityId = UUID.randomUUID();
        var sourceId = DigestAlgorithm.DEFAULT.digest("source".getBytes());
        var targetId = DigestAlgorithm.DEFAULT.digest("target".getBytes());

        when(consensus.requestConsensus(any(MigrationProposal.class)))
            .thenReturn(CompletableFuture.completedFuture(false));

        var rejectionDetected = new AtomicBoolean(false);

        // When: Create proposal with rejection callback
        integration.createMigrationProposal(entityId, sourceId, targetId, approved -> {
            if (!approved) rejectionDetected.set(true);
        });

        // Then: Rejection callback invoked
        assertTrue(rejectionDetected.get(), "Rejection callback should be invoked");
    }

    @Test
    void testViewChangeRollsBackPendingProposals() {
        // Given: Pending migration proposals
        var entity1 = UUID.randomUUID();
        var entity2 = UUID.randomUUID();
        var sourceId = DigestAlgorithm.DEFAULT.digest("source".getBytes());
        var targetId = DigestAlgorithm.DEFAULT.digest("target".getBytes());

        var future1 = new CompletableFuture<Boolean>();
        var future2 = new CompletableFuture<Boolean>();

        when(consensus.requestConsensus(any(MigrationProposal.class)))
            .thenReturn(future1, future2);

        var rollbackCount = new AtomicInteger(0);

        // Create proposals (not yet completed)
        integration.createMigrationProposal(entity1, sourceId, targetId, approved -> {
            if (!approved) rollbackCount.incrementAndGet();
        });
        integration.createMigrationProposal(entity2, sourceId, targetId, approved -> {
            if (!approved) rollbackCount.incrementAndGet();
        });

        // When: View change occurs
        var newViewId = DigestAlgorithm.DEFAULT.digest("view-2".getBytes());
        integration.onViewChange(newViewId);

        // Complete futures after view change (should be ignored or rolled back)
        future1.complete(true);
        future2.complete(true);

        // Then: Pending proposals rolled back
        assertEquals(2, rollbackCount.get(), "All pending proposals should be rolled back on view change");
    }

    @Test
    void testViewChangeUpdatesCurrentViewId() {
        // Given: Current view ID
        assertEquals(currentViewId, integration.getCurrentViewId(), "Initial view ID should match");

        // When: View change occurs
        var newViewId = DigestAlgorithm.DEFAULT.digest("view-2".getBytes());
        integration.onViewChange(newViewId);

        // Then: Current view ID updated
        assertEquals(newViewId, integration.getCurrentViewId(), "View ID should be updated after view change");
    }

    @Test
    void testProposalTimeoutHandled() {
        // Given: Migration proposal that will timeout
        var entityId = UUID.randomUUID();
        var sourceId = DigestAlgorithm.DEFAULT.digest("source".getBytes());
        var targetId = DigestAlgorithm.DEFAULT.digest("target".getBytes());

        var timeoutFuture = new CompletableFuture<Boolean>();
        timeoutFuture.completeExceptionally(new java.util.concurrent.TimeoutException("Consensus timeout"));

        when(consensus.requestConsensus(any(MigrationProposal.class)))
            .thenReturn(timeoutFuture);

        var timeoutDetected = new AtomicBoolean(false);

        // When: Create proposal with timeout
        integration.createMigrationProposal(entityId, sourceId, targetId, approved -> {
            if (!approved) timeoutDetected.set(true);
        });

        // Then: Timeout treated as rejection
        assertTrue(timeoutDetected.get(), "Timeout should trigger rejection callback");
    }

    @Test
    void testPendingProposalsTracked() {
        // Given: Migration proposal
        var entityId = UUID.randomUUID();
        var sourceId = DigestAlgorithm.DEFAULT.digest("source".getBytes());
        var targetId = DigestAlgorithm.DEFAULT.digest("target".getBytes());

        var future = new CompletableFuture<Boolean>();
        when(consensus.requestConsensus(any(MigrationProposal.class)))
            .thenReturn(future);

        // When: Create proposal (not yet completed)
        integration.createMigrationProposal(entityId, sourceId, targetId, approved -> {});

        // Then: Proposal tracked as pending
        assertTrue(integration.hasPendingProposals(), "Pending proposals should be tracked");

        // When: Complete proposal
        future.complete(true);

        // Give async callback time to complete
        try { Thread.sleep(100); } catch (InterruptedException e) { }

        // Then: No longer pending
        assertFalse(integration.hasPendingProposals(), "Completed proposals should be removed from pending");
    }

    @Test
    void testMultipleConcurrentProposals() {
        // Given: Multiple concurrent migration proposals
        var entity1 = UUID.randomUUID();
        var entity2 = UUID.randomUUID();
        var entity3 = UUID.randomUUID();
        var sourceId = DigestAlgorithm.DEFAULT.digest("source".getBytes());
        var targetId = DigestAlgorithm.DEFAULT.digest("target".getBytes());

        when(consensus.requestConsensus(any(MigrationProposal.class)))
            .thenReturn(CompletableFuture.completedFuture(true));

        var approvalCount = new AtomicInteger(0);

        // When: Create multiple proposals concurrently
        integration.createMigrationProposal(entity1, sourceId, targetId, approved -> {
            if (approved) approvalCount.incrementAndGet();
        });
        integration.createMigrationProposal(entity2, sourceId, targetId, approved -> {
            if (approved) approvalCount.incrementAndGet();
        });
        integration.createMigrationProposal(entity3, sourceId, targetId, approved -> {
            if (approved) approvalCount.incrementAndGet();
        });

        // Then: All proposals approved
        assertEquals(3, approvalCount.get(), "All concurrent proposals should be approved");
        verify(consensus, times(3)).requestConsensus(any(MigrationProposal.class));
    }

    @Test
    void testProposalIdUniqueness() {
        // Given: Two identical migration requests
        var entityId = UUID.randomUUID();
        var sourceId = DigestAlgorithm.DEFAULT.digest("source".getBytes());
        var targetId = DigestAlgorithm.DEFAULT.digest("target".getBytes());

        when(consensus.requestConsensus(any(MigrationProposal.class)))
            .thenReturn(CompletableFuture.completedFuture(true));

        var proposalId1 = new UUID[1];
        var proposalId2 = new UUID[1];

        // When: Create two proposals for same migration
        integration.createMigrationProposal(entityId, sourceId, targetId, approved -> {});
        integration.createMigrationProposal(entityId, sourceId, targetId, approved -> {});

        // Then: Each proposal has unique proposal ID
        verify(consensus, times(2)).requestConsensus(argThat(proposal -> {
            if (proposalId1[0] == null) {
                proposalId1[0] = proposal.proposalId();
                return true;
            } else {
                proposalId2[0] = proposal.proposalId();
                return true;
            }
        }));

        assertNotEquals(proposalId1[0], proposalId2[0], "Each proposal should have unique proposalId");
    }

    @Test
    void testNullConsensusRejected() {
        // When/Then: Creating integration with null consensus should throw
        assertThrows(NullPointerException.class, () -> {
            new ConsensusMigrationIntegration(null, currentViewId);
        }, "Null consensus should be rejected");
    }

    @Test
    void testNullViewIdRejected() {
        // When/Then: Creating integration with null view ID should throw
        assertThrows(NullPointerException.class, () -> {
            new ConsensusMigrationIntegration(consensus, null);
        }, "Null view ID should be rejected");
    }

    @Test
    void testNullEntityIdInProposalRejected() {
        // Given: Null entity ID
        var sourceId = DigestAlgorithm.DEFAULT.digest("source".getBytes());
        var targetId = DigestAlgorithm.DEFAULT.digest("target".getBytes());

        // When/Then: Creating proposal with null entity ID should throw
        assertThrows(NullPointerException.class, () -> {
            integration.createMigrationProposal(null, sourceId, targetId, approved -> {});
        }, "Null entity ID should be rejected");
    }

    @Test
    void testNullSourceIdInProposalRejected() {
        // Given: Null source ID
        var entityId = UUID.randomUUID();
        var targetId = DigestAlgorithm.DEFAULT.digest("target".getBytes());

        // When/Then: Creating proposal with null source ID should throw
        assertThrows(NullPointerException.class, () -> {
            integration.createMigrationProposal(entityId, null, targetId, approved -> {});
        }, "Null source ID should be rejected");
    }

    @Test
    void testNullTargetIdInProposalRejected() {
        // Given: Null target ID
        var entityId = UUID.randomUUID();
        var sourceId = DigestAlgorithm.DEFAULT.digest("source".getBytes());

        // When/Then: Creating proposal with null target ID should throw
        assertThrows(NullPointerException.class, () -> {
            integration.createMigrationProposal(entityId, sourceId, null, approved -> {});
        }, "Null target ID should be rejected");
    }
}
