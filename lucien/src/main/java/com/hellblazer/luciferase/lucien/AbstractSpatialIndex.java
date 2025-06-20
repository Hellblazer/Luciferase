/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.balancing.DefaultBalancingStrategy;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancingStrategy;
import com.hellblazer.luciferase.lucien.collision.CollisionShape;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.visitor.TraversalContext;
import com.hellblazer.luciferase.lucien.visitor.TraversalStrategy;
import com.hellblazer.luciferase.lucien.visitor.TreeVisitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Abstract base class for spatial index implementations. Provides common functionality for entity management,
 * configuration, and basic spatial operations while allowing concrete implementations to specialize the spatial
 * decomposition strategy.
 *
 * @param <ID>       The type of EntityID used
 * @param <Content>  The type of content stored
 * @param <NodeType> The type of spatial node used by the implementation
 * @author hal.hildebrand
 */
public abstract class AbstractSpatialIndex<ID extends EntityID, Content, NodeType extends SpatialNodeStorage<ID>>
implements SpatialIndex<ID, Content> {

    private static final Logger log = LoggerFactory.getLogger(AbstractSpatialIndex.class);

    // Common fields
    protected final EntityManager<ID, Content>                        entityManager;
    protected final int                                               maxEntitiesPerNode;
    protected final byte                                              maxDepth;
    protected final EntitySpanningPolicy                              spanningPolicy;
    // Spatial index: Long index -> Node containing entity IDs
    protected final Map<Long, NodeType>                               spatialIndex;
    // Sorted spatial indices for efficient range queries
    protected final NavigableSet<Long>                                sortedSpatialIndices;
    // Read-write lock for thread safety
    protected final ReadWriteLock                                     lock;
    // Fine-grained locking strategy for high-concurrency operations
    protected final FineGrainedLockingStrategy<ID, Content, NodeType> lockingStrategy;
    protected final Set<Long>                                         deferredSubdivisionNodes = new HashSet<>();
    private final   TreeBalancer<ID>                                  treeBalancer;
    // Tree balancing support
    private         TreeBalancingStrategy<ID>                         balancingStrategy;
    private         boolean                                           autoBalancingEnabled = false;
    private         long                                              lastBalancingTime    = 0;
    
    // Bulk operation support
    protected       BulkOperationConfig                               bulkConfig               = new BulkOperationConfig();
    protected       boolean                                           bulkLoadingMode          = false;
    protected       BulkOperationProcessor<ID, Content>               bulkProcessor;
    protected       DeferredSubdivisionManager<ID, NodeType>          subdivisionManager;
    protected       SpatialNodePool<NodeType>                         nodePool;
    protected       ParallelBulkOperations<ID, Content, NodeType>     parallelOperations;
    protected       SubdivisionStrategy<ID, Content>                  subdivisionStrategy;
    protected       StackBasedTreeBuilder<ID, Content, NodeType>      treeBuilder;

    /**
     * Constructor with common parameters
     */
    protected AbstractSpatialIndex(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                                   EntitySpanningPolicy spanningPolicy) {
        this.entityManager = new EntityManager<>(idGenerator);
        this.maxEntitiesPerNode = maxEntitiesPerNode;
        this.maxDepth = maxDepth;
        this.spanningPolicy = Objects.requireNonNull(spanningPolicy);
        this.spatialIndex = new HashMap<>();
        this.sortedSpatialIndices = new SpatialIndexSet();
        this.lock = new ReentrantReadWriteLock();
        this.balancingStrategy = new DefaultBalancingStrategy<>();
        this.treeBalancer = createTreeBalancer();
        this.bulkProcessor = new BulkOperationProcessor<>(this);
        this.subdivisionManager = new DeferredSubdivisionManager<>();
        this.nodePool = new SpatialNodePool<>(this::createNode);
        this.parallelOperations = new ParallelBulkOperations<>(this, bulkProcessor,
                                                               ParallelBulkOperations.defaultConfig());
        this.lockingStrategy = new FineGrainedLockingStrategy<>(this, FineGrainedLockingStrategy.defaultConfig());
        this.subdivisionStrategy = createDefaultSubdivisionStrategy();
        this.treeBuilder = new StackBasedTreeBuilder<>(StackBasedTreeBuilder.defaultConfig(), this::getLevelFromIndex);
    }

    @Override
    public Stream<SpatialNode<ID>> boundedBy(Spatial volume) {
        validateSpatialConstraints(volume);

        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        lock.readLock().lock();
        try {
            // Must collect results inside lock to avoid concurrent modification
            List<SpatialNode<ID>> results = spatialRangeQuery(bounds, false).filter(
            entry -> isNodeContainedInVolume(entry.getKey(), volume)).map(
            entry -> new SpatialNode<>(entry.getKey(), new HashSet<>(entry.getValue().getEntityIds()))).collect(
            Collectors.toList());
            return results.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Stream<SpatialNode<ID>> bounding(Spatial volume) {
        validateSpatialConstraints(volume);

        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        lock.readLock().lock();
        try {
            // Must collect results inside lock to avoid concurrent modification
            List<SpatialNode<ID>> results = spatialRangeQuery(bounds, true).filter(
            entry -> doesNodeIntersectVolume(entry.getKey(), volume)).map(
            entry -> new SpatialNode<>(entry.getKey(), new HashSet<>(entry.getValue().getEntityIds()))).collect(
            Collectors.toList());
            return results.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<CollisionPair<ID, Content>> checkCollision(ID entityId1, ID entityId2) {
        if (entityId1.equals(entityId2)) {
            return Optional.empty();
        }

        lock.readLock().lock();
        try {
            EntityBounds bounds1 = entityManager.getEntityBounds(entityId1);
            EntityBounds bounds2 = entityManager.getEntityBounds(entityId2);

            // Quick early rejection check
            if (bounds1 != null && bounds2 != null) {
                // Both have bounds - quick AABB check
                if (!boundsIntersect(bounds1, bounds2)) {
                    return Optional.empty();
                }
            } else if (bounds1 == null && bounds2 == null) {
                // Both are points - check distance
                Point3f pos1 = entityManager.getEntityPosition(entityId1);
                Point3f pos2 = entityManager.getEntityPosition(entityId2);

                if (pos1 == null || pos2 == null) {
                    return Optional.empty();
                }

                float threshold = 0.1f; // Small threshold for point entities
                if (pos1.distance(pos2) > threshold) {
                    return Optional.empty();
                }
            }
            // For mixed types (one bounded, one point), skip early rejection and go to detailed check

            // Perform detailed collision check
            return performDetailedCollisionCheck(entityId1, entityId2);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Common Entity Management Methods =====

    @Override
    public void configureBulkOperations(BulkOperationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Bulk operation config cannot be null");
        }
        this.bulkConfig = config;
    }
    
    /**
     * Configure subdivision strategy
     */
    public void configureSubdivisionStrategy(SubdivisionStrategy<ID, Content> strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("Subdivision strategy cannot be null");
        }
        this.subdivisionStrategy = strategy;
    }
    
    /**
     * Get current subdivision strategy
     */
    public SubdivisionStrategy<ID, Content> getSubdivisionStrategy() {
        return subdivisionStrategy;
    }
    
    /**
     * Configure stack-based tree builder
     */
    public void configureTreeBuilder(StackBasedTreeBuilder.BuildConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Tree builder config cannot be null");
        }
        this.treeBuilder = new StackBasedTreeBuilder<>(config, this::getLevelFromIndex);
    }
    
    /**
     * Build tree using stack-based approach for better cache locality
     */
    public StackBasedTreeBuilder.BuildResult buildTreeStackBased(List<Point3f> positions, 
                                                                 List<Content> contents, 
                                                                 byte startLevel) {
        if (positions.size() != contents.size()) {
            throw new IllegalArgumentException("Positions and contents must have the same size");
        }
        
        lock.writeLock().lock();
        try {
            // Clear existing tree if needed
            if (!spatialIndex.isEmpty()) {
                spatialIndex.clear();
                sortedSpatialIndices.clear();
                entityManager.clear();
            }
            
            // Build tree
            return treeBuilder.buildTree(this, positions, contents, startLevel);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Configure parallel bulk operations
     */
    public void configureParallelOperations(ParallelBulkOperations.ParallelConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Parallel config cannot be null");
        }
        this.parallelOperations = new ParallelBulkOperations<>(this, bulkProcessor, config);
    }

    @Override
    public boolean containsEntity(ID entityId) {
        lock.readLock().lock();
        try {
            return entityManager.containsEntity(entityId);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void enableBulkLoading() {
        lock.writeLock().lock();
        try {
            this.bulkLoadingMode = true;
            this.deferredSubdivisionNodes.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int entityCount() {
        lock.readLock().lock();
        try {
            return entityManager.getEntityCount();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void finalizeBulkLoading() {
        lock.writeLock().lock();
        try {
            this.bulkLoadingMode = false;

            // Process all deferred subdivisions using the manager
            if (bulkConfig.isDeferSubdivision()) {
                DeferredSubdivisionManager.SubdivisionResult result = subdivisionManager.processAll(
                new DeferredSubdivisionManager.SubdivisionProcessor<ID, NodeType>() {
                    @Override
                    public Result subdivideNode(long nodeIndex, NodeType node, byte level) {
                        int initialCount = spatialIndex.size();
                        int entityCount = node.getEntityCount();

                        // Only subdivide if still over threshold
                        if (entityCount > maxEntitiesPerNode && level < maxDepth) {
                            handleNodeSubdivision(nodeIndex, level, node);
                            int newNodes = spatialIndex.size() - initialCount;
                            return new Result(true, newNodes, entityCount);
                        }
                        return new Result(false, 0, 0);
                    }
                });

                // Log deferred subdivision results
                if (result.nodesProcessed > 0) {
                    log.debug("Deferred subdivisions: {} processed, {} subdivided, {} new nodes in {:.2f}ms",
                              result.nodesProcessed, result.nodesSubdivided, result.newNodesCreated,
                              result.getProcessingTimeMs());
                }
            }

            deferredSubdivisionNodes.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<CollisionPair<ID, Content>> findAllCollisions() {
        lock.readLock().lock();
        try {
            List<CollisionPair<ID, Content>> collisions = new ArrayList<>();
            Set<UnorderedPair<ID>> checkedPairs = new HashSet<>();

            // Locality-constrained collision detection: Only check spatially adjacent entities
            // This avoids the O(n²) problem of checking every entity against every other entity

            for (Long nodeIndex : sortedSpatialIndices) {
                NodeType node = spatialIndex.get(nodeIndex);
                if (node == null || node.isEmpty()) {
                    continue;
                }

                List<ID> nodeEntities = new ArrayList<>(node.getEntityIds());

                // 1. Check entities within the same node (spatial locality guarantee)
                for (int i = 0; i < nodeEntities.size(); i++) {
                    ID id1 = nodeEntities.get(i);

                    for (int j = i + 1; j < nodeEntities.size(); j++) {
                        ID id2 = nodeEntities.get(j);
                        UnorderedPair<ID> pair = new UnorderedPair<>(id1, id2);

                        if (checkedPairs.add(pair)) {
                            checkAndAddCollision(id1, id2, collisions);
                        }
                    }

                    // 2. For bounded entities, check spatial bounds intersection with nearby nodes
                    EntityBounds bounds = entityManager.getEntityBounds(id1);
                    if (bounds != null) {
                        // Only search nodes that could intersect with this entity's bounds
                        Set<Long> intersectingNodes = findNodesIntersectingBounds(bounds);

                        for (Long intersectingNodeIndex : intersectingNodes) {
                            // Skip self (we already checked entities within the same node above)
                            if (intersectingNodeIndex.equals(nodeIndex)) {
                                continue;
                            }

                            NodeType intersectingNode = spatialIndex.get(intersectingNodeIndex);
                            if (intersectingNode == null || intersectingNode.isEmpty()) {
                                continue;
                            }

                            for (ID otherId : intersectingNode.getEntityIds()) {
                                UnorderedPair<ID> pair = new UnorderedPair<>(id1, otherId);
                                if (checkedPairs.add(pair)) {
                                    checkAndAddCollision(id1, otherId, collisions);
                                }
                            }
                        }
                    }
                }

                // 3. Check against immediate neighboring nodes (for point entities and close proximity)
                Queue<Long> neighbors = new LinkedList<>();
                Set<Long> visitedNeighbors = new HashSet<>();
                addNeighboringNodes(nodeIndex, neighbors, visitedNeighbors);

                while (!neighbors.isEmpty()) {
                    Long neighborIndex = neighbors.poll();

                    // Skip already processed nodes (SFC ordering optimization)
                    if (neighborIndex < nodeIndex) {
                        continue;
                    }

                    NodeType neighborNode = spatialIndex.get(neighborIndex);
                    if (neighborNode == null || neighborNode.isEmpty()) {
                        continue;
                    }

                    // Check entities between adjacent nodes
                    for (ID id1 : nodeEntities) {
                        // Skip bounded entities (already handled above)
                        if (entityManager.getEntityBounds(id1) != null) {
                            continue;
                        }

                        for (ID id2 : neighborNode.getEntityIds()) {
                            UnorderedPair<ID> pair = new UnorderedPair<>(id1, id2);
                            if (checkedPairs.add(pair)) {
                                checkAndAddCollision(id1, id2, collisions);
                            }
                        }
                    }
                }
            }

            // Sort by penetration depth (deepest first)
            Collections.sort(collisions);
            return collisions;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CollisionPair<ID, Content>> findCollisions(ID entityId) {
        lock.readLock().lock();
        try {
            List<CollisionPair<ID, Content>> collisions = new ArrayList<>();

            // Get entity's locations
            Set<Long> locations = entityManager.getEntityLocations(entityId);
            if (locations.isEmpty()) {
                return collisions;
            }

            Set<ID> checkedEntities = new HashSet<>();
            checkedEntities.add(entityId);

            // Get entity bounds for enhanced search
            EntityBounds entityBounds = entityManager.getEntityBounds(entityId);

            if (entityBounds != null) {
                // For bounded entities, find all nodes that intersect with the bounds
                Set<Long> nodesToCheck = findNodesIntersectingBounds(entityBounds);

                // Check all nodes that intersect with the entity's bounds
                for (Long nodeIndex : nodesToCheck) {
                    NodeType node = spatialIndex.get(nodeIndex);
                    if (node == null || node.isEmpty()) {
                        continue;
                    }

                    // Check against all entities in these nodes
                    for (ID otherId : node.getEntityIds()) {
                        if (!checkedEntities.add(otherId)) {
                            continue;
                        }
                        checkAndAddCollision(entityId, otherId, collisions);
                    }
                }
            } else {
                // For point entities, use the original neighbor-based approach
                for (Long nodeIndex : locations) {
                    NodeType node = spatialIndex.get(nodeIndex);
                    if (node == null) {
                        continue;
                    }

                    // Check against other entities in the same node
                    for (ID otherId : node.getEntityIds()) {
                        if (!checkedEntities.add(otherId)) {
                            continue;
                        }
                        checkAndAddCollision(entityId, otherId, collisions);
                    }

                    // Check neighboring nodes
                    Queue<Long> neighbors = new LinkedList<>();
                    Set<Long> visitedNeighbors = new HashSet<>();
                    addNeighboringNodes(nodeIndex, neighbors, visitedNeighbors);

                    while (!neighbors.isEmpty()) {
                        Long neighborIndex = neighbors.poll();
                        NodeType neighborNode = spatialIndex.get(neighborIndex);
                        if (neighborNode == null || neighborNode.isEmpty()) {
                            continue;
                        }

                        for (ID otherId : neighborNode.getEntityIds()) {
                            if (!checkedEntities.add(otherId)) {
                                continue;
                            }
                            checkAndAddCollision(entityId, otherId, collisions);
                        }
                    }
                }
            }

            Collections.sort(collisions);
            return collisions;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Common Insert Operations =====

    @Override
    public List<CollisionPair<ID, Content>> findCollisionsInRegion(Spatial region) {
        lock.readLock().lock();
        try {
            List<CollisionPair<ID, Content>> collisions = new ArrayList<>();
            Set<UnorderedPair<ID>> checkedPairs = new HashSet<>();

            // Find all nodes intersecting the region
            var bounds = getVolumeBounds(region);
            if (bounds == null) {
                return collisions;
            }

            Stream<Map.Entry<Long, NodeType>> nodesInRegion = spatialRangeQuery(bounds, true);
            List<Map.Entry<Long, NodeType>> nodeList = nodesInRegion.collect(Collectors.toList());

            // Check entities within and between nodes
            for (int i = 0; i < nodeList.size(); i++) {
                List<ID> nodeEntities = new ArrayList<>(nodeList.get(i).getValue().getEntityIds());

                // Check within node
                for (int j = 0; j < nodeEntities.size(); j++) {
                    for (int k = j + 1; k < nodeEntities.size(); k++) {
                        ID id1 = nodeEntities.get(j);
                        ID id2 = nodeEntities.get(k);

                        // Check if both entities are actually in the region
                        if (isEntityInRegion(id1, region) && isEntityInRegion(id2, region)) {
                            UnorderedPair<ID> pair = new UnorderedPair<>(id1, id2);
                            if (checkedPairs.add(pair)) {
                                checkAndAddCollision(id1, id2, collisions);
                            }
                        }
                    }
                }

                // Check between nodes
                for (int j = i + 1; j < nodeList.size(); j++) {
                    for (ID id1 : nodeEntities) {
                        if (!isEntityInRegion(id1, region)) {
                            continue;
                        }

                        for (ID id2 : nodeList.get(j).getValue().getEntityIds()) {
                            if (!isEntityInRegion(id2, region)) {
                                continue;
                            }

                            UnorderedPair<ID> pair = new UnorderedPair<>(id1, id2);
                            if (checkedPairs.add(pair)) {
                                checkAndAddCollision(id1, id2, collisions);
                            }
                        }
                    }
                }
            }

            Collections.sort(collisions);
            return collisions;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find all entities that are completely inside the frustum (not partially visible).
     *
     * @param frustum        the frustum to test
     * @param cameraPosition the camera position for distance sorting
     * @return list of entities completely inside the frustum, sorted by distance from camera
     */
    public List<FrustumIntersection<ID, Content>> frustumCullInside(Frustum3D frustum, Point3f cameraPosition) {
        return frustumCullVisible(frustum, cameraPosition).stream()
                                                          .filter(FrustumIntersection::isCompletelyInside)
                                                          .collect(Collectors.toList());
    }

    /**
     * Find all entities that intersect the frustum boundary (partially visible).
     *
     * @param frustum        the frustum to test
     * @param cameraPosition the camera position for distance sorting
     * @return list of entities intersecting frustum boundary, sorted by distance from camera
     */
    public List<FrustumIntersection<ID, Content>> frustumCullIntersecting(Frustum3D frustum, Point3f cameraPosition) {
        return frustumCullVisible(frustum, cameraPosition).stream()
                                                          .filter(FrustumIntersection::isPartiallyVisible)
                                                          .collect(Collectors.toList());
    }

    /**
     * Find all entities that are visible within the given frustum. Returns entities that are either completely inside
     * or intersecting the frustum.
     *
     * @param frustum        the frustum to test
     * @param cameraPosition the camera position for distance sorting
     * @return list of frustum intersections sorted by distance from camera
     */
    public List<FrustumIntersection<ID, Content>> frustumCullVisible(Frustum3D frustum, Point3f cameraPosition) {
        lock.readLock().lock();
        try {
            List<FrustumIntersection<ID, Content>> intersections = new ArrayList<>();
            Set<ID> visitedEntities = new HashSet<>();

            // Traverse nodes that could intersect with the frustum
            getFrustumTraversalOrder(frustum, cameraPosition).forEach(nodeIndex -> {
                NodeType node = spatialIndex.get(nodeIndex);
                if (node == null || node.isEmpty()) {
                    return;
                }

                // Check if frustum intersects this node
                if (!doesFrustumIntersectNode(nodeIndex, frustum)) {
                    return;
                }

                // Check each entity in the node
                for (ID entityId : node.getEntityIds()) {
                    // Skip if already processed (for spanning entities)
                    if (!visitedEntities.add(entityId)) {
                        continue;
                    }

                    Content content = entityManager.getEntityContent(entityId);
                    if (content == null) {
                        continue;
                    }

                    Point3f entityPos = entityManager.getEntityPosition(entityId);
                    EntityBounds bounds = entityManager.getEntityBounds(entityId);

                    // Calculate frustum-entity intersection
                    FrustumIntersection<ID, Content> intersection = calculateFrustumEntityIntersection(frustum,
                                                                                                       cameraPosition,
                                                                                                       entityId,
                                                                                                       content,
                                                                                                       entityPos,
                                                                                                       bounds);

                    if (intersection != null && intersection.isVisible()) {
                        intersections.add(intersection);
                    }
                }
            });

            Collections.sort(intersections);
            return intersections;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find all entities within the specified distance from the camera that are visible in the frustum.
     *
     * @param frustum        the frustum to test
     * @param cameraPosition the camera position
     * @param maxDistance    maximum distance from camera to include
     * @return list of entities within distance, sorted by distance from camera
     */
    public List<FrustumIntersection<ID, Content>> frustumCullWithinDistance(Frustum3D frustum, Point3f cameraPosition,
                                                                            float maxDistance) {
        return frustumCullVisible(frustum, cameraPosition).stream().filter(
        intersection -> intersection.distanceFromCamera() <= maxDistance).collect(Collectors.toList());
    }

    /**
     * Get balancing statistics for the tree.
     */
    public TreeBalancingStrategy.TreeBalancingStats getBalancingStats() {
        lock.readLock().lock();
        try {
            int totalNodes = 0;
            int underpopulatedNodes = 0;
            int overpopulatedNodes = 0;
            int emptyNodes = 0;
            int maxDepth = 0;
            long totalEntities = 0;

            // SFC-optimized balancing stats: Process nodes in spatial order for better cache locality
            // This improves memory access patterns during statistics calculation
            for (Long nodeIndex : sortedSpatialIndices) {
                NodeType node = spatialIndex.get(nodeIndex);
                if (node == null) {
                    continue;
                }

                byte level = getLevelFromIndex(nodeIndex);
                maxDepth = Math.max(maxDepth, level);

                if (node.isEmpty()) {
                    emptyNodes++;
                    totalNodes++;
                } else {
                    totalNodes++;
                    int entityCount = node.getEntityCount();
                    totalEntities += entityCount;

                    int mergeThreshold = balancingStrategy.getMergeThreshold(level, maxEntitiesPerNode);
                    int splitThreshold = balancingStrategy.getSplitThreshold(level, maxEntitiesPerNode);

                    if (entityCount < mergeThreshold) {
                        underpopulatedNodes++;
                    } else if (entityCount > splitThreshold) {
                        overpopulatedNodes++;
                    }
                }
            }

            double averageLoad = totalNodes > 0 ? (double) totalEntities / totalNodes : 0;

            // Calculate variance using SFC ordering for improved cache performance
            double variance = 0;
            if (totalNodes > 0) {
                for (Long nodeIndex : sortedSpatialIndices) {
                    NodeType node = spatialIndex.get(nodeIndex);
                    if (node != null) {
                        double diff = node.getEntityCount() - averageLoad;
                        variance += diff * diff;
                    }
                }
                variance /= totalNodes;
            }

            return new TreeBalancingStrategy.TreeBalancingStats(totalNodes, underpopulatedNodes, overpopulatedNodes,
                                                                emptyNodes, maxDepth, averageLoad, variance);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Common Remove Operations =====

    @Override
    public CollisionShape getCollisionShape(ID entityId) {
        lock.readLock().lock();
        try {
            return entityManager.getEntityCollisionShape(entityId);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Content> getEntities(List<ID> entityIds) {
        lock.readLock().lock();
        try {
            return entityManager.getEntitiesContent(entityIds);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<ID, Point3f> getEntitiesWithPositions() {
        lock.readLock().lock();
        try {
            return entityManager.getEntitiesWithPositions();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Content getEntity(ID entityId) {
        lock.readLock().lock();
        try {
            return entityManager.getEntityContent(entityId);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Common Update Operations =====

    @Override
    public EntityBounds getEntityBounds(ID entityId) {
        lock.readLock().lock();
        try {
            return entityManager.getEntityBounds(entityId);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Common Query Operations =====

    @Override
    public Point3f getEntityPosition(ID entityId) {
        lock.readLock().lock();
        try {
            return entityManager.getEntityPosition(entityId);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int getEntitySpanCount(ID entityId) {
        lock.readLock().lock();
        try {
            return entityManager.getEntitySpanCount(entityId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get memory usage statistics for capacity planning
     */
    public MemoryStats getMemoryStats() {
        lock.readLock().lock();
        try {
            int nodeCount = spatialIndex.size();
            long totalEntities = entityManager.getEntityCount();
            float avgEntitiesPerNode = nodeCount > 0 ? (float) totalEntities / nodeCount : 0;

            long estimatedMemory = NodeEstimator.estimateMemoryUsage(nodeCount, (int) Math.ceil(avgEntitiesPerNode));

            return new MemoryStats(nodeCount, totalEntities, avgEntitiesPerNode, estimatedMemory);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get parallel operations performance statistics
     */
    public Map<String, Object> getParallelPerformanceStats() {
        return parallelOperations.getPerformanceStatistics();
    }

    // ===== Common Statistics =====

    @Override
    public NavigableMap<Long, Set<ID>> getSpatialMap() {
        lock.readLock().lock();
        try {
            NavigableMap<Long, Set<ID>> map = new TreeMap<>();
            for (var entry : getSpatialIndex().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    map.put(entry.getKey(), new HashSet<>(entry.getValue().getEntityIds()));
                }
            }
            return map;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Common Utility Methods =====

    @Override
    public EntityStats getStats() {
        lock.readLock().lock();
        try {
            int nodeCount = 0;
            int entityCount = entityManager.getEntityCount();
            int totalEntityReferences = 0;
            int maxDepth = 0;

            for (Map.Entry<Long, NodeType> entry : getSpatialIndex().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    nodeCount++;
                }
                totalEntityReferences += entry.getValue().getEntityCount();

                // Calculate depth from spatial index
                byte depth = getLevelFromIndex(entry.getKey());
                maxDepth = Math.max(maxDepth, depth);
            }

            return new EntityStats(nodeCount, entityCount, totalEntityReferences, maxDepth);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean hasNode(long spatialIndex) {
        lock.readLock().lock();
        try {
            NodeType node = getSpatialIndex().get(spatialIndex);
            return node != null && !node.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public ID insert(Point3f position, byte level, Content content) {
        lock.writeLock().lock();
        try {
            ID entityId = entityManager.generateEntityId();
            insert(entityId, position, level, content);
            return entityId;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void insert(ID entityId, Point3f position, byte level, Content content) {
        lock.writeLock().lock();
        try {
            insert(entityId, position, level, content, null);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void insert(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds) {
        lock.writeLock().lock();
        try {
            // Validate spatial constraints
            validateSpatialConstraints(position);

            // Create or update entity
            entityManager.createOrUpdateEntity(entityId, content, position, bounds);

            // If spanning is enabled and entity has bounds, check for spanning
            if (spanningPolicy.isSpanningEnabled() && bounds != null) {
                // Use advanced spanning logic
                if (shouldSpanEntity(bounds, level)) {
                    insertWithAdvancedSpanning(entityId, bounds, level);
                } else {
                    // Standard single-node insertion even with bounds
                    insertAtPosition(entityId, position, level);
                }
            } else {
                // Standard single-node insertion
                insertAtPosition(entityId, position, level);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ===== Common k-NN Search Implementation =====

    @Override
    public List<ID> insertBatch(List<Point3f> positions, List<Content> contents, byte level) {
        if (positions == null || contents == null) {
            throw new IllegalArgumentException("Positions and contents cannot be null");
        }
        if (positions.size() != contents.size()) {
            throw new IllegalArgumentException("Positions and contents must have the same size");
        }
        if (positions.isEmpty()) {
            return Collections.emptyList();
        }

        long startTime = System.nanoTime();
        List<ID> insertedIds = new ArrayList<>(positions.size());

        lock.writeLock().lock();
        try {
            // Check if we should use stack-based builder for this bulk operation
            if (bulkConfig.isUseStackBasedBuilder() && positions.size() >= bulkConfig.getStackBuilderThreshold()) {
                // Configure tree builder with the provided config
                configureTreeBuilder(bulkConfig.getStackBuilderConfig());
                
                // Use stack-based builder for efficient bulk construction
                StackBasedTreeBuilder.BuildResult<ID> buildResult = treeBuilder.buildTree(this, positions, contents, level);
                
                // Log performance metrics
                long elapsedMs = buildResult.timeTaken;
                log.debug("Stack-based bulk insertion completed: {} entities in {}ms, {} nodes created",
                        buildResult.entitiesProcessed, elapsedMs, buildResult.nodesCreated);
                
                // Return the actual inserted IDs from the builder
                return buildResult.insertedIds;
            }
            
            // Enable bulk loading mode if configured
            boolean wasInBulkMode = bulkLoadingMode;
            if (bulkConfig.isDeferSubdivision() && !bulkLoadingMode) {
                enableBulkLoading();
            }

            // Use BulkOperationProcessor for optimized processing
            boolean useParallel = bulkConfig.isEnableParallel()
            && positions.size() >= bulkConfig.getParallelThreshold();

            List<BulkOperationProcessor.MortonEntity<Content>> mortonEntities;
            if (useParallel) {
                mortonEntities = bulkProcessor.preprocessBatchParallel(positions, contents, level,
                                                                       bulkConfig.isPreSortByMorton(),
                                                                       bulkConfig.getParallelThreshold());
            } else {
                mortonEntities = bulkProcessor.preprocessBatch(positions, contents, level,
                                                               bulkConfig.isPreSortByMorton());
            }

            // Group by spatial node if batch is large enough
            if (positions.size() > bulkConfig.getBatchSize()) {
                BulkOperationProcessor.GroupedEntities<Content> grouped = bulkProcessor.groupByNode(mortonEntities,
                                                                                                    level);

                // Process each group
                for (Map.Entry<Long, List<BulkOperationProcessor.MortonEntity<Content>>> entry : grouped.getGroups()
                                                                                                        .entrySet()) {
                    for (BulkOperationProcessor.MortonEntity<Content> entity : entry.getValue()) {
                        ID entityId = entityManager.generateEntityId();
                        insertedIds.add(entityId);

                        // Store entity data
                        entityManager.createOrUpdateEntity(entityId, entity.content, entity.position, null);

                        // Insert at position
                        insertAtPosition(entityId, entity.position, level);
                    }
                }
            } else {
                // Process directly for smaller batches
                for (BulkOperationProcessor.MortonEntity<Content> entity : mortonEntities) {
                    ID entityId = entityManager.generateEntityId();
                    insertedIds.add(entityId);

                    // Store entity data
                    entityManager.createOrUpdateEntity(entityId, entity.content, entity.position, null);

                    // Insert at position
                    insertAtPosition(entityId, entity.position, level);
                }
            }

            // Restore bulk mode state
            if (!wasInBulkMode && bulkConfig.isDeferSubdivision()) {
                finalizeBulkLoading();
            }

        } finally {
            lock.writeLock().unlock();
        }

        long elapsedTime = System.nanoTime() - startTime;

        // Log performance if significant batch
        if (positions.size() > 1000) {
            double rate = positions.size() * 1_000_000_000.0 / elapsedTime;
            log.debug("Bulk inserted {} entities in {:.2f}ms ({:.0f} entities/sec)", positions.size(),
                              elapsedTime / 1_000_000.0, rate);
        }

        return insertedIds;
    }

    /**
     * Perform parallel bulk insertion for large datasets
     */
    public ParallelBulkOperations.ParallelOperationResult<ID> insertBatchParallel(List<Point3f> positions,
                                                                                  List<Content> contents, byte level)
    throws InterruptedException {
        return parallelOperations.insertBatchParallel(positions, contents, level);
    }

    @Override
    public List<ID> insertBatchWithSpanning(List<EntityBounds> bounds, List<Content> contents, byte level) {
        if (bounds == null || contents == null) {
            throw new IllegalArgumentException("Bounds and contents cannot be null");
        }
        if (bounds.size() != contents.size()) {
            throw new IllegalArgumentException("Bounds and contents must have the same size");
        }
        if (bounds.isEmpty()) {
            return Collections.emptyList();
        }

        long startTime = System.nanoTime();
        List<ID> insertedIds = new ArrayList<>(bounds.size());

        lock.writeLock().lock();
        try {
            // Enable bulk loading mode if configured
            boolean wasInBulkMode = bulkLoadingMode;
            if (bulkConfig.isDeferSubdivision() && !bulkLoadingMode) {
                enableBulkLoading();
            }

            // Process each entity with bounds
            for (int i = 0; i < bounds.size(); i++) {
                EntityBounds entityBounds = bounds.get(i);
                Content content = contents.get(i);

                // Calculate center position
                Point3f center = new Point3f((entityBounds.getMinX() + entityBounds.getMaxX()) / 2,
                                             (entityBounds.getMinY() + entityBounds.getMaxY()) / 2,
                                             (entityBounds.getMinZ() + entityBounds.getMaxZ()) / 2);

                // Generate ID and store entity
                ID entityId = entityManager.generateEntityId();
                insertedIds.add(entityId);
                entityManager.createOrUpdateEntity(entityId, content, center, entityBounds);

                // Handle spanning if configured
                float entitySize = Math.max(entityBounds.getMaxX() - entityBounds.getMinX(),
                                            Math.max(entityBounds.getMaxY() - entityBounds.getMinY(),
                                                     entityBounds.getMaxZ() - entityBounds.getMinZ()));
                float nodeSize = getCellSizeAtLevel(level);
                if (spanningPolicy.shouldSpan(entitySize, nodeSize)) {
                    insertWithSpanning(entityId, entityBounds, level);
                } else {
                    insertAtPosition(entityId, center, level);
                }
            }

            // Restore bulk mode state
            if (!wasInBulkMode && bulkConfig.isDeferSubdivision()) {
                finalizeBulkLoading();
            }

        } finally {
            lock.writeLock().unlock();
        }

        long elapsedTime = System.nanoTime() - startTime;

        // Log performance if significant batch
        if (bounds.size() > 1000) {
            double rate = bounds.size() * 1_000_000_000.0 / elapsedTime;
            log.debug("Bulk inserted {} entities with spanning in {:.2f}ms ({:.0f} entities/sec)", bounds.size(),
                              elapsedTime / 1_000_000.0, rate);
        }

        return insertedIds;
    }

    // ===== Common Spatial Query Base =====

    /**
     * Check if automatic balancing is enabled.
     */
    public boolean isAutoBalancingEnabled() {
        lock.readLock().lock();
        try {
            return autoBalancingEnabled;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    // Package-private accessors for StackBasedTreeBuilder
    EntityManager<ID, Content> getEntityManager() {
        return entityManager;
    }
    
    ID generateId() {
        return entityManager.generateEntityId();
    }
    
    int getMaxEntitiesPerNode() {
        return maxEntitiesPerNode;
    }
    
    byte getMaxDepth() {
        return maxDepth;
    }
    

    /**
     * Find k nearest neighbors to a query point using spatial locality optimization
     *
     * @param queryPoint  the point to search from
     * @param k           the number of neighbors to find
     * @param maxDistance maximum search distance
     * @return list of entity IDs sorted by distance (closest first)
     */
    public List<ID> kNearestNeighbors(Point3f queryPoint, int k, float maxDistance) {
        validateSpatialConstraints(queryPoint);

        if (k <= 0) {
            return Collections.emptyList();
        }

        // Use fine-grained locking for read operations
        return lockingStrategy.executeRead(0L, () -> {
            // Priority queue to keep track of k nearest entities (max heap)
            PriorityQueue<EntityDistance<ID>> candidates = new PriorityQueue<>(EntityDistance.maxHeapComparator());

            // Track visited entities to avoid duplicates
            Set<ID> visited = new HashSet<>();

            // Use spatial locality: start with nodes closest to query point and expand outward
            // This avoids the O(n) problem of checking all nodes

            // Create expanding search radius around query point
            float searchRadius = Math.min(maxDistance, getCellSizeAtLevel(maxDepth));
            int searchExpansions = 0;
            final int maxExpansions = 5; // Limit search expansion to prevent runaway searches

            while (candidates.size() < k && searchRadius <= maxDistance && searchExpansions < maxExpansions) {
                // Create search bounds around query point
                VolumeBounds searchBounds = new VolumeBounds(queryPoint.x - searchRadius, queryPoint.y - searchRadius,
                                                             queryPoint.z - searchRadius, queryPoint.x + searchRadius,
                                                             queryPoint.y + searchRadius, queryPoint.z + searchRadius);

                // Find nodes that intersect with search bounds (spatial locality constraint)
                boolean foundNewEntities = false;
                spatialRangeQuery(searchBounds, true).forEach(entry -> {
                    Long nodeIndex = entry.getKey();
                    NodeType node = entry.getValue();

                    if (node == null || node.isEmpty()) {
                        return;
                    }

                    // Check all entities in this spatially-relevant node
                    for (ID entityId : node.getEntityIds()) {
                        if (visited.add(entityId)) {
                            Point3f entityPos = entityManager.getEntityPosition(entityId);
                            if (entityPos != null) {
                                float distance = queryPoint.distance(entityPos);
                                if (distance <= maxDistance) {
                                    candidates.add(new EntityDistance<>(entityId, distance));

                                    // Keep only k elements (maintain heap property)
                                    if (candidates.size() > k) {
                                        candidates.poll();
                                    }
                                }
                            }
                        }
                    }
                });

                // If we didn't find enough candidates, expand search radius
                if (candidates.size() < k) {
                    searchRadius *= 2.0f;
                    searchExpansions++;
                } else {
                    break;
                }
            }

            // If still no candidates found and search area is small, do a targeted search
            // using the sorted spatial indices for better cache locality
            if (candidates.isEmpty() && !sortedSpatialIndices.isEmpty()) {
                // Find the nearest nodes to query point using SFC ordering
                float bestDistance = Float.MAX_VALUE;
                Long nearestNodeIndex = null;

                // Use SFC ordering to find the nearest node efficiently
                for (Long nodeIndex : sortedSpatialIndices) {
                    NodeType node = spatialIndex.get(nodeIndex);
                    if (node == null || node.isEmpty()) {
                        continue;
                    }

                    // Estimate distance from query point to node center
                    float nodeDistance = estimateNodeDistance(nodeIndex, queryPoint);
                    if (nodeDistance < bestDistance) {
                        bestDistance = nodeDistance;
                        nearestNodeIndex = nodeIndex;
                    }

                    // Early termination: if we find a very close node, start there
                    if (nodeDistance < getCellSizeAtLevel(maxDepth)) {
                        break;
                    }
                }

                // If we found a starting node, search from there
                if (nearestNodeIndex != null) {
                    Queue<Long> toVisit = new LinkedList<>();
                    Set<Long> visitedNodes = new HashSet<>();
                    toVisit.add(nearestNodeIndex);

                    // Breadth-first search from nearest node with distance-based pruning
                    while (!toVisit.isEmpty() && candidates.size() < k) {
                        Long current = toVisit.poll();
                        if (!visitedNodes.add(current)) {
                            continue;
                        }

                        NodeType node = spatialIndex.get(current);
                        if (node == null) {
                            continue;
                        }

                        // Check entities in current node
                        for (ID entityId : node.getEntityIds()) {
                            if (visited.add(entityId)) {
                                Point3f entityPos = entityManager.getEntityPosition(entityId);
                                if (entityPos != null) {
                                    float distance = queryPoint.distance(entityPos);
                                    if (distance <= maxDistance) {
                                        candidates.add(new EntityDistance<>(entityId, distance));
                                        if (candidates.size() > k) {
                                            candidates.poll();
                                        }
                                    }
                                }
                            }
                        }

                        // Add neighboring nodes if we need more candidates
                        if (candidates.size() < k || shouldContinueKNNSearch(current, queryPoint, candidates)) {
                            addNeighboringNodes(current, toVisit, visitedNodes);
                        }
                    }
                }
            }

            // Convert to sorted list (closest first)
            List<EntityDistance<ID>> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparingDouble(EntityDistance::distance));

            return sorted.stream().map(EntityDistance::entityId).collect(Collectors.toList());
        });
    }

    // ===== Ray Intersection Abstract Methods =====

    @Override
    public int nodeCount() {
        lock.readLock().lock();
        try {
            return (int) getSpatialIndex().values().stream().filter(node -> !node.isEmpty()).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Stream<SpatialNode<ID>> nodes() {
        lock.readLock().lock();
        try {
            // Must collect results inside lock to avoid concurrent modification
            List<SpatialNode<ID>> results = getSpatialIndex().entrySet().stream().filter(
            entry -> !entry.getValue().isEmpty()).map(
            entry -> new SpatialNode<>(entry.getKey(), new HashSet<>(entry.getValue().getEntityIds()))).collect(
            Collectors.toList());
            return results.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find all entities that intersect with the given plane. Returns entities that either intersect the plane or are
     * within the specified tolerance distance from it.
     *
     * @param plane     the plane to test intersection with
     * @param tolerance maximum distance from plane to consider as intersection (use 0 for exact intersection)
     * @return list of plane intersections sorted by distance from plane
     */
    public List<PlaneIntersection<ID, Content>> planeIntersectAll(Plane3D plane, float tolerance) {
        lock.readLock().lock();
        try {
            List<PlaneIntersection<ID, Content>> intersections = new ArrayList<>();

            // Traverse nodes that could intersect with the plane
            getPlaneTraversalOrder(plane).forEach(nodeIndex -> {
                NodeType node = spatialIndex.get(nodeIndex);
                if (node == null || node.isEmpty()) {
                    return;
                }

                // Check if plane intersects this node
                if (!doesPlaneIntersectNode(nodeIndex, plane)) {
                    return;
                }

                // Check each entity in the node
                for (ID entityId : node.getEntityIds()) {
                    Content content = entityManager.getEntityContent(entityId);
                    if (content == null) {
                        continue;
                    }

                    Point3f entityPos = entityManager.getEntityPosition(entityId);
                    EntityBounds bounds = entityManager.getEntityBounds(entityId);

                    // Calculate plane-entity intersection
                    PlaneIntersection<ID, Content> intersection = calculatePlaneEntityIntersection(plane, entityId,
                                                                                                   content, entityPos,
                                                                                                   bounds, tolerance);

                    if (intersection != null) {
                        intersections.add(intersection);
                    }
                }
            });

            Collections.sort(intersections);
            return intersections;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Ray Intersection Implementation =====

    /**
     * Find all entities that intersect with the given plane (exact intersection only).
     *
     * @param plane the plane to test intersection with
     * @return list of plane intersections sorted by distance from plane
     */
    public List<PlaneIntersection<ID, Content>> planeIntersectAll(Plane3D plane) {
        return planeIntersectAll(plane, 0.0f);
    }

    /**
     * Find all entities on the negative side of the plane (opposite to normal).
     *
     * @param plane the plane to test
     * @return list of entities on negative side, sorted by distance from plane
     */
    public List<PlaneIntersection<ID, Content>> planeIntersectNegativeSide(Plane3D plane) {
        return planeIntersectAll(plane, Float.MAX_VALUE).stream().filter(PlaneIntersection::isOnNegativeSide).collect(
        Collectors.toList());
    }

    /**
     * Find all entities on the positive side of the plane (in direction of normal).
     *
     * @param plane the plane to test
     * @return list of entities on positive side, sorted by distance from plane
     */
    public List<PlaneIntersection<ID, Content>> planeIntersectPositiveSide(Plane3D plane) {
        return planeIntersectAll(plane, Float.MAX_VALUE).stream().filter(PlaneIntersection::isOnPositiveSide).collect(
        Collectors.toList());
    }

    /**
     * Find all entities within the specified distance from the plane (on either side).
     *
     * @param plane       the plane to test
     * @param maxDistance maximum distance from plane to include
     * @return list of entities within distance, sorted by distance from plane
     */
    public List<PlaneIntersection<ID, Content>> planeIntersectWithinDistance(Plane3D plane, float maxDistance) {
        return planeIntersectAll(plane, maxDistance).stream().filter(
        intersection -> Math.abs(intersection.distanceFromPlane()) <= maxDistance).collect(Collectors.toList());
    }

    /**
     * Pre-allocate nodes based on sample positions. Analyzes the sample to predict node distribution for the full
     * dataset.
     *
     * @param samplePositions    Sample positions representing the distribution
     * @param totalExpectedCount Total number of entities expected
     * @param level              The tree level for analysis
     */
    public void preAllocateAdaptive(List<Point3f> samplePositions, int totalExpectedCount, byte level) {
        if (samplePositions.isEmpty()) {
            return;
        }

        lock.writeLock().lock();
        try {
            // Analyze sample distribution
            Set<Long> uniqueMortonCodes = new HashSet<>();
            for (Point3f pos : samplePositions) {
                long morton = calculateSpatialIndex(pos, level);
                uniqueMortonCodes.add(morton);
            }

            // Estimate total nodes needed
            int estimatedNodes = NodeEstimator.estimateFromSamples(totalExpectedCount, samplePositions.size(),
                                                                   uniqueMortonCodes.size(), maxEntitiesPerNode);

            // Pre-allocate the unique nodes found in sample
            int created = 0;
            for (Long morton : uniqueMortonCodes) {
                if (!spatialIndex.containsKey(morton)) {
                    NodeType node = createNode();
                    spatialIndex.put(morton, node);
                    sortedSpatialIndices.add(morton);
                    created++;
                }
            }

            // Note: HashMap pre-sizing would require recreating the map with initial capacity
            // For now, just log the recommendation
            int remainingCapacity = estimatedNodes - created;
            if (remainingCapacity > 0) {
                log.debug("Recommendation: Pre-size HashMap for {} additional nodes", remainingCapacity);
            }

            log.debug("Pre-allocated {} nodes adaptively (sample size: {}, estimated total: {})", created,
                              samplePositions.size(), estimatedNodes);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Pre-allocate nodes based on expected entity count and distribution. This can significantly improve bulk insertion
     * performance by reducing allocation overhead.
     *
     * @param expectedEntityCount Expected number of entities to insert
     * @param distribution        Spatial distribution pattern of entities
     */
    public void preAllocateNodes(int expectedEntityCount, NodeEstimator.SpatialDistribution distribution) {
        lock.writeLock().lock();
        try {
            // Estimate required nodes
            int estimatedNodes = NodeEstimator.estimateNodeCount(expectedEntityCount, maxEntitiesPerNode, maxDepth,
                                                                 distribution);

            // Pre-size the HashMap to avoid rehashing
            // Note: We would need to recreate the HashMap with initial capacity since
            // Java's HashMap doesn't have ensureCapacity. For now, we'll just log the recommendation.
            int recommendedCapacity = (int) (estimatedNodes / 0.75f) + 1; // Account for load factor

            // Pre-allocate TreeSet capacity if possible
            // Note: TreeSet doesn't have pre-allocation, but we can optimize by
            // using a more efficient set implementation if needed

            log.debug("Pre-allocated capacity for {} nodes (estimated from {} entities)", estimatedNodes,
                              expectedEntityCount);

        } finally {
            lock.writeLock().unlock();
        }
    }

    // ===== Plane Intersection Abstract Methods =====

    /**
     * Pre-allocate nodes for a uniform grid at a specific level. Useful when you know entities will be distributed
     * uniformly.
     *
     * @param level             The tree level to pre-allocate
     * @param nodesPerDimension Number of nodes per dimension (total = n³)
     */
    public void preAllocateUniformGrid(byte level, int nodesPerDimension) {
        if (level > maxDepth) {
            throw new IllegalArgumentException("Level " + level + " exceeds maxDepth " + maxDepth);
        }

        lock.writeLock().lock();
        try {
            int totalNodes = NodeEstimator.estimateUniformGridNodes(level, nodesPerDimension);

            // Pre-create nodes in a grid pattern
            float cellSize = getCellSizeAtLevel(level);
            int created = 0;

            for (int x = 0; x < nodesPerDimension && created < totalNodes; x++) {
                for (int y = 0; y < nodesPerDimension && created < totalNodes; y++) {
                    for (int z = 0; z < nodesPerDimension && created < totalNodes; z++) {
                        Point3f position = new Point3f(x * cellSize + cellSize / 2, y * cellSize + cellSize / 2,
                                                       z * cellSize + cellSize / 2);

                        long mortonIndex = calculateSpatialIndex(position, level);

                        // Only create if doesn't exist
                        if (!spatialIndex.containsKey(mortonIndex)) {
                            NodeType node = createNode();
                            spatialIndex.put(mortonIndex, node);
                            sortedSpatialIndices.add(mortonIndex);
                            created++;
                        }
                    }
                }
            }

            log.debug("Pre-allocated {} nodes in uniform grid at level {}", created, level);

        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<RayIntersection<ID, Content>> rayIntersectAll(Ray3D ray) {
        validateSpatialConstraints(ray.origin());

        lock.readLock().lock();
        try {
            List<RayIntersection<ID, Content>> intersections = new ArrayList<>();
            Set<ID> visitedEntities = new HashSet<>();

            // Get nodes in traversal order
            getRayTraversalOrder(ray).forEach(nodeIndex -> {
                NodeType node = spatialIndex.get(nodeIndex);
                if (node == null || node.isEmpty()) {
                    return;
                }

                // Check if ray intersects this node
                if (!doesRayIntersectNode(nodeIndex, ray)) {
                    return;
                }

                // Check entities in this node
                for (ID entityId : node.getEntityIds()) {
                    // Skip if already processed (for spanning entities)
                    if (!visitedEntities.add(entityId)) {
                        continue;
                    }

                    // Get entity details
                    Point3f entityPos = entityManager.getEntityPosition(entityId);
                    if (entityPos == null) {
                        continue;
                    }

                    // Check ray-entity intersection
                    float distance = getRayEntityDistance(ray, entityId, entityPos);
                    if (distance >= 0 && ray.isWithinDistance(distance)) {
                        Content content = entityManager.getEntityContent(entityId);
                        EntityBounds bounds = entityManager.getEntityBounds(entityId);

                        // Calculate intersection point
                        Point3f intersectionPoint = ray.pointAt(distance);

                        // Calculate normal (simplified - towards ray origin)
                        Vector3f normal = new Vector3f();
                        normal.sub(ray.origin(), intersectionPoint);
                        normal.normalize();

                        intersections.add(
                        new RayIntersection<>(entityId, content, distance, intersectionPoint, normal, bounds));
                    }
                }
            });

            // Sort by distance
            Collections.sort(intersections);
            return intersections;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<RayIntersection<ID, Content>> rayIntersectFirst(Ray3D ray) {
        validateSpatialConstraints(ray.origin());

        lock.readLock().lock();
        try {
            Set<ID> visitedEntities = new HashSet<>();
            RayIntersection<ID, Content> closest = null;
            float closestDistance = Float.MAX_VALUE;

            // Traverse nodes in order until we find an intersection
            var nodeIterator = getRayTraversalOrder(ray).iterator();
            while (nodeIterator.hasNext()) {
                long nodeIndex = nodeIterator.next();

                // Early termination if node is beyond closest found
                float nodeDistance = getRayNodeIntersectionDistance(nodeIndex, ray);
                if (nodeDistance > closestDistance) {
                    break;
                }

                NodeType node = spatialIndex.get(nodeIndex);
                if (node == null || node.isEmpty()) {
                    continue;
                }

                // Check if ray intersects this node
                if (!doesRayIntersectNode(nodeIndex, ray)) {
                    continue;
                }

                // Check entities in this node
                for (ID entityId : node.getEntityIds()) {
                    if (!visitedEntities.add(entityId)) {
                        continue;
                    }

                    Point3f entityPos = entityManager.getEntityPosition(entityId);
                    if (entityPos == null) {
                        continue;
                    }

                    float distance = getRayEntityDistance(ray, entityId, entityPos);
                    if (distance >= 0 && distance < closestDistance && ray.isWithinDistance(distance)) {
                        Content content = entityManager.getEntityContent(entityId);
                        EntityBounds bounds = entityManager.getEntityBounds(entityId);
                        Point3f intersectionPoint = ray.pointAt(distance);

                        Vector3f normal = new Vector3f();
                        normal.sub(ray.origin(), intersectionPoint);
                        normal.normalize();

                        closest = new RayIntersection<>(entityId, content, distance, intersectionPoint, normal, bounds);
                        closestDistance = distance;
                    }
                }
            }

            return Optional.ofNullable(closest);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<RayIntersection<ID, Content>> rayIntersectWithin(Ray3D ray, float maxDistance) {
        validateSpatialConstraints(ray.origin());

        if (maxDistance <= 0) {
            return Collections.emptyList();
        }

        // Create a bounded ray with the minimum of ray's max distance and provided max distance
        Ray3D boundedRay = ray.withMaxDistance(Math.min(ray.maxDistance(), maxDistance));

        lock.readLock().lock();
        try {
            List<RayIntersection<ID, Content>> intersections = new ArrayList<>();
            Set<ID> visitedEntities = new HashSet<>();

            getRayTraversalOrder(boundedRay).forEach(nodeIndex -> {
                // Early termination if node is beyond max distance
                float nodeDistance = getRayNodeIntersectionDistance(nodeIndex, boundedRay);
                if (nodeDistance > maxDistance) {
                    return;
                }

                NodeType node = spatialIndex.get(nodeIndex);
                if (node == null || node.isEmpty()) {
                    return;
                }

                if (!doesRayIntersectNode(nodeIndex, boundedRay)) {
                    return;
                }

                for (ID entityId : node.getEntityIds()) {
                    if (!visitedEntities.add(entityId)) {
                        continue;
                    }

                    Point3f entityPos = entityManager.getEntityPosition(entityId);
                    if (entityPos == null) {
                        continue;
                    }

                    float distance = getRayEntityDistance(boundedRay, entityId, entityPos);
                    if (distance >= 0 && distance <= maxDistance) {
                        Content content = entityManager.getEntityContent(entityId);
                        EntityBounds bounds = entityManager.getEntityBounds(entityId);
                        Point3f intersectionPoint = boundedRay.pointAt(distance);

                        Vector3f normal = new Vector3f();
                        normal.sub(boundedRay.origin(), intersectionPoint);
                        normal.normalize();

                        intersections.add(
                        new RayIntersection<>(entityId, content, distance, intersectionPoint, normal, bounds));
                    }
                }
            });

            Collections.sort(intersections);
            return intersections;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Plane Intersection Implementation =====

    /**
     * Manually trigger tree rebalancing.
     */
    public TreeBalancer.RebalancingResult rebalanceTree() {
        lock.writeLock().lock();
        try {
            return treeBalancer.rebalanceTree();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Perform parallel batch removal
     */
    public CompletableFuture<Integer> removeBatchParallel(List<ID> entityIds) {
        return parallelOperations.removeBatchParallel(entityIds);
    }

    @Override
    public boolean removeEntity(ID entityId) {
        lock.writeLock().lock();
        try {
            // Get all locations where this entity appears
            Set<Long> locations = entityManager.getEntityLocations(entityId);

            // Remove from entity storage
            Entity<Content> removed = entityManager.removeEntity(entityId);
            if (removed == null) {
                return false;
            }

            if (!locations.isEmpty()) {
                // Remove from each node
                for (Long spatialIndex : locations) {
                    NodeType node = getSpatialIndex().get(spatialIndex);
                    if (node != null) {
                        node.removeEntity(entityId);

                        // Remove empty nodes
                        cleanupEmptyNode(spatialIndex, node);
                    }
                }
            }

            // Check for auto-balancing after removal
            checkAutoBalance();

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Removed ensureAncestorNodes - not needed in pointerless SFC implementation

    /**
     * Enable or disable automatic balancing.
     */
    public void setAutoBalancingEnabled(boolean enabled) {
        lock.writeLock().lock();
        try {
            this.autoBalancingEnabled = enabled;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Set the balancing strategy.
     */
    public void setBalancingStrategy(TreeBalancingStrategy<ID> strategy) {
        lock.writeLock().lock();
        try {
            this.balancingStrategy = Objects.requireNonNull(strategy);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void setCollisionShape(ID entityId, CollisionShape shape) {
        lock.writeLock().lock();
        try {
            entityManager.setEntityCollisionShape(entityId, shape);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Shutdown parallel operations (cleanup resources)
     */
    public void shutdownParallelOperations() {
        if (parallelOperations != null) {
            parallelOperations.shutdown();
        }
    }

    @Override
    public void traverse(TreeVisitor<ID, Content> visitor, TraversalStrategy strategy) {
        lock.readLock().lock();
        try {
            // Count total nodes and entities
            int totalNodes = nodeCount();
            int totalEntities = entityCount();

            visitor.beginTraversal(totalNodes, totalEntities);

            TraversalContext<ID> context = new TraversalContext<>();

            // Get root nodes based on implementation
            Set<Long> rootNodes = getRootNodes();

            for (Long rootIndex : rootNodes) {
                if (context.isCancelled()) {
                    break;
                }
                traverseNode(rootIndex, visitor, strategy, context, -1, 0);
            }

            visitor.endTraversal(context.getNodesVisited(), context.getEntitiesVisited());
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Frustum Culling Implementation =====

    @Override
    public void traverseFrom(TreeVisitor<ID, Content> visitor, TraversalStrategy strategy, long startNodeIndex) {
        lock.readLock().lock();
        try {
            if (!hasNode(startNodeIndex)) {
                visitor.beginTraversal(0, 0);
                visitor.endTraversal(0, 0);
                return;
            }

            visitor.beginTraversal(-1, -1); // Unknown totals

            TraversalContext<ID> context = new TraversalContext<>();
            traverseNode(startNodeIndex, visitor, strategy, context, -1, 0);

            visitor.endTraversal(context.getNodesVisited(), context.getEntitiesVisited());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void traverseRegion(TreeVisitor<ID, Content> visitor, Spatial region, TraversalStrategy strategy) {
        lock.readLock().lock();
        try {
            validateSpatialConstraints(region);

            var bounds = getVolumeBounds(region);
            if (bounds == null) {
                visitor.beginTraversal(0, 0);
                visitor.endTraversal(0, 0);
                return;
            }

            // Find nodes in region
            List<Long> nodesInRegion = spatialRangeQuery(bounds, true).map(Map.Entry::getKey).collect(
            Collectors.toList());

            visitor.beginTraversal(nodesInRegion.size(), -1);

            TraversalContext<ID> context = new TraversalContext<>();

            for (Long nodeIndex : nodesInRegion) {
                if (context.isCancelled()) {
                    break;
                }

                if (!context.isVisited(nodeIndex)) {
                    traverseNode(nodeIndex, visitor, strategy, context, -1, 0);
                }
            }

            visitor.endTraversal(context.getNodesVisited(), context.getEntitiesVisited());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Clear pre-allocated but empty nodes to free memory. Useful after bulk loading if many pre-allocated nodes weren't
     * used.
     */
    public void trimEmptyNodes() {
        lock.writeLock().lock();
        try {
            List<Long> emptyNodes = new ArrayList<>();

            // Find empty nodes
            for (Map.Entry<Long, NodeType> entry : spatialIndex.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    emptyNodes.add(entry.getKey());
                }
            }

            // Remove empty nodes
            for (Long morton : emptyNodes) {
                spatialIndex.remove(morton);
                sortedSpatialIndices.remove(morton);
            }

            if (!emptyNodes.isEmpty()) {
                log.debug("Trimmed {} empty pre-allocated nodes", emptyNodes.size());
            }

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Perform parallel batch updates
     */
    public CompletableFuture<List<ID>> updateBatchParallel(List<ID> entityIds, List<Point3f> newPositions, byte level) {
        return parallelOperations.updateBatchParallel(entityIds, newPositions, level);
    }

    @Override
    public void updateEntity(ID entityId, Point3f newPosition, byte level) {
        lock.writeLock().lock();
        try {
            validateSpatialConstraints(newPosition);

            // Get the old position to calculate movement delta
            Point3f oldPosition = entityManager.getEntityPosition(entityId);
            if (oldPosition == null) {
                throw new IllegalArgumentException("Entity not found: " + entityId);
            }

            // Calculate movement delta
            Vector3f delta = new Vector3f();
            delta.sub(newPosition, oldPosition);

            // Update entity position
            entityManager.updateEntityPosition(entityId, newPosition);

            // Update collision shape position if present
            CollisionShape shape = entityManager.getEntityCollisionShape(entityId);
            if (shape != null) {
                shape.translate(delta);
                // Update bounds from the translated collision shape
                entityManager.setEntityCollisionShape(entityId, shape);
            } else {
                // Update entity bounds if no collision shape
                EntityBounds oldBounds = entityManager.getEntityBounds(entityId);
                if (oldBounds != null) {
                    // Translate the bounds
                    Point3f newMin = new Point3f(oldBounds.getMinX() + delta.x, oldBounds.getMinY() + delta.y,
                                                 oldBounds.getMinZ() + delta.z);
                    Point3f newMax = new Point3f(oldBounds.getMaxX() + delta.x, oldBounds.getMaxY() + delta.y,
                                                 oldBounds.getMaxZ() + delta.z);
                    EntityBounds newBounds = new EntityBounds(newMin, newMax);
                    entityManager.setEntityBounds(entityId, newBounds);
                }
            }

            // Remove from all current locations
            Set<Long> oldLocations = entityManager.getEntityLocations(entityId);
            for (Long spatialIndex : oldLocations) {
                NodeType node = getSpatialIndex().get(spatialIndex);
                if (node != null) {
                    node.removeEntity(entityId);

                    // Remove empty nodes
                    cleanupEmptyNode(spatialIndex, node);
                }
            }
            entityManager.clearEntityLocations(entityId);

            // Re-insert at new position
            insertAtPosition(entityId, newPosition, level);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Add neighboring nodes to the k-NN search queue
     *
     * @param nodeIndex    current node index
     * @param toVisit      queue of nodes to visit
     * @param visitedNodes set of already visited nodes
     */
    protected abstract void addNeighboringNodes(long nodeIndex, Queue<Long> toVisit, Set<Long> visitedNodes);

    /**
     * Calculate the intersection between a frustum and an entity.
     *
     * @param frustum        the frustum
     * @param cameraPosition the camera position
     * @param entityId       the entity ID
     * @param content        the entity content
     * @param entityPos      the entity position
     * @param bounds         the entity bounds (null for point entities)
     * @return frustum intersection result, or null if outside frustum
     */
    protected FrustumIntersection<ID, Content> calculateFrustumEntityIntersection(Frustum3D frustum,
                                                                                  Point3f cameraPosition, ID entityId,
                                                                                  Content content, Point3f entityPos,
                                                                                  EntityBounds bounds) {
        if (bounds != null) {
            // For bounded entities, perform frustum-AABB intersection
            return frustumAABBIntersection(frustum, cameraPosition, entityId, content, bounds);
        } else {
            // For point entities, test point containment
            return frustumPointIntersection(frustum, cameraPosition, entityId, content, entityPos);
        }
    }

    /**
     * Calculate the intersection between a plane and an entity.
     *
     * @param plane     the plane
     * @param entityId  the entity ID
     * @param content   the entity content
     * @param entityPos the entity position
     * @param bounds    the entity bounds (null for point entities)
     * @param tolerance tolerance for intersection detection
     * @return plane intersection result, or null if no intersection within tolerance
     */
    protected PlaneIntersection<ID, Content> calculatePlaneEntityIntersection(Plane3D plane, ID entityId,
                                                                              Content content, Point3f entityPos,
                                                                              EntityBounds bounds, float tolerance) {
        if (bounds != null) {
            // For bounded entities, perform plane-AABB intersection
            return planeAABBIntersection(plane, entityId, content, bounds, tolerance);
        } else {
            // For point entities, calculate distance to plane
            return planePointIntersection(plane, entityId, content, entityPos, tolerance);
        }
    }

    // ===== Collision Detection Implementation =====

    /**
     * Calculate the spatial index for a position at a given level
     */
    protected abstract long calculateSpatialIndex(Point3f position, byte level);

    /**
     * Check and perform automatic balancing if needed.
     */
    protected void checkAutoBalance() {
        if (!autoBalancingEnabled) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBalancingTime < balancingStrategy.getMinRebalancingInterval()) {
            return;
        }

        TreeBalancingStrategy.TreeBalancingStats stats = getBalancingStats();
        if (balancingStrategy.shouldRebalanceTree(stats)) {
            lastBalancingTime = currentTime;
            treeBalancer.rebalanceTree();
        }
    }

    /**
     * Clean up empty nodes from the spatial index
     */
    protected void cleanupEmptyNode(long spatialIndex, NodeType node) {
        if (node.isEmpty() && !hasChildren(spatialIndex)) {
            getSpatialIndex().remove(spatialIndex);
            onNodeRemoved(spatialIndex);
            // Return node to pool for reuse
            nodePool.release(node);
        }
    }

    /**
     * Create a new node instance
     */
    protected abstract NodeType createNode();
    
    /**
     * Create the default subdivision strategy for this spatial index.
     * Subclasses should override to provide their specific strategy.
     */
    protected abstract SubdivisionStrategy<ID, Content> createDefaultSubdivisionStrategy();

    /**
     * Create a spatial volume from bounds for filtering
     *
     * @param bounds the volume bounds
     * @return spatial volume
     */
    protected Spatial createSpatialFromBounds(VolumeBounds bounds) {
        return new Spatial.aabb(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(),
                                bounds.maxZ());
    }

    /**
     * Create a tree balancer instance. Default implementation provides basic balancing. Subclasses can override to
     * provide specialized balancing.
     */
    protected TreeBalancer<ID> createTreeBalancer() {
        return new DefaultTreeBalancer();
    }

    /**
     * Check if a frustum intersects with the given node
     *
     * @param nodeIndex the node's spatial index
     * @param frustum   the frustum to test
     * @return true if the frustum intersects the node
     */
    protected abstract boolean doesFrustumIntersectNode(long nodeIndex, Frustum3D frustum);

    /**
     * Check if a node's bounds intersect with a volume
     */
    protected abstract boolean doesNodeIntersectVolume(long nodeIndex, Spatial volume);

    /**
     * Test if a plane intersects with a node
     *
     * @param nodeIndex the node's spatial index
     * @param plane     the plane to test
     * @return true if the plane intersects the node
     */
    protected abstract boolean doesPlaneIntersectNode(long nodeIndex, Plane3D plane);

    /**
     * Test if a ray intersects with a node
     *
     * @param nodeIndex the node's spatial index
     * @param ray       the ray to test
     * @return true if the ray intersects the node
     */
    protected abstract boolean doesRayIntersectNode(long nodeIndex, Ray3D ray);

    /**
     * Estimate the distance from a query point to the center of a spatial node. This is used for k-NN search
     * optimization to find the nearest starting nodes.
     *
     * @param nodeIndex  the spatial node index
     * @param queryPoint the query point
     * @return estimated distance from query point to node center
     */
    protected abstract float estimateNodeDistance(long nodeIndex, Point3f queryPoint);

    /**
     * Filter nodes in SFC order using the given predicate and collect them into a list. This utility method combines
     * spatial ordering with filtering for better performance.
     *
     * @param nodePredicate predicate to test each node
     * @return list of node indices that match the predicate, in SFC order
     */
    protected List<Long> filterNodesInSFCOrder(java.util.function.BiPredicate<Long, NodeType> nodePredicate) {
        List<Long> filteredNodes = new ArrayList<>();
        for (Long nodeIndex : sortedSpatialIndices) {
            NodeType node = spatialIndex.get(nodeIndex);
            if (node != null && !node.isEmpty() && nodePredicate.test(nodeIndex, node)) {
                filteredNodes.add(nodeIndex);
            }
        }
        return filteredNodes;
    }

    /**
     * Find the closest point on an AABB to the camera
     *
     * @param cameraPosition the camera position
     * @param bounds         the AABB bounds
     * @return closest point on AABB surface to the camera
     */
    protected Point3f findClosestPointOnAABBToCamera(Point3f cameraPosition, EntityBounds bounds) {
        // Clamp camera position to AABB bounds
        float x = Math.max(bounds.getMinX(), Math.min(cameraPosition.x, bounds.getMaxX()));
        float y = Math.max(bounds.getMinY(), Math.min(cameraPosition.y, bounds.getMaxY()));
        float z = Math.max(bounds.getMinZ(), Math.min(cameraPosition.z, bounds.getMaxZ()));

        return new Point3f(x, y, z);
    }

    /**
     * Find the closest point on an AABB to a plane
     *
     * @param plane  the plane
     * @param bounds the AABB bounds
     * @return closest point on AABB surface to the plane
     */
    protected Point3f findClosestPointOnAABBToPlane(Plane3D plane, EntityBounds bounds) {
        Vector3f normal = plane.getNormal();

        // Find the point on the AABB closest to the plane
        float x = (normal.x >= 0) ? bounds.getMinX() : bounds.getMaxX();
        float y = (normal.y >= 0) ? bounds.getMinY() : bounds.getMaxY();
        float z = (normal.z >= 0) ? bounds.getMinZ() : bounds.getMaxZ();

        return new Point3f(x, y, z);
    }

    /**
     * Find minimum containing level for bounds
     */
    protected byte findMinimumContainingLevel(VolumeBounds bounds) {
        float maxExtent = bounds.maxExtent();

        // Find the level where cell size >= maxExtent
        for (byte level = 0; level <= maxDepth; level++) {
            if (getCellSizeAtLevel(level) >= maxExtent) {
                return level;
            }
        }
        return maxDepth;
    }

    /**
     * Find all nodes that intersect with the given entity bounds. This is used for collision detection with bounded
     * entities.
     *
     * @param bounds the entity bounds to check
     * @return set of node indices that intersect with the bounds
     */
    protected Set<Long> findNodesIntersectingBounds(EntityBounds bounds) {
        Set<Long> intersectingNodes = new HashSet<>();

        // Convert EntityBounds to VolumeBounds for spatial query
        VolumeBounds volumeBounds = new VolumeBounds(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
                                                     bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());

        // Use spatial range query to find intersecting nodes
        spatialRangeQuery(volumeBounds, true).forEach(entry -> intersectingNodes.add(entry.getKey()));

        return intersectingNodes;
    }

    /**
     * Frustum-AABB intersection test
     *
     * @param frustum        the frustum
     * @param cameraPosition the camera position
     * @param entityId       the entity ID
     * @param content        the entity content
     * @param bounds         the AABB bounds
     * @return frustum intersection result, or null if outside frustum
     */
    protected FrustumIntersection<ID, Content> frustumAABBIntersection(Frustum3D frustum, Point3f cameraPosition,
                                                                       ID entityId, Content content,
                                                                       EntityBounds bounds) {
        float minX = bounds.getMinX();
        float minY = bounds.getMinY();
        float minZ = bounds.getMinZ();
        float maxX = bounds.getMaxX();
        float maxY = bounds.getMaxY();
        float maxZ = bounds.getMaxZ();

        // Check if AABB is completely inside frustum
        boolean completelyInside = frustum.containsAABB(minX, minY, minZ, maxX, maxY, maxZ);

        // Check if AABB intersects frustum
        boolean intersects = frustum.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);

        if (!intersects) {
            // Outside frustum
            return null;
        }

        // Determine visibility type
        FrustumIntersection.VisibilityType visibilityType;
        if (completelyInside) {
            visibilityType = FrustumIntersection.VisibilityType.INSIDE;
        } else {
            visibilityType = FrustumIntersection.VisibilityType.INTERSECTING;
        }

        // Calculate distance from camera to entity center
        Point3f entityCenter = bounds.getCenter();
        float distanceFromCamera = cameraPosition.distance(entityCenter);

        // Calculate closest point on AABB to camera
        Point3f closestPoint = findClosestPointOnAABBToCamera(cameraPosition, bounds);

        return new FrustumIntersection<>(entityId, content, distanceFromCamera, closestPoint, visibilityType, bounds);
    }

    /**
     * Frustum-point intersection test
     *
     * @param frustum        the frustum
     * @param cameraPosition the camera position
     * @param entityId       the entity ID
     * @param content        the entity content
     * @param point          the point position
     * @return frustum intersection result, or null if outside frustum
     */
    protected FrustumIntersection<ID, Content> frustumPointIntersection(Frustum3D frustum, Point3f cameraPosition,
                                                                        ID entityId, Content content, Point3f point) {
        // Check if point is inside frustum
        boolean isInside = frustum.containsPoint(point);

        if (!isInside) {
            return null;
        }

        // Calculate distance from camera to point
        float distanceFromCamera = cameraPosition.distance(point);

        // Point is always completely inside if it passes the containment test
        FrustumIntersection.VisibilityType visibilityType = FrustumIntersection.VisibilityType.INSIDE;

        return new FrustumIntersection<>(entityId, content, distanceFromCamera, new Point3f(point), visibilityType,
                                         null);
    }

    /**
     * Get the cell size at a given level (to be implemented by subclasses)
     */
    protected abstract int getCellSizeAtLevel(byte level);

    /**
     * Get child nodes of a given node. Default implementation returns empty list. Subclasses should override to provide
     * actual parent-child relationships.
     */
    protected List<Long> getChildNodes(long nodeIndex) {
        return Collections.emptyList();
    }

    /**
     * Get nodes that should be traversed for frustum culling, ordered by distance from camera
     *
     * @param frustum        the frustum to test
     * @param cameraPosition the camera position for distance sorting
     * @return stream of node indices ordered by distance from camera
     */
    protected abstract Stream<Long> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition);

    /**
     * Extract the level from a spatial index
     */
    protected abstract byte getLevelFromIndex(long index);

    /**
     * Get the spatial bounds of a node
     */
    protected abstract Spatial getNodeBounds(long index);

    /**
     * Get nodes that should be traversed for plane intersection, ordered by distance from plane
     *
     * @param plane the plane to test
     * @return stream of node indices ordered by distance from plane
     */
    protected abstract Stream<Long> getPlaneTraversalOrder(Plane3D plane);

    /**
     * Calculate the distance from ray origin to entity
     *
     * @param ray       the ray to test
     * @param entityId  the entity ID
     * @param entityPos the entity position
     * @return distance along ray, or -1 if no intersection
     */
    protected float getRayEntityDistance(Ray3D ray, ID entityId, Point3f entityPos) {
        EntityBounds bounds = entityManager.getEntityBounds(entityId);

        if (bounds != null) {
            // For bounded entities, perform ray-AABB intersection
            return rayAABBIntersection(ray, bounds);
        } else {
            // For point entities, perform ray-sphere intersection with small radius
            return raySphereIntersection(ray, entityPos, 0.1f);
        }
    }

    /**
     * Get the distance from ray origin to node intersection
     *
     * @param nodeIndex the node's spatial index
     * @param ray       the ray to test
     * @return distance to node entry point, or Float.MAX_VALUE if no intersection
     */
    protected abstract float getRayNodeIntersectionDistance(long nodeIndex, Ray3D ray);

    /**
     * Get nodes that should be traversed for ray intersection, ordered by distance
     *
     * @param ray the ray to test
     * @return stream of node indices ordered by ray intersection distance
     */
    protected abstract Stream<Long> getRayTraversalOrder(Ray3D ray);

    /**
     * Get root nodes for traversal. Default implementation returns all nodes at the minimum level. Subclasses can
     * override for specific root node logic.
     */
    protected Set<Long> getRootNodes() {
        Set<Long> roots = new HashSet<>();
        byte minLevel = Byte.MAX_VALUE;

        // Find minimum level
        for (Long index : spatialIndex.keySet()) {
            byte level = getLevelFromIndex(index);
            if (level < minLevel) {
                minLevel = level;
                roots.clear();
                roots.add(index);
            } else if (level == minLevel) {
                roots.add(index);
            }
        }

        return roots;
    }

    /**
     * Get the sorted spatial indices for SFC-ordered operations. This provides spatially-ordered access to improve
     * cache locality.
     *
     * @return NavigableSet containing spatial indices in SFC order
     */
    protected NavigableSet<Long> getSortedSpatialIndices() {
        return sortedSpatialIndices;
    }

    /**
     * Get the spatial index storage map
     */
    protected Map<Long, NodeType> getSpatialIndex() {
        return spatialIndex;
    }

    /**
     * Get the range of spatial indices that could intersect with the given bounds This method should be overridden by
     * subclasses for specific optimizations
     *
     * @param bounds the volume bounds
     * @return navigable set of spatial indices
     */
    protected NavigableSet<Long> getSpatialIndexRange(VolumeBounds bounds) {
        // Default implementation: return all indices
        // Subclasses should override for better performance
        return new TreeSet<>(sortedSpatialIndices);
    }

    /**
     * 1     * 1 Get volume bounds helper
     */
    protected VolumeBounds getVolumeBounds(Spatial volume) {
        return VolumeBounds.from(volume);
    }

    // ===== Tree Traversal Implementation =====

    /**
     * Hook for subclasses to handle node subdivision
     */
    protected void handleNodeSubdivision(long spatialIndex, byte level, NodeType node) {
        // Default: no subdivision. Subclasses can override
    }

    /**
     * Check if a node has children (to be implemented by subclasses if needed)
     */
    protected boolean hasChildren(long spatialIndex) {
        return false; // Default: no children tracking
    }

    /**
     * Insert entity at a single position (no spanning)
     */
    protected void insertAtPosition(ID entityId, Point3f position, byte level) {
        long spatialIndex = calculateSpatialIndex(position, level);

        // Get or create node directly - no need for ancestor nodes in SFC-based implementation
        NodeType node = getSpatialIndex().computeIfAbsent(spatialIndex, k -> {
            sortedSpatialIndices.add(spatialIndex);
            return nodePool.acquire();
        });

        // Add entity to node
        boolean shouldSplit = node.addEntity(entityId);

        // Track entity location
        entityManager.addEntityLocation(entityId, spatialIndex);

        // Handle subdivision if needed
        if (shouldSplit && level < maxDepth) {
            if (bulkLoadingMode) {
                // Defer subdivision during bulk loading
                subdivisionManager.deferSubdivision(spatialIndex, node, node.getEntityCount(), level);
            } else {
                // Immediate subdivision
                handleNodeSubdivision(spatialIndex, level, node);
            }
        }

        // Check for auto-balancing after insertion
        checkAutoBalance();
    }

    /**
     * Adaptive spanning implementation
     */
    protected void insertWithAdaptiveSpanning(ID entityId, EntityBounds bounds, byte level, int maxSpanNodes) {
        // Adapt spanning strategy based on current system state
        int currentNodeCount = spatialIndex.size();
        int entityCount = entityManager.getEntityCount();

        if (entityCount > 0) {
            float avgNodesPerEntity = (float) currentNodeCount / entityCount;

            if (avgNodesPerEntity > 100) {
                // High memory usage - use conservative spanning
                insertWithMemoryEfficientSpanning(entityId, bounds, level, maxSpanNodes / 2);
            } else if (avgNodesPerEntity < 10) {
                // Low memory usage - use aggressive spanning
                insertWithPerformanceOptimizedSpanning(entityId, bounds, level, maxSpanNodes);
            } else {
                // Balanced spanning
                insertWithBalancedSpanning(entityId, bounds, level, maxSpanNodes);
            }
        } else {
            // First entity - use balanced approach
            insertWithBalancedSpanning(entityId, bounds, level, maxSpanNodes);
        }
    }

    /**
     * Insert entity with advanced spanning strategies
     */
    protected void insertWithAdvancedSpanning(ID entityId, EntityBounds bounds, byte level) {
        // Calculate entity size for policy decisions
        float entitySize = Math.max(Math.max(bounds.getMaxX() - bounds.getMinX(), bounds.getMaxY() - bounds.getMinY()),
                                    bounds.getMaxZ() - bounds.getMinZ());
        int nodeSize = getCellSizeAtLevel(level);

        // Calculate maximum span nodes based on policy
        int maxSpanNodes = spanningPolicy.calculateMaxSpanNodes(entitySize, nodeSize, spatialIndex.size());

        // Apply spanning optimization strategy
        switch (spanningPolicy.getOptimization()) {
            case MEMORY_EFFICIENT -> insertWithMemoryEfficientSpanning(entityId, bounds, level, maxSpanNodes);
            case PERFORMANCE_FOCUSED -> insertWithPerformanceOptimizedSpanning(entityId, bounds, level, maxSpanNodes);
            case ADAPTIVE -> insertWithAdaptiveSpanning(entityId, bounds, level, maxSpanNodes);
            default -> insertWithBalancedSpanning(entityId, bounds, level, maxSpanNodes);
        }
    }

    /**
     * Balanced spanning implementation
     */
    protected void insertWithBalancedSpanning(ID entityId, EntityBounds bounds, byte level, int maxSpanNodes) {
        // Use standard spanning implementation
        insertWithSpanning(entityId, bounds, level);
    }

    /**
     * Memory-efficient spanning implementation
     */
    protected void insertWithMemoryEfficientSpanning(ID entityId, EntityBounds bounds, byte level, int maxSpanNodes) {
        // Use conservative spanning to minimize memory usage
        Point3f center = bounds.getCenter();

        // Start with center node
        insertAtPosition(entityId, center, level);

        // Only span to immediately adjacent nodes if entity is very large
        float entitySize = Math.max(Math.max(bounds.getMaxX() - bounds.getMinX(), bounds.getMaxY() - bounds.getMinY()),
                                    bounds.getMaxZ() - bounds.getMinZ());
        int nodeSize = getCellSizeAtLevel(level);

        if (entitySize > nodeSize * 2.0f && maxSpanNodes > 1) {
            // Delegate to subclass for specific spanning implementation
            insertWithSpanning(entityId, bounds, level);
        }
    }

    /**
     * Performance-optimized spanning implementation
     */
    protected void insertWithPerformanceOptimizedSpanning(ID entityId, EntityBounds bounds, byte level,
                                                          int maxSpanNodes) {
        // Use aggressive spanning for better query performance
        insertWithSpanning(entityId, bounds, level);
    }

    /**
     * Hook for subclasses to handle entity spanning
     */
    protected void insertWithSpanning(ID entityId, EntityBounds bounds, byte level) {
        // Default: single node insertion. Subclasses can override for spanning
        Point3f position = entityManager.getEntityPosition(entityId);
        if (position != null) {
            insertAtPosition(entityId, position, level);
        }
    }

    /**
     * Check if a node's bounds are contained within a volume
     */
    protected abstract boolean isNodeContainedInVolume(long nodeIndex, Spatial volume);

    /**
     * Hook for subclasses when a node is removed
     */
    protected void onNodeRemoved(long spatialIndex) {
        sortedSpatialIndices.remove(spatialIndex);
    }

    /**
     * Plane-AABB intersection test
     *
     * @param plane     the plane
     * @param entityId  the entity ID
     * @param content   the entity content
     * @param bounds    the AABB bounds
     * @param tolerance tolerance for intersection
     * @return plane intersection result, or null if outside tolerance
     */
    protected PlaneIntersection<ID, Content> planeAABBIntersection(Plane3D plane, ID entityId, Content content,
                                                                   EntityBounds bounds, float tolerance) {
        float minX = bounds.getMinX();
        float minY = bounds.getMinY();
        float minZ = bounds.getMinZ();
        float maxX = bounds.getMaxX();
        float maxY = bounds.getMaxY();
        float maxZ = bounds.getMaxZ();

        // Calculate distances to all 8 corners of the AABB
        Point3f[] corners = { new Point3f(minX, minY, minZ), new Point3f(maxX, minY, minZ), new Point3f(minX, maxY,
                                                                                                        minZ),
                              new Point3f(maxX, maxY, minZ), new Point3f(minX, minY, maxZ), new Point3f(maxX, minY,
                                                                                                        maxZ),
                              new Point3f(minX, maxY, maxZ), new Point3f(maxX, maxY, maxZ) };

        float minDistance = Float.POSITIVE_INFINITY;
        float maxDistance = Float.NEGATIVE_INFINITY;
        Point3f closestPoint = new Point3f();

        // Find min and max distances to plane
        for (Point3f corner : corners) {
            float distance = plane.distanceToPoint(corner);
            if (distance < minDistance) {
                minDistance = distance;
                closestPoint.set(corner);
            }
            if (distance > maxDistance) {
                maxDistance = distance;
            }
        }

        // Determine intersection type
        PlaneIntersection.IntersectionType intersectionType;
        float resultDistance;

        if (Math.abs(minDistance) <= tolerance && Math.abs(maxDistance) <= tolerance) {
            // Box lies on plane
            intersectionType = PlaneIntersection.IntersectionType.ON_PLANE;
            resultDistance = (minDistance + maxDistance) / 2.0f;
        } else if (minDistance * maxDistance <= 0) {
            // Box spans plane
            intersectionType = PlaneIntersection.IntersectionType.INTERSECTING;
            resultDistance = Math.abs(minDistance) < Math.abs(maxDistance) ? minDistance : maxDistance;
        } else if (minDistance > 0) {
            // Box is on positive side
            intersectionType = PlaneIntersection.IntersectionType.POSITIVE_SIDE;
            resultDistance = minDistance;
        } else {
            // Box is on negative side
            intersectionType = PlaneIntersection.IntersectionType.NEGATIVE_SIDE;
            resultDistance = maxDistance;
        }

        // Check tolerance
        if (tolerance > 0 && Math.abs(resultDistance) > tolerance) {
            return null;
        }

        // Calculate closest point on AABB to plane
        if (intersectionType == PlaneIntersection.IntersectionType.INTERSECTING) {
            // For intersecting boxes, find actual closest point on box surface to plane
            closestPoint = findClosestPointOnAABBToPlane(plane, bounds);
        }

        return new PlaneIntersection<>(entityId, content, resultDistance, closestPoint, intersectionType, bounds);
    }

    /**
     * Plane-point intersection test
     *
     * @param plane     the plane
     * @param entityId  the entity ID
     * @param content   the entity content
     * @param point     the point position
     * @param tolerance tolerance for intersection
     * @return plane intersection result, or null if outside tolerance
     */
    protected PlaneIntersection<ID, Content> planePointIntersection(Plane3D plane, ID entityId, Content content,
                                                                    Point3f point, float tolerance) {
        float distance = plane.distanceToPoint(point);

        // Check tolerance
        if (tolerance > 0 && Math.abs(distance) > tolerance) {
            return null;
        }

        // Determine intersection type
        PlaneIntersection.IntersectionType intersectionType;
        if (Math.abs(distance) <= 1e-6f) {
            intersectionType = PlaneIntersection.IntersectionType.ON_PLANE;
        } else if (distance > 0) {
            intersectionType = PlaneIntersection.IntersectionType.POSITIVE_SIDE;
        } else {
            intersectionType = PlaneIntersection.IntersectionType.NEGATIVE_SIDE;
        }

        return new PlaneIntersection<>(entityId, content, distance, new Point3f(point), intersectionType, null);
    }

    /**
     * Process all entities in SFC order with the given consumer. This utility method provides a convenient way to
     * iterate over all entities in spatial order for improved cache performance.
     *
     * @param entityProcessor function to process each entity ID with its containing node index
     */
    protected void processEntitiesInSFCOrder(java.util.function.BiConsumer<Long, ID> entityProcessor) {
        for (Long nodeIndex : sortedSpatialIndices) {
            NodeType node = spatialIndex.get(nodeIndex);
            if (node != null && !node.isEmpty()) {
                for (ID entityId : node.getEntityIds()) {
                    entityProcessor.accept(nodeIndex, entityId);
                }
            }
        }
    }

    /**
     * Process all nodes in SFC order with the given consumer. This utility method provides a convenient way to iterate
     * over all nodes in spatial order for improved cache performance.
     *
     * @param nodeProcessor function to process each non-empty node
     */
    protected void processNodesInSFCOrder(java.util.function.BiConsumer<Long, NodeType> nodeProcessor) {
        for (Long nodeIndex : sortedSpatialIndices) {
            NodeType node = spatialIndex.get(nodeIndex);
            if (node != null && !node.isEmpty()) {
                nodeProcessor.accept(nodeIndex, node);
            }
        }
    }

    // ===== Bulk Operations Implementation =====

    /**
     * Ray-AABB intersection test
     *
     * @param ray    the ray
     * @param bounds the axis-aligned bounding box
     * @return distance to intersection, or -1 if no intersection
     */
    protected float rayAABBIntersection(Ray3D ray, EntityBounds bounds) {
        float tmin = 0.0f;
        float tmax = ray.maxDistance();

        // For each axis
        for (int i = 0; i < 3; i++) {
            float origin = getComponent(ray.origin(), i);
            float direction = getComponent(ray.direction(), i);
            float min = getComponent(bounds, i, true);
            float max = getComponent(bounds, i, false);

            if (Math.abs(direction) < 1e-6f) {
                // Ray is parallel to slab
                if (origin < min || origin > max) {
                    return -1;
                }
            } else {
                // Compute intersection distances
                float t1 = (min - origin) / direction;
                float t2 = (max - origin) / direction;

                if (t1 > t2) {
                    float temp = t1;
                    t1 = t2;
                    t2 = temp;
                }

                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);

                if (tmin > tmax) {
                    return -1;
                }
            }
        }

        return tmin;
    }

    /**
     * Ray-sphere intersection test
     *
     * @param ray    the ray
     * @param center sphere center
     * @param radius sphere radius
     * @return distance to intersection, or -1 if no intersection
     */
    protected float raySphereIntersection(Ray3D ray, Point3f center, float radius) {
        // Use geometric algorithm for better numerical stability with distant entities

        // First check if ray origin is inside the sphere
        float distFromOrigin = ray.origin().distance(center);
        if (distFromOrigin <= radius) {
            // Ray starts inside sphere, return 0
            return 0.0f;
        }

        // Find the closest point on the ray to the sphere center
        Vector3f toCenter = new Vector3f();
        toCenter.sub(center, ray.origin());

        float t = toCenter.dot(ray.direction());  // Parameter for closest point
        if (t < 0) {
            // Sphere is behind ray origin
            return -1;
        }

        // Calculate closest point on ray
        Point3f closestPoint = new Point3f(ray.origin().x + t * ray.direction().x,
                                           ray.origin().y + t * ray.direction().y,
                                           ray.origin().z + t * ray.direction().z);

        float distToCenter = closestPoint.distance(center);

        if (distToCenter > radius) {
            // Ray misses sphere
            return -1;
        }

        // Calculate intersection points
        float halfChordLength = (float) Math.sqrt(radius * radius - distToCenter * distToCenter);

        float t1 = t - halfChordLength;
        float t2 = t + halfChordLength;

        // Return the closest positive t value within max distance
        if (t1 >= 0 && t1 <= ray.maxDistance()) {
            return t1;
        }
        if (t2 >= 0 && t2 <= ray.maxDistance()) {
            return t2;
        }

        return -1;
    }

    /**
     * Check if k-NN search should continue based on current candidates
     *
     * @param nodeIndex  current node index
     * @param queryPoint the query point
     * @param candidates current candidate entities
     * @return true if search should continue
     */
    protected abstract boolean shouldContinueKNNSearch(long nodeIndex, Point3f queryPoint,
                                                       PriorityQueue<EntityDistance<ID>> candidates);

    /**
     * Check if an entity should span multiple nodes using advanced policies
     */
    protected boolean shouldSpanEntity(EntityBounds bounds, byte level) {
        if (bounds == null || !spanningPolicy.isSpanningEnabled()) {
            return false;
        }

        // Calculate entity size
        float entitySize = Math.max(Math.max(bounds.getMaxX() - bounds.getMinX(), bounds.getMaxY() - bounds.getMinY()),
                                    bounds.getMaxZ() - bounds.getMinZ());

        // Get node size at this level
        int nodeSize = getCellSizeAtLevel(level);

        // Use advanced spanning logic
        return spanningPolicy.shouldSpanAdvanced(entitySize, nodeSize, spatialIndex.size(),
                                                 entityManager.getEntityCount(), level);
    }

    /**
     * Perform spatial range query with optimization
     *
     * @param bounds              the volume bounds to query
     * @param includeIntersecting whether to include intersecting nodes
     * @return stream of node entries that match the query
     */
    protected Stream<Map.Entry<Long, NodeType>> spatialRangeQuery(VolumeBounds bounds, boolean includeIntersecting) {
        // Get range of spatial indices that could contain or intersect the bounds
        NavigableSet<Long> candidateIndices = getSpatialIndexRange(bounds);

        return candidateIndices.stream().map(index -> Map.entry(index, getSpatialIndex().get(index))).filter(
        entry -> entry.getValue() != null && !entry.getValue().isEmpty()).filter(entry -> {
            // Final precise filtering
            if (includeIntersecting) {
                return doesNodeIntersectVolume(entry.getKey(), createSpatialFromBounds(bounds));
            } else {
                return isNodeContainedInVolume(entry.getKey(), createSpatialFromBounds(bounds));
            }
        });
    }

    /**
     * Validate spatial constraints (e.g., positive coordinates for Tetree)
     */
    protected abstract void validateSpatialConstraints(Point3f position);

    /**
     * Validate spatial constraints for volumes
     */
    protected abstract void validateSpatialConstraints(Spatial volume);

    /**
     * Check if two bounds intersect
     */
    private boolean boundsIntersect(EntityBounds b1, EntityBounds b2) {
        return b1.getMaxX() >= b2.getMinX() && b1.getMinX() <= b2.getMaxX() && b1.getMaxY() >= b2.getMinY()
        && b1.getMinY() <= b2.getMaxY() && b1.getMaxZ() >= b2.getMinZ() && b1.getMinZ() <= b2.getMaxZ();
    }

    /**
     * Check if bounds intersect with a volume
     */
    private boolean boundsIntersectVolume(EntityBounds bounds, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube ->
            bounds.getMaxX() >= cube.originX() && bounds.getMinX() <= cube.originX() + cube.extent()
            && bounds.getMaxY() >= cube.originY() && bounds.getMinY() <= cube.originY() + cube.extent()
            && bounds.getMaxZ() >= cube.originZ() && bounds.getMinZ() <= cube.originZ() + cube.extent();

            case Spatial.Sphere sphere -> {
                // Find closest point on bounds to sphere center
                float closestX = Math.max(bounds.getMinX(), Math.min(sphere.centerX(), bounds.getMaxX()));
                float closestY = Math.max(bounds.getMinY(), Math.min(sphere.centerY(), bounds.getMaxY()));
                float closestZ = Math.max(bounds.getMinZ(), Math.min(sphere.centerZ(), bounds.getMaxZ()));

                float dx = closestX - sphere.centerX();
                float dy = closestY - sphere.centerY();
                float dz = closestZ - sphere.centerZ();
                yield (dx * dx + dy * dy + dz * dz) <= (sphere.radius() * sphere.radius());
            }

            default -> true;
        };
    }

    /**
     * Calculate contact normal between two AABBs
     */
    private Vector3f calculateContactNormal(EntityBounds b1, EntityBounds b2) {
        // Find the axis with minimum penetration
        float[] penetrations = new float[6];
        penetrations[0] = b1.getMaxX() - b2.getMinX(); // +X
        penetrations[1] = b2.getMaxX() - b1.getMinX(); // -X
        penetrations[2] = b1.getMaxY() - b2.getMinY(); // +Y
        penetrations[3] = b2.getMaxY() - b1.getMinY(); // -Y
        penetrations[4] = b1.getMaxZ() - b2.getMinZ(); // +Z
        penetrations[5] = b2.getMaxZ() - b1.getMinZ(); // -Z

        int minAxis = 0;
        float minPenetration = penetrations[0];
        for (int i = 1; i < 6; i++) {
            if (penetrations[i] < minPenetration) {
                minPenetration = penetrations[i];
                minAxis = i;
            }
        }

        // Return normal based on minimum penetration axis
        return switch (minAxis) {
            case 0 -> new Vector3f(1, 0, 0);   // +X
            case 1 -> new Vector3f(-1, 0, 0);  // -X
            case 2 -> new Vector3f(0, 1, 0);   // +Y
            case 3 -> new Vector3f(0, -1, 0);  // -Y
            case 4 -> new Vector3f(0, 0, 1);   // +Z
            case 5 -> new Vector3f(0, 0, -1);  // -Z
            default -> new Vector3f(1, 0, 0);
        };
    }

    /**
     * Calculate contact point between two AABBs
     */
    private Point3f calculateContactPoint(EntityBounds b1, EntityBounds b2) {
        // Use the center of the intersection volume
        float minX = Math.max(b1.getMinX(), b2.getMinX());
        float minY = Math.max(b1.getMinY(), b2.getMinY());
        float minZ = Math.max(b1.getMinZ(), b2.getMinZ());
        float maxX = Math.min(b1.getMaxX(), b2.getMaxX());
        float maxY = Math.min(b1.getMaxY(), b2.getMaxY());
        float maxZ = Math.min(b1.getMaxZ(), b2.getMaxZ());

        return new Point3f((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }

    /**
     * Calculate penetration depth between two AABBs
     */
    private float calculatePenetrationDepth(EntityBounds b1, EntityBounds b2) {
        float xPenetration = Math.min(b1.getMaxX() - b2.getMinX(), b2.getMaxX() - b1.getMinX());
        float yPenetration = Math.min(b1.getMaxY() - b2.getMinY(), b2.getMaxY() - b1.getMinY());
        float zPenetration = Math.min(b1.getMaxZ() - b2.getMinZ(), b2.getMaxZ() - b1.getMinZ());

        return Math.min(Math.min(xPenetration, yPenetration), zPenetration);
    }

    // ===== Parallel Operations API =====

    /**
     * Check two entities for collision and add to list if colliding
     */
    private void checkAndAddCollision(ID id1, ID id2, List<CollisionPair<ID, Content>> collisions) {
        if (id1.equals(id2)) {
            return;
        }

        Optional<CollisionPair<ID, Content>> collision = performDetailedCollisionCheck(id1, id2);
        collision.ifPresent(collisions::add);
    }

    /**
     * Helper to get component from Point3f
     */
    private float getComponent(Point3f point, int axis) {
        return switch (axis) {
            case 0 -> point.x;
            case 1 -> point.y;
            case 2 -> point.z;
            default -> throw new IllegalArgumentException("Invalid axis: " + axis);
        };
    }

    /**
     * Helper to get component from Vector3f
     */
    private float getComponent(Vector3f vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x;
            case 1 -> vector.y;
            case 2 -> vector.z;
            default -> throw new IllegalArgumentException("Invalid axis: " + axis);
        };
    }

    /**
     * Helper to get min/max component from EntityBounds
     */
    private float getComponent(EntityBounds bounds, int axis, boolean min) {
        return switch (axis) {
            case 0 -> min ? bounds.getMinX() : bounds.getMaxX();
            case 1 -> min ? bounds.getMinY() : bounds.getMaxY();
            case 2 -> min ? bounds.getMinZ() : bounds.getMaxZ();
            default -> throw new IllegalArgumentException("Invalid axis: " + axis);
        };
    }

    /**
     * Check if an entity is within a region
     */
    private boolean isEntityInRegion(ID entityId, Spatial region) {
        Point3f pos = entityManager.getEntityPosition(entityId);
        if (pos == null) {
            return false;
        }

        EntityBounds bounds = entityManager.getEntityBounds(entityId);
        if (bounds != null) {
            // Check if bounds intersect region
            return boundsIntersectVolume(bounds, region);
        } else {
            // Check if point is in region
            return isPointInVolume(pos, region);
        }
    }

    /**
     * Check if a point is inside a volume
     */
    private boolean isPointInVolume(Point3f point, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube ->
            point.x >= cube.originX() && point.x <= cube.originX() + cube.extent() && point.y >= cube.originY()
            && point.y <= cube.originY() + cube.extent() && point.z >= cube.originZ()
            && point.z <= cube.originZ() + cube.extent();

            case Spatial.Sphere sphere -> {
                float dx = point.x - sphere.centerX();
                float dy = point.y - sphere.centerY();
                float dz = point.z - sphere.centerZ();
                yield (dx * dx + dy * dy + dz * dz) <= (sphere.radius() * sphere.radius());
            }

            default -> true; // Conservative for unknown types
        };
    }

    // ===== Memory Pre-allocation Methods =====

    /**
     * Perform detailed collision check between two entities
     */
    private Optional<CollisionPair<ID, Content>> performDetailedCollisionCheck(ID id1, ID id2) {
        EntityBounds bounds1 = entityManager.getEntityBounds(id1);
        EntityBounds bounds2 = entityManager.getEntityBounds(id2);
        Content content1 = entityManager.getEntityContent(id1);
        Content content2 = entityManager.getEntityContent(id2);

        // Check if we have collision shapes for narrow-phase detection
        CollisionShape shape1 = entityManager.getEntityCollisionShape(id1);
        CollisionShape shape2 = entityManager.getEntityCollisionShape(id2);

        if (shape1 != null && shape2 != null) {
            // Use narrow-phase collision detection
            CollisionShape.CollisionResult result = shape1.collidesWith(shape2);
            if (result.collides) {
                return Optional.of(
                CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, result.contactPoint,
                                     result.contactNormal, result.penetrationDepth));
            }
            return Optional.empty();
        }

        // Fall back to AABB collision detection
        if (bounds1 != null && bounds2 != null) {
            // AABB-AABB collision
            if (boundsIntersect(bounds1, bounds2)) {
                // Calculate collision details
                Point3f contactPoint = calculateContactPoint(bounds1, bounds2);
                Vector3f contactNormal = calculateContactNormal(bounds1, bounds2);
                float penetrationDepth = calculatePenetrationDepth(bounds1, bounds2);

                return Optional.of(
                CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, contactPoint, contactNormal,
                                     penetrationDepth));
            }
        } else if (bounds1 != null || bounds2 != null) {
            // Mixed collision: one entity has bounds, the other is a point
            EntityBounds bounds = bounds1 != null ? bounds1 : bounds2;
            Point3f pointPos = bounds1 != null ? entityManager.getEntityPosition(id2) : entityManager.getEntityPosition(
            id1);

            if (pointPos != null) {
                // Check if point is inside or within threshold of bounds
                // For zero-size bounds, we need to check if the point is within collision threshold
                float threshold = 0.1f;
                boolean inBounds = pointPos.x >= bounds.getMinX() && pointPos.x <= bounds.getMaxX()
                && pointPos.y >= bounds.getMinY() && pointPos.y <= bounds.getMaxY() && pointPos.z >= bounds.getMinZ()
                && pointPos.z <= bounds.getMaxZ();

                // For zero-size bounds, check distance to bounds center
                if (!inBounds && bounds.getMinX() == bounds.getMaxX() && bounds.getMinY() == bounds.getMaxY()
                && bounds.getMinZ() == bounds.getMaxZ()) {
                    // Zero-size bounds - check distance to the single point
                    Point3f boundsPoint = new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());
                    float distance = pointPos.distance(boundsPoint);
                    inBounds = distance <= threshold;
                }

                if (inBounds) {

                    // Point is inside bounds - collision detected
                    Point3f contactPoint = new Point3f(pointPos);

                    // Calculate normal from bounds center to point
                    Point3f boundsCenter = new Point3f((bounds.getMinX() + bounds.getMaxX()) / 2,
                                                       (bounds.getMinY() + bounds.getMaxY()) / 2,
                                                       (bounds.getMinZ() + bounds.getMaxZ()) / 2);

                    Vector3f contactNormal = new Vector3f();
                    contactNormal.sub(pointPos, boundsCenter);
                    if (contactNormal.length() > 0) {
                        contactNormal.normalize();
                    } else {
                        contactNormal.set(1, 0, 0); // Default normal
                    }

                    // Calculate penetration depth
                    float penetrationDepth;
                    if (bounds.getMinX() == bounds.getMaxX() && bounds.getMinY() == bounds.getMaxY()
                    && bounds.getMinZ() == bounds.getMaxZ()) {
                        // Zero-size bounds - use distance
                        Point3f boundsPoint = new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());
                        float distance = pointPos.distance(boundsPoint);
                        penetrationDepth = (distance == 0) ? 0 : Math.max(0, threshold - distance);
                    } else {
                        // Regular bounds - distance from point to nearest surface
                        penetrationDepth = Math.min(
                        Math.min(pointPos.x - bounds.getMinX(), bounds.getMaxX() - pointPos.x),
                        Math.min(Math.min(pointPos.y - bounds.getMinY(), bounds.getMaxY() - pointPos.y),
                                 Math.min(pointPos.z - bounds.getMinZ(), bounds.getMaxZ() - pointPos.z)));
                    }

                    return Optional.of(
                    CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, contactPoint, contactNormal,
                                         penetrationDepth));
                }
            }
        } else {
            // Point-based collision (both entities are points)
            Point3f pos1 = entityManager.getEntityPosition(id1);
            Point3f pos2 = entityManager.getEntityPosition(id2);

            if (pos1 != null && pos2 != null) {
                float distance = pos1.distance(pos2);
                float threshold = 0.1f; // Collision threshold for points

                if (distance <= threshold) {
                    Point3f contactPoint = new Point3f();
                    contactPoint.interpolate(pos1, pos2, 0.5f);

                    Vector3f contactNormal = new Vector3f();
                    contactNormal.sub(pos2, pos1);
                    if (contactNormal.length() > 0) {
                        contactNormal.normalize();
                    } else {
                        contactNormal.set(1, 0, 0); // Default normal for identical positions
                    }

                    // For identical positions, penetration depth is 0
                    // Otherwise it's the overlap amount
                    float penetrationDepth = (distance == 0) ? 0 : Math.max(0, threshold - distance);

                    return Optional.of(
                    CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, contactPoint, contactNormal,
                                         penetrationDepth));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Process nodes in breadth-first order
     */
    private void processBreadthFirstQueue(TreeVisitor<ID, Content> visitor, TraversalStrategy strategy,
                                          TraversalContext<ID> context) {
        Long nodeIndex;
        while ((nodeIndex = context.popNode()) != null) {
            int level = context.getNodeLevel(nodeIndex);
            traverseNode(nodeIndex, visitor, strategy, context, -1, level);
        }
    }

    /**
     * Traverse a single node and its children recursively
     */
    private void traverseNode(long nodeIndex, TreeVisitor<ID, Content> visitor, TraversalStrategy strategy,
                              TraversalContext<ID> context, long parentIndex, int level) {

        if (context.isCancelled() || context.isVisited(nodeIndex)) {
            return;
        }

        // Check max depth
        if (visitor.getMaxDepth() >= 0 && level > visitor.getMaxDepth()) {
            return;
        }

        // Get node
        NodeType node = spatialIndex.get(nodeIndex);
        if (node == null || node.isEmpty()) {
            return;
        }

        // Create SpatialNode wrapper
        SpatialNode<ID> spatialNode = new SpatialNode<>(nodeIndex, new HashSet<>(node.getEntityIds()));

        // Mark as visited
        context.markVisited(nodeIndex);

        // Pre-order visit
        boolean shouldContinue = visitor.visitNode(spatialNode, level, parentIndex);

        // Visit entities if requested
        if (shouldContinue && visitor.shouldVisitEntities()) {
            for (ID entityId : node.getEntityIds()) {
                Content content = entityManager.getEntityContent(entityId);
                visitor.visitEntity(entityId, content, nodeIndex, level);
                context.incrementEntitiesVisited();
            }
        }

        // Visit children if requested
        if (shouldContinue) {
            List<Long> children = getChildNodes(nodeIndex);
            int childCount = 0;

            for (Long childIndex : children) {
                if (context.isCancelled()) {
                    break;
                }

                // Apply traversal strategy
                switch (strategy) {
                    case DEPTH_FIRST, PRE_ORDER -> {
                        traverseNode(childIndex, visitor, strategy, context, nodeIndex, level + 1);
                        childCount++;
                    }
                    case BREADTH_FIRST, LEVEL_ORDER -> {
                        context.pushNode(childIndex, level + 1);
                        childCount++;
                    }
                    case POST_ORDER -> {
                        // Queue for post-order processing
                        context.pushNode(childIndex, level + 1);
                        childCount++;
                    }
                    case IN_ORDER -> {
                        // For spatial trees, treat as pre-order
                        traverseNode(childIndex, visitor, strategy, context, nodeIndex, level + 1);
                        childCount++;
                    }
                }
            }

            // Process breadth-first queue
            if (strategy == TraversalStrategy.BREADTH_FIRST || strategy == TraversalStrategy.LEVEL_ORDER) {
                processBreadthFirstQueue(visitor, strategy, context);
            }

            // Post-order visit
            visitor.leaveNode(spatialNode, level, childCount);
        }
    }

    /**
     * Helper class for unordered entity pairs
     */
    private static class UnorderedPair<T extends EntityID> {
        private final T first;
        private final T second;

        UnorderedPair(T a, T b) {
            if (a.compareTo(b) <= 0) {
                this.first = a;
                this.second = b;
            } else {
                this.first = b;
                this.second = a;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof UnorderedPair<?> that)) {
                return false;
            }
            return Objects.equals(first, that.first) && Objects.equals(second, that.second);
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, second);
        }
    }

    /**
     * Memory usage statistics
     */
    public record MemoryStats(int nodeCount, long entityCount, float avgEntitiesPerNode, long estimatedMemoryBytes) {
        public double estimatedMemoryMB() {
            return estimatedMemoryBytes / (1024.0 * 1024.0);
        }

        public double memoryPerEntity() {
            return entityCount > 0 ? (double) estimatedMemoryBytes / entityCount : 0;
        }
    }

    /**
     * Helper class for sorting entities by Morton code
     */
    private static class IndexedEntity {
        final int  originalIndex;
        final long mortonCode;

        IndexedEntity(int originalIndex, long mortonCode) {
            this.originalIndex = originalIndex;
            this.mortonCode = mortonCode;
        }
    }

    /**
     * Default tree balancer implementation.
     */
    protected class DefaultTreeBalancer implements TreeBalancer<ID> {

        @Override
        public BalancingAction checkNodeBalance(long nodeIndex) {
            NodeType node = spatialIndex.get(nodeIndex);
            if (node == null) {
                return BalancingAction.NONE;
            }

            byte level = getLevelFromIndex(nodeIndex);
            int entityCount = node.getEntityCount();

            // Check split condition
            if (balancingStrategy.shouldSplit(entityCount, level, maxEntitiesPerNode)) {
                return BalancingAction.SPLIT;
            }

            // Check merge condition
            int[] siblingCounts = getSiblingEntityCounts(nodeIndex);
            if (balancingStrategy.shouldMerge(entityCount, level, siblingCounts)) {
                return BalancingAction.MERGE;
            }

            return BalancingAction.NONE;
        }

        @Override
        public TreeBalancingStrategy.TreeBalancingStats getBalancingStats() {
            return AbstractSpatialIndex.this.getBalancingStats();
        }

        @Override
        public boolean isAutoBalancingEnabled() {
            return AbstractSpatialIndex.this.isAutoBalancingEnabled();
        }

        @Override
        public boolean mergeNodes(Set<Long> nodeIndices, long parentIndex) {
            // Default implementation does not support merging
            // Subclasses should override for actual merging logic
            return false;
        }

        @Override
        public int rebalanceSubtree(long rootNodeIndex) {
            return rebalanceSubtreeImpl(rootNodeIndex, new HashSet<>());
        }

        @Override
        public RebalancingResult rebalanceTree() {
            long startTime = System.currentTimeMillis();
            int nodesCreated = 0;
            int nodesRemoved = 0;
            int nodesMerged = 0;
            int nodesSplit = 0;
            int entitiesRelocated = 0;

            try {
                // Get root nodes and rebalance each subtree
                Set<Long> roots = getRootNodes();
                for (Long root : roots) {
                    int modifications = rebalanceSubtree(root);
                    // Track modifications (simplified)
                    nodesSplit += modifications;
                }

                long timeTaken = System.currentTimeMillis() - startTime;
                return new RebalancingResult(nodesCreated, nodesRemoved, nodesMerged, nodesSplit, entitiesRelocated,
                                             timeTaken, true);
            } catch (Exception e) {
                long timeTaken = System.currentTimeMillis() - startTime;
                return new RebalancingResult(0, 0, 0, 0, 0, timeTaken, false);
            }
        }

        @Override
        public void setAutoBalancingEnabled(boolean enabled) {
            AbstractSpatialIndex.this.setAutoBalancingEnabled(enabled);
        }

        @Override
        public void setBalancingStrategy(TreeBalancingStrategy<ID> strategy) {
            AbstractSpatialIndex.this.setBalancingStrategy(strategy);
        }

        @Override
        public List<Long> splitNode(long nodeIndex, byte nodeLevel) {
            // Default implementation does not support splitting
            // Subclasses should override for actual splitting logic
            return Collections.emptyList();
        }

        /**
         * Find sibling nodes for the given node.
         */
        protected Set<Long> findSiblings(long nodeIndex) {
            // Default implementation: no siblings
            // Subclasses should override based on their structure
            return Collections.emptySet();
        }

        /**
         * Get parent node index.
         */
        protected long getParentIndex(long nodeIndex) {
            // Default implementation: no parent tracking
            // Subclasses should override based on their structure
            return -1;
        }

        /**
         * Get entity counts of sibling nodes.
         */
        protected int[] getSiblingEntityCounts(long nodeIndex) {
            Set<Long> siblings = findSiblings(nodeIndex);
            int[] counts = new int[siblings.size()];
            int i = 0;
            for (Long sibling : siblings) {
                NodeType node = spatialIndex.get(sibling);
                counts[i++] = node != null ? node.getEntityCount() : 0;
            }
            return counts;
        }

        private int rebalanceSubtreeImpl(long rootNodeIndex, Set<Long> visited) {
            // Prevent infinite recursion
            if (!visited.add(rootNodeIndex)) {
                return 0; // Already processed this node
            }

            int modifications = 0;

            NodeType node = spatialIndex.get(rootNodeIndex);
            if (node == null) {
                return 0;
            }

            byte level = getLevelFromIndex(rootNodeIndex);
            int entityCount = node.getEntityCount();

            // Check if node needs balancing
            BalancingAction action = checkNodeBalance(rootNodeIndex);

            switch (action) {
                case SPLIT -> {
                    if (level < maxDepth) {
                        List<Long> children = splitNode(rootNodeIndex, level);
                        modifications += children.size();
                    }
                }
                case MERGE -> {
                    // Find siblings for merging
                    Set<Long> siblings = findSiblings(rootNodeIndex);
                    if (!siblings.isEmpty()) {
                        long parent = getParentIndex(rootNodeIndex);
                        if (mergeNodes(siblings, parent)) {
                            modifications++;
                        }
                    }
                }
                case REDISTRIBUTE -> {
                    // Redistribution not implemented in default balancer
                }
                case NONE -> {
                    // No action needed
                }
            }

            // Recursively balance children
            List<Long> children = getChildNodes(rootNodeIndex);
            for (Long child : children) {
                modifications += rebalanceSubtreeImpl(child, visited);
            }

            return modifications;
        }
    }
}
