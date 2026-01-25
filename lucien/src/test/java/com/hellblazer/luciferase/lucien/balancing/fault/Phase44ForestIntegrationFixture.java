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
package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.DistributedForestImpl;
import com.hellblazer.luciferase.lucien.balancing.InMemoryPartitionRegistry;
import com.hellblazer.luciferase.lucien.balancing.ParallelBalancer;
import com.hellblazer.luciferase.lucien.balancing.TwoOneBalanceChecker;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ForestConfig;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostBoundaryDetector;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GrpcGhostChannel;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration test fixture for Phase D forest integration work.
 *
 * <p>Provides real Forest infrastructure with Octree spatial structure,
 * GhostLayer integration, and 2:1 balance violation tracking for
 * downstream D.3-D.7 integration tests.
 *
 * <p><b>Features:</b>
 * <ul>
 *   <li>Real Octree forest with 100-200 entities in coherent clusters</li>
 *   <li>Multi-partition topology (4-5 partitions across spatial domain)</li>
 *   <li>GhostLayer integration with DistributedGhostManager</li>
 *   <li>2:1 balance violation detection and tracking</li>
 *   <li>Helper methods for balance state inspection</li>
 * </ul>
 *
 * <p><b>Usage Pattern:</b>
 * <pre>{@code
 * var fixture = new Phase44ForestIntegrationFixture();
 * var distributedForest = fixture.createForest();
 * fixture.syncGhostLayer();
 * var violations = fixture.findCurrentViolations();
 * fixture.assertBalanceInvariant();
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class Phase44ForestIntegrationFixture {

    private static final Logger log = LoggerFactory.getLogger(Phase44ForestIntegrationFixture.class);

    // Spatial domain: 1000x1000x1000 cube
    private static final float DOMAIN_SIZE = 1000.0f;
    private static final int DEFAULT_ENTITY_COUNT = 150;
    private static final int DEFAULT_PARTITION_COUNT = 4;

    // Test entity record
    public record TestEntity(UUID id, Point3f location, String data) {}

    // State tracking
    private Forest<MortonKey, LongEntityID, TestEntity> forest;
    private DistributedGhostManager<MortonKey, LongEntityID, TestEntity> ghostManager;
    private GhostBoundaryDetector<MortonKey, LongEntityID, TestEntity> ghostBoundaryDetector;
    private InMemoryPartitionRegistry partitionRegistry;
    private TwoOneBalanceChecker<MortonKey, LongEntityID, TestEntity> balanceChecker;

    private final SequentialLongIDGenerator idGenerator;
    private final Map<Integer, SpatialRegion> partitionBoundaries;
    private final Map<UUID, Integer> entityToPartition;
    private final List<TestEntity> allEntities;
    private final AtomicInteger partitionIdCounter;

    /**
     * Spatial region representing a partition boundary.
     */
    public record SpatialRegion(
        Point3f minCorner,
        Point3f maxCorner,
        int partitionId
    ) {
        public boolean contains(Point3f point) {
            return point.x >= minCorner.x && point.x <= maxCorner.x &&
                   point.y >= minCorner.y && point.y <= maxCorner.y &&
                   point.z >= minCorner.z && point.z <= maxCorner.z;
        }
    }

    /**
     * Create a new Phase 4.4 forest integration fixture.
     */
    public Phase44ForestIntegrationFixture() {
        this.idGenerator = new SequentialLongIDGenerator();
        this.partitionBoundaries = new HashMap<>();
        this.entityToPartition = new HashMap<>();
        this.allEntities = new ArrayList<>();
        this.partitionIdCounter = new AtomicInteger(0);
        this.balanceChecker = new TwoOneBalanceChecker<>();
    }

    /**
     * Create a real distributed forest with Octree spatial structure.
     *
     * <p>Creates:
     * <ul>
     *   <li>Octree with 150 entities in coherent spatial clusters</li>
     *   <li>4 partition boundaries across spatial domain</li>
     *   <li>DistributedGhostManager with GhostBoundaryDetector</li>
     *   <li>InMemoryPartitionRegistry for coordination</li>
     * </ul>
     *
     * @return distributed forest ready for balancing operations
     */
    public ParallelBalancer.DistributedForest<MortonKey, LongEntityID, TestEntity> createForest() {
        return createForest(DEFAULT_ENTITY_COUNT, DEFAULT_PARTITION_COUNT);
    }

    /**
     * Create a distributed forest with specified entity and partition counts.
     *
     * @param entityCount number of entities to populate
     * @param partitionCount number of partitions to create
     * @return distributed forest
     */
    public ParallelBalancer.DistributedForest<MortonKey, LongEntityID, TestEntity> createForest(
        int entityCount,
        int partitionCount
    ) {
        log.info("Creating distributed forest: {} entities, {} partitions", entityCount, partitionCount);

        // Step 1: Create partition boundaries
        definePartitionBoundaries(partitionCount);

        // Step 2: Create forest with Octree
        forest = new Forest<>(ForestConfig.defaultConfig());
        var octree = new Octree<LongEntityID, TestEntity>(idGenerator);
        forest.addTree(octree);

        // Step 3: Populate entities in coherent clusters
        populateEntitiesInClusters(octree, entityCount, partitionCount);

        // Step 4: Create ghost infrastructure
        ghostBoundaryDetector = new GhostBoundaryDetector<>(
            octree,
            octree.getNeighborDetector(),
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES,
            com.hellblazer.luciferase.lucien.forest.ghost.GhostAlgorithm.CONSERVATIVE
        );

        // Create mock GrpcGhostChannel for testing (no actual network communication)
        var mockGhostChannel = createMockGhostChannel(octree);

        ghostManager = new DistributedGhostManager<>(
            octree,
            mockGhostChannel,
            ghostBoundaryDetector
        );

        // Step 5: Create partition registry
        partitionRegistry = new InMemoryPartitionRegistry(partitionCount);

        // Step 6: Wrap in DistributedForestImpl
        var distributedForest = new DistributedForestImpl<>(
            forest,
            ghostManager,
            new PartitionRegistryAdapter(partitionRegistry, 0),
            0,  // Current partition rank
            partitionCount
        );

        log.info("Forest created: {} entities, {} trees, {} partitions",
                allEntities.size(), forest.getTreeCount(), partitionCount);

        return distributedForest;
    }

    /**
     * Synchronize ghost layer with current forest state.
     *
     * <p>Creates ghost boundary elements and syncs with DistributedGhostManager.
     */
    public void syncGhostLayer() {
        log.debug("Syncing ghost layer");
        ghostBoundaryDetector.createGhostLayer();
        ghostManager.createDistributedGhostLayer();
        log.debug("Ghost layer synced: {} ghost elements",
                 ghostBoundaryDetector.getGhostLayer().getNumGhostElements());
    }

    /**
     * Find current 2:1 balance violations across partition boundaries.
     *
     * @return list of violations found
     */
    public List<TwoOneBalanceChecker.BalanceViolation<MortonKey>> findCurrentViolations() {
        if (ghostBoundaryDetector == null) {
            return Collections.emptyList();
        }

        var ghostLayer = ghostBoundaryDetector.getGhostLayer();
        var violations = balanceChecker.findViolations(ghostLayer, forest);

        log.debug("Found {} balance violations", violations.size());
        return violations;
    }

    /**
     * Get the partition ID for a given entity.
     *
     * @param entity the entity
     * @return partition ID (0 to partitionCount-1)
     */
    public int getPartitionForEntity(TestEntity entity) {
        return entityToPartition.getOrDefault(entity.id(), -1);
    }

    /**
     * Get the ghost layer for boundary violation checking.
     *
     * @return ghost layer instance
     */
    public GhostLayer<MortonKey, LongEntityID, TestEntity> getGhostLayer() {
        if (ghostBoundaryDetector == null) {
            throw new IllegalStateException("Forest not created - call createForest() first");
        }
        return ghostBoundaryDetector.getGhostLayer();
    }

    /**
     * Assert that 2:1 balance invariant holds (no violations).
     *
     * @throws AssertionError if violations found
     */
    public void assertBalanceInvariant() {
        var violations = findCurrentViolations();
        if (!violations.isEmpty()) {
            var message = String.format("Found %d balance violations: %s",
                                      violations.size(),
                                      violations.subList(0, Math.min(5, violations.size())));
            throw new AssertionError(message);
        }
    }

    /**
     * Get all test entities created in this fixture.
     *
     * @return unmodifiable list of entities
     */
    public List<TestEntity> getAllEntities() {
        return Collections.unmodifiableList(allEntities);
    }

    /**
     * Get partition boundaries.
     *
     * @return map of partition ID to spatial region
     */
    public Map<Integer, SpatialRegion> getPartitionBoundaries() {
        return Collections.unmodifiableMap(partitionBoundaries);
    }

    /**
     * Get the forest instance.
     *
     * @return forest
     */
    public Forest<MortonKey, LongEntityID, TestEntity> getForest() {
        return forest;
    }

    // ========== Private Implementation ==========

    /**
     * Define partition boundaries by dividing spatial domain into regions.
     */
    private void definePartitionBoundaries(int partitionCount) {
        // Simple 2x2 or 2x2x1 grid partitioning
        if (partitionCount == 4) {
            // 2x2 grid in XY plane
            var halfSize = DOMAIN_SIZE / 2.0f;

            partitionBoundaries.put(0, new SpatialRegion(
                new Point3f(0, 0, 0),
                new Point3f(halfSize, halfSize, DOMAIN_SIZE),
                0
            ));

            partitionBoundaries.put(1, new SpatialRegion(
                new Point3f(halfSize, 0, 0),
                new Point3f(DOMAIN_SIZE, halfSize, DOMAIN_SIZE),
                1
            ));

            partitionBoundaries.put(2, new SpatialRegion(
                new Point3f(0, halfSize, 0),
                new Point3f(halfSize, DOMAIN_SIZE, DOMAIN_SIZE),
                2
            ));

            partitionBoundaries.put(3, new SpatialRegion(
                new Point3f(halfSize, halfSize, 0),
                new Point3f(DOMAIN_SIZE, DOMAIN_SIZE, DOMAIN_SIZE),
                3
            ));
        } else {
            // Fallback: simple linear partitioning along X axis
            var partitionWidth = DOMAIN_SIZE / partitionCount;
            for (var i = 0; i < partitionCount; i++) {
                partitionBoundaries.put(i, new SpatialRegion(
                    new Point3f(i * partitionWidth, 0, 0),
                    new Point3f((i + 1) * partitionWidth, DOMAIN_SIZE, DOMAIN_SIZE),
                    i
                ));
            }
        }

        log.debug("Defined {} partition boundaries", partitionBoundaries.size());
    }

    /**
     * Populate entities in coherent spatial clusters.
     */
    private void populateEntitiesInClusters(
        Octree<LongEntityID, TestEntity> octree,
        int entityCount,
        int partitionCount
    ) {
        var random = new Random(42); // Deterministic seed
        var entitiesPerPartition = entityCount / partitionCount;

        for (var partitionId = 0; partitionId < partitionCount; partitionId++) {
            var region = partitionBoundaries.get(partitionId);

            // Create cluster center within partition
            var clusterCenterX = region.minCorner.x +
                                (region.maxCorner.x - region.minCorner.x) * 0.5f;
            var clusterCenterY = region.minCorner.y +
                                (region.maxCorner.y - region.minCorner.y) * 0.5f;
            var clusterCenterZ = region.minCorner.z +
                                (region.maxCorner.z - region.minCorner.z) * 0.5f;

            // Populate entities around cluster center
            for (var i = 0; i < entitiesPerPartition; i++) {
                // Random offset within 25% of partition size
                var offsetRange = (region.maxCorner.x - region.minCorner.x) * 0.25f;
                var x = clusterCenterX + (random.nextFloat() - 0.5f) * offsetRange;
                var y = clusterCenterY + (random.nextFloat() - 0.5f) * offsetRange;
                var z = clusterCenterZ + (random.nextFloat() - 0.5f) * offsetRange;

                // Clamp to partition bounds
                x = Math.max(region.minCorner.x, Math.min(region.maxCorner.x, x));
                y = Math.max(region.minCorner.y, Math.min(region.maxCorner.y, y));
                z = Math.max(region.minCorner.z, Math.min(region.maxCorner.z, z));

                var location = new Point3f(x, y, z);
                var entityId = UUID.randomUUID();
                var entity = new TestEntity(
                    entityId,
                    location,
                    String.format("entity-p%d-%d", partitionId, i)
                );

                // Insert into octree
                octree.insert(
                    idGenerator.generateID(),
                    location,
                    (byte) 0,  // Level 0
                    entity,
                    null       // No custom bounds
                );

                // Track entity
                allEntities.add(entity);
                entityToPartition.put(entityId, partitionId);
            }
        }

        log.info("Populated {} entities across {} partitions", allEntities.size(), partitionCount);
    }

    /**
     * Create a mock GrpcGhostChannel for testing (no network communication).
     */
    @SuppressWarnings("unchecked")
    private GrpcGhostChannel<MortonKey, LongEntityID, TestEntity> createMockGhostChannel(
        Octree<LongEntityID, TestEntity> octree
    ) {
        // Use Mockito to create a minimal mock GhostCommunicationManager
        // The mock doesn't need to implement any methods for basic testing
        var mockCommManager = org.mockito.Mockito.mock(
            com.hellblazer.luciferase.lucien.forest.ghost.grpc.GhostCommunicationManager.class
        );

        return new GrpcGhostChannel<>(
            mockCommManager,  // Mock GhostCommunicationManager
            0,                // currentRank
            1L,               // treeId
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES
        );
    }

    /**
     * Adapter to bridge InMemoryPartitionRegistry to ParallelBalancer.PartitionRegistry.
     */
    private static class PartitionRegistryAdapter implements ParallelBalancer.PartitionRegistry {
        private final InMemoryPartitionRegistry delegate;
        private final int currentPartitionId;

        PartitionRegistryAdapter(InMemoryPartitionRegistry delegate, int currentPartitionId) {
            this.delegate = delegate;
            this.currentPartitionId = currentPartitionId;
        }

        @Override
        public int getCurrentPartitionId() {
            return currentPartitionId;
        }

        @Override
        public int getPartitionCount() {
            return delegate.getPartitionCount();
        }

        @Override
        public void barrier(int round) throws InterruptedException {
            delegate.barrier();
        }

        @Override
        public void requestRefinement(Object elementKey) {
            // No-op for testing - real implementation would coordinate refinements
        }

        @Override
        public int getPendingRefinements() {
            // No pending refinements in test fixture
            return 0;
        }
    }
}
