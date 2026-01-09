/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SkewedDistributionStrategy (80/20 split approach).
 * <p>
 * Phase 6B6: 8-Process Scaling & GC Benchmarking
 *
 * @author hal.hildebrand
 */
class SkewedDistributionStrategyTest {

    /**
     * Test 1: Basic Heavy/Light Split
     * <p>
     * Validates that heavy bubbles receive more entities than light bubbles.
     */
    @Test
    void testHeavyLightSplit() {
        // Given: 16 bubbles, 4 heavy (indices 0,4,8,12), 80% weight
        var topology = new TestProcessTopology(8, 2);  // 16 bubbles
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42);

        // When: Distribute 1200 entities
        for (int i = 0; i < 1200; i++) {
            var entityId = UUID.randomUUID();
            strategy.selectBubble(entityId);
        }

        // Then: Verify split
        assertEquals(4, strategy.getHeavyBubbleCount(), "Should have 4 heavy bubbles");
        assertEquals(12, strategy.getLightBubbleCount(), "Should have 12 light bubbles");

        var stats = strategy.getStats();
        assertEquals(1200, stats.total(), "Should have 1200 total entities");

        // Heavy bubbles should average significantly more than light
        assertTrue(stats.heavyAverage() > stats.lightAverage(),
                String.format("Heavy avg (%.1f) should be > light avg (%.1f)",
                        stats.heavyAverage(), stats.lightAverage()));
    }

    /**
     * Test 2: 80/20 Weight Distribution
     * <p>
     * Validates that approximately 80% of entities go to heavy bubbles.
     */
    @Test
    void test80_20Distribution() {
        // Given: 16 bubbles, 4 heavy, 0.8 weight
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42);

        // When: Distribute 1200 entities
        for (int i = 0; i < 1200; i++) {
            var entityId = UUID.randomUUID();
            strategy.selectBubble(entityId);
        }

        // Then: Heavy bubbles should get ~80% (960 entities)
        var stats = strategy.getStats();
        var heavyPercentage = (double) stats.heavyTotal() / stats.total();

        assertTrue(heavyPercentage >= 0.75 && heavyPercentage <= 0.85,
                "Heavy bubbles should get ~80%, got: " + String.format("%.1f%%", heavyPercentage * 100));
    }

    /**
     * Test 3: Reproducibility with Same Seed
     * <p>
     * Validates that same seed produces identical distribution.
     */
    @Test
    void testReproducibility() {
        // Given: Two strategies with same seed
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy1 = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42);
        var strategy2 = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42);

        // When: Distribute same entities to both
        var testEntities = new ArrayList<UUID>();
        for (int i = 0; i < 100; i++) {
            testEntities.add(UUID.randomUUID());
        }

        var bubbles1 = new ArrayList<UUID>();
        var bubbles2 = new ArrayList<UUID>();

        for (var entityId : testEntities) {
            bubbles1.add(strategy1.selectBubble(entityId));
            bubbles2.add(strategy2.selectBubble(entityId));
        }

        // Then: Should select same bubbles
        assertEquals(bubbles1, bubbles2, "Same seed should produce identical distribution");
    }

    /**
     * Test 4: Entity Count Tracking
     * <p>
     * Validates that entity counts are tracked correctly per bubble.
     */
    @Test
    void testEntityCountTracking() {
        // Given: 16 bubbles with skewed distribution
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42);

        // When: Distribute 800 entities
        for (int i = 0; i < 800; i++) {
            var entityId = UUID.randomUUID();
            strategy.selectBubble(entityId);
        }

        // Then: Total entity count should match
        assertEquals(800, strategy.getTotalEntityCount(), "Total count should be 800");

        // And: Sum of individual counts should equal total
        var allBubbles = topology.getAllBubbleIds();
        var sumOfCounts = allBubbles.stream()
                .mapToInt(strategy::getEntityCount)
                .sum();
        assertEquals(800, sumOfCounts, "Sum of bubble counts should equal total");
    }

    /**
     * Test 5: Heavy Bubble Distribution
     * <p>
     * Validates that entities are distributed evenly within heavy bubbles.
     */
    @Test
    void testHeavyBubbleDistribution() {
        // Given: 16 bubbles, 4 heavy
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42);

        // When: Distribute 1200 entities
        for (int i = 0; i < 1200; i++) {
            var entityId = UUID.randomUUID();
            strategy.selectBubble(entityId);
        }

        // Then: Each heavy bubble should get roughly equal share
        var allBubbles = new ArrayList<>(topology.getAllBubbleIds());
        var heavyCounts = new ArrayList<Integer>();
        for (int idx : heavyIndices) {
            var bubbleId = allBubbles.get(idx);
            heavyCounts.add(strategy.getEntityCount(bubbleId));
        }

        var avgHeavy = heavyCounts.stream().mapToInt(Integer::intValue).average().orElse(0);
        for (var count : heavyCounts) {
            var deviation = Math.abs(count - avgHeavy) / avgHeavy;
            assertTrue(deviation < 0.3,
                    String.format("Heavy bubble deviation should be <30%%, got: %.1f%%", deviation * 100));
        }
    }

    /**
     * Test 6: Light Bubble Distribution
     * <p>
     * Validates that light bubbles receive fewer entities.
     */
    @Test
    void testLightBubbleDistribution() {
        // Given: 16 bubbles, 12 light
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42);

        // When: Distribute 1200 entities
        for (int i = 0; i < 1200; i++) {
            var entityId = UUID.randomUUID();
            strategy.selectBubble(entityId);
        }

        // Then: Light bubbles should have fewer entities than heavy
        var allBubbles = new ArrayList<>(topology.getAllBubbleIds());
        var stats = strategy.getStats();

        assertTrue(stats.lightAverage() < stats.heavyAverage(),
                String.format("Light avg (%.1f) should be < heavy avg (%.1f)",
                        stats.lightAverage(), stats.heavyAverage()));
    }

    /**
     * Test 7: Edge Case - All Heavy Bubbles
     * <p>
     * Validates handling when all bubbles are designated heavy.
     */
    @Test
    void testAllHeavyBubbles() {
        // Given: All 16 bubbles are heavy
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42);

        // When: Distribute entities
        for (int i = 0; i < 800; i++) {
            var entityId = UUID.randomUUID();
            strategy.selectBubble(entityId);
        }

        // Then: Should work correctly
        assertEquals(16, strategy.getHeavyBubbleCount(), "Should have 16 heavy bubbles");
        assertEquals(0, strategy.getLightBubbleCount(), "Should have 0 light bubbles");
        assertEquals(800, strategy.getTotalEntityCount(), "Should have 800 entities");
    }

    /**
     * Test 8: Edge Case - Single Heavy Bubble
     * <p>
     * Validates handling with only one heavy bubble.
     */
    @Test
    void testSingleHeavyBubble() {
        // Given: Only 1 heavy bubble
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0);
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42);

        // When: Distribute entities
        for (int i = 0; i < 1000; i++) {
            var entityId = UUID.randomUUID();
            strategy.selectBubble(entityId);
        }

        // Then: Heavy bubble should get ~80%
        var stats = strategy.getStats();
        var heavyPercentage = (double) stats.heavyTotal() / stats.total();

        assertTrue(heavyPercentage >= 0.75 && heavyPercentage <= 0.85,
                "Single heavy bubble should get ~80%, got: " + String.format("%.1f%%", heavyPercentage * 100));
    }

    /**
     * Test 9: Stats Calculation
     * <p>
     * Validates that distribution statistics are computed correctly.
     */
    @Test
    void testStatsCalculation() {
        // Given: Skewed distribution
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42);

        // When: Distribute entities
        for (int i = 0; i < 1200; i++) {
            var entityId = UUID.randomUUID();
            strategy.selectBubble(entityId);
        }

        // Then: Stats should be consistent
        var stats = strategy.getStats();
        assertEquals(stats.total(), stats.heavyTotal() + stats.lightTotal(),
                "Total should equal heavy + light");

        var calculatedHeavyAvg = (double) stats.heavyTotal() / strategy.getHeavyBubbleCount();
        assertEquals(calculatedHeavyAvg, stats.heavyAverage(), 0.1,
                "Heavy average should match calculation");

        var calculatedLightAvg = (double) stats.lightTotal() / strategy.getLightBubbleCount();
        assertEquals(calculatedLightAvg, stats.lightAverage(), 0.1,
                "Light average should match calculation");
    }

    /**
     * Test 10: Different Heavy Weights
     * <p>
     * Validates that different heavy weights produce different distributions.
     */
    @Test
    void testDifferentHeavyWeights() {
        // Given: Same topology, different weights
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy60 = new SkewedDistributionStrategy(topology, heavyIndices, 0.6, 42);
        var strategy90 = new SkewedDistributionStrategy(topology, heavyIndices, 0.9, 42);

        // When: Distribute same entities
        for (int i = 0; i < 1000; i++) {
            var entityId = UUID.randomUUID();
            strategy60.selectBubble(entityId);
            strategy90.selectBubble(entityId);
        }

        // Then: Higher weight should produce higher heavy percentage
        var stats60 = strategy60.getStats();
        var stats90 = strategy90.getStats();

        var heavyPercentage60 = (double) stats60.heavyTotal() / stats60.total();
        var heavyPercentage90 = (double) stats90.heavyTotal() / stats90.total();

        assertTrue(heavyPercentage90 > heavyPercentage60,
                String.format("0.9 weight (%.1f%%) should produce higher heavy percentage than 0.6 weight (%.1f%%)",
                        heavyPercentage90 * 100, heavyPercentage60 * 100));
    }
}
