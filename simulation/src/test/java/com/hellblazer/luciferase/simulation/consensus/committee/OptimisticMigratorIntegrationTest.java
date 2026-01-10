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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OptimisticMigratorIntegration adapter.
 * <p>
 * Verifies that OptimisticMigrator correctly delegates to ViewCommitteeConsensus
 * and that migration proposals are properly tagged with view IDs.
 * <p>
 * Phase 7G Day 3: ViewCommitteeConsensus & OptimisticMigrator Integration
 *
 * @author hal.hildebrand
 */
public class OptimisticMigratorIntegrationTest {

    private DynamicContext<Member> context;
    private ViewCommitteeSelector selector;
    private CommitteeVotingProtocol votingProtocol;
    private ViewCommitteeConsensus consensus;
    private OptimisticMigratorIntegration integration;
    private MockViewMonitor mockMonitor;
    private Digest view1;
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

        // Create view ID
        view1 = DigestAlgorithm.DEFAULT.digest("view1".getBytes());

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

        // Create integration adapter
        integration = new OptimisticMigratorIntegration(consensus, mockMonitor);
    }

    @Test
    public void testRequestMigrationApprovalDelegates() throws Exception {
        // Verify integration delegates to consensus correctly
        var entityId = UUID.randomUUID();
        var sourceId = members.get(0).getId();
        var targetNodeId = members.get(1).getId();

        var future = integration.requestMigrationApproval(entityId, sourceId, targetNodeId);

        assertNotNull(future);
        assertFalse(future.isDone(), "Future should not be completed immediately");

        // Simulate committee approval
        var committee = selector.selectCommittee(view1);
        var proposal = integration.getLastProposal();
        assertNotNull(proposal, "Proposal should be created");

        for (var member : committee) {
            votingProtocol.recordVote(new Vote(proposal.proposalId(), member.getId(), true, view1));
        }

        // Wait for approval
        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "Migration should be approved");
    }

    @Test
    public void testMigrationProposalIncludesViewId() {
        // Verify proposal is tagged with current view
        var entityId = UUID.randomUUID();
        var sourceId = members.get(0).getId();
        var targetNodeId = members.get(1).getId();

        integration.requestMigrationApproval(entityId, sourceId, targetNodeId);

        var proposal = integration.getLastProposal();
        assertNotNull(proposal);
        assertEquals(view1, proposal.viewId(), "Proposal should be tagged with current view");
        assertEquals(entityId, proposal.entityId());
        assertEquals(sourceId, proposal.sourceNodeId());
        assertEquals(targetNodeId, proposal.targetNodeId());
    }

    @Test
    public void testSuccessfulMigrationApprovedByConsensus() throws Exception {
        // Full end-to-end integration test
        var entityId = UUID.randomUUID();
        var sourceId = members.get(0).getId();
        var targetNodeId = members.get(1).getId();

        var future = integration.requestMigrationApproval(entityId, sourceId, targetNodeId);

        // Committee votes YES
        var committee = selector.selectCommittee(view1);
        var proposal = integration.getLastProposal();

        for (var member : committee) {
            votingProtocol.recordVote(new Vote(proposal.proposalId(), member.getId(), true, view1));
        }

        // Wait for approval
        var approved = future.get(1, TimeUnit.SECONDS);
        assertTrue(approved, "Migration should be approved");

        // Verify migration was recorded
        assertTrue(integration.hasMigrationApproval(entityId), "Migration approval should be recorded");
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
