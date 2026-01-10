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
 * Unit tests for Vote immutable record.
 *
 * Tests cover:
 * - Vote immutability (record semantics)
 * - View ID matching with proposal
 * - Boolean voting (approved/rejected)
 *
 * @author hal.hildebrand
 */
class VoteTest {

    @Test
    void testVoteImmutability() {
        // Given: A vote
        var proposalId = UUID.randomUUID();
        var voterId = DigestAlgorithm.DEFAULT.random();
        var approved = true;
        var viewId = DigestAlgorithm.DEFAULT.random();

        // When: Created as record
        var vote = new Vote(proposalId, voterId, approved, viewId);

        // Then: All fields accessible and immutable (record contract)
        assertEquals(proposalId, vote.proposalId());
        assertEquals(voterId, vote.voterId());
        assertEquals(approved, vote.approved());
        assertEquals(viewId, vote.viewId());
    }

    @Test
    void testVoteViewIdMatching() {
        // Given: Proposal and vote with matching view IDs
        var proposalId = UUID.randomUUID();
        var voterId = DigestAlgorithm.DEFAULT.random();
        var viewId = DigestAlgorithm.DEFAULT.random();

        // When: Vote created with same view ID as proposal
        var vote = new Vote(proposalId, voterId, true, viewId);

        // Then: Vote has correct view ID (prevents cross-view race)
        assertEquals(viewId, vote.viewId());
    }

    @Test
    void testBooleanVoting() {
        // Given: A proposal
        var proposalId = UUID.randomUUID();
        var voterId = DigestAlgorithm.DEFAULT.random();
        var viewId = DigestAlgorithm.DEFAULT.random();

        // When: Creating approved and rejected votes
        var approvedVote = new Vote(proposalId, voterId, true, viewId);
        var rejectedVote = new Vote(proposalId, voterId, false, viewId);

        // Then: Votes have correct approval status
        assertTrue(approvedVote.approved());
        assertFalse(rejectedVote.approved());
    }

    @Test
    void testVoteEquality() {
        // Given: Identical votes
        var proposalId = UUID.randomUUID();
        var voterId = DigestAlgorithm.DEFAULT.random();
        var viewId = DigestAlgorithm.DEFAULT.random();

        // When: Two votes with same data
        var vote1 = new Vote(proposalId, voterId, true, viewId);
        var vote2 = new Vote(proposalId, voterId, true, viewId);

        // Then: Equal and same hash code (record semantics)
        assertEquals(vote1, vote2);
        assertEquals(vote1.hashCode(), vote2.hashCode());
    }

    @Test
    void testVoteDifferentViewIds() {
        // Given: Same vote in different views
        var proposalId = UUID.randomUUID();
        var voterId = DigestAlgorithm.DEFAULT.random();
        var viewId1 = DigestAlgorithm.DEFAULT.random();
        var viewId2 = DigestAlgorithm.DEFAULT.random();

        // When: Votes created in different views
        var vote1 = new Vote(proposalId, voterId, true, viewId1);
        var vote2 = new Vote(proposalId, voterId, true, viewId2);

        // Then: Votes are different (prevent cross-view race condition)
        assertNotEquals(vote1, vote2);
    }
}
