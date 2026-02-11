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
 * Five-node cluster integration tests.
 * <p>
 * In a 5-node cluster with t=2 (toleranceLevel=2), quorum=3.
 * Can tolerate 2 Byzantine nodes while maintaining consensus.
 * <p>
 * This is the standard production BFT configuration:
 * - Byzantine fault tolerance for 2 nodes (t=2)
 * - Quorum = 3 (strict majority)
 * - 3 honest nodes can always reach consensus even with 2 adversaries
 * <p>
 * Phase 7G Day 5: Integration Testing & Raft Deletion
 *
 * @author hal.hildebrand
 */
public class FiveNodeIntegrationTest {

    private DynamicContext<Member> context;
    private ViewCommitteeSelector selector;
    private CommitteeVotingProtocol votingProtocol;
    private ViewCommitteeConsensus consensus;
    private MockViewMonitor viewMonitor;
    private ScheduledExecutorService scheduler;
    private Digest viewId;
    private Digest view2;
    private List<Member> members;

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

        // Setup view IDs
        viewId = DigestAlgorithm.DEFAULT.digest("view-5node".getBytes());
        view2 = DigestAlgorithm.DEFAULT.digest("view-5node-v2".getBytes());

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
    }

    @AfterEach
    public void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("Five-node cluster with quorum=3, tolerates 2 Byzantine")
    public void testFiveNodeQuorumWithTwoByzantine() throws Exception {
        // 5 nodes: t=2, quorum=3
        // 2 Byzantine NO votes + 3 honest YES votes = approved
        var proposal = createProposal(members.get(0).getId(), members.get(1).getId());

        var future = consensus.requestConsensus(proposal);

        // 2 Byzantine nodes vote NO
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(3).getId(), false, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(4).getId(), false, viewId));

        // 3 honest nodes vote YES (quorum)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), true, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "3 honest YES votes should overcome 2 Byzantine NO votes");
    }

    @Test
    @DisplayName("Two Byzantine nodes cannot corrupt 5-node cluster")
    public void testFiveNodeBftTolerance() throws Exception {
        // BFT guarantee: 2f+1 honest nodes (where f=2) = 3 honest nodes can reach consensus
        var proposal = createProposal(members.get(0).getId(), members.get(2).getId());

        var future = consensus.requestConsensus(proposal);

        // Byzantine nodes try to corrupt by voting YES to potentially invalid migration
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(3).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(4).getId(), true, viewId));

        // 3 honest nodes vote NO (override Byzantine attack)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), false, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), false, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), false, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result, "3 honest NO votes should reject despite 2 Byzantine YES votes");
    }

    @Test
    @DisplayName("Network partition recovery re-establishes consensus")
    public void testFiveNodePartitionRecovery() throws Exception {
        // Simulate: View1 → Partition → View2 (recovery)
        var proposal1 = createProposal(members.get(0).getId(), members.get(1).getId());

        var future1 = consensus.requestConsensus(proposal1);

        // Partial votes before partition
        votingProtocol.recordVote(new Vote(proposal1.proposalId(), members.get(0).getId(), true, viewId));

        // Network partition triggers view change
        viewMonitor.setCurrentViewId(view2);
        consensus.onViewChange(view2);

        // Old proposal should be aborted or return false
        try {
            var result = future1.get(1, TimeUnit.SECONDS);
            // If it completes, should be rejected (view changed during voting)
            assertFalse(result, "Proposal from old view should be rejected");
        } catch (ExecutionException e) {
            // Expected - proposal aborted with IllegalStateException
            assertTrue(e.getCause() instanceof IllegalStateException ||
                      e.getCause().getMessage().contains("aborted"),
                      "Should be view change abort: " + e.getCause());
        }

        // After recovery: new proposal in new view succeeds
        var proposal2 = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(1).getId(),
            view2,  // New view
            System.currentTimeMillis()
        );

        var future2 = consensus.requestConsensus(proposal2);

        // Quorum votes in new view
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(0).getId(), true, view2));
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(1).getId(), true, view2));
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(2).getId(), true, view2));

        var result2 = future2.get(1, TimeUnit.SECONDS);
        assertTrue(result2, "New proposal in recovered view should succeed");
    }

    @Test
    @DisplayName("Leaderless consensus - no single point of failure")
    public void testFiveNodeLeaderlessConsensus() throws Exception {
        // Verify consensus works without a designated leader
        // Any node can propose, committee votes
        for (int i = 0; i < 3; i++) {
            // Each iteration: different proposer
            var proposer = members.get(i);
            var target = members.get((i + 1) % 5);

            var proposal = new MigrationProposal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                proposer.getId(),
                target.getId(),
                viewId,
                System.currentTimeMillis()
            );

            var future = consensus.requestConsensus(proposal);

            // Any 3 committee members can approve
            votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, viewId));
            votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), true, viewId));
            votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(4).getId(), true, viewId));

            var result = future.get(1, TimeUnit.SECONDS);
            assertTrue(result, "Proposal " + i + " should succeed with quorum (no leader required)");
        }
    }

    @Test
    @DisplayName("Multiple concurrent proposals handled correctly")
    public void testFiveNodeConcurrentVoting() throws Exception {
        // Submit 3 concurrent proposals
        var proposals = new ArrayList<MigrationProposal>();
        var futures = new ArrayList<CompletableFuture<Boolean>>();

        for (int i = 0; i < 3; i++) {
            var proposal = new MigrationProposal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                members.get(i).getId(),
                members.get((i + 1) % 5).getId(),
                viewId,
                System.currentTimeMillis()
            );
            proposals.add(proposal);
            futures.add(consensus.requestConsensus(proposal));
        }

        // Vote on all proposals
        for (int i = 0; i < 3; i++) {
            var proposal = proposals.get(i);
            // Different voting patterns: approve odds, reject evens
            boolean approve = (i % 2 == 1);

            for (int j = 0; j < 3; j++) {
                votingProtocol.recordVote(new Vote(
                    proposal.proposalId(),
                    members.get(j).getId(),
                    approve,
                    viewId
                ));
            }
        }

        // Verify results
        assertFalse(futures.get(0).get(1, TimeUnit.SECONDS), "Proposal 0 should be rejected");
        assertTrue(futures.get(1).get(1, TimeUnit.SECONDS), "Proposal 1 should be approved");
        assertFalse(futures.get(2).get(1, TimeUnit.SECONDS), "Proposal 2 should be rejected");
    }

    // Helper to create migration proposal
    private MigrationProposal createProposal(Digest source, Digest target) {
        return new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            source,
            target,
            viewId,
            System.currentTimeMillis()
        );
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
