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
 * CRITICAL TEST: View ID race condition prevention.
 * <p>
 * This test validates the fix for the double-commit race condition identified by substantive-critic.
 * <p>
 * Race Condition Scenario:
 * <pre>
 * t1: Committee approves E: A→B (viewId=V1)
 * t2: View changes to V2  ← Entity could start migrating to B here
 * t3: New committee approves E: C→D (viewId=V2)
 * t4: E ends up in both B and D ← CORRUPTION!
 * </pre>
 * <p>
 * Solution: View ID verification ensures proposal.viewId() == getCurrentViewId() before execution.
 * Proposals with stale viewIds are immediately aborted.
 * <p>
 * Phase 7G Day 3: ViewCommitteeConsensus & OptimisticMigrator Integration
 *
 * @author hal.hildebrand
 */
public class ViewIdRaceConditionTest {

    private DynamicContext<Member> context;
    private ViewCommitteeSelector selector;
    private CommitteeVotingProtocol votingProtocol;
    private ViewCommitteeConsensus consensus;
    private MockViewMonitor mockMonitor;
    private ScheduledExecutorService scheduler;
    private Digest view1;
    private Digest view2;

    @BeforeEach
    public void setUp() {
        // Create mock context with 5 members (t=1, quorum=2)
        context = Mockito.mock(DynamicContext.class);
        when(context.size()).thenReturn(5);
        when(context.toleranceLevel()).thenReturn(1);

        // Create view IDs
        view1 = DigestAlgorithm.DEFAULT.digest("view1".getBytes());
        view2 = DigestAlgorithm.DEFAULT.digest("view2".getBytes());

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
    public void testDoubleCommitPrevention() throws Exception {
        // CRITICAL: Entity cannot migrate to both targets across view boundary
        var entityId = UUID.randomUUID();
        var targetA = DigestAlgorithm.DEFAULT.digest("targetA");
        var targetB = DigestAlgorithm.DEFAULT.digest("targetB");

        // t1: Committee votes to migrate entity to targetA in view1
        var proposal1 = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            DigestAlgorithm.DEFAULT.digest("source"),
            targetA,
            view1,
            System.currentTimeMillis()
        );

        var future1 = consensus.requestConsensus(proposal1);

        // Simulate quorum votes for proposal1 (quorum=2)
        for (int i = 0; i < 2; i++) {
            votingProtocol.recordVote(new Vote(proposal1.proposalId(), DigestAlgorithm.DEFAULT.digest("member-" + i), true, view1));
        }

        // Wait for proposal1 approval
        var result1 = future1.get(1, TimeUnit.SECONDS);
        assertTrue(result1, "Proposal1 should be approved");

        // t2: View changes to view2
        mockMonitor.setCurrentViewId(view2);
        consensus.onViewChange(view2);

        // t3: New committee tries to migrate SAME entity to targetB in view2
        var proposal2 = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            DigestAlgorithm.DEFAULT.digest("source"),
            targetB,
            view2,
            System.currentTimeMillis()
        );

        var future2 = consensus.requestConsensus(proposal2);

        // New committee votes for approval (quorum=2)
        for (int i = 0; i < 2; i++) {
            votingProtocol.recordVote(new Vote(proposal2.proposalId(), DigestAlgorithm.DEFAULT.digest("member-" + i), true, view2));
        }

        var result2 = future2.get(1, TimeUnit.SECONDS);
        assertTrue(result2, "Proposal2 should be approved");

        // CRITICAL ASSERTION: Only proposal2 should execute (view2 is current)
        // Proposal1 execution should be blocked because viewId=view1 but current=view2
        assertFalse(consensus.canExecuteMigration(proposal1, entityId), "OLD VIEW proposal1 should be rejected");
        assertTrue(consensus.canExecuteMigration(proposal2, entityId), "CURRENT VIEW proposal2 should be accepted");
    }

    @Test
    public void testViewIdMatchingBetweenProposalAndVote() throws Exception {
        // Votes must have same viewId as proposal
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);

        // First vote with correct view1
        votingProtocol.recordVote(new Vote(proposal.proposalId(), DigestAlgorithm.DEFAULT.digest("member-0"), true, view1));

        // Second vote with WRONG view2 (should be ignored)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), DigestAlgorithm.DEFAULT.digest("member-1"), true, view2));

        // Wait a bit - should NOT complete (only 1/2 votes with correct viewId)
        Thread.sleep(200);
        assertFalse(future.isDone(), "Proposal should not complete with votes from wrong view");
    }

    @Test
    public void testRaceConditionWith5NodeCommittee() throws Exception {
        // Byzantine scenario: view changes during voting
        var entityId = UUID.randomUUID();
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);

        // First vote arrives
        votingProtocol.recordVote(new Vote(proposal.proposalId(), DigestAlgorithm.DEFAULT.digest("member-0"), true, view1));

        // VIEW CHANGES mid-voting
        mockMonitor.setCurrentViewId(view2);
        consensus.onViewChange(view2);

        // Second vote arrives (but view has changed)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), DigestAlgorithm.DEFAULT.digest("member-1"), true, view1));

        // Proposal should be aborted due to view change
        try {
            future.get(1, TimeUnit.SECONDS);
            fail("Proposal should be aborted due to view change");
        } catch (Exception e) {
            // Expected - aborted
            assertTrue(e.getCause() instanceof IllegalStateException || e.getMessage().contains("aborted"));
        }

        // Double-check: cannot execute migration from old view
        assertFalse(consensus.canExecuteMigration(proposal, entityId), "Proposal from old view should not execute");
    }

    @Test
    public void testEarlyViewChangeInterrupt() throws Exception {
        // View change before any votes arrive (simulates slow network)
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            view1,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);

        // View changes IMMEDIATELY (before any votes)
        mockMonitor.setCurrentViewId(view2);
        consensus.onViewChange(view2);

        // Try to vote (should be ignored - proposal already aborted)
        for (int i = 0; i < 2; i++) {
            votingProtocol.recordVote(new Vote(proposal.proposalId(), DigestAlgorithm.DEFAULT.digest("member-" + i), true, view1));
        }

        // Future should be aborted
        try {
            future.get(1, TimeUnit.SECONDS);
            fail("Should be aborted due to view change");
        } catch (Exception e) {
            // Expected
            assertTrue(e.getCause() instanceof IllegalStateException || e.getMessage().contains("aborted"));
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
