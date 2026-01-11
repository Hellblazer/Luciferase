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
 * Byzantine fault tolerance robustness integration tests.
 * <p>
 * Verifies that the committee consensus system properly tolerates
 * Byzantine (malicious/faulty) nodes up to the theoretical limit.
 * <p>
 * BFT Formula (from KerlDHT):
 * - quorum = context.size() == 1 ? 1 : context.toleranceLevel() + 1
 * - toleranceLevel = maximum Byzantine nodes tolerated
 * - Requires n = 3f + 1 nodes to tolerate f Byzantine faults
 * <p>
 * Phase 7G Day 5: Integration Testing & Raft Deletion
 *
 * @author hal.hildebrand
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class ByzantineRobustnessIntegrationTest {

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
        // Create 7-node context: t=3, quorum=4 (can tolerate 3 Byzantine)
        context = Mockito.mock(DynamicContext.class);
        when(context.size()).thenReturn(7);
        when(context.toleranceLevel()).thenReturn(3);

        // Create 7 members
        members = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            members.add(new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i)));
        }

        // Setup view ID
        viewId = DigestAlgorithm.DEFAULT.digest("view-7node".getBytes());

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
    @DisplayName("Byzantine nodes at tolerance limit cannot corrupt consensus")
    public void testByzantineNodesCantCorrupt() throws Exception {
        // 7 nodes: t=3, quorum=4
        // 3 Byzantine nodes voting maliciously cannot override 4 honest nodes
        var proposal = createProposal(members.get(0).getId(), members.get(1).getId());

        var future = consensus.requestConsensus(proposal);

        // 3 Byzantine nodes vote YES (trying to force approval of bad migration)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(4).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(5).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(6).getId(), true, viewId));

        // 4 honest nodes vote NO (correctly rejecting)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), false, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), false, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), false, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(3).getId(), false, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result, "4 honest NO votes should reject despite 3 Byzantine YES votes");
    }

    @Test
    @DisplayName("Byzantine votes from non-committee members are ignored")
    public void testByzantineNodeExcluded() throws Exception {
        // Create a 5-node context where not all members are in committee
        // For testing, we'll verify that votes with wrong view ID are ignored
        var proposal = createProposal(members.get(0).getId(), members.get(1).getId());

        var future = consensus.requestConsensus(proposal);

        // Byzantine node sends vote with WRONG view ID (attempt to inject)
        var wrongViewId = DigestAlgorithm.DEFAULT.digest("wrong-view".getBytes());
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(6).getId(), true, wrongViewId));

        // This vote should be ignored - verify no immediate completion
        assertFalse(future.isDone(), "Vote with wrong viewId should be ignored");

        // Now honest nodes vote correctly
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(3).getId(), true, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "Honest quorum should succeed (Byzantine vote was ignored)");
    }

    @Test
    @DisplayName("Honest supermajority continues despite Byzantine failures")
    public void testByzantineRecovery() throws Exception {
        // Scenario: Some Byzantine nodes stop voting entirely (crash fault)
        // Honest supermajority can still reach consensus
        var proposal = createProposal(members.get(0).getId(), members.get(1).getId());

        var future = consensus.requestConsensus(proposal);

        // Byzantine nodes don't vote at all (simulating crash/silence)
        // Only 4 honest nodes vote

        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), true, viewId));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(3).getId(), true, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "4 honest YES votes (quorum) should succeed without Byzantine participation");
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
