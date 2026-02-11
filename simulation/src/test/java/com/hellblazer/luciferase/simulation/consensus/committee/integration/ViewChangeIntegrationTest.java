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
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
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
 * View change integration tests.
 * <p>
 * View changes occur when membership changes (nodes join/leave/fail).
 * Fireflies Virtual Synchrony guarantees:
 * 1. All nodes see view changes in the same order
 * 2. Pending proposals are atomically aborted on view change
 * 3. No split-brain scenarios
 * <p>
 * Phase 7G Day 5: Integration Testing & Raft Deletion
 *
 * @author hal.hildebrand
 */
public class ViewChangeIntegrationTest {

    private DynamicContext<Member> context;
    private ViewCommitteeSelector selector;
    private CommitteeVotingProtocol votingProtocol;
    private ViewCommitteeConsensus consensus;
    private MockViewMonitor viewMonitor;
    private ScheduledExecutorService scheduler;
    private Digest view1;
    private Digest view2;
    private Digest view3;
    private List<Member> members;

    @BeforeEach
    public void setUp() {
        // Create 5-node context
        context = Mockito.mock(DynamicContext.class);
        when(context.size()).thenReturn(5);
        when(context.toleranceLevel()).thenReturn(2);

        // Create 5 members
        members = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            members.add(new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i)));
        }

        // Setup view IDs
        view1 = DigestAlgorithm.DEFAULT.digest("view1".getBytes());
        view2 = DigestAlgorithm.DEFAULT.digest("view2".getBytes());
        view3 = DigestAlgorithm.DEFAULT.digest("view3".getBytes());

        // Mock bftSubset to return committee as LinkedHashSet (SequencedSet)
        var committee = new LinkedHashSet<>(members);
        when(context.bftSubset(any(Digest.class))).thenReturn(committee);

        // Mock allMembers for Byzantine validation (ViewCommitteeSelector.isNodeInView)
        // Use thenAnswer to create fresh stream for each call (streams can only be consumed once)
        when(context.allMembers()).thenAnswer(invocation -> members.stream());

        // Create view monitor
        viewMonitor = new MockViewMonitor(view1);

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
    }

    @AfterEach
    public void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("View change during voting atomically aborts all proposals")
    public void testViewChangeDuringVoting() throws Exception {
        // Submit proposal in view1
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(1).getId(),
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);

        // Partial votes (not yet quorum)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, view1));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), true, view1));

        // Verify proposal is pending
        assertTrue(consensus.hasPendingProposals(), "Should have pending proposal before view change");

        // View change to view2
        viewMonitor.setCurrentViewId(view2);
        consensus.onViewChange(view2);

        // After view change, proposal should either:
        // 1. Fail with exception, OR
        // 2. Return false (rejected due to view change)
        try {
            var result = future.get(1, TimeUnit.SECONDS);
            // If we get here, it returned false (view changed during voting)
            assertFalse(result, "Proposal should be rejected after view change");
        } catch (ExecutionException e) {
            // Expected - proposal aborted with IllegalStateException
            assertTrue(e.getCause() instanceof IllegalStateException ||
                      e.getCause().getMessage().contains("aborted"),
                      "Should be view change abort: " + e.getCause());
        }

        // No pending proposals after rollback
        assertFalse(consensus.hasPendingProposals(), "No pending proposals after view change");
    }

    @Test
    @DisplayName("View change updates committee membership deterministically")
    public void testViewChangeUpdatesMembership() {
        // Committee for view1
        var committee1 = selector.selectCommittee(view1);

        // Committee for view2 (same members, different selection point)
        var committee2 = selector.selectCommittee(view2);

        // Both should be valid committees
        assertNotNull(committee1);
        assertNotNull(committee2);

        // Same selector + same view = same committee (deterministic)
        var committee1Again = selector.selectCommittee(view1);
        assertEquals(committee1, committee1Again, "Same view ID should produce same committee");

        // Different view IDs may produce different committee ordering
        // (for larger clusters, different members could be selected)
        // In 5-node cluster, all members are in committee, but order may differ
        assertEquals(committee1.size(), committee2.size(), "Committee size should be consistent");
    }

    @Test
    @DisplayName("Entities are not lost during view change")
    public void testViewChangePreservesEntityState() throws Exception {
        // Entity migration approved in view1
        var entityId = UUID.randomUUID();
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            members.get(0).getId(),
            members.get(1).getId(),
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);

        // Quorum votes - approved before view change
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, view1));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), true, view1));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), true, view1));

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "Migration should be approved before view change");

        // Verify can execute migration (view still matches)
        assertTrue(consensus.canExecuteMigration(proposal, entityId));

        // View changes AFTER approval
        viewMonitor.setCurrentViewId(view2);
        consensus.onViewChange(view2);

        // Cannot execute migration with old view ID
        assertFalse(consensus.canExecuteMigration(proposal, entityId),
            "Cannot execute migration after view changed");

        // Must re-propose in new view if execution was missed
        var reproposal = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            members.get(0).getId(),
            members.get(1).getId(),
            view2,  // New view
            System.currentTimeMillis()
        );

        var refuture = consensus.requestConsensus(reproposal);
        votingProtocol.recordVote(new Vote(reproposal.proposalId(), members.get(0).getId(), true, view2));
        votingProtocol.recordVote(new Vote(reproposal.proposalId(), members.get(1).getId(), true, view2));
        votingProtocol.recordVote(new Vote(reproposal.proposalId(), members.get(2).getId(), true, view2));

        assertTrue(refuture.get(1, TimeUnit.SECONDS), "Re-proposal in new view should succeed");
        assertTrue(consensus.canExecuteMigration(reproposal, entityId), "Can execute in current view");
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
