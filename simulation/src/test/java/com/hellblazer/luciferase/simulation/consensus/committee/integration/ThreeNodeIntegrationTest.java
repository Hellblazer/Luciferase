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
 * Three-node cluster integration tests.
 * <p>
 * In a 3-node cluster with t=1 (toleranceLevel=1), quorum=2.
 * Can tolerate 1 Byzantine node while maintaining consensus.
 * <p>
 * This is the minimal BFT configuration:
 * - Byzantine fault tolerance for 1 node (t=1)
 * - Quorum = 2 (majority)
 * - 2 honest nodes can always reach consensus
 * <p>
 * Phase 7G Day 5: Integration Testing & Raft Deletion
 *
 * @author hal.hildebrand
 */
public class ThreeNodeIntegrationTest {

    private DynamicContext<Member> context;
    private ViewCommitteeSelector selector;
    private CommitteeVotingProtocol votingProtocol;
    private ViewCommitteeConsensus consensus;
    private MockViewMonitor viewMonitor;
    private ScheduledExecutorService scheduler;
    private Digest viewId;
    private List<Member> members;

    @BeforeEach
    public void setUp() {
        // Create 3-node context: t=1, quorum=2
        context = Mockito.mock(DynamicContext.class);
        when(context.size()).thenReturn(3);
        when(context.toleranceLevel()).thenReturn(1);

        // Create 3 members
        members = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            members.add(new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i)));
        }

        // Setup view ID
        viewId = DigestAlgorithm.DEFAULT.digest("view-3node".getBytes());

        // Mock bftSubset to return committee as LinkedHashSet (SequencedSet)
        var committee = new LinkedHashSet<>(members);
        when(context.bftSubset(any(Digest.class))).thenReturn(committee);

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
    @DisplayName("Three-node cluster tolerates 1 Byzantine node (t=1)")
    public void testThreeNodeQuorumWithOneByzantine() throws Exception {
        // 3 nodes: t=1, quorum=2
        // 1 Byzantine NO vote + 2 honest YES votes = approved
        var proposal = createProposal(members.get(0).getId(), members.get(1).getId());

        var future = consensus.requestConsensus(proposal);

        // Byzantine node votes NO
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), false, viewId));

        // 2 honest nodes vote YES (quorum)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), true, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "2 honest YES votes should overcome 1 Byzantine NO vote");
    }

    @Test
    @DisplayName("Majority (2 of 3) approves migration")
    public void testThreeNodeMajorityApproves() throws Exception {
        var proposal = createProposal(members.get(0).getId(), members.get(1).getId());

        var future = consensus.requestConsensus(proposal);

        // 2 of 3 vote YES
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), true, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "2 of 3 votes should approve (quorum=2)");
    }

    @Test
    @DisplayName("Single Byzantine node cannot block consensus")
    public void testThreeNodeByzantineRejection() throws Exception {
        // Byzantine node tries to block by voting NO
        var proposal = createProposal(members.get(0).getId(), members.get(1).getId());

        var future = consensus.requestConsensus(proposal);

        // Byzantine votes NO first
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), false, viewId));

        // But cannot block - honest majority still wins
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), true, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "Byzantine node cannot block when honest majority votes YES");
    }

    @Test
    @DisplayName("Concurrent migrations resolve consistently")
    public void testThreeNodeConcurrentMigrations() throws Exception {
        // Two concurrent migrations: A→B and A→C
        var proposal1 = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(1).getId(),
            viewId,
            System.currentTimeMillis()
        );

        var proposal2 = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(2).getId(),
            viewId,
            System.currentTimeMillis()
        );

        var future1 = consensus.requestConsensus(proposal1);
        var future2 = consensus.requestConsensus(proposal2);

        // First migration approved
        votingProtocol.recordVote(new Vote(proposal1.proposalId(), members.get(0).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal1.proposalId(), members.get(1).getId(), true, viewId));

        // Second migration rejected
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(0).getId(), false, viewId));
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(2).getId(), false, viewId));

        var result1 = future1.get(1, TimeUnit.SECONDS);
        var result2 = future2.get(1, TimeUnit.SECONDS);

        assertTrue(result1, "First migration should be approved");
        assertFalse(result2, "Second migration should be rejected");
    }

    @Test
    @DisplayName("All nodes see same committee membership")
    public void testThreeNodeViewConsistency() {
        // Each node should compute the same committee for the same viewId
        var committee1 = selector.selectCommittee(viewId);
        var committee2 = selector.selectCommittee(viewId);
        var committee3 = selector.selectCommittee(viewId);

        // Same viewId → same committee (deterministic)
        assertEquals(committee1, committee2, "Same viewId should produce same committee");
        assertEquals(committee2, committee3, "Same viewId should produce same committee");

        // Committee includes all 3 nodes (small cluster = full membership)
        assertEquals(3, committee1.size(), "3-node committee should include all members");
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
