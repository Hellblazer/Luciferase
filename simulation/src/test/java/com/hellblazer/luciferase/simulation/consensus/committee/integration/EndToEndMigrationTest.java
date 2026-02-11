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

package com.hellblazer.luciferase.simulation.consensus.committee.integration;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.consensus.committee.*;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end migration integration tests.
 * <p>
 * Tests the full migration workflow from proposal to execution:
 * 1. Entity owner proposes migration to target bubble
 * 2. Committee votes on proposal
 * 3. If approved, migration executes
 * 4. If rejected, entity remains at source
 * <p>
 * Phase 7G Day 5: Integration Testing & Raft Deletion
 *
 * @author hal.hildebrand
 */
public class EndToEndMigrationTest {

    private DynamicContext<Member> context;
    private ViewCommitteeSelector selector;
    private CommitteeVotingProtocol votingProtocol;
    private ViewCommitteeConsensus consensus;
    private MockViewMonitor viewMonitor;
    private ScheduledExecutorService scheduler;
    private Digest viewId;
    private List<Member> members;

    // Simulated entity store for testing
    private Map<UUID, Digest> entityOwnership;

    @BeforeEach
    public void setUp() {
        // Create 5-node context: t=2, quorum=3
        context = Mockito.mock(DynamicContext.class);
        when(context.size()).thenReturn(5);
        when(context.toleranceLevel()).thenReturn(2);

        // Create 5 members
        members = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            members.add(new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i)));
        }

        // Setup view ID
        viewId = DigestAlgorithm.DEFAULT.digest("view-e2e".getBytes());

        // Mock bftSubset to return committee as LinkedHashSet (SequencedSet)
        var committee = new LinkedHashSet<>(members);
        when(context.bftSubset(any(Digest.class))).thenReturn(committee);

        // Mock allMembers for Byzantine validation (ViewCommitteeSelector.isNodeInView)
        // Use thenAnswer to create fresh stream for each call (streams can only be consumed once)
        when(context.allMembers()).thenAnswer(invocation -> members.stream());

        // Create view monitor
        viewMonitor = new MockViewMonitor(viewId);

        // Create committee selector
        selector = new ViewCommitteeSelector(context);

        // Create voting protocol with scheduler
        var config = CommitteeConfig.defaultConfig();
        scheduler = Executors.newScheduledThreadPool(1);
        votingProtocol = new CommitteeVotingProtocol(context, config, scheduler);

        // Create consensus orchestrator
        consensus = new ViewCommitteeConsensus();
        consensus.setViewMonitor(viewMonitor);
        consensus.setCommitteeSelector(selector);
        consensus.setVotingProtocol(votingProtocol);

        // Initialize entity ownership tracker
        entityOwnership = new ConcurrentHashMap<>();
    }

    @AfterEach
    public void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("Full migration workflow: entity crosses bubble boundary")
    public void testFullMigrationWorkflow() throws Exception {
        // Entity starts at member[0]
        var entityId = UUID.randomUUID();
        var sourceBubble = members.get(0).getId();
        var targetBubble = members.get(1).getId();
        entityOwnership.put(entityId, sourceBubble);

        // Create migration proposal
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            sourceBubble,
            targetBubble,
            viewId,
            System.currentTimeMillis()
        );

        // Submit for consensus
        var future = consensus.requestConsensus(proposal);

        // Committee votes YES
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), true, viewId));

        // Wait for consensus
        var approved = future.get(1, TimeUnit.SECONDS);
        assertTrue(approved, "Migration should be approved by committee");

        // Verify can execute
        assertTrue(consensus.canExecuteMigration(proposal, entityId));

        // Execute migration
        if (approved && consensus.canExecuteMigration(proposal, entityId)) {
            entityOwnership.put(entityId, targetBubble);
        }

        // Verify entity is now at target
        assertEquals(targetBubble, entityOwnership.get(entityId),
            "Entity should now be owned by target bubble");
    }

    @Test
    @DisplayName("Migration with consensus approval follows correct protocol")
    public void testMigrationWithConsensusApproval() throws Exception {
        var entityId = UUID.randomUUID();
        var sourceBubble = members.get(0).getId();
        var targetBubble = members.get(2).getId();
        entityOwnership.put(entityId, sourceBubble);

        // Proposal
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            sourceBubble,
            targetBubble,
            viewId,
            System.currentTimeMillis()
        );

        // Pre-conditions
        assertEquals(sourceBubble, entityOwnership.get(entityId));

        // Request consensus
        var future = consensus.requestConsensus(proposal);

        // Simulate asynchronous voting
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(50); // Simulate network delay
                votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), true, viewId));
                Thread.sleep(30);
                votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), true, viewId));
                Thread.sleep(20);
                votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(3).getId(), true, viewId));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Wait for consensus
        var approved = future.get(2, TimeUnit.SECONDS);
        assertTrue(approved);

        // Execute migration atomically
        synchronized (entityOwnership) {
            if (approved && consensus.canExecuteMigration(proposal, entityId)) {
                var current = entityOwnership.get(entityId);
                if (current.equals(sourceBubble)) {
                    entityOwnership.put(entityId, targetBubble);
                }
            }
        }

        // Post-conditions
        assertEquals(targetBubble, entityOwnership.get(entityId));
    }

    @Test
    @DisplayName("Migration rollback on rejection preserves entity location")
    public void testMigrationRollbackOnRejection() throws Exception {
        var entityId = UUID.randomUUID();
        var sourceBubble = members.get(0).getId();
        var targetBubble = members.get(3).getId();
        entityOwnership.put(entityId, sourceBubble);

        // Proposal
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            sourceBubble,
            targetBubble,
            viewId,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);

        // Committee votes NO (rejects migration)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), false, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), false, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(3).getId(), false, viewId));

        var approved = future.get(1, TimeUnit.SECONDS);
        assertFalse(approved, "Migration should be rejected");

        // Entity should remain at source (no migration executed)
        assertEquals(sourceBubble, entityOwnership.get(entityId),
            "Entity should remain at source after rejection");
    }

    @Test
    @DisplayName("Duplicate migration requests are handled idempotently")
    public void testMigrationIdempotency() throws Exception {
        var entityId = UUID.randomUUID();
        var sourceBubble = members.get(0).getId();
        var targetBubble = members.get(1).getId();
        entityOwnership.put(entityId, sourceBubble);

        // First migration request
        var proposal1 = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            sourceBubble,
            targetBubble,
            viewId,
            System.currentTimeMillis()
        );

        var future1 = consensus.requestConsensus(proposal1);
        votingProtocol.recordVote(new Vote(proposal1.proposalId(), members.get(0).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal1.proposalId(), members.get(1).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal1.proposalId(), members.get(2).getId(), true, viewId));

        assertTrue(future1.get(1, TimeUnit.SECONDS));

        // Execute first migration
        if (consensus.canExecuteMigration(proposal1, entityId)) {
            entityOwnership.put(entityId, targetBubble);
        }

        assertEquals(targetBubble, entityOwnership.get(entityId));

        // Duplicate request (different proposal ID, same entity)
        var proposal2 = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            sourceBubble,  // Wrong source - entity already moved
            targetBubble,
            viewId,
            System.currentTimeMillis()
        );

        var future2 = consensus.requestConsensus(proposal2);
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(0).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(1).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(2).getId(), true, viewId));

        // Even if approved, execution should check actual ownership
        var approved2 = future2.get(1, TimeUnit.SECONDS);

        // Idempotent check: only migrate if entity is actually at source
        if (consensus.canExecuteMigration(proposal2, entityId)) {
            var current = entityOwnership.get(entityId);
            if (current.equals(proposal2.sourceNodeId())) {
                entityOwnership.put(entityId, proposal2.targetNodeId());
            }
            // else: no-op, entity already migrated
        }

        // Entity should still be at target (idempotent - no double migration)
        assertEquals(targetBubble, entityOwnership.get(entityId),
            "Entity should remain at target (idempotent handling)");
    }

    // Mock Member implementation
    private static class MockMember implements Member {
        private final Digest id;

        MockMember(Digest id) {
            this.id = id;
        }

        @Override
        public Digest getId() {
            return id;
        }

        @Override
        public int compareTo(Member o) {
            return id.compareTo(o.getId());
        }

        @Override
        public boolean verify(SigningThreshold threshold, JohnHancock signature, InputStream is) {
            return true;
        }

        @Override
        public boolean verify(JohnHancock signature, InputStream is) {
            return true;
        }
    }

    // Mock ViewMonitor
    private static class MockViewMonitor extends FirefliesViewMonitor {
        private Digest currentViewId;

        MockViewMonitor(Digest initialViewId) {
            super(new MockMembershipView());
            this.currentViewId = initialViewId;
        }

        @Override
        public Digest getCurrentViewId() {
            return currentViewId;
        }

        void setCurrentViewId(Digest viewId) {
            this.currentViewId = viewId;
        }
    }

    // Mock MembershipView
    private static class MockMembershipView implements MembershipView<Member> {
        @Override
        public void addListener(Consumer<ViewChange<Member>> listener) {}

        @Override
        public Stream<Member> getMembers() {
            return Stream.empty();
        }
    }
}
