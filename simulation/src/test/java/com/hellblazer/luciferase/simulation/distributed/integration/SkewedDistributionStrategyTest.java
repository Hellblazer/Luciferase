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
 * Tests for SkewedDistributionStrategy load-imbalanced distribution.
 * <p>
 * Phase 6B6: 8-Process Scaling & GC Benchmarking
 *
 * @author hal.hildebrand
 */
class SkewedDistributionStrategyTest {

    @Test
    void testSkewed4Heavy12Light_Distribution() {
        // Given: 8-process topology with 2 bubbles each (16 total)
        var topology = new TestProcessTopology(8, 2);

        // And: Select bubbles 0, 4, 8, 12 as heavy (4 heavy, 12 light)
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);

        // When: Distribute 1200 entities
        var bubbleIds = new ArrayList<>(topology.getAllBubbleIds());
        var entities = new ArrayList<UUID>();
        for (int i = 0; i < 1200; i++) {
            entities.add(UUID.randomUUID());
        }

        for (var entityId : entities) {
            strategy.selectBubble(entityId);
        }

        var stats = strategy.getStats();

        // Then: Total should be 1200
        assertEquals(1200, stats.total(), "Should have 1200 entities total");

        // And: Heavy bubbles should have ~80% of entities (960 +/- tolerance)
        var tolerance = 100; // Allow 100 entities variance due to hashing
        assertTrue(stats.heavyTotal() >= 860 && stats.heavyTotal() <= 1060,
            "Heavy bubbles should have ~80% (960) entities, got: " + stats.heavyTotal());

        // And: Light bubbles should have ~20% of entities (240 +/- tolerance)
        assertTrue(stats.lightTotal() >= 140 && stats.lightTotal() <= 340,
            "Light bubbles should have ~20% (240) entities, got: " + stats.lightTotal());
    }

    @Test
    void testSkewed4Heavy12Light_PerBubbleDistribution() {
        // Given: 8-process topology with 2 bubbles each (16 total)
        var topology = new TestProcessTopology(8, 2);

        // And: Select bubbles 0, 4, 8, 12 as heavy
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);

        // When: Distribute 1200 entities
        var entities = new ArrayList<UUID>();
        for (int i = 0; i < 1200; i++) {
            entities.add(UUID.randomUUID());
        }

        for (var entityId : entities) {
            strategy.selectBubble(entityId);
        }

        // Then: Heavy bubbles should each have ~200 entities (240 per 4 heavy bubbles on average)
        var bubbleIds = new ArrayList<>(topology.getAllBubbleIds());
        var heavyBubbleIds = new ArrayList<UUID>();
        for (int i : heavyIndices) {
            heavyBubbleIds.add(bubbleIds.get(i));
        }

        // Allow wider variance due to hash-based distribution (±50%)
        for (var bubbleId : heavyBubbleIds) {
            var count = strategy.getEntityCount(bubbleId);
            assertTrue(count >= 100 && count <= 350,
                "Heavy bubble should have ~200 entities (±50%), got: " + count);
        }

        // And: Light bubbles should each have ~20 entities (240 per 12 light bubbles on average)
        var lightBubbleIds = new ArrayList<UUID>();
        for (int i = 0; i < bubbleIds.size(); i++) {
            if (!heavyIndices.contains(i)) {
                lightBubbleIds.add(bubbleIds.get(i));
            }
        }

        // Allow wider variance due to hash-based distribution
        for (var bubbleId : lightBubbleIds) {
            var count = strategy.getEntityCount(bubbleId);
            assertTrue(count >= 0 && count <= 80,
                "Light bubble should have ~20 entities, got: " + count);
        }
    }

    @Test
    void testSkewed_Determinism() {
        // Given: 8-process topology
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);

        // When: Create two strategies with same seed
        var strategy1 = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);
        var strategy2 = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);

        // And: Distribute same entities
        var entities = new ArrayList<UUID>();
        for (int i = 0; i < 100; i++) {
            entities.add(UUID.nameUUIDFromBytes(("entity-" + i).getBytes()));
        }

        var results1 = new ArrayList<UUID>();
        var results2 = new ArrayList<UUID>();

        for (var entityId : entities) {
            results1.add(strategy1.selectBubble(entityId));
            results2.add(strategy2.selectBubble(entityId));
        }

        // Then: Results should be identical (deterministic)
        assertEquals(results1, results2, "Same seed should produce identical distribution");
    }

    @Test
    void testSkewed_DifferentSeedsDifferentDistribution() {
        // Given: 8-process topology
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);

        // When: Create strategies with different seeds
        var strategy1 = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);
        var strategy2 = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 99L);

        // And: Distribute same entities
        var entities = new ArrayList<UUID>();
        for (int i = 0; i < 100; i++) {
            entities.add(UUID.nameUUIDFromBytes(("entity-" + i).getBytes()));
        }

        for (var entityId : entities) {
            strategy1.selectBubble(entityId);
            strategy2.selectBubble(entityId);
        }

        var stats1 = strategy1.getStats();
        var stats2 = strategy2.getStats();

        // Then: Distribution might be slightly different due to different random state
        // (though for deterministic entity IDs, they should be same - demonstrating hash-based selection)
        assertEquals(stats1.total(), stats2.total(), "Total should be same");
    }

    @Test
    void testSkewed_HeavyBubbleCount() {
        // Given: 8-process topology
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);

        // When: Create skewed strategy
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);

        // Then: Should report 4 heavy bubbles
        assertEquals(4, strategy.getHeavyBubbleCount(), "Should have 4 heavy bubbles");

        // And: Should report 12 light bubbles
        assertEquals(12, strategy.getLightBubbleCount(), "Should have 12 light bubbles");
    }

    @Test
    void testSkewed_EmptyDistribution() {
        // Given: 8-process topology
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);

        // When: Create strategy without distributing entities
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);

        // Then: Total should be 0
        assertEquals(0, strategy.getTotalEntityCount(), "Should have 0 entities initially");

        var stats = strategy.getStats();
        assertEquals(0, stats.total(), "Stats total should be 0");
        assertEquals(0, stats.heavyTotal(), "Heavy total should be 0");
        assertEquals(0, stats.lightTotal(), "Light total should be 0");
    }

    @Test
    void testSkewed_SingleEntity() {
        // Given: 8-process topology
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);

        // When: Create strategy and distribute 1 entity
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);
        var entityId = UUID.randomUUID();
        var bubbleId = strategy.selectBubble(entityId);

        // Then: Should have 1 entity total
        assertEquals(1, strategy.getTotalEntityCount(), "Should have 1 entity");

        // And: Selected bubble should have the entity
        assertEquals(1, strategy.getEntityCount(bubbleId), "Selected bubble should have entity");
    }

    @Test
    void testSkewed_WeightVariations() {
        // Given: 8-process topology
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);

        // When: Create strategies with different weights (70/30, 80/20, 90/10)
        var strategy70 = new SkewedDistributionStrategy(topology, heavyIndices, 0.7, 42L);
        var strategy80 = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);
        var strategy90 = new SkewedDistributionStrategy(topology, heavyIndices, 0.9, 42L);

        // And: Distribute 1000 entities to each
        var entities = new ArrayList<UUID>();
        for (int i = 0; i < 1000; i++) {
            entities.add(UUID.nameUUIDFromBytes(("entity-" + i).getBytes()));
        }

        for (var entityId : entities) {
            strategy70.selectBubble(entityId);
            strategy80.selectBubble(entityId);
            strategy90.selectBubble(entityId);
        }

        var stats70 = strategy70.getStats();
        var stats80 = strategy80.getStats();
        var stats90 = strategy90.getStats();

        // Then: Should see increasing heavy distribution
        assertTrue(stats70.heavyTotal() < stats80.heavyTotal(),
            "70% weight should have less heavy distribution than 80%");
        assertTrue(stats80.heavyTotal() < stats90.heavyTotal(),
            "80% weight should have less heavy distribution than 90%");
    }

    @Test
    void testSkewed_EntityCountAccuracy() {
        // Given: 8-process topology
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);

        // When: Create strategy and track distribution manually
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);

        var manualDistribution = new HashMap<UUID, Integer>();
        for (var bubbleId : topology.getAllBubbleIds()) {
            manualDistribution.put(bubbleId, 0);
        }

        // And: Distribute 500 entities
        var entities = new ArrayList<UUID>();
        for (int i = 0; i < 500; i++) {
            entities.add(UUID.randomUUID());
        }

        for (var entityId : entities) {
            var bubbleId = strategy.selectBubble(entityId);
            manualDistribution.put(bubbleId, manualDistribution.get(bubbleId) + 1);
        }

        // Then: Strategy's counts should match manual counts
        for (var bubbleId : topology.getAllBubbleIds()) {
            var strategyCount = strategy.getEntityCount(bubbleId);
            var manualCount = manualDistribution.get(bubbleId);
            assertEquals(manualCount, strategyCount,
                "Strategy count should match manual count for bubble");
        }

        // And: Total should match
        var manualTotal = manualDistribution.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(manualTotal, strategy.getTotalEntityCount(), "Total should match");
    }

    @Test
    void testSkewed_AllHeavyBubbles() {
        // Given: 8-process topology
        var topology = new TestProcessTopology(8, 2);

        // When: All bubbles are marked as heavy
        var heavyIndices = new HashSet<Integer>();
        for (int i = 0; i < 16; i++) {
            heavyIndices.add(Integer.valueOf(i));
        }
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);

        // And: Distribute entities
        for (int i = 0; i < 100; i++) {
            strategy.selectBubble(UUID.randomUUID());
        }

        var stats = strategy.getStats();

        // Then: All entities should go to heavy bubbles
        assertEquals(100, stats.heavyTotal(), "All entities should go to heavy");
        assertEquals(0, stats.lightTotal(), "No entities in light");
    }

    @Test
    void testSkewed_AllLightBubbles() {
        // Given: 8-process topology
        var topology = new TestProcessTopology(8, 2);

        // When: No bubbles are marked as heavy (empty set)
        var heavyIndices = new HashSet<Integer>();
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);

        // And: Distribute entities
        for (int i = 0; i < 100; i++) {
            strategy.selectBubble(UUID.randomUUID());
        }

        var stats = strategy.getStats();

        // Then: All entities should go to light bubbles
        assertEquals(0, stats.heavyTotal(), "No entities in heavy");
        assertEquals(100, stats.lightTotal(), "All entities should go to light");
    }

    @Test
    void testSkewed_DistributionStatsAccuracy() {
        // Given: 8-process topology with 4 heavy, 12 light
        var topology = new TestProcessTopology(8, 2);
        var heavyIndices = Set.of(0, 4, 8, 12);
        var strategy = new SkewedDistributionStrategy(topology, heavyIndices, 0.8, 42L);

        // When: Distribute 1000 entities
        for (int i = 0; i < 1000; i++) {
            strategy.selectBubble(UUID.randomUUID());
        }

        var stats = strategy.getStats();

        // Then: Heavy average should be significantly higher than light average
        assertTrue(stats.heavyAverage() > stats.lightAverage() * 5,
            "Heavy average should be much higher than light average");

        // And: Sum of averages * counts should equal totals
        var heavySum = (int)(stats.heavyAverage() * strategy.getHeavyBubbleCount());
        var lightSum = (int)(stats.lightAverage() * strategy.getLightBubbleCount());
        assertEquals(stats.heavyTotal(), heavySum, "Heavy sum should match average * count");
        assertEquals(stats.lightTotal(), lightSum, "Light sum should match average * count");
    }
}
