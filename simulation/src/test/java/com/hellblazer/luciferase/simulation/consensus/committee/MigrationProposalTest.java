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
 * Unit tests for MigrationProposal immutable record.
 *
 * Tests cover:
 * - Immutability (record semantics)
 * - View ID prevents cross-view race conditions
 * - Equality and hashing for collections
 *
 * @author hal.hildebrand
 */
class MigrationProposalTest {

    @Test
    void testImmutability() {
        // Given: A migration proposal
        var proposalId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var sourceNodeId = DigestAlgorithm.DEFAULT.random();
        var targetNodeId = DigestAlgorithm.DEFAULT.random();
        var viewId = DigestAlgorithm.DEFAULT.random();
        var timestamp = System.currentTimeMillis();

        // When: Created as record
        var proposal = new MigrationProposal(proposalId, entityId, sourceNodeId, targetNodeId, viewId, timestamp);

        // Then: All fields accessible and immutable (record contract)
        assertEquals(proposalId, proposal.proposalId());
        assertEquals(entityId, proposal.entityId());
        assertEquals(sourceNodeId, proposal.sourceNodeId());
        assertEquals(targetNodeId, proposal.targetNodeId());
        assertEquals(viewId, proposal.viewId());
        assertEquals(timestamp, proposal.timestamp());
    }

    @Test
    void testViewIdPrevention() {
        // Given: Same proposal with different view IDs
        var proposalId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var sourceNodeId = DigestAlgorithm.DEFAULT.random();
        var targetNodeId = DigestAlgorithm.DEFAULT.random();
        var viewId1 = DigestAlgorithm.DEFAULT.random();
        var viewId2 = DigestAlgorithm.DEFAULT.random();

        // When: Proposals created in different views
        var proposal1 = new MigrationProposal(proposalId, entityId, sourceNodeId, targetNodeId, viewId1, 1000L);
        var proposal2 = new MigrationProposal(proposalId, entityId, sourceNodeId, targetNodeId, viewId2, 1000L);

        // Then: Proposals are different (prevent cross-view race condition)
        assertNotEquals(proposal1, proposal2, "Same proposal ID in different views must be distinct");
        assertNotEquals(proposal1.hashCode(), proposal2.hashCode());
    }

    @Test
    void testEqualityAndHashing() {
        // Given: Identical proposals
        var proposalId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var sourceNodeId = DigestAlgorithm.DEFAULT.random();
        var targetNodeId = DigestAlgorithm.DEFAULT.random();
        var viewId = DigestAlgorithm.DEFAULT.random();
        var timestamp = 1000L;

        // When: Two proposals with same data
        var proposal1 = new MigrationProposal(proposalId, entityId, sourceNodeId, targetNodeId, viewId, timestamp);
        var proposal2 = new MigrationProposal(proposalId, entityId, sourceNodeId, targetNodeId, viewId, timestamp);

        // Then: Equal and same hash code (record semantics)
        assertEquals(proposal1, proposal2);
        assertEquals(proposal1.hashCode(), proposal2.hashCode());
    }

    @Test
    void testDifferentProposalIds() {
        // Given: Same migration with different proposal IDs
        var proposalId1 = UUID.randomUUID();
        var proposalId2 = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var sourceNodeId = DigestAlgorithm.DEFAULT.random();
        var targetNodeId = DigestAlgorithm.DEFAULT.random();
        var viewId = DigestAlgorithm.DEFAULT.random();

        // When: Proposals with different IDs
        var proposal1 = new MigrationProposal(proposalId1, entityId, sourceNodeId, targetNodeId, viewId, 1000L);
        var proposal2 = new MigrationProposal(proposalId2, entityId, sourceNodeId, targetNodeId, viewId, 1000L);

        // Then: Proposals are different
        assertNotEquals(proposal1, proposal2);
    }
}
