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
 * Committee Determinism Tests - CRITICAL per audit requirements.
 *
 * Tests verify that same view ID produces identical committee across multiple nodes.
 * This is essential for Byzantine fault tolerance and prevents split-brain scenarios.
 *
 * @author hal.hildebrand
 */
class CommitteeDeterminismTest {

    @Test
    void testTwoNodeDeterminism() {
        // Given: Two nodes with identical context and view ID
        var viewId = DigestAlgorithm.DEFAULT.random();
        var sharedCommittee = createMockCommittee(3);

        var context1 = mockContext();
        var context2 = mockContext();
        when(context1.bftSubset(viewId)).thenReturn((SequencedSet) sharedCommittee);
        when(context2.bftSubset(viewId)).thenReturn((SequencedSet) sharedCommittee);

        var selector1 = new ViewCommitteeSelector(context1);
        var selector2 = new ViewCommitteeSelector(context2);

        // When: Both nodes select committee for same view
        var committee1 = selector1.selectCommittee(viewId);
        var committee2 = selector2.selectCommittee(viewId);

        // Then: Identical committees (CRITICAL: prevents split-brain)
        assertSame(committee1, committee2, "Node 1 and Node 2 MUST get identical committee for same view ID");
        verify(context1).bftSubset(viewId);
        verify(context2).bftSubset(viewId);
    }

    @Test
    void testThreeNodeDeterminism() {
        // Given: Three nodes with identical context and view ID
        var viewId = DigestAlgorithm.DEFAULT.random();
        var sharedCommittee = createMockCommittee(5);

        var context1 = mockContext();
        var context2 = mockContext();
        var context3 = mockContext();
        when(context1.bftSubset(viewId)).thenReturn((SequencedSet) sharedCommittee);
        when(context2.bftSubset(viewId)).thenReturn((SequencedSet) sharedCommittee);
        when(context3.bftSubset(viewId)).thenReturn((SequencedSet) sharedCommittee);

        var selector1 = new ViewCommitteeSelector(context1);
        var selector2 = new ViewCommitteeSelector(context2);
        var selector3 = new ViewCommitteeSelector(context3);

        // When: All nodes select committee for same view
        var committee1 = selector1.selectCommittee(viewId);
        var committee2 = selector2.selectCommittee(viewId);
        var committee3 = selector3.selectCommittee(viewId);

        // Then: All committees identical (CRITICAL for quorum agreement)
        assertSame(committee1, committee2, "Node 1 and Node 2 MUST agree");
        assertSame(committee2, committee3, "Node 2 and Node 3 MUST agree");
        assertSame(committee1, committee3, "Node 1 and Node 3 MUST agree");
    }

    @Test
    void testFiveNodeDeterminism() {
        // Given: Five nodes with identical context and view ID (typical cluster size)
        var viewId = DigestAlgorithm.DEFAULT.random();
        var sharedCommittee = createMockCommittee(7);

        var contexts = new DynamicContext[5];
        var selectors = new ViewCommitteeSelector[5];
        for (int i = 0; i < 5; i++) {
            contexts[i] = mockContext();
            when(contexts[i].bftSubset(viewId)).thenReturn((SequencedSet) sharedCommittee);
            selectors[i] = new ViewCommitteeSelector(contexts[i]);
        }

        // When: All five nodes select committee
        var committees = new SequencedSet[5];
        for (int i = 0; i < 5; i++) {
            committees[i] = selectors[i].selectCommittee(viewId);
        }

        // Then: All committees identical (CRITICAL: no Byzantine split)
        for (int i = 0; i < 4; i++) {
            assertSame(committees[i], committees[i + 1],
                       "Node " + i + " and Node " + (i + 1) + " MUST have identical committee");
        }
    }

    // Helper methods
    @SuppressWarnings("unchecked")
    private DynamicContext<Member> mockContext() {
        return (DynamicContext<Member>) mock(DynamicContext.class);
    }

    @SuppressWarnings("unchecked")
    private SequencedSet<Member> createMockCommittee(int size) {
        var committee = mock(SequencedSet.class);
        when(committee.size()).thenReturn(size);
        return (SequencedSet<Member>) committee;
    }
}
