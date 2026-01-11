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

package com.hellblazer.luciferase.simulation.consensus.committee.proto;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.simulation.consensus.committee.CommitteeProtoConverter;
import com.hellblazer.luciferase.simulation.consensus.committee.MigrationProposal;
import com.hellblazer.luciferase.simulation.consensus.committee.Vote;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CommitteeProtoConverter - verifies bidirectional conversion
 * between domain objects and proto messages.
 * <p>
 * Tests:
 * 1. Digest ↔ hex string round-trip
 * 2. UUID ↔ string round-trip
 * 3. MigrationProposal serialization/deserialization
 * 4. proposer_address field handling
 * <p>
 * Phase 7G Day 4: Proto & P2P Communication Layer
 *
 * @author hal.hildebrand
 */
class CommitteeProtoConverterTest {

    @Test
    void testDigestToHexRoundTrip() {
        // Given: A random Digest
        var originalDigest = DigestAlgorithm.DEFAULT.getOrigin();

        // When: Convert to hex and back
        var hexString = CommitteeProtoConverter.digestToHex(originalDigest);
        var roundTripped = CommitteeProtoConverter.hexToDigest(hexString);

        // Then: Should match original
        assertThat(roundTripped).isEqualTo(originalDigest);
        assertThat(hexString).isNotBlank();
    }

    @Test
    void testDigestToHexDifferentValues() {
        // Given: Two different Digests
        var digest1 = DigestAlgorithm.DEFAULT.getOrigin();
        var digest2 = DigestAlgorithm.DEFAULT.digest("test".getBytes());

        // When: Convert to hex
        var hex1 = CommitteeProtoConverter.digestToHex(digest1);
        var hex2 = CommitteeProtoConverter.digestToHex(digest2);

        // Then: Should produce different hex strings
        assertThat(hex1).isNotEqualTo(hex2);
    }

    @Test
    void testUuidToStringRoundTrip() {
        // Given: A random UUID
        var originalUuid = UUID.randomUUID();

        // When: Convert to string and back
        var uuidString = CommitteeProtoConverter.uuidToString(originalUuid);
        var roundTripped = CommitteeProtoConverter.stringToUuid(uuidString);

        // Then: Should match original
        assertThat(roundTripped).isEqualTo(originalUuid);
        assertThat(uuidString).isEqualTo(originalUuid.toString());
    }

    @Test
    void testMigrationProposalSerializationRoundTrip() {
        // Given: A MigrationProposal with all fields
        var proposalId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var sourceNodeId = DigestAlgorithm.DEFAULT.getOrigin();
        var targetNodeId = DigestAlgorithm.DEFAULT.digest("target".getBytes());
        var viewId = DigestAlgorithm.DEFAULT.digest("view123".getBytes());
        var timestamp = System.currentTimeMillis();

        var proposal = new MigrationProposal(
            proposalId,
            entityId,
            sourceNodeId,
            targetNodeId,
            viewId,
            timestamp
        );

        var proposerAddress = "localhost:8080";

        // When: Convert to proto and back
        var proto = CommitteeProtoConverter.toProto(proposal, proposerAddress);
        var roundTripped = CommitteeProtoConverter.fromProto(proto);

        // Then: All fields should match
        assertThat(roundTripped.proposalId()).isEqualTo(proposalId);
        assertThat(roundTripped.entityId()).isEqualTo(entityId);
        assertThat(roundTripped.sourceNodeId()).isEqualTo(sourceNodeId);
        assertThat(roundTripped.targetNodeId()).isEqualTo(targetNodeId);
        assertThat(roundTripped.viewId()).isEqualTo(viewId);
        assertThat(roundTripped.timestamp()).isEqualTo(timestamp);
    }

    @Test
    void testProposerAddressIncludedInProto() {
        // Given: A MigrationProposal and proposer address
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            DigestAlgorithm.DEFAULT.digest("target".getBytes()),
            DigestAlgorithm.DEFAULT.digest("view".getBytes()),
            System.currentTimeMillis()
        );

        var proposerAddress = "localhost:9090";

        // When: Convert to proto
        var proto = CommitteeProtoConverter.toProto(proposal, proposerAddress);

        // Then: proposer_address field should be set
        assertThat(proto.getProposerAddress()).isEqualTo(proposerAddress);
    }

    @Test
    void testViewIdPreservedThroughSerialization() {
        // Given: A proposal with specific viewId
        var viewId = DigestAlgorithm.DEFAULT.digest("critical-view-id".getBytes());
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.getOrigin(),
            DigestAlgorithm.DEFAULT.digest("target".getBytes()),
            viewId,
            System.currentTimeMillis()
        );

        var proposerAddress = "localhost:7070";

        // When: Serialize and deserialize
        var proto = CommitteeProtoConverter.toProto(proposal, proposerAddress);
        var deserialized = CommitteeProtoConverter.fromProto(proto);

        // Then: viewId must be exactly preserved (critical for race prevention)
        assertThat(deserialized.viewId()).isEqualTo(viewId);
    }
}
