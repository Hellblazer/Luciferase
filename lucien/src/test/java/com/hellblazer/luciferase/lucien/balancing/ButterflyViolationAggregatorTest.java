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

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ButterflyViolationAggregator - distributed violation exchange using butterfly pattern.
 * Operates on domain types ({@link ViolationBatch}, {@link TwoOneBalanceChecker.BalanceViolation}).
 *
 * @author hal.hildebrand
 */
class ButterflyViolationAggregatorTest {

    private MockViolationExchanger mockExchanger;

    @BeforeEach
    void setUp() {
        mockExchanger = new MockViolationExchanger();
    }

    /**
     * Mock violation exchanger for testing.
     * Simulates bidirectional exchange between partners.
     */
    static class MockViolationExchanger implements BiFunction<Integer, ViolationBatch<MortonKey>, ViolationBatch<MortonKey>> {
        private ViolationBatch<MortonKey> lastSentBatch;
        private ViolationBatch<MortonKey> responseToReturn;

        void setResponse(ViolationBatch<MortonKey> response) {
            this.responseToReturn = response;
        }

        ViolationBatch<MortonKey> getLastSentBatch() {
            return lastSentBatch;
        }

        @Override
        public ViolationBatch<MortonKey> apply(Integer partner, ViolationBatch<MortonKey> batch) {
            lastSentBatch = batch;
            return responseToReturn != null ? responseToReturn :
                new ViolationBatch<>(partner, batch.requesterRank(), batch.roundNumber(),
                                     List.of(), System.currentTimeMillis());
        }
    }

    @Test
    void testSinglePartitionNoExchange() {
        var aggregator = new ButterflyViolationAggregator<MortonKey>(0, 1, mockExchanger);

        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 0)
        );

        var result = aggregator.aggregateViolations(localViolations);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.containsAll(localViolations));

        // No exchanges should happen for single partition
        assertNull(mockExchanger.getLastSentBatch());
    }

    @Test
    void testTwoPartitionsOneRound() {
        // Partition 0 exchanges with partition 1
        var aggregator = new ButterflyViolationAggregator<MortonKey>(0, 2, mockExchanger);

        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 0)
        );

        var partnerViolations = List.of(
            createViolation(3, 4, 6, 8, 2, 1)
        );

        // Mock the exchange response
        mockExchanger.setResponse(
            new ViolationBatch<>(1, 0, 0, partnerViolations, System.currentTimeMillis())
        );

        var result = aggregator.aggregateViolations(localViolations);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.containsAll(localViolations));
        assertTrue(result.containsAll(partnerViolations));

        // Verify one exchange happened
        var lastBatch = mockExchanger.getLastSentBatch();
        assertNotNull(lastBatch);
        assertEquals(0, lastBatch.requesterRank());
        assertEquals(0, lastBatch.roundNumber());
        assertEquals(1, lastBatch.violations().size());
    }

    @Test
    void testFourPartitionsTwoRounds() {
        // Partition 0 exchanges with:
        // - Round 0: partition 1 (0 XOR 1 = 1)
        // - Round 1: partition 2 (0 XOR 2 = 2)
        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 0)
        );

        // Create round-specific responses
        var round0Violations = List.of(createViolation(3, 4, 6, 8, 2, 1));
        var round1Violations = List.of(createViolation(5, 6, 7, 9, 2, 2));

        // Use a custom exchanger that returns different violations per round
        var roundAwareExchanger = new BiFunction<Integer, ViolationBatch<MortonKey>, ViolationBatch<MortonKey>>() {
            @Override
            public ViolationBatch<MortonKey> apply(Integer partner, ViolationBatch<MortonKey> batch) {
                var violations = batch.roundNumber() == 0 ? round0Violations : round1Violations;
                return new ViolationBatch<>(partner, batch.requesterRank(), batch.roundNumber(),
                                            violations, System.currentTimeMillis());
            }
        };

        var aggregator = new ButterflyViolationAggregator<MortonKey>(0, 4, roundAwareExchanger);
        var result = aggregator.aggregateViolations(localViolations);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertTrue(result.containsAll(localViolations));
        assertTrue(result.containsAll(round0Violations));
        assertTrue(result.containsAll(round1Violations));
    }

    @Test
    void testNonPowerOfTwoPartitions() {
        // 6 partitions: needs ceil(log2(6)) = 3 rounds
        // Partition 5 in round 0: partner = 5 XOR 1 = 4 (valid)
        // Partition 5 in round 1: partner = 5 XOR 2 = 7 (>= 6, skip)
        // Partition 5 in round 2: partner = 5 XOR 4 = 1 (valid)
        var aggregator = new ButterflyViolationAggregator<MortonKey>(5, 6, mockExchanger);

        var localViolations = List.of(
            createViolation(1, 2, 5, 7, 2, 5)
        );

        var result = aggregator.aggregateViolations(localViolations);

        assertNotNull(result);
        // Should include local violations plus any received
        assertTrue(result.containsAll(localViolations));
    }

    @Test
    void testDeduplicationOfDuplicateViolations() {
        var aggregator = new ButterflyViolationAggregator<MortonKey>(0, 2, mockExchanger);

        // Same violation appears locally and from partner
        var duplicateViolation = createViolation(1, 2, 5, 7, 2, 0);
        var localViolations = List.of(duplicateViolation);

        // Partner sends the same violation
        mockExchanger.setResponse(
            new ViolationBatch<>(1, 0, 0, List.of(duplicateViolation), System.currentTimeMillis())
        );

        var result = aggregator.aggregateViolations(localViolations);

        assertNotNull(result);
        // Should deduplicate to 1 violation
        assertEquals(1, result.size());
    }

    @Test
    void testEmptyLocalViolations() {
        var aggregator = new ButterflyViolationAggregator<MortonKey>(0, 2, mockExchanger);

        var result = aggregator.aggregateViolations(List.of());

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testNullLocalViolationsThrowsException() {
        var aggregator = new ButterflyViolationAggregator<MortonKey>(0, 2, mockExchanger);

        assertThrows(NullPointerException.class, () -> {
            aggregator.aggregateViolations(null);
        });
    }

    @Test
    void testInvalidRankThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ButterflyViolationAggregator<MortonKey>(-1, 4, mockExchanger);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new ButterflyViolationAggregator<MortonKey>(4, 4, mockExchanger);
        });
    }

    @Test
    void testInvalidTotalPartitionsThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ButterflyViolationAggregator<MortonKey>(0, 0, mockExchanger);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new ButterflyViolationAggregator<MortonKey>(0, -1, mockExchanger);
        });
    }

    @Test
    void testNullExchangerThrowsException() {
        assertThrows(NullPointerException.class, () -> {
            new ButterflyViolationAggregator<MortonKey>(0, 4, null);
        });
    }

    @Test
    void testButterflyPartnerCalculation() {
        // Test that correct partners are calculated per round
        var localViolations = List.of(createViolation(1, 2, 5, 7, 2, 0));

        // Track which partners are contacted
        var contactedPartners = new java.util.HashSet<Integer>();
        var partnerTracker = new BiFunction<Integer, ViolationBatch<MortonKey>, ViolationBatch<MortonKey>>() {
            @Override
            public ViolationBatch<MortonKey> apply(Integer partner, ViolationBatch<MortonKey> batch) {
                contactedPartners.add(partner);
                return new ViolationBatch<>(partner, batch.requesterRank(), batch.roundNumber(),
                                            List.of(), System.currentTimeMillis());
            }
        };

        var aggregator = new ButterflyViolationAggregator<MortonKey>(0, 8, partnerTracker);
        aggregator.aggregateViolations(localViolations);

        // For rank 0 with 8 partitions (3 rounds):
        // Round 0: 0 XOR 1 = 1
        // Round 1: 0 XOR 2 = 2
        // Round 2: 0 XOR 4 = 4
        assertEquals(3, contactedPartners.size());
        assertTrue(contactedPartners.contains(1));
        assertTrue(contactedPartners.contains(2));
        assertTrue(contactedPartners.contains(4));
    }

    /**
     * Helper to create a domain BalanceViolation for testing (level-0 Morton keys, levelDiff > 1).
     */
    private TwoOneBalanceChecker.BalanceViolation<MortonKey> createViolation(long localKeyId, long ghostKeyId,
                                                                             int localLevel, int ghostLevel,
                                                                             int levelDiff, int sourceRank) {
        return new TwoOneBalanceChecker.BalanceViolation<>(
            new MortonKey(localKeyId, (byte) 0),
            new MortonKey(ghostKeyId, (byte) 0),
            localLevel, ghostLevel, levelDiff, sourceRank);
    }
}
