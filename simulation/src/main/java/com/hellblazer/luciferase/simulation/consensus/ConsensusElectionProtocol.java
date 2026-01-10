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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ConsensusElectionProtocol - Three-state FSM implementation for leader election.
 * <p>
 * States:
 * - FOLLOWER: Default state, waits for leader heartbeat
 * - CANDIDATE: Election in progress, requesting votes from quorum
 * - LEADER: Won election, broadcasts periodic heartbeats
 * <p>
 * Thread-safe implementation using atomic references and scheduled executor.
 */
public class ConsensusElectionProtocol {
    private static final Logger log = LoggerFactory.getLogger(ConsensusElectionProtocol.class);
    private static final long HEARTBEAT_INTERVAL_MS = 100; // Leader sends heartbeats every 100ms

    /**
     * Interface for broadcasting messages to peer nodes.
     * Allows different implementations (gRPC, in-process testing, etc.)
     */
    public interface PeerCommunicator {
        /**
         * Broadcasts vote request to all peers.
         *
         * @param candidateId the candidate requesting votes
         * @param term        the election term
         */
        void broadcastVoteRequest(UUID candidateId, long term);

        /**
         * Broadcasts heartbeat to all peers.
         *
         * @param leaderId the leader ID
         * @param term     the leader's term
         */
        void broadcastHeartbeat(UUID leaderId, long term);
    }

    private final UUID nodeId;
    private final int quorumSize;
    private final long electionTimeoutMs;
    private final AtomicReference<ElectionState> currentState;
    private final AtomicLong currentTerm;
    private final AtomicLong heartbeatCount;
    private final AtomicBoolean running;
    private final BallotBox ballotBox;
    private final FailureDetector failureDetector;
    private final ScheduledExecutorService executor;
    private final AtomicReference<UUID> votedFor;
    private final AtomicReference<UUID> currentLeader;
    private volatile long lastHeartbeatTime;
    private volatile PeerCommunicator peerCommunicator;

    /**
     * Creates a new consensus election protocol.
     *
     * @param nodeId             the UUID of this node
     * @param quorumSize         the number of nodes needed for quorum
     * @param electionTimeoutMs  the timeout in milliseconds before starting election
     */
    public ConsensusElectionProtocol(UUID nodeId, int quorumSize, long electionTimeoutMs) {
        this.nodeId = nodeId;
        this.quorumSize = quorumSize;
        this.electionTimeoutMs = electionTimeoutMs;
        this.currentState = new AtomicReference<>(ElectionState.FOLLOWER);
        this.currentTerm = new AtomicLong(0);
        this.heartbeatCount = new AtomicLong(0);
        this.running = new AtomicBoolean(false);
        this.ballotBox = new BallotBox(nodeId, quorumSize);
        this.failureDetector = new FailureDetector(nodeId, electionTimeoutMs / 2, electionTimeoutMs, 3);
        this.executor = Executors.newScheduledThreadPool(2, r -> {
            var thread = new Thread(r);
            thread.setName("ConsensusElection-" + nodeId);
            thread.setDaemon(true);
            return thread;
        });
        this.votedFor = new AtomicReference<>(null);
        this.currentLeader = new AtomicReference<>(null);
        this.lastHeartbeatTime = System.currentTimeMillis();
    }

    /**
     * Starts the consensus protocol.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("ConsensusElectionProtocol started for node {} (timeout={}ms)", nodeId, electionTimeoutMs);
            lastHeartbeatTime = System.currentTimeMillis();
            scheduleElectionTimeoutMonitor();
        }
    }

    /**
     * Stops the consensus protocol and cleans up resources.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("ConsensusElectionProtocol stopping for node {}", nodeId);
            executor.shutdown();
            failureDetector.close();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns the current election state.
     */
    public ElectionState getCurrentState() {
        return currentState.get();
    }

    /**
     * Returns the current term number.
     */
    public long getCurrentTerm() {
        return currentTerm.get();
    }

    /**
     * Returns the heartbeat count (for testing).
     */
    public long getHeartbeatCount() {
        return heartbeatCount.get();
    }

    /**
     * Returns whether the protocol is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the node ID.
     */
    public UUID getNodeId() {
        return nodeId;
    }

    /**
     * Returns the ballot box (for testing).
     */
    public BallotBox getBallotBox() {
        return ballotBox;
    }

    /**
     * Sets the peer communicator for broadcasting messages.
     * Must be set before starting elections for multi-node consensus.
     *
     * @param communicator the peer communicator implementation
     */
    public void setPeerCommunicator(PeerCommunicator communicator) {
        this.peerCommunicator = communicator;
    }

    /**
     * Records a vote for a proposal.
     *
     * @param proposalId the proposal ID
     * @param voterId    the voter ID
     * @param approved   true if vote is YES, false if NO
     */
    public void recordVote(String proposalId, UUID voterId, boolean approved) {
        boolean recorded;
        if (approved) {
            recorded = ballotBox.recordYesVote(proposalId, voterId);
        } else {
            recorded = ballotBox.recordNoVote(proposalId, voterId);
        }

        // Only check for election win if vote was actually recorded (not a duplicate)
        if (recorded && currentState.get() == ElectionState.CANDIDATE && proposalId.equals(nodeId.toString())) {
            var decision = ballotBox.getDecisionState(proposalId);
            if (decision == BallotBox.DecisionState.APPROVED) {
                transitionToLeader();
            }
        }
    }

    /**
     * Requests a vote from this node.
     *
     * @param candidateId the candidate requesting the vote
     * @param term        the election term
     * @return future completing with true if vote granted
     */
    public CompletableFuture<Boolean> requestVote(UUID candidateId, long term) {
        var result = new CompletableFuture<Boolean>();

        executor.submit(() -> {
            try {
                // If term is higher, step down and update term
                if (term > currentTerm.get()) {
                    stepDown(term);
                }

                // Grant vote if:
                // 1. Haven't voted in this term, OR
                // 2. Already voted for this candidate
                var currentVote = votedFor.get();
                if (term == currentTerm.get() && (currentVote == null || currentVote.equals(candidateId))) {
                    votedFor.set(candidateId);
                    lastHeartbeatTime = System.currentTimeMillis(); // Reset timeout
                    result.complete(true);
                    log.debug("Node {} granted vote to {} for term {}", nodeId, candidateId, term);
                } else {
                    result.complete(false);
                    log.debug("Node {} denied vote to {} for term {}", nodeId, candidateId, term);
                }
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });

        return result;
    }

    /**
     * Sends heartbeat from leader.
     *
     * @param leaderId the leader ID
     */
    public void sendHeartbeat(UUID leaderId) {
        sendHeartbeat(leaderId, currentTerm.get());
    }

    /**
     * Sends heartbeat from leader with specific term.
     *
     * @param leaderId the leader ID
     * @param term     the leader's term
     */
    public void sendHeartbeat(UUID leaderId, long term) {
        // If term is higher, step down
        if (term > currentTerm.get()) {
            stepDown(term);
        }

        // Record heartbeat and reset timeout
        currentLeader.set(leaderId);
        failureDetector.recordHeartbeat(leaderId);
        lastHeartbeatTime = System.currentTimeMillis();

        log.trace("Node {} received heartbeat from leader {} (term={})", nodeId, leaderId, term);
    }

    /**
     * Handles election timeout - transitions to CANDIDATE and starts election.
     */
    public void handleElectionTimeout() {
        if (!running.get()) {
            return;
        }

        // Check if enough time has actually elapsed since last heartbeat
        var timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime;
        if (timeSinceLastHeartbeat < electionTimeoutMs) {
            // Not really a timeout - heartbeat was received recently
            return;
        }

        var state = currentState.get();
        if (state == ElectionState.FOLLOWER || state == ElectionState.CANDIDATE) {
            startElection();
        }
    }

    private void startElection() {
        // Increment term
        var newTerm = currentTerm.incrementAndGet();

        // Transition to CANDIDATE
        currentState.set(ElectionState.CANDIDATE);
        votedFor.set(nodeId); // Vote for self
        currentLeader.set(null);

        log.info("Node {} starting election for term {}", nodeId, newTerm);

        // Register proposal for self-election
        var proposalId = nodeId.toString();
        ballotBox.registerProposal(proposalId, "ELECTION");

        // Vote for self
        ballotBox.recordYesVote(proposalId, nodeId);

        // Check if we've already achieved quorum (single-node case)
        var decision = ballotBox.getDecisionState(proposalId);
        if (decision == BallotBox.DecisionState.APPROVED) {
            transitionToLeader();
        }

        // Reset timeout for new election
        lastHeartbeatTime = System.currentTimeMillis();

        // Broadcast vote request to peers
        if (peerCommunicator != null) {
            peerCommunicator.broadcastVoteRequest(nodeId, newTerm);
        }
    }

    private void transitionToLeader() {
        if (currentState.compareAndSet(ElectionState.CANDIDATE, ElectionState.LEADER)) {
            currentLeader.set(nodeId);
            log.info("Node {} became LEADER for term {}", nodeId, currentTerm.get());

            // Start sending heartbeats
            executor.scheduleAtFixedRate(() -> {
                if (currentState.get() == ElectionState.LEADER) {
                    broadcastHeartbeat();
                }
            }, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void broadcastHeartbeat() {
        heartbeatCount.incrementAndGet();
        log.trace("Leader {} broadcasting heartbeat (count={})", nodeId, heartbeatCount.get());

        // Broadcast heartbeat to peers
        if (peerCommunicator != null) {
            peerCommunicator.broadcastHeartbeat(nodeId, currentTerm.get());
        }
    }

    private void stepDown(long newTerm) {
        var oldState = currentState.get();
        if (oldState != ElectionState.FOLLOWER) {
            log.info("Node {} stepping down from {} to FOLLOWER (term {} -> {})", nodeId, oldState, currentTerm.get(),
                     newTerm);
            currentState.set(ElectionState.FOLLOWER);
        }

        currentTerm.set(newTerm);
        votedFor.set(null);
        currentLeader.set(null);
        lastHeartbeatTime = System.currentTimeMillis();
    }

    private void scheduleElectionTimeoutMonitor() {
        if (!running.get()) {
            return;
        }

        // Add random jitter (0-50% of timeout) to prevent simultaneous elections
        var jitter = (long) (Math.random() * electionTimeoutMs * 0.5);
        var initialDelay = electionTimeoutMs + jitter;

        // Use fixed-rate monitoring instead of rescheduling
        executor.scheduleAtFixedRate(() -> {
            var timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime;
            var effectiveTimeout = electionTimeoutMs + (long) (Math.random() * electionTimeoutMs * 0.1);
            if (timeSinceLastHeartbeat >= effectiveTimeout) {
                handleElectionTimeout();
            }
        }, initialDelay, electionTimeoutMs / 4, TimeUnit.MILLISECONDS);
    }
}
