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

/**
 * Butterfly communication pattern for O(log P) distributed coordination.
 *
 * <p>In each round r, partition p communicates with partner p XOR 2^r.
 * This ensures all partitions exchange information in log2(P) rounds.
 *
 * <p>The butterfly pattern is fundamental to distributed parallel algorithms,
 * enabling all-to-all communication in O(log P) rounds instead of O(P) rounds.
 *
 * @see <a href="https://doi.org/10.1137/100791634">p4est: Parallel AMR on Forests of Octrees</a>
 */
public final class ButterflyPattern {

    private ButterflyPattern() {} // Utility class

    /**
     * Calculate partner partition for butterfly communication.
     *
     * <p>In round r, partition p's partner is p XOR 2^r.
     * For non-power-of-2 partition counts, some partitions may not have a valid
     * partner in higher rounds (when partner >= totalPartitions).
     *
     * @param myRank this partition's rank (0 to P-1)
     * @param round current round number (0-based, 0 to log2(P)-1)
     * @param totalPartitions total number of partitions P
     * @return partner rank (0 to P-1), or -1 if no valid partner (non-power-of-2 edge case)
     * @throws IllegalArgumentException if parameters invalid
     */
    public static int getPartner(int myRank, int round, int totalPartitions) {
        validateParameters(myRank, round, totalPartitions);
        int partner = myRank ^ (1 << round);
        return (partner < totalPartitions) ? partner : -1;
    }

    /**
     * Calculate number of rounds needed for P partitions.
     *
     * <p>For P partitions, ceil(log2(P)) rounds are sufficient for all partitions
     * to communicate with each other via the butterfly pattern.
     *
     * @param totalPartitions total number of partitions P
     * @return ceil(log2(P)) rounds needed
     * @throws IllegalArgumentException if totalPartitions <= 0
     */
    public static int requiredRounds(int totalPartitions) {
        if (totalPartitions <= 0) {
            throw new IllegalArgumentException("totalPartitions must be positive");
        }
        if (totalPartitions <= 1) {
            return 0;
        }
        return (int) Math.ceil(Math.log(totalPartitions) / Math.log(2));
    }

    /**
     * Check if partition participates in this round.
     *
     * <p>A partition participates if it has a valid partner in the given round.
     * For power-of-2 partition counts, all partitions participate in all rounds.
     * For non-power-of-2 counts, higher-numbered partitions may not participate
     * in higher rounds.
     *
     * @param myRank partition rank
     * @param round round number (0-based)
     * @param totalPartitions total partitions
     * @return true if partition has a valid partner in this round, false otherwise
     * @throws IllegalArgumentException if parameters invalid
     */
    public static boolean participatesInRound(int myRank, int round, int totalPartitions) {
        return getPartner(myRank, round, totalPartitions) >= 0;
    }

    /**
     * Validate that partner calculation is symmetric.
     *
     * <p>The butterfly pattern has a critical property: if p1's partner in round r
     * is p2, then p2's partner in round r must be p1. This method validates that
     * property for testing and debugging.
     *
     * @param rank1 first partition rank
     * @param round round number
     * @param totalPartitions total partitions
     * @return true if symmetry holds (or if one rank has no partner), false otherwise
     * @throws IllegalArgumentException if parameters invalid
     */
    public static boolean validateSymmetry(int rank1, int round, int totalPartitions) {
        validateParameters(rank1, round, totalPartitions);
        int partner = getPartner(rank1, round, totalPartitions);
        if (partner < 0) {
            return true; // No partner is symmetric (can't violate symmetry)
        }
        int partnerOfPartner = getPartner(partner, round, totalPartitions);
        return partnerOfPartner == rank1;
    }

    /**
     * Validate input parameters for butterfly calculations.
     *
     * @param myRank this partition's rank (0 to P-1)
     * @param round current round number (0-based)
     * @param totalPartitions total number of partitions P
     * @throws IllegalArgumentException if parameters invalid
     */
    private static void validateParameters(int myRank, int round, int totalPartitions) {
        if (totalPartitions <= 0) {
            throw new IllegalArgumentException("totalPartitions must be positive, got " + totalPartitions);
        }
        if (myRank < 0 || myRank >= totalPartitions) {
            throw new IllegalArgumentException(
                "myRank must be in [0, totalPartitions), got " + myRank + " with totalPartitions=" + totalPartitions);
        }
        if (round < 0) {
            throw new IllegalArgumentException("round must be non-negative, got " + round);
        }
    }
}
