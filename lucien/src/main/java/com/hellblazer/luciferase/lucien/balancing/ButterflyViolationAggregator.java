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

import com.hellblazer.luciferase.lucien.balancing.proto.BalanceViolation;
import com.hellblazer.luciferase.lucien.balancing.proto.ViolationBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Aggregates 2:1 balance violations across partitions using butterfly pattern.
 * Achieves O(P * log P) message complexity instead of O(P^2).
 *
 * <p>The butterfly pattern ensures all partitions exchange information in
 * ceil(log2(P)) rounds. In each round r, partition p communicates with
 * partner p XOR 2^r.
 *
 * <p>Violations are deduplicated using a composite key of (localKey, ghostKey)
 * to handle cases where the same violation is reported by multiple partitions.
 *
 * @see ButterflyPattern
 * @author hal.hildebrand
 */
public class ButterflyViolationAggregator {

    private static final Logger log = LoggerFactory.getLogger(ButterflyViolationAggregator.class);

    private final int myRank;
    private final int totalPartitions;
    private final BiFunction<Integer, ViolationBatch, ViolationBatch> violationExchanger;

    /**
     * Creates a new butterfly violation aggregator.
     *
     * @param myRank this partition's rank (0 to P-1)
     * @param totalPartitions total number of partitions P
     * @param violationExchanger function to exchange violations with a partner
     * @throws IllegalArgumentException if parameters invalid
     * @throws NullPointerException if violationExchanger is null
     */
    public ButterflyViolationAggregator(int myRank, int totalPartitions,
                                       BiFunction<Integer, ViolationBatch, ViolationBatch> violationExchanger) {
        if (totalPartitions <= 0) {
            throw new IllegalArgumentException("totalPartitions must be positive, got " + totalPartitions);
        }
        if (myRank < 0 || myRank >= totalPartitions) {
            throw new IllegalArgumentException(
                "myRank must be in [0, totalPartitions), got " + myRank + " with totalPartitions=" + totalPartitions);
        }
        Objects.requireNonNull(violationExchanger, "violationExchanger cannot be null");

        this.myRank = myRank;
        this.totalPartitions = totalPartitions;
        this.violationExchanger = violationExchanger;

        log.debug("ButterflyViolationAggregator initialized for rank {} of {} partitions",
                 myRank, totalPartitions);
    }

    /**
     * Aggregates violations across all partitions using butterfly pattern.
     *
     * <p>Algorithm:
     * <pre>
     * for round = 0 to ceil(log2(P)) - 1:
     *     partner = myRank XOR (1 << round)
     *     if partner < totalPartitions:
     *         send myViolations to partner
     *         receive partnerViolations from partner
     *         merge into aggregatedSet (deduplicate)
     * return aggregatedSet
     * </pre>
     *
     * @param localViolations this partition's local violations
     * @return set of all violations from all partitions (deduplicated)
     * @throws NullPointerException if localViolations is null
     */
    public Set<BalanceViolation> aggregateViolations(List<BalanceViolation> localViolations) {
        Objects.requireNonNull(localViolations, "localViolations cannot be null");

        // Use LinkedHashMap for deduplication while preserving insertion order
        var aggregatedViolations = new LinkedHashMap<ViolationKey, BalanceViolation>();

        // Add local violations
        for (var violation : localViolations) {
            var key = new ViolationKey(violation);
            aggregatedViolations.put(key, violation);
        }

        // Single partition - no exchange needed
        if (totalPartitions == 1) {
            log.debug("Single partition - no exchange needed");
            return new LinkedHashSet<>(aggregatedViolations.values());
        }

        // Calculate number of rounds needed
        int rounds = ButterflyPattern.requiredRounds(totalPartitions);
        log.debug("Starting butterfly aggregation: {} rounds for {} partitions", rounds, totalPartitions);

        // Execute butterfly pattern rounds
        for (int round = 0; round < rounds; round++) {
            int partner = ButterflyPattern.getPartner(myRank, round, totalPartitions);

            if (partner < 0) {
                // No valid partner in this round (non-power-of-2 edge case)
                log.debug("Round {}: no valid partner for rank {}", round, myRank);
                continue;
            }

            log.debug("Round {}: exchanging with partner {}", round, partner);

            // Exchange violations with partner
            var receivedBatch = exchangeWithPartner(partner, round, aggregatedViolations.values());

            // Merge received violations (deduplicate)
            for (var violation : receivedBatch.getViolationsList()) {
                var key = new ViolationKey(violation);
                aggregatedViolations.putIfAbsent(key, violation);
            }

            log.debug("Round {}: aggregated set now contains {} violations",
                     round, aggregatedViolations.size());
        }

        log.debug("Butterfly aggregation complete: {} total violations", aggregatedViolations.size());
        return new LinkedHashSet<>(aggregatedViolations.values());
    }

    /**
     * Exchanges violations with a partner partition.
     *
     * @param partner partner rank
     * @param round round number
     * @param violations current aggregated violations to send
     * @return violations received from partner
     */
    private ViolationBatch exchangeWithPartner(int partner, int round,
                                              Collection<BalanceViolation> violations) {
        // Build batch to send
        var batchBuilder = ViolationBatch.newBuilder()
            .setRequesterRank(myRank)
            .setResponderRank(partner)
            .setRoundNumber(round)
            .setTimestamp(System.currentTimeMillis());

        // Add all current violations (includes local + previously received)
        for (var violation : violations) {
            batchBuilder.addViolations(violation);
        }

        var batch = batchBuilder.build();

        log.debug("Sending {} violations to partner {} in round {}", violations.size(), partner, round);

        // Exchange with partner using the provided exchanger function
        var receivedBatch = violationExchanger.apply(partner, batch);

        log.debug("Received {} violations from partner {} in round {}",
                 receivedBatch.getViolationsCount(), partner, round);

        return receivedBatch;
    }

    /**
     * Composite key for violation deduplication.
     * Uses localKey and ghostKey to uniquely identify violations.
     */
    private static class ViolationKey {
        private final int localKeyHash;
        private final int ghostKeyHash;

        ViolationKey(BalanceViolation violation) {
            // Use the full SpatialKey hashCode for uniqueness
            // SpatialKey contains oneof(MortonKey, TetreeKey) so hashCode handles both
            this.localKeyHash = violation.getLocalKey().hashCode();
            this.ghostKeyHash = violation.getGhostKey().hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            var that = (ViolationKey) o;
            return localKeyHash == that.localKeyHash && ghostKeyHash == that.ghostKeyHash;
        }

        @Override
        public int hashCode() {
            return Objects.hash(localKeyHash, ghostKeyHash);
        }
    }
}
