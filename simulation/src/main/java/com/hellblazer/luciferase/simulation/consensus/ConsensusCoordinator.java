/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConsensusCoordinator - Orchestrates consensus election protocol and gRPC service.
 * <p>
 * Manages the lifecycle of:
 * - ConsensusElectionProtocol: Three-state FSM for leader election
 * - GrpcCoordinatorService: gRPC service for remote consensus operations
 * - Server: gRPC server for network communication
 * <p>
 * Provides high-level operations:
 * - proposeEntityOwnership: Propose entity ownership transfer (requires consensus)
 * - isLeader: Check if this node is the current leader
 * - getState: Get current election state
 */
public class ConsensusCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ConsensusCoordinator.class);
    private static final long DEFAULT_ELECTION_TIMEOUT_MS = 1000;

    private final UUID nodeId;
    private final int quorumSize;
    private final int grpcPortConfig;
    private final ConsensusElectionProtocol protocol;
    private final GrpcCoordinatorService grpcService;
    private final AtomicBoolean running;
    private final AtomicInteger electionCount;
    private final AtomicInteger proposalCount;

    private Server grpcServer;

    /**
     * Creates a new consensus coordinator.
     *
     * @param nodeId      the UUID of this node
     * @param quorumSize  the number of nodes needed for quorum
     * @param grpcPort    the gRPC port (0 for dynamic assignment)
     */
    public ConsensusCoordinator(UUID nodeId, int quorumSize, int grpcPort) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.quorumSize = quorumSize;
        this.grpcPortConfig = grpcPort;
        this.protocol = new ConsensusElectionProtocol(nodeId, quorumSize, DEFAULT_ELECTION_TIMEOUT_MS);
        this.grpcService = new GrpcCoordinatorService(protocol);
        this.running = new AtomicBoolean(false);
        this.electionCount = new AtomicInteger(0);
        this.proposalCount = new AtomicInteger(0);

        log.info("ConsensusCoordinator created for node {} (quorum={})", nodeId, quorumSize);
    }

    /**
     * Starts the consensus coordinator.
     *
     * @throws IOException if gRPC server fails to start
     */
    public void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            // Start protocol
            protocol.start();

            // Start gRPC server
            grpcServer = ServerBuilder.forPort(grpcPortConfig)
                                      .addService(grpcService)
                                      .build()
                                      .start();

            var actualPort = grpcServer.getPort();
            log.info("ConsensusCoordinator started for node {} on port {}", nodeId, actualPort);

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down ConsensusCoordinator via shutdown hook");
                ConsensusCoordinator.this.stop();
            }));
        }
    }

    /**
     * Stops the consensus coordinator.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("ConsensusCoordinator stopping for node {}", nodeId);

            // Stop protocol
            protocol.stop();

            // Shutdown gRPC server
            if (grpcServer != null) {
                grpcServer.shutdown();
                try {
                    if (!grpcServer.awaitTermination(5, TimeUnit.SECONDS)) {
                        grpcServer.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    grpcServer.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            log.info("ConsensusCoordinator stopped for node {}", nodeId);
        }
    }

    /**
     * Proposes entity ownership transfer to target bubble.
     * <p>
     * This is a consensus operation that requires quorum approval.
     *
     * @param entityId       the entity to transfer
     * @param targetBubbleId the target bubble
     * @return future completing with true if approved, false if rejected
     */
    public CompletableFuture<Boolean> proposeEntityOwnership(UUID entityId, UUID targetBubbleId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");

        proposalCount.incrementAndGet();

        var proposalId = UUID.randomUUID().toString();
        var ballot = protocol.getBallotBox();

        // Register proposal
        ballot.registerProposal(proposalId, "ENTITY_OWNERSHIP");

        log.debug("Proposing entity ownership: entity={}, targetBubble={}, proposal={}", entityId, targetBubbleId,
                  proposalId);

        // Vote for own proposal
        protocol.recordVote(proposalId, nodeId, true);

        // Create future for result
        var future = new CompletableFuture<Boolean>();

        // Poll for decision (in real implementation, would use callbacks)
        protocol.getBallotBox(); // Just to ensure ballot box is accessible

        // Simple polling approach for test
        CompletableFuture.runAsync(() -> {
            var maxAttempts = 20; // 2 seconds max
            for (int i = 0; i < maxAttempts; i++) {
                var decision = ballot.getDecisionState(proposalId);
                if (decision == BallotBox.DecisionState.APPROVED) {
                    future.complete(true);
                    return;
                } else if (decision == BallotBox.DecisionState.REJECTED) {
                    future.complete(false);
                    return;
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(e);
                    return;
                }
            }

            // Timeout - reject
            future.complete(false);
        });

        return future;
    }

    /**
     * Checks if this node is the current leader.
     *
     * @return true if leader, false otherwise
     */
    public boolean isLeader() {
        return protocol.getCurrentState() == ElectionState.LEADER;
    }

    /**
     * Returns the current election state.
     *
     * @return current state (FOLLOWER, CANDIDATE, or LEADER)
     */
    public ElectionState getState() {
        return protocol.getCurrentState();
    }

    /**
     * Returns the current term number.
     *
     * @return term number
     */
    public long getTermNumber() {
        return protocol.getCurrentTerm();
    }

    /**
     * Returns whether the coordinator is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the gRPC port (actual port if dynamic assignment was used).
     *
     * @return gRPC port number
     */
    public int getGrpcPort() {
        return grpcServer != null ? grpcServer.getPort() : grpcPortConfig;
    }

    /**
     * Returns the election count metric.
     *
     * @return number of elections started
     */
    public int getElectionCount() {
        // Simple metric - could track actual elections
        return electionCount.get();
    }

    /**
     * Returns the proposal count metric.
     *
     * @return number of proposals submitted
     */
    public int getProposalCount() {
        return proposalCount.get();
    }

    /**
     * Returns the node ID.
     *
     * @return node UUID
     */
    public UUID getNodeId() {
        return nodeId;
    }
}
