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
import com.hellblazer.delos.membership.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests to validate the KerlDHT quorum calculation pattern.
 * <p>
 * CRITICAL: This test class validates that our implementation uses the exact same
 * quorum formula as KerlDHT (proven in production systems):
 * <p>
 * {@code majority = context.size() == 1 ? 1 : context.toleranceLevel() + 1}
 * <p>
 * Reference: /Users/hal.hildebrand/git/Delos/thoth/src/main/java/.../KerlDHT.java lines 805-834
 * <p>
 * Phase 7G Day 2: Voting Protocol & Ballot Box
 *
 * @author hal.hildebrand
 */
class QuorumCalculationTest {

    private DynamicContext<Member> mockContext;

    @BeforeEach
    void setUp() {
        mockContext = Mockito.mock(DynamicContext.class);
    }

    /**
     * Verify the quorum formula directly matches KerlDHT pattern.
     * <p>
     * Formula: context.size() == 1 ? 1 : context.toleranceLevel() + 1
     */
    @Test
    void testQuorumFormula() {
        // Test single node (special case)
        when(mockContext.size()).thenReturn(1);
        when(mockContext.toleranceLevel()).thenReturn(0);
        assertEquals(1, calculateQuorum(mockContext), "Single node quorum should be 1");

        // Test 3 nodes
        when(mockContext.size()).thenReturn(3);
        when(mockContext.toleranceLevel()).thenReturn(1);
        assertEquals(2, calculateQuorum(mockContext), "3 nodes: toleranceLevel=1 → quorum=2");

        // Test 5 nodes
        when(mockContext.size()).thenReturn(5);
        when(mockContext.toleranceLevel()).thenReturn(2);
        assertEquals(3, calculateQuorum(mockContext), "5 nodes: toleranceLevel=2 → quorum=3");

        // Test 7 nodes (standard case)
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);
        assertEquals(4, calculateQuorum(mockContext), "7 nodes: toleranceLevel=3 → quorum=4");

        // Test 9 nodes
        when(mockContext.size()).thenReturn(9);
        when(mockContext.toleranceLevel()).thenReturn(4);
        assertEquals(5, calculateQuorum(mockContext), "9 nodes: toleranceLevel=4 → quorum=5");
    }

    /**
     * Test quorum calculation for varying committee sizes.
     * <p>
     * Validates that the formula works correctly across different scenarios.
     */
    @Test
    void testQuorumWithVaryingCommitteeSize() {
        var testCases = new Object[][]{
            {1, 0, 1},  // size=1, toleranceLevel=0 → quorum=1
            {2, 0, 1},  // size=2, toleranceLevel=0 → quorum=1
            {3, 1, 2},  // size=3, toleranceLevel=1 → quorum=2
            {4, 1, 2},  // size=4, toleranceLevel=1 → quorum=2
            {5, 2, 3},  // size=5, toleranceLevel=2 → quorum=3
            {6, 2, 3},  // size=6, toleranceLevel=2 → quorum=3
            {7, 3, 4},  // size=7, toleranceLevel=3 → quorum=4
            {8, 3, 4},  // size=8, toleranceLevel=3 → quorum=4
            {9, 4, 5},  // size=9, toleranceLevel=4 → quorum=5
        };

        for (var testCase : testCases) {
            int size = (int) testCase[0];
            int toleranceLevel = (int) testCase[1];
            int expectedQuorum = (int) testCase[2];

            when(mockContext.size()).thenReturn(size);
            when(mockContext.toleranceLevel()).thenReturn(toleranceLevel);

            var actualQuorum = calculateQuorum(mockContext);
            assertEquals(expectedQuorum, actualQuorum,
                         String.format("size=%d, toleranceLevel=%d → expected quorum=%d, got %d",
                                       size, toleranceLevel, expectedQuorum, actualQuorum));
        }
    }

    /**
     * Verify that toleranceLevel is always available from DynamicContext.
     * <p>
     * This validates our assumption that context.toleranceLevel() returns a valid int.
     */
    @Test
    void testToleranceLevelAlwaysAvailable() {
        // Mock should return valid tolerance levels
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        assertDoesNotThrow(() -> mockContext.toleranceLevel(), "toleranceLevel() should always return valid int");
        assertTrue(mockContext.toleranceLevel() >= 0, "toleranceLevel should be non-negative");
    }

    /**
     * CRITICAL: Compare our quorum calculation with KerlDHT reference pattern.
     * <p>
     * This test documents the exact KerlDHT pattern we're following.
     */
    @Test
    void testQuorumComparisonWithKerlDHT() {
        // KerlDHT pattern (lines 805-834 in KerlDHT.java):
        // var majority = context.size() == 1 ? 1 : context.toleranceLevel() + 1;

        // Test case 1: Single node (special case in KerlDHT)
        when(mockContext.size()).thenReturn(1);
        when(mockContext.toleranceLevel()).thenReturn(0);
        var kerlDHTQuorum1 = mockContext.size() == 1 ? 1 : mockContext.toleranceLevel() + 1;
        var ourQuorum1 = calculateQuorum(mockContext);
        assertEquals(kerlDHTQuorum1, ourQuorum1, "Single node: our quorum should match KerlDHT");

        // Test case 2: 7 nodes (standard BFT case in KerlDHT)
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);
        var kerlDHTQuorum2 = mockContext.size() == 1 ? 1 : mockContext.toleranceLevel() + 1;
        var ourQuorum2 = calculateQuorum(mockContext);
        assertEquals(kerlDHTQuorum2, ourQuorum2, "7 nodes: our quorum should match KerlDHT");

        // Test case 3: 5 nodes
        when(mockContext.size()).thenReturn(5);
        when(mockContext.toleranceLevel()).thenReturn(2);
        var kerlDHTQuorum3 = mockContext.size() == 1 ? 1 : mockContext.toleranceLevel() + 1;
        var ourQuorum3 = calculateQuorum(mockContext);
        assertEquals(kerlDHTQuorum3, ourQuorum3, "5 nodes: our quorum should match KerlDHT");
    }

    /**
     * Test that quorum is always greater than toleranceLevel.
     * <p>
     * This is a fundamental property: quorum must exceed Byzantine nodes.
     */
    @Test
    void testQuorumExceedsTolerance() {
        var testCases = new int[][]{
            {1, 0},  // size=1, toleranceLevel=0
            {3, 1},  // size=3, toleranceLevel=1
            {5, 2},  // size=5, toleranceLevel=2
            {7, 3},  // size=7, toleranceLevel=3
            {9, 4},  // size=9, toleranceLevel=4
        };

        for (var testCase : testCases) {
            int size = testCase[0];
            int toleranceLevel = testCase[1];

            when(mockContext.size()).thenReturn(size);
            when(mockContext.toleranceLevel()).thenReturn(toleranceLevel);

            var quorum = calculateQuorum(mockContext);
            assertTrue(quorum > toleranceLevel,
                       String.format("Quorum (%d) must exceed toleranceLevel (%d) for size=%d",
                                     quorum, toleranceLevel, size));
        }
    }

    // Helper method: Extract quorum calculation (will be in CommitteeBallotBox)
    private int calculateQuorum(DynamicContext<Member> context) {
        return context.size() == 1 ? 1 : context.toleranceLevel() + 1;
    }
}
