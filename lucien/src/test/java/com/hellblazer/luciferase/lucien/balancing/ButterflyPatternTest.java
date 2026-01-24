/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.balancing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for ButterflyPattern utility class.
 *
 * <p>The butterfly pattern provides O(log P) communication complexity by having each partition
 * communicate with log(P) partners across all rounds using the formula: partner = rank XOR 2^round
 *
 * @author hal.hildebrand
 */
public class ButterflyPatternTest {

    @Test
    public void testPartnerCalculationRound0() {
        // Round 0: partner = rank XOR 1
        // (0,1), (2,3), (4,5), (6,7)
        assertEquals(1, ButterflyPattern.getPartner(0, 0, 8), "Rank 0 should partner with 1 in round 0");
        assertEquals(0, ButterflyPattern.getPartner(1, 0, 8), "Rank 1 should partner with 0 in round 0");
        assertEquals(3, ButterflyPattern.getPartner(2, 0, 8), "Rank 2 should partner with 3 in round 0");
        assertEquals(2, ButterflyPattern.getPartner(3, 0, 8), "Rank 3 should partner with 2 in round 0");
    }

    @Test
    public void testPartnerCalculationRound1() {
        // Round 1: partner = rank XOR 2
        // (0,2), (1,3), (4,6), (5,7)
        assertEquals(2, ButterflyPattern.getPartner(0, 1, 8), "Rank 0 should partner with 2 in round 1");
        assertEquals(3, ButterflyPattern.getPartner(1, 1, 8), "Rank 1 should partner with 3 in round 1");
        assertEquals(0, ButterflyPattern.getPartner(2, 1, 8), "Rank 2 should partner with 0 in round 1");
        assertEquals(1, ButterflyPattern.getPartner(3, 1, 8), "Rank 3 should partner with 1 in round 1");
    }

    @Test
    public void testPartnerCalculationRound2() {
        // Round 2: partner = rank XOR 4
        // (0,4), (1,5), (2,6), (3,7)
        assertEquals(4, ButterflyPattern.getPartner(0, 2, 8), "Rank 0 should partner with 4 in round 2");
        assertEquals(5, ButterflyPattern.getPartner(1, 2, 8), "Rank 1 should partner with 5 in round 2");
        assertEquals(6, ButterflyPattern.getPartner(2, 2, 8), "Rank 2 should partner with 6 in round 2");
        assertEquals(7, ButterflyPattern.getPartner(3, 2, 8), "Rank 3 should partner with 7 in round 2");
    }

    @Test
    public void testPartnerSymmetry() {
        // Verify partner relationship is symmetric: p's partner's partner is p
        for (int p = 0; p < 8; p++) {
            for (int round = 0; round < 3; round++) {
                assertTrue(ButterflyPattern.validateSymmetry(p, round, 8),
                          "Symmetry must hold for rank=" + p + ", round=" + round);
            }
        }
    }

    @Test
    public void testRequiredRounds() {
        // Rounds needed for P partitions: ceil(log2(P))
        assertEquals(0, ButterflyPattern.requiredRounds(1), "1 partition needs 0 rounds");
        assertEquals(1, ButterflyPattern.requiredRounds(2), "2 partitions need 1 round");
        assertEquals(2, ButterflyPattern.requiredRounds(4), "4 partitions need 2 rounds");
        assertEquals(3, ButterflyPattern.requiredRounds(8), "8 partitions need 3 rounds");
        assertEquals(4, ButterflyPattern.requiredRounds(16), "16 partitions need 4 rounds");
        assertEquals(5, ButterflyPattern.requiredRounds(32), "32 partitions need 5 rounds");
    }

    @Test
    public void testNonPowerOf2Partitions() {
        // For non-power-of-2, some ranks have no partner in later rounds
        // For P=7, round 2: rank 6 has partner=2 XOR 4 = 2 (valid)
        // But rank 5 in round 2: 5 XOR 4 = 1 (valid)
        // For P=6, round 2: rank 5 has partner=5 XOR 4 = 1 (valid)
        // For P=5, round 2: rank 4 has partner=4 XOR 4 = 0 (valid)
        // For P=4, rank 3 round 2: 3 XOR 4 = 7 (invalid, >=4)
        assertEquals(-1, ButterflyPattern.getPartner(3, 2, 4),
                    "Rank 3 with P=4 should have no partner in round 2 (returns -1)");

        // But lower rounds should work
        assertEquals(3, ButterflyPattern.getPartner(2, 0, 4),
                    "Rank 2 with P=4 should have partner 3 in round 0");
    }

    @Test
    public void testParticipatesInRound() {
        // Check which ranks participate in each round
        assertTrue(ButterflyPattern.participatesInRound(0, 0, 8), "Rank 0 participates in round 0");
        assertTrue(ButterflyPattern.participatesInRound(0, 1, 8), "Rank 0 participates in round 1");
        assertTrue(ButterflyPattern.participatesInRound(0, 2, 8), "Rank 0 participates in round 2");

        // For P=4, rank 3 doesn't have a valid partner in round 2 (3 XOR 4 = 7, invalid)
        assertTrue(ButterflyPattern.participatesInRound(3, 0, 4), "Rank 3 participates in round 0 (P=4)");
        assertTrue(ButterflyPattern.participatesInRound(3, 1, 4), "Rank 3 participates in round 1 (P=4)");
        assertFalse(ButterflyPattern.participatesInRound(3, 2, 4), "Rank 3 doesn't participate in round 2 (P=4)");
    }

    @Test
    public void testInvalidRankThrows() {
        // Rank out of range
        assertThrows(IllegalArgumentException.class, () -> ButterflyPattern.getPartner(-1, 0, 8),
                    "Negative rank should throw");
        assertThrows(IllegalArgumentException.class, () -> ButterflyPattern.getPartner(8, 0, 8),
                    "Rank >= P should throw");
    }

    @Test
    public void testInvalidRoundThrows() {
        // Round negative
        assertThrows(IllegalArgumentException.class, () -> ButterflyPattern.getPartner(0, -1, 8),
                    "Negative round should throw");
    }

    @Test
    public void testInvalidPartitionCountThrows() {
        // P <= 0
        assertThrows(IllegalArgumentException.class, () -> ButterflyPattern.getPartner(0, 0, 0),
                    "P=0 should throw");
        assertThrows(IllegalArgumentException.class, () -> ButterflyPattern.getPartner(0, 0, -1),
                    "Negative P should throw");
    }
}
