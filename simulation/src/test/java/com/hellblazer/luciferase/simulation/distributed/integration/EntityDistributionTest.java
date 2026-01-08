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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for entity distribution across bubbles in a distributed simulation.
 * <p>
 * Phase 6B5.3: Entity Distribution & Initialization
 * Bead: Luciferase-sy60
 *
 * @author hal.hildebrand
 */
class EntityDistributionTest {

    private TestProcessCluster cluster;
    private DistributedEntityFactory entityFactory;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new TestProcessCluster(4, 2); // 4 processes, 2 bubbles each
        cluster.start();
        entityFactory = new DistributedEntityFactory(cluster);
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop();
        }
    }

    @Test
    void testEntityCreation() {
        // When: Create 800 entities
        entityFactory.createEntities(800);

        // Then: All 800 entities should be created with unique IDs
        var allEntities = entityFactory.getAllEntityIds();
        assertEquals(800, allEntities.size(), "Should create 800 entities");

        // Verify all IDs are unique
        var uniqueIds = new HashSet<>(allEntities);
        assertEquals(800, uniqueIds.size(), "All entity IDs should be unique");
    }

    @Test
    void testEntityDistribution() {
        // When: Create and distribute 800 entities
        entityFactory.createEntities(800);

        // Then: Entities should be distributed across 8 bubbles
        var distribution = entityFactory.getDistribution();
        assertEquals(8, distribution.size(), "Should have 8 bubbles");

        // Each bubble should have 100 entities (800/8)
        for (var entry : distribution.entrySet()) {
            assertEquals(100, entry.getValue(), "Each bubble should have 100 entities");
        }
    }

    @Test
    void testEntityAccountantRegistration() {
        // When: Create and distribute entities
        entityFactory.createEntities(800);

        // Then: All entities should be registered in accountant
        var accountant = cluster.getEntityAccountant();
        var totalEntities = accountant.getDistribution().values().stream()
            .mapToInt(Integer::intValue)
            .sum();
        assertEquals(800, totalEntities, "EntityAccountant should track all 800 entities");
    }

    @Test
    void testNoEntityLoss() {
        // When: Create entities
        entityFactory.createEntities(800);

        // Then: Validate no entity is lost
        var validation = cluster.getEntityAccountant().validate();
        assertTrue(validation.success(), "Validation should pass: " + validation.details());
    }

    @Test
    void testEntityMetadata() {
        // When: Create entities
        entityFactory.createEntities(100);

        // Then: Each entity should have valid snapshot data
        for (var entityId : entityFactory.getAllEntityIds()) {
            var snapshot = entityFactory.getEntitySnapshot(entityId);
            assertNotNull(snapshot, "Entity should have snapshot");
            assertNotNull(snapshot.entityId(), "Snapshot should have entity ID");
            assertNotNull(snapshot.position(), "Snapshot should have position");
        }
    }

    @Test
    void testBubblePopulation() {
        // When: Create entities
        entityFactory.createEntities(800);

        // Then: Each bubble should have between 100-200 entities (configurable)
        var topology = cluster.getTopology();
        var accountant = cluster.getEntityAccountant();

        for (var bubbleId : topology.getAllBubbleIds()) {
            var entities = accountant.entitiesInBubble(bubbleId);
            assertTrue(entities.size() >= 100 && entities.size() <= 200,
                "Bubble should have 100-200 entities, got: " + entities.size());
        }
    }

    @Test
    void testDeterministicDistribution() {
        // Given: Two factories with same seed
        var factory1 = new DistributedEntityFactory(cluster, 12345L);
        var factory2 = new DistributedEntityFactory(cluster, 12345L);

        // When: Create entities with both
        factory1.createEntities(100);

        // Reset accountant and create with second factory
        cluster.getEntityAccountant().reset();
        factory2.createEntities(100);

        // Then: Same entities should be created
        assertEquals(factory1.getAllEntityIds(), factory2.getAllEntityIds(),
            "Same seed should produce same entities");
    }

    @Test
    void testCustomDistributionStrategy() {
        // Given: A round-robin distribution strategy
        var strategy = new RoundRobinDistributionStrategy(cluster.getTopology());
        var factory = new DistributedEntityFactory(cluster, strategy);

        // When: Create entities
        factory.createEntities(80);

        // Then: Each bubble should have exactly 10 entities (80/8)
        var distribution = factory.getDistribution();
        for (var entry : distribution.entrySet()) {
            assertEquals(10, entry.getValue(), "Round-robin should distribute evenly");
        }
    }

    @Test
    void testEntityPositionsWithinBubbleBounds() {
        // When: Create entities
        entityFactory.createEntities(100);

        // Then: Entity positions should be within bubble bounds
        var topology = cluster.getTopology();
        for (var entityId : entityFactory.getAllEntityIds()) {
            var snapshot = entityFactory.getEntitySnapshot(entityId);
            var bubbleId = entityFactory.getBubbleForEntity(entityId);
            var bubbleInfo = topology.getBubbleInfo(bubbleId);

            var distance = bubbleInfo.position().distance(snapshot.position());
            assertTrue(distance <= bubbleInfo.radius(),
                "Entity should be within bubble radius");
        }
    }

    @Test
    void testMetricsUpdatedAfterDistribution() {
        // When: Create entities
        entityFactory.createEntities(800);

        // Then: Metrics should reflect entity count
        var metrics = cluster.getMetrics();
        assertEquals(800, metrics.getTotalEntities());
    }
}
