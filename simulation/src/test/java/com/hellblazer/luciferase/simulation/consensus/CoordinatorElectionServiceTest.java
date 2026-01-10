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

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CoordinatorElectionService protocol buffer messages.
 *
 * Validates message creation, serialization, and field access for:
 * - VoteRequest/VoteResponse
 * - HeartbeatRequest/HeartbeatResponse
 * - BallotProposal/BallotVote
 *
 * @author hal.hildebrand
 */
class CoordinatorElectionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorElectionServiceTest.class);

    /**
     * Verify VoteRequest message creation with all required fields.
     */
    @Test
    void testVoteRequestMessageCreation() {
        var candidateId = UUID.randomUUID().toString();
        var term = 5L;
        var lastLogIndex = 100L;
        var lastLogTerm = 4L;

        var request = VoteRequest.newBuilder()
            .setVersion("1.0")
            .setCandidateId(candidateId)
            .setTerm(term)
            .setLastLogIndex(lastLogIndex)
            .setLastLogTerm(lastLogTerm)
            .build();

        assertThat(request).isNotNull();
        assertThat(request.getVersion()).isEqualTo("1.0");
        assertThat(request.getCandidateId()).isEqualTo(candidateId);
        assertThat(request.getTerm()).isEqualTo(term);
        assertThat(request.getLastLogIndex()).isEqualTo(lastLogIndex);
        assertThat(request.getLastLogTerm()).isEqualTo(lastLogTerm);
    }

    /**
     * Verify VoteResponse message creation with all required fields.
     */
    @Test
    void testVoteResponseMessageCreation() {
        var term = 5L;
        var voteGranted = true;
        var reason = "Candidate log is up-to-date";

        var response = VoteResponse.newBuilder()
            .setVersion("1.0")
            .setTerm(term)
            .setVoteGranted(voteGranted)
            .setReason(reason)
            .build();

        assertThat(response).isNotNull();
        assertThat(response.getVersion()).isEqualTo("1.0");
        assertThat(response.getTerm()).isEqualTo(term);
        assertThat(response.getVoteGranted()).isTrue();
        assertThat(response.getReason()).isEqualTo(reason);
    }

    /**
     * Verify HeartbeatRequest message creation with all required fields.
     */
    @Test
    void testHeartbeatRequestMessageCreation() {
        var leaderId = UUID.randomUUID().toString();
        var term = 10L;
        var leaderCommit = 200L;
        var prevLogIndex = 199L;
        var prevLogTerm = 9L;

        var entry = LogEntry.newBuilder()
            .setIndex(200L)
            .setTerm(10L)
            .setEntryType("VOTE")
            .setData(ByteString.copyFromUtf8("test data"))
            .build();

        var request = HeartbeatRequest.newBuilder()
            .setVersion("1.0")
            .setLeaderId(leaderId)
            .setTerm(term)
            .setLeaderCommit(leaderCommit)
            .addEntries(entry)
            .setPrevLogIndex(prevLogIndex)
            .setPrevLogTerm(prevLogTerm)
            .build();

        assertThat(request).isNotNull();
        assertThat(request.getVersion()).isEqualTo("1.0");
        assertThat(request.getLeaderId()).isEqualTo(leaderId);
        assertThat(request.getTerm()).isEqualTo(term);
        assertThat(request.getLeaderCommit()).isEqualTo(leaderCommit);
        assertThat(request.getEntriesCount()).isEqualTo(1);
        assertThat(request.getEntries(0).getIndex()).isEqualTo(200L);
        assertThat(request.getPrevLogIndex()).isEqualTo(prevLogIndex);
        assertThat(request.getPrevLogTerm()).isEqualTo(prevLogTerm);
    }

    /**
     * Verify HeartbeatResponse message creation with all required fields.
     */
    @Test
    void testHeartbeatResponseMessageCreation() {
        var term = 10L;
        var success = true;
        var matchIndex = 200L;
        var reason = "Heartbeat accepted";

        var response = HeartbeatResponse.newBuilder()
            .setVersion("1.0")
            .setTerm(term)
            .setSuccess(success)
            .setMatchIndex(matchIndex)
            .setReason(reason)
            .build();

        assertThat(response).isNotNull();
        assertThat(response.getVersion()).isEqualTo("1.0");
        assertThat(response.getTerm()).isEqualTo(term);
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getMatchIndex()).isEqualTo(matchIndex);
        assertThat(response.getReason()).isEqualTo(reason);
    }

    /**
     * Verify BallotProposal message creation with all required fields.
     */
    @Test
    void testBallotProposalMessageCreation() {
        var proposalId = UUID.randomUUID().toString();
        var term = 15L;
        var proposalType = "ENTITY_OWNERSHIP";
        var entityId = UUID.randomUUID().toString();
        var data = ByteString.copyFromUtf8("proposal data");
        var quorumSize = 3L;

        var proposal = BallotProposal.newBuilder()
            .setVersion("1.0")
            .setProposalId(proposalId)
            .setTerm(term)
            .setProposalType(proposalType)
            .setEntityId(entityId)
            .setData(data)
            .setQuorumSize(quorumSize)
            .build();

        assertThat(proposal).isNotNull();
        assertThat(proposal.getVersion()).isEqualTo("1.0");
        assertThat(proposal.getProposalId()).isEqualTo(proposalId);
        assertThat(proposal.getTerm()).isEqualTo(term);
        assertThat(proposal.getProposalType()).isEqualTo(proposalType);
        assertThat(proposal.getEntityId()).isEqualTo(entityId);
        assertThat(proposal.getData()).isEqualTo(data);
        assertThat(proposal.getQuorumSize()).isEqualTo(quorumSize);
    }

    /**
     * Verify BallotVote message creation with all required fields.
     */
    @Test
    void testBallotVoteMessageCreation() {
        var proposalId = UUID.randomUUID().toString();
        var voterId = UUID.randomUUID().toString();
        var term = 15L;
        var vote = true;
        var reason = "Proposal approved";

        var ballotVote = BallotVote.newBuilder()
            .setVersion("1.0")
            .setProposalId(proposalId)
            .setVoterId(voterId)
            .setTerm(term)
            .setVote(vote)
            .setReason(reason)
            .build();

        assertThat(ballotVote).isNotNull();
        assertThat(ballotVote.getVersion()).isEqualTo("1.0");
        assertThat(ballotVote.getProposalId()).isEqualTo(proposalId);
        assertThat(ballotVote.getVoterId()).isEqualTo(voterId);
        assertThat(ballotVote.getTerm()).isEqualTo(term);
        assertThat(ballotVote.getVote()).isTrue();
        assertThat(ballotVote.getReason()).isEqualTo(reason);
    }

    /**
     * Verify message serialization and deserialization round-trip works correctly.
     */
    @Test
    void testMessageSerializationRoundTrip() throws Exception {
        var candidateId = UUID.randomUUID().toString();
        var originalRequest = VoteRequest.newBuilder()
            .setVersion("1.0")
            .setCandidateId(candidateId)
            .setTerm(5L)
            .setLastLogIndex(100L)
            .setLastLogTerm(4L)
            .build();

        // Serialize to bytes
        var bytes = originalRequest.toByteArray();
        assertThat(bytes).isNotEmpty();

        // Deserialize back
        var deserializedRequest = VoteRequest.parseFrom(bytes);

        assertThat(deserializedRequest).isNotNull();
        assertThat(deserializedRequest.getVersion()).isEqualTo(originalRequest.getVersion());
        assertThat(deserializedRequest.getCandidateId()).isEqualTo(originalRequest.getCandidateId());
        assertThat(deserializedRequest.getTerm()).isEqualTo(originalRequest.getTerm());
        assertThat(deserializedRequest.getLastLogIndex()).isEqualTo(originalRequest.getLastLogIndex());
        assertThat(deserializedRequest.getLastLogTerm()).isEqualTo(originalRequest.getLastLogTerm());
    }

    /**
     * Verify messages can be created with empty/default values (proto3 behavior).
     */
    @Test
    void testNullMessageHandling() {
        // Proto3 doesn't have null - empty string and 0 are defaults
        var request = VoteRequest.newBuilder().build();

        assertThat(request).isNotNull();
        assertThat(request.getVersion()).isEmpty(); // Default empty string
        assertThat(request.getCandidateId()).isEmpty();
        assertThat(request.getTerm()).isEqualTo(0L); // Default 0
        assertThat(request.getLastLogIndex()).isEqualTo(0L);
        assertThat(request.getLastLogTerm()).isEqualTo(0L);
    }

    /**
     * Verify message fields have correct types and constraints.
     */
    @Test
    void testMessageFieldValidation() {
        var voteRequest = VoteRequest.newBuilder()
            .setVersion("1.0")
            .setCandidateId(UUID.randomUUID().toString())
            .setTerm(1L)
            .setLastLogIndex(0L)
            .setLastLogTerm(0L)
            .build();

        // Verify string fields
        assertThat(voteRequest.getVersion()).isInstanceOf(String.class);
        assertThat(voteRequest.getCandidateId()).isInstanceOf(String.class);

        // Verify long fields
        assertThat(voteRequest.getTerm()).isInstanceOf(Long.class);
        assertThat(voteRequest.getLastLogIndex()).isInstanceOf(Long.class);
        assertThat(voteRequest.getLastLogTerm()).isInstanceOf(Long.class);

        // Verify response boolean field
        var voteResponse = VoteResponse.newBuilder()
            .setVersion("1.0")
            .setTerm(1L)
            .setVoteGranted(true)
            .setReason("test")
            .build();

        assertThat(voteResponse.getVoteGranted()).isInstanceOf(Boolean.class);
    }

    /**
     * Verify CoordinatorElectionService gRPC service descriptor exists.
     */
    @Test
    void testServiceDefinerExists() {
        // Verify the service descriptor can be accessed
        var serviceDescriptor = CoordinatorElectionServiceGrpc.getServiceDescriptor();

        assertThat(serviceDescriptor).isNotNull();
        assertThat(serviceDescriptor.getName()).isEqualTo("lucien.CoordinatorElectionService");

        // Verify the service has expected RPC methods
        var methods = serviceDescriptor.getMethods();
        assertThat(methods).isNotEmpty();

        var methodNames = methods.stream()
            .map(m -> m.getBareMethodName())
            .toList();

        assertThat(methodNames).contains("RequestVote", "AppendHeartbeat", "GetLeader");
    }
}
