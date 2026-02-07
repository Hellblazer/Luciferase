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

import com.hellblazer.luciferase.simulation.distributed.migration.EntitySnapshot;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and distributing entities across bubbles in a cluster.
 * <p>
 * Generates entities with deterministic UUIDs (for reproducibility) and
 * distributes them across bubbles using a configurable strategy.
 * <p>
 * Phase 6B5.3: Entity Distribution & Initialization
 *
 * @author hal.hildebrand
 */
public class DistributedEntityFactory {

    private static final Logger log = LoggerFactory.getLogger(DistributedEntityFactory.class);

    private final TestProcessCluster cluster;
    private final EntityDistributionStrategy strategy;
    private final Random random;
    private final Map<UUID, EntitySnapshot> entitySnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> entityToBubble = new ConcurrentHashMap<>();
    private volatile Clock clock = Clock.system();

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Creates a factory with default round-robin distribution.
     *
     * @param cluster the test process cluster
     */
    public DistributedEntityFactory(TestProcessCluster cluster) {
        this(cluster, new RoundRobinDistributionStrategy(cluster.getTopology()));
    }

    /**
     * Creates a factory with default distribution and explicit seed.
     *
     * @param cluster the test process cluster
     * @param seed    random seed for reproducibility
     */
    public DistributedEntityFactory(TestProcessCluster cluster, long seed) {
        this(cluster, new RoundRobinDistributionStrategy(cluster.getTopology()), seed);
    }

    /**
     * Creates a factory with a custom distribution strategy.
     *
     * @param cluster  the test process cluster
     * @param strategy the distribution strategy
     */
    public DistributedEntityFactory(TestProcessCluster cluster, EntityDistributionStrategy strategy) {
        this(cluster, strategy, Clock.system().nanoTime());
    }

    /**
     * Creates a factory with custom distribution and explicit seed.
     *
     * @param cluster  the test process cluster
     * @param strategy the distribution strategy
     * @param seed     random seed for reproducibility
     */
    public DistributedEntityFactory(TestProcessCluster cluster, EntityDistributionStrategy strategy, long seed) {
        this.cluster = cluster;
        this.strategy = strategy;
        this.random = new Random(seed);
    }

    /**
     * Creates and distributes the specified number of entities.
     *
     * @param count number of entities to create
     */
    public void createEntities(int count) {
        log.info("Creating {} entities across {} bubbles", count, cluster.getTopology().getBubbleCount());

        var accountant = cluster.getEntityAccountant();
        var topology = cluster.getTopology();

        for (int i = 0; i < count; i++) {
            // Generate deterministic entity ID
            var entityId = generateEntityId(i);

            // Determine target bubble
            var bubbleId = strategy.selectBubble(entityId);
            var bubbleInfo = topology.getBubbleInfo(bubbleId);

            // Generate position within bubble bounds
            var position = generatePositionInBubble(bubbleInfo);

            // Create snapshot
            var snapshot = new EntitySnapshot(
                entityId.toString(),
                position,
                "TestEntity",
                bubbleId,
                1L,  // epoch
                1L,  // version
                clock.currentTimeMillis()
            );

            // Store locally
            entitySnapshots.put(entityId, snapshot);
            entityToBubble.put(entityId, bubbleId);

            // Register with accountant
            accountant.register(bubbleId, entityId);
        }

        // Update metrics
        cluster.getMetrics().setTotalEntities(count);

        log.info("Created {} entities, distribution: {}", count, getDistribution());
    }

    /**
     * Returns all entity IDs.
     *
     * @return set of entity UUIDs
     */
    public Set<UUID> getAllEntityIds() {
        return entitySnapshots.keySet();
    }

    /**
     * Returns the entity snapshot for an entity.
     *
     * @param entityId entity UUID
     * @return EntitySnapshot or null if not found
     */
    public EntitySnapshot getEntitySnapshot(UUID entityId) {
        return entitySnapshots.get(entityId);
    }

    /**
     * Returns the bubble that contains an entity.
     *
     * @param entityId entity UUID
     * @return bubble UUID or null if not found
     */
    public UUID getBubbleForEntity(UUID entityId) {
        return entityToBubble.get(entityId);
    }

    /**
     * Returns the distribution of entities across bubbles.
     *
     * @return map of bubble ID to entity count
     */
    public Map<UUID, Integer> getDistribution() {
        var distribution = new HashMap<UUID, Integer>();
        for (var bubbleId : entityToBubble.values()) {
            distribution.merge(bubbleId, 1, Integer::sum);
        }
        return distribution;
    }

    private UUID generateEntityId(int index) {
        // Deterministic UUID based on seed and index
        var seed = random.nextLong() ^ index;
        var entityRandom = new Random(seed);
        return new UUID(entityRandom.nextLong(), entityRandom.nextLong());
    }

    private Point3D generatePositionInBubble(TestProcessTopology.BubbleInfo bubbleInfo) {
        // Generate random position within bubble radius
        var theta = random.nextDouble() * 2 * Math.PI;
        var phi = random.nextDouble() * Math.PI;
        var r = random.nextDouble() * bubbleInfo.radius() * 0.9; // 90% of radius to stay inside

        var x = bubbleInfo.position().getX() + r * Math.sin(phi) * Math.cos(theta);
        var y = bubbleInfo.position().getY() + r * Math.sin(phi) * Math.sin(theta);
        var z = bubbleInfo.position().getZ() + r * Math.cos(phi);

        return new Point3D(x, y, z);
    }
}
