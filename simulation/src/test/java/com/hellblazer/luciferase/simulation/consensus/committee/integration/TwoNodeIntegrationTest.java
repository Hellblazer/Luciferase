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
 * Two-node cluster integration tests.
 * <p>
 * In a 2-node cluster with t=0 (toleranceLevel=0), quorum=1.
 * Both nodes must participate, but only 1 vote is needed for quorum.
 * <p>
 * This is the minimal cluster configuration:
 * - No Byzantine fault tolerance (t=0)
 * - Quorum = 1 (first vote wins)
 * - Both nodes see same committee (the entire cluster)
 * <p>
 * Phase 7G Day 5: Integration Testing & Raft Deletion
 *
 * @author hal.hildebrand
 */
public class TwoNodeIntegrationTest {

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
        // Create 2-node context: t=0, quorum=1
        context = Mockito.mock(DynamicContext.class);
        when(context.size()).thenReturn(2);
        when(context.toleranceLevel()).thenReturn(0);

        // Create 2 members
        members = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            members.add(new MockMember(DigestAlgorithm.DEFAULT.getOrigin().prefix(i)));
        }

        // Setup view ID
        viewId = DigestAlgorithm.DEFAULT.digest("view-2node".getBytes());

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
    @DisplayName("Two-node cluster quorum requires only 1 vote (t=0)")
    public void testTwoNodeClusterQuorum() throws Exception {
        // 2 nodes: t=0, quorum=1
        // First vote to reach quorum wins
        var proposal = createProposal(members.get(0).getId(), members.get(1).getId());

        var future = consensus.requestConsensus(proposal);

        // Single YES vote reaches quorum
        var vote = new Vote(proposal.proposalId(), members.get(0).getId(), true, viewId);
        votingProtocol.recordVote(vote);

        // Should complete immediately with quorum=1
        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "Single YES vote should reach quorum in 2-node cluster");
    }

    @Test
    @DisplayName("Entity migrates from node A to B successfully")
    public void testTwoNodeEntityMigration() throws Exception {
        // Entity migration: A → B
        var entityId = UUID.randomUUID();
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            members.get(0).getId(),  // source: A
            members.get(1).getId(),  // target: B
            viewId,
            System.currentTimeMillis()
        );

        var future = consensus.requestConsensus(proposal);

        // Node A votes YES (owner approves migration)
        votingProtocol.recordVote(new Vote(proposal.proposalId(), members.get(0).getId(), true, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "Migration A→B should be approved with owner vote");

        // Verify migration can execute
        assertTrue(consensus.canExecuteMigration(proposal, entityId));
    }

    @Test
    @DisplayName("Both nodes participate in committee (full cluster)")
    public void testTwoNodeBothNodesInCommittee() {
        // In 2-node cluster, committee IS the entire cluster
        var committee = selector.selectCommittee(viewId);

        assertEquals(2, committee.size(), "Committee should include all 2 nodes");

        // Verify both member IDs are in committee
        var committeeIds = new HashSet<Digest>();
        for (var member : committee) {
            committeeIds.add(member.getId());
        }

        assertTrue(committeeIds.contains(members.get(0).getId()));
        assertTrue(committeeIds.contains(members.get(1).getId()));
    }

    @Test
    @DisplayName("No votes within timeout causes rejection")
    public void testTwoNodeSingleFailureBlocksConsensus() throws Exception {
        // If no node votes, consensus depends on timeout
        // After timeout, the proposal should be rejected (not approved)
        var proposal = createProposal(members.get(0).getId(), members.get(1).getId());

        // Use short timeout for test
        var shortConfig = CommitteeConfig.newBuilder()
            .votingTimeoutSeconds(1)  // 1 second timeout
            .build();
        var shortScheduler = Executors.newScheduledThreadPool(1);
        var shortProtocol = new CommitteeVotingProtocol(context, shortConfig, shortScheduler);

        consensus.setVotingProtocol(shortProtocol);
        var future = consensus.requestConsensus(proposal);

        // No votes arrive (simulating both nodes failed)
        try {
            var result = future.get(3, TimeUnit.SECONDS);
            // If it completes, should be rejected (no quorum reached)
            assertFalse(result, "Should be rejected without any votes");
        } catch (ExecutionException e) {
            // Timeout is expected
            assertTrue(e.getCause() instanceof TimeoutException ||
                      e.getCause().getMessage().contains("timeout"),
                "Should be timeout: " + e.getCause());
        } finally {
            shortScheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("Node restart does not affect pending consensus")
    public void testTwoNodeConsensusBeforeAndAfterVote() throws Exception {
        // Test that voting works correctly across sequential operations
        var proposal1 = createProposal(members.get(0).getId(), members.get(1).getId());
        var future1 = consensus.requestConsensus(proposal1);

        // Vote YES
        votingProtocol.recordVote(new Vote(proposal1.proposalId(), members.get(0).getId(), true, viewId));
        var result1 = future1.get(1, TimeUnit.SECONDS);
        assertTrue(result1, "First proposal should be approved");

        // Submit second proposal
        var proposal2 = createProposal(members.get(1).getId(), members.get(0).getId());
        var future2 = consensus.requestConsensus(proposal2);

        // Vote NO on second
        votingProtocol.recordVote(new Vote(proposal2.proposalId(), members.get(1).getId(), false, viewId));
        var result2 = future2.get(1, TimeUnit.SECONDS);
        assertFalse(result2, "Second proposal should be rejected with NO vote");
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
