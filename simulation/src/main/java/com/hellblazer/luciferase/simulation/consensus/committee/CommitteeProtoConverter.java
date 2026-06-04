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
import com.hellblazer.delos.utils.Hex;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.CommitteeMigrationProposal;
import com.hellblazer.luciferase.simulation.consensus.committee.proto.CommitteeVote;

import java.util.UUID;

/**
 * Utility for converting between committee domain objects and protocol buffers.
 * <p>
 * Handles bidirectional conversion of:
 * - Digest ↔ hex string (for node IDs, view IDs)
 * - UUID ↔ string (for proposal IDs, entity IDs)
 * - MigrationProposal ↔ CommitteeMigrationProposal proto
 * - Vote ↔ CommitteeVote proto
 * <p>
 * Phase 7G Day 4: Proto & P2P Communication Layer
 *
 * @author hal.hildebrand
 */
public final class CommitteeProtoConverter {

    private CommitteeProtoConverter() {
        // Utility class - no instantiation
    }

    /**
     * Convert Digest to hex string for proto serialization.
     * <p>
     * The algorithm code is encoded as the first byte of the hex string so that {@link #hexToDigest} can
     * reconstruct the Digest with its original algorithm. {@code Digest.getBytes()} returns only the raw hash
     * bytes (no algorithm tag); hardcoding an algorithm on the way back silently breaks algorithm-aware
     * {@code Digest.equals()} for any non-DEFAULT cluster, causing votes to be discarded (Luciferase-0frcy.19).
     *
     * @param digest Digest to convert
     * @return Hex string representation: 1-byte algorithm code prefix followed by the raw hash bytes
     */
    public static String digestToHex(Digest digest) {
        if (digest == null) {
            throw new IllegalArgumentException("Digest cannot be null");
        }
        var hash = digest.getBytes();
        var tagged = new byte[hash.length + 1];
        tagged[0] = digest.getAlgorithm().digestCode();
        System.arraycopy(hash, 0, tagged, 1, hash.length);
        return Hex.hex(tagged);
    }

    /**
     * Convert hex string back to Digest, restoring the algorithm encoded by {@link #digestToHex}.
     *
     * @param hex Hex string from proto (1-byte algorithm code prefix followed by raw hash bytes)
     * @return Digest instance carrying the original algorithm
     */
    public static Digest hexToDigest(String hex) {
        if (hex == null || hex.isEmpty()) {
            throw new IllegalArgumentException("Hex string cannot be null or empty");
        }
        var tagged = Hex.unhex(hex);
        if (tagged.length < 1) {
            throw new IllegalArgumentException("Hex string too short to contain an algorithm code prefix");
        }
        var algorithm = DigestAlgorithm.fromDigestCode(tagged[0]);
        var hash = new byte[tagged.length - 1];
        System.arraycopy(tagged, 1, hash, 0, hash.length);
        return new Digest(algorithm, hash);
    }

    /**
     * Convert UUID to string for proto serialization.
     *
     * @param uuid UUID to convert
     * @return String representation
     */
    public static String uuidToString(UUID uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("UUID cannot be null");
        }
        return uuid.toString();
    }

    /**
     * Convert string back to UUID.
     *
     * @param str String from proto
     * @return UUID instance
     */
    public static UUID stringToUuid(String str) {
        if (str == null || str.isEmpty()) {
            throw new IllegalArgumentException("UUID string cannot be null or empty");
        }
        return UUID.fromString(str);
    }

    /**
     * Convert domain MigrationProposal to proto CommitteeMigrationProposal.
     *
     * @param proposal        Domain proposal object
     * @param proposerAddress gRPC address of proposer (for vote replies)
     * @return Proto message
     */
    public static CommitteeMigrationProposal toProto(MigrationProposal proposal, String proposerAddress) {
        if (proposal == null) {
            throw new IllegalArgumentException("Proposal cannot be null");
        }
        if (proposerAddress == null || proposerAddress.isEmpty()) {
            throw new IllegalArgumentException("Proposer address cannot be null or empty");
        }

        return CommitteeMigrationProposal.newBuilder()
            .setProposalId(uuidToString(proposal.proposalId()))
            .setEntityId(uuidToString(proposal.entityId()))
            .setSourceNodeId(digestToHex(proposal.sourceNodeId()))
            .setTargetNodeId(digestToHex(proposal.targetNodeId()))
            .setProposerAddress(proposerAddress)
            .setViewId(digestToHex(proposal.viewId()))
            .setTimestamp(proposal.timestamp())
            .build();
    }

    /**
     * Convert proto CommitteeMigrationProposal back to domain MigrationProposal.
     *
     * @param proto Proto message
     * @return Domain proposal object
     */
    public static MigrationProposal fromProto(CommitteeMigrationProposal proto) {
        if (proto == null) {
            throw new IllegalArgumentException("Proto cannot be null");
        }

        return new MigrationProposal(
            stringToUuid(proto.getProposalId()),
            stringToUuid(proto.getEntityId()),
            hexToDigest(proto.getSourceNodeId()),
            hexToDigest(proto.getTargetNodeId()),
            hexToDigest(proto.getViewId()),
            proto.getTimestamp()
        );
    }

    /**
     * Convert domain Vote to proto CommitteeVote.
     *
     * @param vote Domain vote object
     * @return Proto message
     */
    public static CommitteeVote toProto(Vote vote) {
        if (vote == null) {
            throw new IllegalArgumentException("Vote cannot be null");
        }

        return CommitteeVote.newBuilder()
            .setProposalId(uuidToString(vote.proposalId()))
            .setVoterId(digestToHex(vote.voterId()))
            .setApproved(vote.approved())
            .setViewId(digestToHex(vote.viewId()))
            .build();
    }

    /**
     * Convert proto CommitteeVote back to domain Vote.
     *
     * @param proto Proto message
     * @return Domain vote object
     */
    public static Vote fromProto(CommitteeVote proto) {
        if (proto == null) {
            throw new IllegalArgumentException("Proto cannot be null");
        }

        return new Vote(
            stringToUuid(proto.getProposalId()),
            hexToDigest(proto.getVoterId()),
            proto.getApproved(),
            hexToDigest(proto.getViewId())
        );
    }

    /**
     * Extract proposer address from proto CommitteeMigrationProposal.
     *
     * @param proto Proto message
     * @return Proposer gRPC address
     */
    public static String getProposerAddress(CommitteeMigrationProposal proto) {
        if (proto == null) {
            throw new IllegalArgumentException("Proto cannot be null");
        }
        return proto.getProposerAddress();
    }
}
