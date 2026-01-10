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

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import org.junit.jupiter.api.Test;

import java.util.SequencedSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ViewCommitteeSelector.
 *
 * Tests cover:
 * - Deterministic selection (same view ID → same committee)
 * - Consistency across nodes (all nodes get identical committee)
 * - Empty view handling (minimal membership)
 * - BFT tolerance preserved (committee size respects bftSubset constraints)
 * - View change updates committee
 *
 * @author hal.hildebrand
 */
class ViewCommitteeSelectorTest {

    @Test
    void testDeterministicSelection() {
        // Given: A context and selector
        var context = mockContext();
        var viewDiadem = DigestAlgorithm.DEFAULT.random();
        var committee1 = mock(SequencedSet.class);
        when(context.bftSubset(viewDiadem)).thenReturn((SequencedSet) committee1);

        var selector = new ViewCommitteeSelector(context);

        // When: Selecting committee twice with same view ID
        var result1 = selector.selectCommittee(viewDiadem);
        var result2 = selector.selectCommittee(viewDiadem);

        // Then: Same committee returned (deterministic)
        assertSame(result1, result2, "Same view ID must produce same committee");
        verify(context, times(2)).bftSubset(viewDiadem);
    }

    @Test
    void testConsistencyAcrossNodes() {
        // Given: Three nodes with same context and view ID
        var context1 = mockContext();
        var context2 = mockContext();
        var context3 = mockContext();
        var viewDiadem = DigestAlgorithm.DEFAULT.random();
        var sharedCommittee = mock(SequencedSet.class);

        // All contexts return same committee for same view ID
        when(context1.bftSubset(viewDiadem)).thenReturn((SequencedSet) sharedCommittee);
        when(context2.bftSubset(viewDiadem)).thenReturn((SequencedSet) sharedCommittee);
        when(context3.bftSubset(viewDiadem)).thenReturn((SequencedSet) sharedCommittee);

        var selector1 = new ViewCommitteeSelector(context1);
        var selector2 = new ViewCommitteeSelector(context2);
        var selector3 = new ViewCommitteeSelector(context3);

        // When: All nodes select committee
        var result1 = selector1.selectCommittee(viewDiadem);
        var result2 = selector2.selectCommittee(viewDiadem);
        var result3 = selector3.selectCommittee(viewDiadem);

        // Then: All get identical committee
        assertSame(result1, result2, "Node 1 and 2 must get same committee");
        assertSame(result2, result3, "Node 2 and 3 must get same committee");
    }

    @Test
    void testEmptyViewHandling() {
        // Given: Context with minimal membership (size == 1)
        var context = mockContext();
        when(context.size()).thenReturn(1);
        var viewDiadem = DigestAlgorithm.DEFAULT.random();
        var emptyCommittee = mock(SequencedSet.class);
        when(context.bftSubset(viewDiadem)).thenReturn((SequencedSet) emptyCommittee);

        var selector = new ViewCommitteeSelector(context);

        // When: Selecting committee with minimal membership
        var result = selector.selectCommittee(viewDiadem);

        // Then: Returns committee (even if empty/minimal)
        assertNotNull(result, "Committee must be returned even with minimal membership");
        verify(context).bftSubset(viewDiadem);
    }

    @Test
    void testBftTolerancePreserved() {
        // Given: Context with BFT tolerance level
        var context = mockContext();
        when(context.toleranceLevel()).thenReturn(2);  // f=2 Byzantine nodes
        when(context.size()).thenReturn(7);  // 7 nodes total
        var viewDiadem = DigestAlgorithm.DEFAULT.random();
        var committee = mock(SequencedSet.class);
        when(committee.size()).thenReturn(3);  // quorum = f+1 = 3
        when(context.bftSubset(viewDiadem)).thenReturn((SequencedSet) committee);

        var selector = new ViewCommitteeSelector(context);

        // When: Selecting committee
        var result = selector.selectCommittee(viewDiadem);

        // Then: Committee size respects BFT constraints (bftSubset ensures this)
        assertNotNull(result);
        assertEquals(3, result.size(), "Committee size should be f+1 for BFT quorum");
    }

    @Test
    void testViewChangeUpdatesCommittee() {
        // Given: Context and two different view IDs
        var context = mockContext();
        var viewDiadem1 = DigestAlgorithm.DEFAULT.random();
        var viewDiadem2 = DigestAlgorithm.DEFAULT.random();
        var committee1 = mock(SequencedSet.class);
        var committee2 = mock(SequencedSet.class);
        when(context.bftSubset(viewDiadem1)).thenReturn((SequencedSet) committee1);
        when(context.bftSubset(viewDiadem2)).thenReturn((SequencedSet) committee2);

        var selector = new ViewCommitteeSelector(context);

        // When: Selecting committee with different view IDs
        var result1 = selector.selectCommittee(viewDiadem1);
        var result2 = selector.selectCommittee(viewDiadem2);

        // Then: Different committees returned (or possibly same size, but different calls)
        assertNotSame(result1, result2, "Different view IDs should query bftSubset separately");
        verify(context).bftSubset(viewDiadem1);
        verify(context).bftSubset(viewDiadem2);
    }

    // Helper method to create mock DynamicContext
    @SuppressWarnings("unchecked")
    private DynamicContext<Member> mockContext() {
        return (DynamicContext<Member>) mock(DynamicContext.class);
    }
}
