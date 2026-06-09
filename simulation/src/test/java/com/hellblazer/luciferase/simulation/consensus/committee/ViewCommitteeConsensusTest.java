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

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;

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
    // Fixed-base clock for proposal/vote timestamps (determinism mandate, Luciferase-ze0eq).
    private static final TestClock ZE0EQ_CLOCK = new TestClock(1_000L);

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
        when(context.active()).thenAnswer(invocation -> members.stream());


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
            ZE0EQ_CLOCK.currentTimeMillis()
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
            ZE0EQ_CLOCK.currentTimeMillis()
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
            ZE0EQ_CLOCK.currentTimeMillis()
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
            ZE0EQ_CLOCK.currentTimeMillis()
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
            ZE0EQ_CLOCK.currentTimeMillis()
        );

        // Change view BEFORE submitting proposal
        mockMonitor.setCurrentViewId(view2);

        // Submit proposal with old view1 tag
        var future = consensus.requestConsensus(proposal);

        // Should immediately return false (view mismatch)
        var result = future.get(100, TimeUnit.MILLISECONDS);
        assertFalse(result, "Proposal with old viewId should be rejected immediately");
    }

    // ===== RDR-020 S3: TOPOLOGY-kind self-source proposals skip the self-migration reject =====

    @Test
    public void testTopologyKindSkipsSelfMigrationReject() throws Exception {
        // A TOPOLOGY proposal is single-region: source == target == owner(region). A committee
        // member (members.get(0)) is both source and target. validateProposal must NOT self-migration-
        // reject it; instead it proceeds to voting (future remains in-flight pending votes), then
        // reaches quorum.
        var owner = members.get(0).getId();
        var proposal = new MigrationProposal(
            UUID.randomUUID(), UUID.randomUUID(),
            owner, owner,                       // self-source == self-target
            view1, ZE0EQ_CLOCK.currentTimeMillis(),
            ProposalKind.TOPOLOGY);

        var future = consensus.requestConsensus(proposal);
        assertFalse(future.isDone(),
                    "TOPOLOGY self-owner proposal must pass validateProposal and enter voting (not self-rejected)");

        for (int i = 0; i < 2; i++) {
            votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(i).getId(), true, view1));
        }
        assertTrue(future.get(1, TimeUnit.SECONDS),
                   "TOPOLOGY proposal must reach quorum once it passes validation");
    }

    @Test
    public void testTopologyKindStillEnforcesInViewMembership() throws Exception {
        // RDR-020 S3: TOPOLOGY skips ONLY the self-migration reject. The isNodeInView gate still
        // applies — a TOPOLOGY proposal whose owner is NOT a current-view member is rejected. (The
        // owner digest here is absent from the committee's member set.)
        var outOfViewOwner = DigestAlgorithm.DEFAULT.digest("not-a-cluster-member");
        var proposal = new MigrationProposal(
            UUID.randomUUID(), UUID.randomUUID(),
            outOfViewOwner, outOfViewOwner,
            view1, ZE0EQ_CLOCK.currentTimeMillis(),
            ProposalKind.TOPOLOGY);

        var future = consensus.requestConsensus(proposal);
        assertTrue(future.isDone(), "out-of-view TOPOLOGY owner must be rejected immediately");
        assertFalse(future.get(100, TimeUnit.MILLISECONDS),
                    "TOPOLOGY proposal with owner not in view must be rejected by isNodeInView");
    }

    @Test
    public void testEntityMigrationKindSelfMigrationRejected() throws Exception {
        // The SAME self-source proposal as ENTITY_MIGRATION (the default kind / old-peer encoding)
        // IS self-migration-rejected — immediately completes false. This pins both the kind-branch
        // and the mixed-version contract: a pre-amendment validator reads TOPOLOGY as ENTITY_MIGRATION
        // and rejects it.
        var owner = members.get(0).getId();
        var proposal = new MigrationProposal(
            UUID.randomUUID(), UUID.randomUUID(),
            owner, owner,                       // self-source == self-target
            view1, ZE0EQ_CLOCK.currentTimeMillis(),
            ProposalKind.ENTITY_MIGRATION);

        var future = consensus.requestConsensus(proposal);
        assertTrue(future.isDone(), "ENTITY_MIGRATION self-source proposal must be rejected immediately");
        assertFalse(future.get(100, TimeUnit.MILLISECONDS),
                    "ENTITY_MIGRATION self-migration must be rejected (source == target)");
    }

    @Test
    public void testEvictedButNotGcdTargetRejectedByActiveOnlyGate() throws Exception {
        // RDR-020 S6 end-to-end (non-tautological): make members.get(2) present in allMembers() but
        // ABSENT from active() — an evicted-but-not-GC'd member. A proposal targeting it must be
        // rejected by validateProposal's isNodeInView gate, which (post-S6) consults active() only.
        when(context.active()).thenAnswer(inv -> Stream.of(members.get(0), members.get(1)));

        var source = members.get(0).getId();       // active
        var evictedTarget = members.get(2).getId(); // in allMembers, NOT active
        var proposal = new MigrationProposal(
            UUID.randomUUID(), UUID.randomUUID(),
            source, evictedTarget,
            view1, ZE0EQ_CLOCK.currentTimeMillis());

        var future = consensus.requestConsensus(proposal);
        assertTrue(future.isDone(),
                   "a proposal targeting an evicted (inactive) member must be rejected immediately");
        assertFalse(future.get(100, TimeUnit.MILLISECONDS),
                    "isNodeInView (active-only) must reject an evicted-but-not-GC'd target");
    }

    // ===== Per-entity in-flight migration mutex (Luciferase-0frcy.94) =====

    @Test
    public void testConcurrentProposalsForSameEntityRejected() throws Exception {
        var entityId = UUID.randomUUID();

        // First proposal for the entity (target = members.get(1)).
        var first = new MigrationProposal(
            UUID.randomUUID(), entityId,
            members.get(0).getId(), members.get(1).getId(),
            view1, ZE0EQ_CLOCK.currentTimeMillis());

        // Second, concurrent proposal for the SAME entity but a DIFFERENT target.
        var second = new MigrationProposal(
            UUID.randomUUID(), entityId,
            members.get(0).getId(), members.get(2).getId(),
            view1, ZE0EQ_CLOCK.currentTimeMillis());

        var firstFuture = consensus.requestConsensus(first);
        assertFalse(firstFuture.isDone(), "First proposal should remain in-flight pending votes");

        // While the first is in-flight, the second must be rejected immediately
        // without ever entering voting — otherwise both could reach quorum and
        // double-register the entity at two targets.
        var secondFuture = consensus.requestConsensus(second);
        assertTrue(secondFuture.isDone(), "Duplicate in-flight proposal must short-circuit");
        assertFalse(secondFuture.get(100, TimeUnit.MILLISECONDS),
                    "Second concurrent proposal for the same entity must be rejected");

        // Drive the first to approval; the entity slot is then released.
        for (int i = 0; i < 2; i++) {
            votingProtocol.recordVote(new Vote(first.proposalId(), members.get(i).getId(), true, view1));
        }
        assertTrue(firstFuture.get(1, TimeUnit.SECONDS), "First proposal should be approved");

        // After completion the slot is free: a fresh proposal for the entity is accepted again.
        var third = new MigrationProposal(
            UUID.randomUUID(), entityId,
            members.get(0).getId(), members.get(1).getId(),
            view1, ZE0EQ_CLOCK.currentTimeMillis());
        var thirdFuture = consensus.requestConsensus(third);
        assertFalse(thirdFuture.isDone(),
                    "A new proposal for the entity must be accepted once the prior one completes");
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
            ZE0EQ_CLOCK.currentTimeMillis()
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
            ZE0EQ_CLOCK.currentTimeMillis()
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
            ZE0EQ_CLOCK.currentTimeMillis()
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
            ZE0EQ_CLOCK.currentTimeMillis()
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
            ZE0EQ_CLOCK.currentTimeMillis()
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
            ZE0EQ_CLOCK.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);
        var result = future.get(100, TimeUnit.MILLISECONDS);

        assertFalse(result, "Proposal with source node not in view should be rejected");
    }

    // ===== Advanced Byzantine Attack Scenarios (Luciferase-qe2v) =====

    @Test
    public void testMaliciousVoterYesForInvalidProposal() throws Exception {
        // Attack: Committee member votes YES for invalid proposal (null entityId)
        // System should reject proposal during validation, not trust committee vote
        var invalidProposal = new MigrationProposal(
            UUID.randomUUID(),
            null,  // ATTACK: invalid proposal with null entityId
            members.get(0).getId(),
            members.get(1).getId(),
            view1,
            ZE0EQ_CLOCK.currentTimeMillis()
        );

        var future = consensus.requestConsensus(invalidProposal);

        // Even if malicious committee member votes YES, proposal validation should reject
        votingProtocol.recordVote(new Vote(invalidProposal.proposalId(), members.get(0).getId(), true, view1));

        var result = future.get(100, TimeUnit.MILLISECONDS);
        assertFalse(result, "Invalid proposal should be rejected even if committee votes YES");
    }

    @Test
    public void testVoteReplayAttackWithOldViewId() throws Exception {
        // Attack: Attacker replays valid votes from old view in new view
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(1).getId(),
            view2,  // Proposal for view2
            ZE0EQ_CLOCK.currentTimeMillis()
        );

        // Change to view2
        mockMonitor.setCurrentViewId(view2);
        var future = consensus.requestConsensus(proposal);

        // ATTACK: Record vote with old view1 ID (vote replay attack)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, view1));  // Old view1

        // Submit valid NO votes with correct view2 to reach quorum
        // This verifies the old view1 vote was ignored (didn't count toward YES quorum)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(1).getId(), false, view2));
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(2).getId(), false, view2));

        // Should reject (2 NO votes from quorum, old view1 vote ignored)
        var result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result, "Votes with old viewId should be ignored and not count toward quorum");
    }

    @Test
    public void testSybilAttackMultipleIdentities() throws Exception {
        // Attack: Attacker creates multiple fake member IDs to flood proposals
        var fakeId1 = DigestAlgorithm.DEFAULT.digest("fake-member-1");
        var fakeId2 = DigestAlgorithm.DEFAULT.digest("fake-member-2");
        var fakeId3 = DigestAlgorithm.DEFAULT.digest("fake-member-3");

        // Submit multiple proposals from fake identities
        var proposal1 = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            fakeId1,  // ATTACK: fake source member
            members.get(1).getId(),
            view1,
            ZE0EQ_CLOCK.currentTimeMillis()
        );

        var proposal2 = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            fakeId2,  // ATTACK: another fake source member
            members.get(1).getId(),
            view1,
            ZE0EQ_CLOCK.currentTimeMillis()
        );

        var future1 = consensus.requestConsensus(proposal1);
        var future2 = consensus.requestConsensus(proposal2);

        // Both proposals should be rejected (fake IDs not in view)
        var result1 = future1.get(100, TimeUnit.MILLISECONDS);
        var result2 = future2.get(100, TimeUnit.MILLISECONDS);

        assertFalse(result1, "Proposal from fake member should be rejected");
        assertFalse(result2, "Multiple proposals from fake members should all be rejected");
    }

    @Test
    public void testSplitBrainScenarioConflictingQuorums() throws Exception {
        // Attack: Network partition causes two concurrent proposals with different views
        // System should prevent split-brain by rejecting stale votes from old view
        var proposal1 = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(0).getId(),
            members.get(1).getId(),
            view1,  // Proposal for view1
            ZE0EQ_CLOCK.currentTimeMillis()
        );

        // Start with view1
        var future1 = consensus.requestConsensus(proposal1);

        // Record one YES vote in view1 (below quorum)
        votingProtocol.recordVote(new Vote(proposal1.proposalId(), members.get(0).getId(), true, view1));

        // Switch to view2 (simulating partition recovery)
        mockMonitor.setCurrentViewId(view2);
        consensus.onViewChange(view2);

        // Proposal1 should abort due to view change (returns false for retry)
        var result1 = future1.get(1, TimeUnit.SECONDS);
        assertFalse(result1, "Proposal should abort on view change to prevent split-brain");

        // New proposal in view2
        var proposal2 = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            members.get(1).getId(),
            members.get(2).getId(),
            view2,
            ZE0EQ_CLOCK.currentTimeMillis()
        );

        var future2 = consensus.requestConsensus(proposal2);

        // ATTACK: Attacker tries to replay old view1 votes
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(0).getId(), true, view1));

        // Submit valid votes in view2 to reach quorum
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(1).getId(), true, view2));
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(2).getId(), true, view2));

        var result2 = future2.get(1, TimeUnit.SECONDS);
        assertTrue(result2, "New proposal in view2 should succeed (old view1 votes ignored)");
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

        @Override
        public Stream<Member> activeMembers() {
            return Stream.empty();
        }
    }
}
