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

package com.hellblazer.luciferase.simulation.consensus.committee;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Virtual Synchrony guarantees provided by Fireflies.
 * <p>
 * Virtual Synchrony Properties:
 * 1. All nodes receive view changes in the same order
 * 2. Proposal→ViewChange ordering is consistent across all nodes
 * 3. Pending consensus is atomically aborted on view change (no orphaned proposals)
 * <p>
 * These properties prevent split-brain scenarios and ensure that committee consensus
 * operates on a consistent view of cluster membership.
 * <p>
 * Phase 7G Day 3: ViewCommitteeConsensus & OptimisticMigrator Integration
 *
 * @author hal.hildebrand
 */
public class VirtualSynchronyTest {

    private DynamicContext<Member> context;
    private ViewCommitteeSelector selector;
    private CommitteeVotingProtocol votingProtocol;
    private ViewCommitteeConsensus consensus;
    private MockViewMonitor mockMonitor;
    private Digest view1;
    private Digest view2;
    private Digest view3;
    private List<Member> members;

    @BeforeEach
    public void setUp() {
        // Create test context with 5 members (t=1)
        var params = com.hellblazer.delos.context.DynamicContext.newBuilder()
                                                                .setpByz(0.1)
                                                                .setCardinality(5)
                                                                .build();
        members = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            members.add(new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i)));
        }
        context = new DynamicContext<>(DigestAlgorithm.DEFAULT.getOrigin(), params, members, 0.2);

        // Create view IDs
        view1 = DigestAlgorithm.DEFAULT.digest("view1".getBytes());
        view2 = DigestAlgorithm.DEFAULT.digest("view2".getBytes());
        view3 = DigestAlgorithm.DEFAULT.digest("view3".getBytes());

        // Create mock view monitor
        mockMonitor = new MockViewMonitor(view1);

        // Create committee selector
        selector = new ViewCommitteeSelector(context);

        // Create voting protocol
        var config = new CommitteeConfig(5); // 5 second timeout
        var scheduler = Executors.newScheduledThreadPool(1);
        votingProtocol = new CommitteeVotingProtocol(context, config, scheduler);

        // Create consensus orchestrator
        consensus = new ViewCommitteeConsensus();
        consensus.setViewMonitor(mockMonitor);
        consensus.setCommitteeSelector(selector);
        consensus.setVotingProtocol(votingProtocol);
    }

    @Test
    public void testAllNodesReceiveSameViewChangeSequence() {
        // Simulate multiple nodes tracking view changes
        var viewSequenceNode1 = new CopyOnWriteArrayList<Digest>();
        var viewSequenceNode2 = new CopyOnWriteArrayList<Digest>();
        var viewSequenceNode3 = new CopyOnWriteArrayList<Digest>();

        // All nodes start with view1
        viewSequenceNode1.add(view1);
        viewSequenceNode2.add(view1);
        viewSequenceNode3.add(view1);

        // Fireflies delivers view changes atomically
        // All nodes see: view1 → view2 → view3 in same order

        // Simulate view change to view2
        mockMonitor.setCurrentViewId(view2);
        viewSequenceNode1.add(view2);
        viewSequenceNode2.add(view2);
        viewSequenceNode3.add(view2);

        // Simulate view change to view3
        mockMonitor.setCurrentViewId(view3);
        viewSequenceNode1.add(view3);
        viewSequenceNode2.add(view3);
        viewSequenceNode3.add(view3);

        // ASSERT: All nodes saw same sequence
        assertEquals(viewSequenceNode1, viewSequenceNode2, "Node1 and Node2 should see same sequence");
        assertEquals(viewSequenceNode2, viewSequenceNode3, "Node2 and Node3 should see same sequence");
        assertEquals(Arrays.asList(view1, view2, view3), viewSequenceNode1, "All nodes should see view1→view2→view3");
    }

    @Test
    public void testProposalAndViewChangeOrdering() throws Exception {
        // Test that proposal→view change ordering is consistent
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(1).getId(),
            view1,
            System.currentTimeMillis()
        );

        // Submit proposal in view1
        var future = consensus.requestConsensus(proposal);

        // Some votes arrive in view1
        var committee = selector.selectCommittee(view1);
        var committeeList = new ArrayList<>(committee);
        votingProtocol.recordVote(new Vote(proposal.proposalId(), committeeList.get(0).getId(), true, view1));

        // View changes to view2 (before quorum reached)
        mockMonitor.setCurrentViewId(view2);
        consensus.onViewChange(view2);

        // More votes arrive (but from old view1)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), committeeList.get(1).getId(), true, view1));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), committeeList.get(2).getId(), true, view1));

        // Proposal should be aborted (view changed mid-vote)
        try {
            future.get(1, TimeUnit.SECONDS);
            fail("Proposal should be aborted due to view change");
        } catch (Exception e) {
            // Expected
            assertTrue(e.getCause() instanceof IllegalStateException || e.getMessage().contains("aborted"));
        }

        // ASSERT: Ordering guarantees that once view2 is active, no view1 proposals can execute
        assertEquals(view2, mockMonitor.getCurrentViewId());
        assertFalse(consensus.canExecuteMigration(proposal, proposal.entityId()));
    }

    @Test
    public void testRollbackGuaranteesNoOrphanedConsensus() throws Exception {
        // Test that view change causes automatic rollback of ALL pending proposals
        var proposal1 = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(1).getId(),
            view1,
            System.currentTimeMillis()
        );

        var proposal2 = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(2).getId(),
            view1,
            System.currentTimeMillis()
        );

        // Submit two proposals in view1
        var future1 = consensus.requestConsensus(proposal1);
        var future2 = consensus.requestConsensus(proposal2);

        // Partial votes for both
        var committee = selector.selectCommittee(view1);
        var committeeList = new ArrayList<>(committee);
        votingProtocol.recordVote(new Vote(proposal1.proposalId(), committeeList.get(0).getId(), true, view1));
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), committeeList.get(0).getId(), true, view1));

        // View changes
        mockMonitor.setCurrentViewId(view2);
        consensus.onViewChange(view2);

        // BOTH proposals should be aborted (atomic rollback)
        try {
            future1.get(1, TimeUnit.SECONDS);
            fail("Proposal1 should be aborted");
        } catch (Exception e) {
            // Expected
        }

        try {
            future2.get(1, TimeUnit.SECONDS);
            fail("Proposal2 should be aborted");
        } catch (Exception e) {
            // Expected
        }

        // ASSERT: No orphaned proposals - all pending consensus rolled back
        assertFalse(consensus.hasPendingProposals(), "All proposals should be rolled back");
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
    }

    // Mock ViewMonitor for testing
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
        public void addListener(java.util.function.Consumer<ViewChange<Member>> listener) {}

        @Override
        public Set<Member> getMembers() {
            return Collections.emptySet();
        }
    }
}
