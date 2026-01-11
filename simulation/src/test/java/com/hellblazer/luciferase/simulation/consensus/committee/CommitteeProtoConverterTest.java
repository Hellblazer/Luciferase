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
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CommitteeProtoConverter - bidirectional proto serialization.
 * <p>
 * Phase 7G Day 4: Proto & P2P Communication Layer
 *
 * @author hal.hildebrand
 */
class CommitteeProtoConverterTest {

    @Test
    void testDigestToHexRoundTrip() {
        // Create a random digest
        var originalDigest = DigestAlgorithm.DEFAULT.random();

        // Convert to hex and back
        var hex = CommitteeProtoConverter.digestToHex(originalDigest);
        var reconstructed = CommitteeProtoConverter.hexToDigest(hex);

        // Verify round-trip works
        assertNotNull(hex, "Hex string should not be null");
        assertFalse(hex.isEmpty(), "Hex string should not be empty");
        assertEquals(originalDigest, reconstructed, "Digest should survive round-trip conversion");
    }

    @Test
    void testDigestToHexNullHandling() {
        // Null digest should throw
        assertThrows(IllegalArgumentException.class, () -> CommitteeProtoConverter.digestToHex(null));
    }

    @Test
    void testHexToDigestNullHandling() {
        // Null hex string should throw
        assertThrows(IllegalArgumentException.class, () -> CommitteeProtoConverter.hexToDigest(null));
        assertThrows(IllegalArgumentException.class, () -> CommitteeProtoConverter.hexToDigest(""));
    }

    @Test
    void testUuidToStringRoundTrip() {
        // Create a random UUID
        var originalUuid = UUID.randomUUID();

        // Convert to string and back
        var str = CommitteeProtoConverter.uuidToString(originalUuid);
        var reconstructed = CommitteeProtoConverter.stringToUuid(str);

        // Verify round-trip works
        assertNotNull(str, "String should not be null");
        assertFalse(str.isEmpty(), "String should not be empty");
        assertEquals(originalUuid, reconstructed, "UUID should survive round-trip conversion");
    }

    @Test
    void testUuidToStringNullHandling() {
        // Null UUID should throw
        assertThrows(IllegalArgumentException.class, () -> CommitteeProtoConverter.uuidToString(null));
    }

    @Test
    void testStringToUuidNullHandling() {
        // Null string should throw
        assertThrows(IllegalArgumentException.class, () -> CommitteeProtoConverter.stringToUuid(null));
        assertThrows(IllegalArgumentException.class, () -> CommitteeProtoConverter.stringToUuid(""));
    }

    @Test
    void testMigrationProposalSerialization() {
        // Create a domain MigrationProposal
        var proposalId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var sourceNodeId = DigestAlgorithm.DEFAULT.random();
        var targetNodeId = DigestAlgorithm.DEFAULT.random();
        var viewId = DigestAlgorithm.DEFAULT.random();
        var timestamp = System.currentTimeMillis();
        var proposerAddress = "localhost:12345";

        var proposal = new MigrationProposal(proposalId, entityId, sourceNodeId, targetNodeId, viewId, timestamp);

        // Convert to proto and back
        var proto = CommitteeProtoConverter.toProto(proposal, proposerAddress);
        var reconstructed = CommitteeProtoConverter.fromProto(proto);

        // Verify all fields preserved
        assertEquals(proposal.proposalId(), reconstructed.proposalId(), "Proposal ID should be preserved");
        assertEquals(proposal.entityId(), reconstructed.entityId(), "Entity ID should be preserved");
        assertEquals(proposal.sourceNodeId(), reconstructed.sourceNodeId(), "Source node ID should be preserved");
        assertEquals(proposal.targetNodeId(), reconstructed.targetNodeId(), "Target node ID should be preserved");
        assertEquals(proposal.viewId(), reconstructed.viewId(), "View ID should be preserved");
        assertEquals(proposal.timestamp(), reconstructed.timestamp(), "Timestamp should be preserved");

        // Verify proposer address stored in proto
        assertEquals(proposerAddress, proto.getProposerAddress(), "Proposer address should be stored in proto");
        assertEquals(proposerAddress, CommitteeProtoConverter.getProposerAddress(proto),
                     "getProposerAddress should return correct address");
    }

    @Test
    void testMigrationProposalNullHandling() {
        // Null proposal should throw
        assertThrows(IllegalArgumentException.class,
                     () -> CommitteeProtoConverter.toProto(null, "localhost:12345"));

        // Null proposer address should throw
        var proposal = new MigrationProposal(UUID.randomUUID(), UUID.randomUUID(),
                                             DigestAlgorithm.DEFAULT.random(), DigestAlgorithm.DEFAULT.random(),
                                             DigestAlgorithm.DEFAULT.random(), System.currentTimeMillis());
        assertThrows(IllegalArgumentException.class, () -> CommitteeProtoConverter.toProto(proposal, null));
        assertThrows(IllegalArgumentException.class, () -> CommitteeProtoConverter.toProto(proposal, ""));

        // Null proto should throw
        assertThrows(IllegalArgumentException.class,
                     () -> CommitteeProtoConverter.fromProto((com.hellblazer.luciferase.simulation.consensus.committee.proto.CommitteeMigrationProposal) null));
    }

    @Test
    void testVoteSerialization() {
        // Create a domain Vote
        var proposalId = UUID.randomUUID();
        var voterId = DigestAlgorithm.DEFAULT.random();
        var approved = true;
        var viewId = DigestAlgorithm.DEFAULT.random();

        var vote = new Vote(proposalId, voterId, approved, viewId);

        // Convert to proto and back
        var proto = CommitteeProtoConverter.toProto(vote);
        var reconstructed = CommitteeProtoConverter.fromProto(proto);

        // Verify all fields preserved
        assertEquals(vote.proposalId(), reconstructed.proposalId(), "Proposal ID should be preserved");
        assertEquals(vote.voterId(), reconstructed.voterId(), "Voter ID should be preserved");
        assertEquals(vote.approved(), reconstructed.approved(), "Approved flag should be preserved");
        assertEquals(vote.viewId(), reconstructed.viewId(), "View ID should be preserved");
    }

    @Test
    void testVoteSerializationWithFalseApproval() {
        // Create a NO vote
        var vote = new Vote(UUID.randomUUID(), DigestAlgorithm.DEFAULT.random(), false,
                            DigestAlgorithm.DEFAULT.random());

        // Convert to proto and back
        var proto = CommitteeProtoConverter.toProto(vote);
        var reconstructed = CommitteeProtoConverter.fromProto(proto);

        // Verify false approval preserved
        assertFalse(reconstructed.approved(), "False approval should be preserved");
    }

    @Test
    void testVoteNullHandling() {
        // Null vote should throw
        assertThrows(IllegalArgumentException.class, () -> CommitteeProtoConverter.toProto((Vote) null));

        // Null proto should throw
        assertThrows(IllegalArgumentException.class,
                     () -> CommitteeProtoConverter.fromProto((com.hellblazer.luciferase.simulation.consensus.committee.proto.CommitteeVote) null));
    }

    @Test
    void testViewIdPreservedAcrossSerialization() {
        // This is critical - viewId must survive proto conversion to prevent double-commit bugs
        var viewId = DigestAlgorithm.DEFAULT.random();

        var proposal = new MigrationProposal(UUID.randomUUID(), UUID.randomUUID(),
                                             DigestAlgorithm.DEFAULT.random(), DigestAlgorithm.DEFAULT.random(),
                                             viewId, System.currentTimeMillis());

        var proto = CommitteeProtoConverter.toProto(proposal, "localhost:12345");
        var reconstructed = CommitteeProtoConverter.fromProto(proto);

        // Verify viewId byte-for-byte identical
        assertEquals(viewId, reconstructed.viewId(), "View ID must be preserved exactly to prevent double-commit");
        assertArrayEquals(viewId.getBytes(), reconstructed.viewId().getBytes(),
                          "View ID bytes must match exactly");
    }
}
