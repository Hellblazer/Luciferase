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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ViewCommitteeConsensus orchestrator.
 * <p>
 * Phase 7G Day 3: ViewCommitteeConsensus & OptimisticMigrator Integration
 * <p>
 * Tests:
 * 1. Request consensus returns non-blocking CompletableFuture
 * 2. Consensus approval with unanimous YES votes
 * 3. Consensus rejection with majority NO votes
 * 4. View change aborts pending consensus (returns false)
 * 5. View ID verification prevents stale execution
 *
 * @author hal.hildebrand
 */
public class ViewCommitteeConsensusTest {

    private DynamicContext<Member> context;
    private ViewCommitteeSelector selector;
    private CommitteeVotingProtocol votingProtocol;
    private ViewCommitteeConsensus consensus;
    private MockViewMonitor mockMonitor;
    private ScheduledExecutorService scheduler;
    private Digest view1;
    private Digest view2;
    private List<Member> members;

    @BeforeEach
    public void setUp() {
        // Create mock context with 5 members (t=1, quorum=2)
        context = Mockito.mock(DynamicContext.class);
        when(context.size()).thenReturn(5);
        when(context.toleranceLevel()).thenReturn(1);

        // Create view IDs
        view1 = DigestAlgorithm.DEFAULT.digest("view1".getBytes());
        view2 = DigestAlgorithm.DEFAULT.digest("view2".getBytes());

        // Mock bftSubset to return a 3-member committee (quorum = t+1 = 2)
        members = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            members.add(new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i)));
        }
        var committee = new java.util.LinkedHashSet<>(members);
        when(context.bftSubset(Mockito.any(Digest.class))).thenReturn((java.util.SequencedSet) committee);

        // Mock allMembers() to return a new stream each time (Byzantine validation)
        when(context.allMembers()).thenAnswer(invocation -> members.stream());

        // Create mock view monitor
        mockMonitor = new MockViewMonitor(view1);

        // Create committee selector
        selector = new ViewCommitteeSelector(context);

        // Create voting protocol
        var config = CommitteeConfig.defaultConfig();
        scheduler = Executors.newScheduledThreadPool(1);
        votingProtocol = new CommitteeVotingProtocol(context, config, scheduler);

        // Create consensus orchestrator
        consensus = new ViewCommitteeConsensus();
        consensus.setViewMonitor(mockMonitor);
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
    public void testRequestConsensusReturnsFuture() {
        // Test non-blocking CompletableFuture return
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),  // Valid source from members
            members.get(1).getId(),  // Valid target from members
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);

        assertNotNull(future);
        assertFalse(future.isDone(), "Future should not be completed immediately");
    }

    @Test
    public void testConsensusApprovalWithUnanimousVotes() throws Exception {
        // All committee members vote YES
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),  // Valid source from members
            members.get(1).getId(),  // Valid target from members
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);

        // Simulate quorum YES votes (quorum=2 for t=1)
        // CRITICAL: Use actual committee member IDs, not arbitrary hashes
        for (int i = 0; i < 2; i++) {
            votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(i).getId(), true, view1));
        }

        // Wait for consensus (should complete with true)
        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "Consensus should be approved with quorum YES votes");
    }

    @Test
    public void testConsensusRejectionWithMajorityNo() throws Exception {
        // Majority of committee votes NO
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(1).getId(),
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);

        // Simulate quorum NO votes (quorum=2 for t=1)
        // CRITICAL: Use actual committee member IDs, not arbitrary hashes
        for (int i = 0; i < 2; i++) {
            votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(i).getId(), false, view1));
        }

        // Wait for consensus (should complete with false)
        var result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result, "Consensus should be rejected with quorum NO votes");
    }

    @Test
    public void testViewChangeAbortsPendingConsensus() throws Exception {
        // Create proposal with view1
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(1).getId(),
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);

        // Simulate view change to view2
        mockMonitor.setCurrentViewId(view2);
        consensus.onViewChange(view2);

        // Per design: View change returns false (not exception) to enable retry in new view
        // ViewCommitteeConsensus.exceptionally() catches IllegalStateException and returns false
        var result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result, "View change should abort proposal and return false for retry");
    }

    @Test
    public void testViewIdVerificationPreventsStaleExecution() throws Exception {
        // CRITICAL TEST: Verify that proposals with old viewId abort
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(1).getId(),
            view1,  // Proposal tagged with view1
            System.currentTimeMillis()
        );

        // Change view BEFORE submitting proposal
        mockMonitor.setCurrentViewId(view2);

        // Submit proposal with old view1 tag
        var future = consensus.requestConsensus(proposal);

        // Should immediately return false (view mismatch)
        var result = future.get(100, TimeUnit.MILLISECONDS);
        assertFalse(result, "Proposal with old viewId should be rejected immediately");
    }

    // ===== Byzantine Input Validation Tests (Luciferase-brtp) =====

    @Test
    public void testMaliciousEntityIdInjection() throws Exception {
        // Attack: null entityId
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            null,  // ATTACK: null entityId
            members.get(0).getId(),
            members.get(1).getId(),
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);
        var result = future.get(100, TimeUnit.MILLISECONDS);

        assertFalse(result, "Proposal with null entityId should be rejected");
    }

    @Test
    public void testDestinationNotInView() throws Exception {
        // Attack: targetNodeId not in current Fireflies view
        var nonExistentNode = DigestAlgorithm.DEFAULT.digest("non-existent-node");

        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            nonExistentNode,  // ATTACK: target not in view
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);
        var result = future.get(100, TimeUnit.MILLISECONDS);

        assertFalse(result, "Proposal with target node not in view should be rejected");
    }

    @Test
    public void testSelfMigrationAttack() throws Exception {
        // Attack: source == target (self-migration)
        var sourceAndTarget = members.get(0).getId();

        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            sourceAndTarget,  // ATTACK: source == target
            sourceAndTarget,
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);
        var result = future.get(100, TimeUnit.MILLISECONDS);

        assertFalse(result, "Proposal with source == target should be rejected");
    }

    @Test
    public void testNullSourceNodeAttack() throws Exception {
        // Attack: null sourceNodeId
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,  // ATTACK: null sourceNodeId
            members.get(1).getId(),
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);
        var result = future.get(100, TimeUnit.MILLISECONDS);

        assertFalse(result, "Proposal with null sourceNodeId should be rejected");
    }

    @Test
    public void testNullTargetNodeAttack() throws Exception {
        // Attack: null targetNodeId
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            null,  // ATTACK: null targetNodeId
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);
        var result = future.get(100, TimeUnit.MILLISECONDS);

        assertFalse(result, "Proposal with null targetNodeId should be rejected");
    }

    @Test
    public void testSourceNodeNotInView() throws Exception {
        // Attack: sourceNodeId not in current Fireflies view
        var nonExistentNode = DigestAlgorithm.DEFAULT.digest("non-existent-source");

        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            nonExistentNode,  // ATTACK: source not in view
            members.get(1).getId(),
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);
        var result = future.get(100, TimeUnit.MILLISECONDS);

        assertFalse(result, "Proposal with source node not in view should be rejected");
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
        public boolean verify(com.hellblazer.delos.cryptography.SigningThreshold threshold, com.hellblazer.delos.cryptography.JohnHancock signature, java.io.InputStream is) {
            // Mock implementation - always valid for testing
            return true;
        }

        @Override
        public boolean verify(com.hellblazer.delos.cryptography.JohnHancock signature, java.io.InputStream is) {
            // Mock implementation - always valid for testing
            return true;
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
        public Stream<Member> getMembers() {
            return Stream.empty();
        }
    }
}
