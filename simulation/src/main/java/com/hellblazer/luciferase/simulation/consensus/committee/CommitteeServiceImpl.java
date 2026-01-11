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

import com.google.protobuf.Empty;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.CommitteeMigrationProposal;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.CommitteeServiceGrpc;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.CommitteeVote;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.QuorumAchieved;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * gRPC service implementation for committee-based consensus voting.
 *
 * Handles:
 * - Incoming migration proposals from proposer nodes
 * - Incoming votes from committee members
 * - Query results for completed proposals
 *
 * Converts proto messages to domain objects and delegates to ViewCommitteeConsensus and CommitteeVotingProtocol.
 *
 * Phase 7G Day 4: Proto & P2P Communication Layer
 *
 * @author hal.hildebrand
 */
public class CommitteeServiceImpl extends CommitteeServiceGrpc.CommitteeServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(CommitteeServiceImpl.class);

    private final ViewCommitteeConsensus consensus;
    private final CommitteeVotingProtocol votingProtocol;

    // Cache for proposal results (for GetQuorumResult queries)
    private final ConcurrentHashMap<String, AtomicReference<Boolean>> proposalResults = new ConcurrentHashMap<>();

    public CommitteeServiceImpl(ViewCommitteeConsensus consensus, CommitteeVotingProtocol votingProtocol) {
        this.consensus = consensus;
        this.votingProtocol = votingProtocol;
    }

    @Override
    public void submitMigrationProposal(CommitteeMigrationProposal proto, StreamObserver<Empty> responseObserver) {
        try {
            // Convert proto to domain object
            var proposal = CommitteeProtoConverter.fromProto(proto);
            var proposerAddress = CommitteeProtoConverter.getProposerAddress(proto);

            log.debug("Received migration proposal: proposalId={}, entity={}, proposer={}",
                     proposal.proposalId(), proposal.entityId(), proposerAddress);

            // Process proposal via consensus orchestrator
            var resultFuture = consensus.requestConsensus(proposal);

            // Store proposal ID for later result lookup
            resultFuture.whenComplete((result, ex) -> {
                if (ex == null) {
                    proposalResults.computeIfAbsent(proposal.proposalId().toString(),
                                                    k -> new AtomicReference<>()).set(result);
                    log.debug("Proposal {} consensus result: {}", proposal.proposalId(), result);
                } else {
                    log.warn("Proposal {} consensus failed", proposal.proposalId(), ex);
                }
            });

            // Send empty response immediately (voting happens asynchronously)
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error processing migration proposal", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void submitVote(CommitteeVote proto, StreamObserver<Empty> responseObserver) {
        try {
            // Convert proto to domain object
            var vote = CommitteeProtoConverter.fromProto(proto);

            log.debug("Received vote: proposalId={}, voter={}, approved={}, viewId={}",
                     vote.proposalId(), vote.voterId(), vote.approved(), vote.viewId());

            // Submit vote to voting protocol
            votingProtocol.recordVote(vote);

            // Send empty response
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error processing vote", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void getQuorumResult(CommitteeMigrationProposal proto, StreamObserver<QuorumAchieved> responseObserver) {
        try {
            var proposal = CommitteeProtoConverter.fromProto(proto);
            var proposalId = proposal.proposalId().toString();

            // Look up cached result
            var resultRef = proposalResults.get(proposalId);
            if (resultRef != null && resultRef.get() != null) {
                var result = resultRef.get();
                var response = QuorumAchieved.newBuilder()
                    .setProposalId(proposalId)
                    .setResult(result)
                    .setViewId(CommitteeProtoConverter.digestToHex(proposal.viewId()))
                    .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } else {
                // Result not yet available
                responseObserver.onError(new RuntimeException("Proposal result not yet available: " + proposalId));
            }

        } catch (Exception e) {
            log.error("Error querying quorum result", e);
            responseObserver.onError(e);
        }
    }

    /**
     * Clear cached results (useful for testing).
     */
    public void clearResultCache() {
        proposalResults.clear();
    }

    /**
     * Get number of cached results (for testing).
     */
    public int getCachedResultCount() {
        return proposalResults.size();
    }
}
