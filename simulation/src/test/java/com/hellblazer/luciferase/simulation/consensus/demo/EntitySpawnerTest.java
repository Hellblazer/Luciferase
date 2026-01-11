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

package com.hellblazer.luciferase.simulation.consensus.demo;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EntitySpawner.
 * <p>
 * Verifies:
 * - Even distribution of 100 entities across 4 bubbles (25 per bubble)
 * - Entity ID uniqueness
 * - Bubble ownership tracking
 * - Ability to spawn additional entities
 * - Scaling to 500 entities
 * <p>
 * Phase 8C Day 1: Behavioral Entity Integration
 *
 * @author hal.hildebrand
 */
class EntitySpawnerTest {

    private ConsensusBubbleGrid grid;
    private EntitySpawner spawner;

    @BeforeEach
    void setUp() {
        // Create 4-bubble grid for testing
        var viewId = DigestAlgorithm.DEFAULT.digest("view1");
        var nodeIds = List.of(
            DigestAlgorithm.DEFAULT.digest("node0"),
            DigestAlgorithm.DEFAULT.digest("node1"),
            DigestAlgorithm.DEFAULT.digest("node2"),
            DigestAlgorithm.DEFAULT.digest("node3")
        );
        grid = new ConsensusBubbleGrid(viewId, null, nodeIds);
        spawner = new EntitySpawner(grid);
    }

    @Test
    void testSpawn100EntitiesEvenly() {
        // Spawn 100 entities
        var entityIds = spawner.spawnEntities(100);

        // Verify count
        assertEquals(100, entityIds.size(), "Should spawn exactly 100 entities");
        assertEquals(100, spawner.getEntityCount(), "Entity count should match");

        // Verify even distribution: 25 per bubble (±0 variance for exact division)
        for (int i = 0; i < 4; i++) {
            var count = spawner.getBubbleEntityCount(i);
            assertEquals(25, count, "Bubble " + i + " should have exactly 25 entities");
        }

        // Verify all entities tracked
        var allEntities = spawner.getAllEntities();
        assertEquals(100, allEntities.size(), "Should track all 100 entities");
    }

    @Test
    void testEntityIdUniqueness() {
        // Spawn 100 entities
        var entityIds = spawner.spawnEntities(100);

        // Verify all IDs are unique
        var uniqueIds = new HashSet<>(entityIds);
        assertEquals(100, uniqueIds.size(), "All entity IDs must be unique");

        // Verify no duplicates in getAllEntities
        var allEntities = spawner.getAllEntities();
        var uniqueAll = new HashSet<>(allEntities);
        assertEquals(allEntities.size(), uniqueAll.size(), "getAllEntities should have no duplicates");
    }

    @Test
    void testBubbleOwnershipTracking() {
        // Spawn 100 entities
        var entityIds = spawner.spawnEntities(100);

        // Verify every entity has a bubble owner
        for (var entityId : entityIds) {
            var bubbleOpt = spawner.getEntityBubble(entityId);
            assertTrue(bubbleOpt.isPresent(), "Entity " + entityId + " should have bubble owner");

            var bubbleIndex = bubbleOpt.get();
            assertTrue(bubbleIndex >= 0 && bubbleIndex < 4,
                      "Bubble index should be 0-3, got " + bubbleIndex);
        }

        // Verify bubble counts match ownership
        int totalTracked = 0;
        for (int i = 0; i < 4; i++) {
            totalTracked += spawner.getBubbleEntityCount(i);
        }
        assertEquals(100, totalTracked, "Sum of bubble counts should equal total entities");
    }

    @Test
    void testSpawnInSpecificBubble() {
        // Spawn 10 entities in bubble 2
        spawner.spawnInBubble(2, 10);

        // Verify count in bubble 2
        assertEquals(10, spawner.getBubbleEntityCount(2), "Bubble 2 should have 10 entities");

        // Verify other bubbles empty
        assertEquals(0, spawner.getBubbleEntityCount(0), "Bubble 0 should be empty");
        assertEquals(0, spawner.getBubbleEntityCount(1), "Bubble 1 should be empty");
        assertEquals(0, spawner.getBubbleEntityCount(3), "Bubble 3 should be empty");

        // Verify total count
        assertEquals(10, spawner.getEntityCount(), "Total count should be 10");
    }

    @Test
    void testSpawnAdditionalEntities() {
        // Spawn initial 100 entities
        var initial = spawner.spawnEntities(100);
        assertEquals(100, initial.size(), "Should spawn 100 initially");

        // Spawn 50 more
        var additional = spawner.spawnEntities(50);
        assertEquals(50, additional.size(), "Should spawn 50 additional");

        // Verify total count
        assertEquals(150, spawner.getEntityCount(), "Total should be 150");

        // Verify no ID overlap
        var allIds = new HashSet<>(initial);
        allIds.addAll(additional);
        assertEquals(150, allIds.size(), "All 150 entity IDs should be unique");
    }

    @Test
    void testScalingTo500Entities() {
        // Spawn 500 entities
        var entityIds = spawner.spawnEntities(500);

        // Verify count
        assertEquals(500, entityIds.size(), "Should spawn exactly 500 entities");
        assertEquals(500, spawner.getEntityCount(), "Entity count should match");

        // Verify even distribution: 125 per bubble
        for (int i = 0; i < 4; i++) {
            var count = spawner.getBubbleEntityCount(i);
            assertEquals(125, count, "Bubble " + i + " should have exactly 125 entities");
        }

        // Verify all IDs unique
        var uniqueIds = new HashSet<>(entityIds);
        assertEquals(500, uniqueIds.size(), "All 500 entity IDs must be unique");
    }

    @Test
    void testGetEntityBubbleForUnknownEntity() {
        // Query unknown entity
        var unknownId = UUID.randomUUID();
        var bubbleOpt = spawner.getEntityBubble(unknownId);

        assertFalse(bubbleOpt.isPresent(), "Unknown entity should have no bubble owner");
    }

    @Test
    void testInitialStateEmpty() {
        // Verify spawner starts empty
        assertEquals(0, spawner.getEntityCount(), "Should start with 0 entities");

        for (int i = 0; i < 4; i++) {
            assertEquals(0, spawner.getBubbleEntityCount(i), "Bubble " + i + " should start empty");
        }

        assertTrue(spawner.getAllEntities().isEmpty(), "getAllEntities should be empty initially");
    }
}
