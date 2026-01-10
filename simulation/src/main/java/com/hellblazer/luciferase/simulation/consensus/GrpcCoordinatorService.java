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

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * gRPC service implementation for consensus coordinator election.
 * <p>
 * Implements the CoordinatorElectionService gRPC interface, providing:
 * - RequestVote: Handles vote requests during elections
 * - AppendHeartbeat: Processes leader heartbeats
 * - ProposeBallot: Handles ballot proposals for consensus decisions
 * <p>
 * Delegates to ConsensusElectionProtocol for actual consensus logic.
 */
public class GrpcCoordinatorService extends CoordinatorElectionServiceGrpc.CoordinatorElectionServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(GrpcCoordinatorService.class);
    private static final String VERSION = "1.0";

    private final ConsensusElectionProtocol protocol;
    private final UUID nodeId;

    /**
     * Creates a new gRPC coordinator service.
     *
     * @param protocol the consensus election protocol to delegate to
     */
    public GrpcCoordinatorService(ConsensusElectionProtocol protocol) {
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null");
        this.nodeId = protocol.getNodeId();
    }

    @Override
    public void requestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        Objects.requireNonNull(responseObserver, "responseObserver must not be null");
        try {
            if (request == null) {
                var error = VoteResponse.newBuilder()
                                       .setVersion(VERSION)
                                       .setTerm(protocol.getCurrentTerm())
                                       .setVoteGranted(false)
                                       .setReason("Null request")
                                       .build();
                responseObserver.onNext(error);
                responseObserver.onCompleted();
                return;
            }

            var candidateId = UUID.fromString(request.getCandidateId());
            var term = request.getTerm();

            log.debug("Received vote request from {} for term {}", candidateId, term);

            // Request vote from protocol
            protocol.requestVote(candidateId, term).thenAccept(voteGranted -> {
                var response = VoteResponse.newBuilder()
                                          .setVersion(VERSION)
                                          .setTerm(protocol.getCurrentTerm())
                                          .setVoteGranted(voteGranted)
                                          .setReason(voteGranted ? "Vote granted" : "Vote denied - already voted")
                                          .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }).exceptionally(throwable -> {
                log.error("Error processing vote request", throwable);
                responseObserver.onError(throwable);
                return null;
            });

        } catch (Exception e) {
            log.error("Error in requestVote", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void appendHeartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
        Objects.requireNonNull(responseObserver, "responseObserver must not be null");
        try {
            if (request == null) {
                var error = HeartbeatResponse.newBuilder()
                                            .setVersion(VERSION)
                                            .setTerm(protocol.getCurrentTerm())
                                            .setSuccess(false)
                                            .setMatchIndex(0)
                                            .setReason("Null request")
                                            .build();
                responseObserver.onNext(error);
                responseObserver.onCompleted();
                return;
            }

            var leaderId = UUID.fromString(request.getLeaderId());
            var term = request.getTerm();

            log.trace("Received heartbeat from leader {} (term={})", leaderId, term);

            // Process heartbeat
            protocol.sendHeartbeat(leaderId, term);

            // Send acknowledgment
            var response = HeartbeatResponse.newBuilder()
                                           .setVersion(VERSION)
                                           .setTerm(protocol.getCurrentTerm())
                                           .setSuccess(true)
                                           .setMatchIndex(request.getPrevLogIndex())
                                           .setReason("Heartbeat acknowledged")
                                           .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in appendHeartbeat", e);
            responseObserver.onError(e);
        }
    }

    /**
     * Handles ballot proposals (not in original proto, but used for entity ownership voting).
     * <p>
     * Note: This method name doesn't match the proto service definition.
     * The proto uses "CoordinatorElectionService" with RequestVote, AppendHeartbeat, GetLeader.
     * Ballot proposals are handled through a separate mechanism.
     */
    public void proposeBallot(BallotProposal request, StreamObserver<BallotVote> responseObserver) {
        Objects.requireNonNull(responseObserver, "responseObserver must not be null");
        try {
            if (request == null) {
                var error = BallotVote.newBuilder()
                                     .setVersion(VERSION)
                                     .setProposalId("")
                                     .setVoterId(nodeId.toString())
                                     .setTerm(protocol.getCurrentTerm())
                                     .setVote(false)
                                     .setReason("Null request")
                                     .build();
                responseObserver.onNext(error);
                responseObserver.onCompleted();
                return;
            }

            var proposalId = request.getProposalId();
            var proposalType = request.getProposalType();
            var term = request.getTerm();

            log.debug("Received ballot proposal {} (type={})", proposalId, proposalType);

            // Check if proposal exists
            var ballot = protocol.getBallotBox();
            var exists = ballot.getActiveProposals().contains(proposalId);

            boolean vote;
            String reason;

            if (!exists) {
                // Auto-reject unknown proposals
                vote = false;
                reason = "Unknown proposal - not registered";
            } else {
                // Vote based on current protocol state
                // In a real implementation, this would check proposal validity
                vote = protocol.getCurrentState() != ElectionState.CANDIDATE;
                reason = vote ? "Proposal accepted" : "Node busy with election";
            }

            var response = BallotVote.newBuilder()
                                    .setVersion(VERSION)
                                    .setProposalId(proposalId)
                                    .setVoterId(nodeId.toString())
                                    .setTerm(term)
                                    .setVote(vote)
                                    .setReason(reason)
                                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in proposeBallot", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void getLeader(GetLeaderRequest request, StreamObserver<GetLeaderResponse> responseObserver) {
        Objects.requireNonNull(responseObserver, "responseObserver must not be null");
        try {
            if (request == null) {
                var error = GetLeaderResponse.newBuilder()
                                            .setVersion(VERSION)
                                            .setLeaderId("")
                                            .setTerm(protocol.getCurrentTerm())
                                            .setLeaderIsValid(false)
                                            .build();
                responseObserver.onNext(error);
                responseObserver.onCompleted();
                return;
            }

            var isLeader = protocol.getCurrentState() == ElectionState.LEADER;
            var leaderId = isLeader ? protocol.getBallotBox().toString() : "";

            var response = GetLeaderResponse.newBuilder()
                                           .setVersion(VERSION)
                                           .setLeaderId(leaderId)
                                           .setTerm(protocol.getCurrentTerm())
                                           .setLeaderIsValid(isLeader)
                                           .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in getLeader", e);
            responseObserver.onError(e);
        }
    }
}
