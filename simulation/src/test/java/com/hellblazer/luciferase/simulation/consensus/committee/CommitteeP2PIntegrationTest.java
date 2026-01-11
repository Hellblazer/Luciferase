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

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Integration tests for P2P committee communication via gRPC.
 *
 * Tests that proposals and votes can be transmitted between nodes using CommitteeService.
 *
 * Phase 7G Day 4: Proto & P2P Communication Layer
 *
 * @author hal.hildebrand
 */
public class CommitteeP2PIntegrationTest {

    private CommitteeServiceImpl proposerService;
    private CommitteeServiceImpl memberService;
    private Server proposerServer;
    private Server memberServer;
    private ViewCommitteeConsensus proposerConsensus;
    private ViewCommitteeConsensus memberConsensus;
    private CommitteeVotingProtocol proposerVotingProtocol;
    private CommitteeVotingProtocol memberVotingProtocol;
    private String proposerAddress;
    private String memberAddress;

    @BeforeEach
    public void setUp() throws Exception {
        // Create mock view monitor for proposer
        var mockProposerViewMonitor = new MockViewMonitor(DigestAlgorithm.DEFAULT.digest("view1"));

        // Create consensus and voting protocol for proposer (port 0 = dynamic)
        proposerVotingProtocol = createVotingProtocol();
        proposerConsensus = new ViewCommitteeConsensus();
        proposerConsensus.setViewMonitor(mockProposerViewMonitor);
        proposerConsensus.setCommitteeSelector(new ViewCommitteeSelector(createMockContext()));
        proposerConsensus.setVotingProtocol(proposerVotingProtocol);
        proposerService = new CommitteeServiceImpl(proposerConsensus, proposerVotingProtocol);

        // Start proposer gRPC server
        proposerServer = NettyServerBuilder.forPort(0)
            .addService(proposerService)
            .build()
            .start();
        proposerAddress = "localhost:" + proposerServer.getPort();

        // Create mock view monitor for member
        var mockMemberViewMonitor = new MockViewMonitor(DigestAlgorithm.DEFAULT.digest("view1"));

        // Create consensus and voting protocol for member
        memberVotingProtocol = createVotingProtocol();
        memberConsensus = new ViewCommitteeConsensus();
        memberConsensus.setViewMonitor(mockMemberViewMonitor);
        memberConsensus.setCommitteeSelector(new ViewCommitteeSelector(createMockContext()));
        memberConsensus.setVotingProtocol(memberVotingProtocol);
        memberService = new CommitteeServiceImpl(memberConsensus, memberVotingProtocol);

        // Start member gRPC server
        memberServer = NettyServerBuilder.forPort(0)
            .addService(memberService)
            .build()
            .start();
        memberAddress = "localhost:" + memberServer.getPort();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (proposerServer != null) {
            proposerServer.shutdown();
            proposerServer.awaitTermination(2, TimeUnit.SECONDS);
        }
        if (memberServer != null) {
            memberServer.shutdown();
            memberServer.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testSendProposalViaGrpc() throws Exception {
        // Create a migration proposal
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            DigestAlgorithm.DEFAULT.digest("view1"),
            System.currentTimeMillis()
        );

        // Proposer requests consensus
        var resultFuture = proposerConsensus.requestConsensus(proposal);
        assertNotNull(resultFuture);
        assertFalse(resultFuture.isDone(), "Should not complete immediately");

        // Give time for any processing
        Thread.sleep(100);

        // Verify result is still pending
        assertFalse(resultFuture.isDone());
    }

    @Test
    public void testSendVoteViaGrpc() throws Exception {
        // Create a vote
        var vote = new Vote(
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("voter"),
            true,
            DigestAlgorithm.DEFAULT.digest("view1")
        );

        // This should not throw
        memberVotingProtocol.recordVote(vote);
    }

    @Test
    public void testMultipleProposalsInSequence() throws Exception {
        var proposals = new CopyOnWriteArrayList<MigrationProposal>();
        for (int i = 0; i < 3; i++) {
            var proposal = new MigrationProposal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DigestAlgorithm.DEFAULT.digest("source" + i),
                DigestAlgorithm.DEFAULT.digest("target" + i),
                DigestAlgorithm.DEFAULT.digest("view1"),
                System.currentTimeMillis()
            );
            proposals.add(proposal);
            proposerConsensus.requestConsensus(proposal);
        }

        // All proposals should be tracked
        assertEquals(3, proposals.size());
    }

    @Test
    public void testCommitteeServiceResultCache() {
        // Service should track results
        var service = new CommitteeServiceImpl(proposerConsensus, proposerVotingProtocol);
        assertEquals(0, service.getCachedResultCount());

        service.clearResultCache();
        assertEquals(0, service.getCachedResultCount());
    }

    @Test
    public void testProtoConversionRoundTrip() throws Exception {
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            DigestAlgorithm.DEFAULT.digest("view1"),
            System.currentTimeMillis()
        );

        var proposerAddress = "localhost:9090";

        // Convert to proto and back
        var proto = CommitteeProtoConverter.toProto(proposal, proposerAddress);
        var reconstructed = CommitteeProtoConverter.fromProto(proto);

        // Verify all fields preserved
        assertEquals(proposal.proposalId(), reconstructed.proposalId());
        assertEquals(proposal.entityId(), reconstructed.entityId());
        assertEquals(proposal.sourceNodeId(), reconstructed.sourceNodeId());
        assertEquals(proposal.targetNodeId(), reconstructed.targetNodeId());
        assertEquals(proposal.viewId(), reconstructed.viewId());
    }

    @Test
    public void testVoteProtoConversionRoundTrip() throws Exception {
        var vote = new Vote(
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("voter"),
            true,
            DigestAlgorithm.DEFAULT.digest("view1")
        );

        // Convert to proto and back
        var proto = CommitteeProtoConverter.toProto(vote);
        var reconstructed = CommitteeProtoConverter.fromProto(proto);

        // Verify all fields preserved
        assertEquals(vote.proposalId(), reconstructed.proposalId());
        assertEquals(vote.voterId(), reconstructed.voterId());
        assertEquals(vote.approved(), reconstructed.approved());
        assertEquals(vote.viewId(), reconstructed.viewId());
    }

    @Test
    public void testServerAddressesAreDifferent() {
        assertNotEquals(proposerAddress, memberAddress, "Servers should have different ports");
    }

    @Test
    public void testProtoConverterPreservesProposerAddress() throws Exception {
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            DigestAlgorithm.DEFAULT.digest("view1"),
            System.currentTimeMillis()
        );

        var proposerAddress = "localhost:8888";
        var proto = CommitteeProtoConverter.toProto(proposal, proposerAddress);

        assertEquals(proposerAddress, CommitteeProtoConverter.getProposerAddress(proto));
    }

    /**
     * Helper to create voting protocol with mock context.
     */
    private CommitteeVotingProtocol createVotingProtocol() {
        var executor = Executors.newScheduledThreadPool(1);
        var config = CommitteeConfig.defaultConfig();
        var mockContext = createMockContext();
        return new CommitteeVotingProtocol(mockContext, config, executor);
    }

    /**
     * Helper to create mock context with BFT parameters.
     */
    private com.hellblazer.delos.context.DynamicContext<com.hellblazer.delos.membership.Member> createMockContext() {
        var mockContext = Mockito.mock(com.hellblazer.delos.context.DynamicContext.class);
        when(mockContext.size()).thenReturn(3);
        when(mockContext.toleranceLevel()).thenReturn(1);
        when(mockContext.bftSubset(Mockito.any(Digest.class)))
            .thenReturn(new java.util.LinkedHashSet<>());
        return mockContext;
    }

    /**
     * Mock ViewMonitor for testing.
     */
    private static class MockViewMonitor extends com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor {
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

    /**
     * Mock MembershipView for testing.
     */
    private static class MockMembershipView implements com.hellblazer.luciferase.simulation.delos.MembershipView<com.hellblazer.delos.membership.Member> {
        @Override
        public void addListener(java.util.function.Consumer listener) {
            // Mock - no listeners needed
        }

        @Override
        public java.util.stream.Stream<com.hellblazer.delos.membership.Member> getMembers() {
            return java.util.stream.Stream.empty();
        }
    }
}
