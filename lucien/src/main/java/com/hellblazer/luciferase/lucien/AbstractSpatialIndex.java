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

import com.hellblazer.luciferase.lucien.FineGrainedLockingStrategy.LockingConfig;
import com.hellblazer.luciferase.lucien.balancing.DefaultBalancingStrategy;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancingStrategy;
import com.hellblazer.luciferase.lucien.collision.CollisionShape;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.internal.EntityCache;
import com.hellblazer.luciferase.lucien.internal.IndexedEntity;
import com.hellblazer.luciferase.lucien.internal.ObjectPools;
import com.hellblazer.luciferase.lucien.internal.UnorderedPair;
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
 * <h2>Thread Safety and Locking Strategy</h2>
 * <p>This class uses a {@link ReadWriteLock} to ensure thread-safe access to the spatial index and entity data.
 * The locking strategy follows these principles:</p>
 * <ul>
 *   <li><b>Read locks</b> for query operations (entity lookups, spatial queries, statistics)</li>
 *   <li><b>Write locks</b> for modification operations (insert, remove, update, tree restructuring)</li>
 *   <li><b>Lock ordering</b> to prevent deadlocks when acquiring multiple locks</li>
 *   <li><b>Minimal lock scope</b> to maximize concurrency</li>
 * </ul>
 *
 * <h3>Why Entity Delegation Methods Need Locking</h3>
 * <p>Although methods like {@code containsEntity()}, {@code getEntity()}, and {@code entityCount()} simply
 * delegate to the EntityManager, they must acquire read locks because:</p>
 * <ol>
 *   <li><b>Consistency:</b> Prevents entity state from changing during the operation</li>
 *   <li><b>Atomicity:</b> Ensures batch operations see a consistent snapshot</li>
 *   <li><b>Memory visibility:</b> Guarantees changes made by other threads are visible</li>
 *   <li><b>Race condition prevention:</b> Avoids issues like an entity being removed between
 *       existence check and content retrieval</li>
 * </ol>
 *
 * <p>The overhead of read locks is minimal as they allow multiple concurrent readers, only blocking
 * writers during the operation.</p>
 *
 * @param <ID>       The type of EntityID used
 * @param <Content>  The type of content stored
 * @param <NodeType> The type of spatial node used by the implementation
 * @author hal.hildebrand
 */
public abstract class AbstractSpatialIndex<Key extends SpatialKey<Key>, ID extends EntityID, Content, NodeType extends SpatialNodeStorage<ID>>
implements SpatialIndex<Key, ID, Content> {

    private static final Logger log = LoggerFactory.getLogger(AbstractSpatialIndex.class);

    // Common fields
    protected final EntityManager<Key, ID, Content>                    entityManager;
    protected final int                                                maxEntitiesPerNode;
    protected final byte                                               maxDepth;
    protected final EntitySpanningPolicy                               spanningPolicy;
    // Spatial index: Long index -> Node containing entity IDs
    protected final Map<Key, NodeType>                                 spatialIndex;
    // Sorted spatial indices for efficient range queries
    protected final NavigableSet<Key>                                  sortedSpatialIndices;
    // Read-write lock for thread safety
    protected final ReadWriteLock                                      lock;
    // Fine-grained locking strategy for high-concurrency operations
    protected FineGrainedLockingStrategy<ID, Content, NodeType>  lockingStrategy;
    protected final Set<Long>                                          deferredSubdivisionNodes = new HashSet<>();
    private final   TreeBalancer<Key, ID>                              treeBalancer;
    // Entity data cache for performance
    private final   EntityCache<ID>                                    entityCache;
    // Bulk operation support
    protected       BulkOperationConfig                                bulkConfig               = new BulkOperationConfig();
    protected       boolean                                            bulkLoadingMode          = false;
    protected       BulkOperationProcessor<Key, ID, Content>           bulkProcessor;
    protected       DeferredSubdivisionManager<Key, ID, NodeType>      subdivisionManager;
    protected       SpatialNodePool<NodeType>                          nodePool;
    protected       ParallelBulkOperations<Key, ID, Content, NodeType> parallelOperations;
    protected       SubdivisionStrategy<Key, ID, Content>              subdivisionStrategy;
    protected       StackBasedTreeBuilder<Key, ID, Content, NodeType>  treeBuilder;
    // Tree balancing support
    private         TreeBalancingStrategy<ID>                          balancingStrategy;
    private         boolean                                            autoBalancingEnabled     = false;
    private         long                                               lastBalancingTime        = 0;

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
        this.sortedSpatialIndices = new TreeSet<>();
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
        this.treeBuilder = new StackBasedTreeBuilder<>(StackBasedTreeBuilder.defaultConfig());
        this.entityCache = new EntityCache<>(10000); // Cache up to 10k entities
    }

    @Override
    public Stream<SpatialNode<Key, ID>> boundedBy(Spatial volume) {
        validateSpatialConstraints(volume);

        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        lock.readLock().lock();
        try {
            // Must collect results inside lock to avoid concurrent modification
            var results = spatialRangeQuery(bounds, false).filter(
            entry -> isNodeContainedInVolume(entry.getKey(), volume)).map(
            entry -> new SpatialNode<>(entry.getKey(), new HashSet<>(entry.getValue().getEntityIds()))).collect(
            Collectors.toList());
            return results.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Stream<SpatialNode<Key, ID>> bounding(Spatial volume) {
        validateSpatialConstraints(volume);

        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        lock.readLock().lock();
        try {
            // Must collect results inside lock to avoid concurrent modification
            var results = spatialRangeQuery(bounds, true).filter(
            entry -> doesNodeIntersectVolume(entry.getKey(), volume)).map(
            entry -> new SpatialNode<>(entry.getKey(), new HashSet<>(entry.getValue().getEntityIds()))).collect(
            Collectors.toList());
            return results.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Build tree using stack-based approach for better cache locality
     */
    public StackBasedTreeBuilder.BuildResult buildTreeStackBased(List<Point3f> positions, List<Content> contents,
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

    // ===== Common Entity Management Methods =====

    @Override
    public Optional<CollisionPair<ID, Content>> checkCollision(ID entityId1, ID entityId2) {
        if (entityId1.equals(entityId2)) {
            return Optional.empty();
        }

        lock.readLock().lock();
        try {
            var bounds1 = getCachedEntityBounds(entityId1);
            var bounds2 = getCachedEntityBounds(entityId2);

            // Quick early rejection check
            if (bounds1 != null && bounds2 != null) {
                // Both have bounds - quick AABB check
                if (!boundsIntersect(bounds1, bounds2)) {
                    return Optional.empty();
                }
            } else if (bounds1 == null && bounds2 == null) {
                // Both are points - check distance
                var pos1 = getCachedEntityPosition(entityId1);
                var pos2 = getCachedEntityPosition(entityId2);

                if (pos1 == null || pos2 == null) {
                    return Optional.empty();
                }

                var threshold = 0.1f; // Small threshold for point entities
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

    @Override
    public void configureBulkOperations(BulkOperationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Bulk operation config cannot be null");
        }
        this.bulkConfig = config;
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

    /**
     * Configure subdivision strategy
     */
    public void configureSubdivisionStrategy(SubdivisionStrategy<Key, ID, Content> strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("Subdivision strategy cannot be null");
        }
        this.subdivisionStrategy = strategy;
    }

    /**
     * Configure stack-based tree builder
     */
    public void configureTreeBuilder(StackBasedTreeBuilder.BuildConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Tree builder config cannot be null");
        }
        this.treeBuilder = new StackBasedTreeBuilder<>(config);
    }

    /**
     * Check if an entity exists in the spatial index.
     *
     * <p>This method delegates to the EntityManager but adds thread-safe locking to ensure
     * consistency in concurrent environments. The read lock allows multiple threads to check entity existence
     * simultaneously while preventing modifications during the check.</p>
     *
     * <p><b>Why locking is necessary:</b> Even though this is a simple delegation, the EntityManager's
     * internal state could be modified by other threads during the check. The read lock ensures a consistent view of
     * the entity state.</p>
     *
     * @param entityId the ID of the entity to check
     * @return true if the entity exists in the spatial index
     */
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

    /**
     * Get all entities within a spatial region.
     *
     * This implementation: 1. Finds all nodes that intersect the region 2. Collects all entities from those nodes 3.
     * Optionally filters by exact position (if precise is true)
     *
     * @param region the spatial region to query
     * @return list of entity IDs in the region
     */
    @Override
    public List<ID> entitiesInRegion(Spatial.Cube region) {
        // Validate region based on implementation constraints
        validateSpatialConstraints(new Point3f(region.originX(), region.originY(), region.originZ()));

        var uniqueEntities = new HashSet<ID>();

        lock.readLock().lock();
        try {
            // Convert to volume bounds
            var bounds = new VolumeBounds(region.originX(), region.originY(), region.originZ(),
                                          region.originX() + region.extent(), region.originY() + region.extent(),
                                          region.originZ() + region.extent());

            // Use spatial range query to find all intersecting nodes
            var nodeList = spatialRangeQuery(bounds, true).collect(java.util.stream.Collectors.toList());

            // Collect all entities from intersecting nodes
            nodeList.forEach(entry -> {
                if (!entry.getValue().isEmpty()) {
                    uniqueEntities.addAll(entry.getValue().getEntityIds());
                }
            });

            // Filter by exact intersection - check entity bounds vs query region
            return uniqueEntities.stream().filter(entityId -> {
                // Check if entity bounds intersect with query region
                var entityBounds = entityManager.getEntityBounds(entityId);
                if (entityBounds != null) {
                    // Entity has bounds - check bounds intersection
                    return entityBounds.intersectsCube(region.originX(), region.originY(), region.originZ(),
                                                       region.extent());
                } else {
                    // Entity is a point - check position intersection
                    var pos = entityManager.getEntityPosition(entityId);
                    if (pos == null) {
                        return false;
                    }
                    return pos.x >= region.originX() && pos.x <= region.originX() + region.extent()
                    && pos.y >= region.originY() && pos.y <= region.originY() + region.extent()
                    && pos.z >= region.originZ() && pos.z <= region.originZ() + region.extent();
                }
            }).collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the total number of entities in the spatial index.
     *
     * <p>This method provides thread-safe access to the entity count by acquiring a read lock.
     * Multiple threads can read the count simultaneously, but modifications are blocked during the read to ensure
     * accuracy.</p>
     *
     * <p><b>Thread safety rationale:</b> Without locking, the count could change between the
     * method call and return, potentially causing issues in code that relies on accurate counts for resource allocation
     * or iteration bounds.</p>
     *
     * @return the number of entities currently stored in the spatial index
     */
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
                var result = subdivisionManager.processAll(
                new DeferredSubdivisionManager.SubdivisionProcessor<Key, ID, NodeType>() {
                    @Override
                    public Result subdivideNode(Key nodeIndex, NodeType node, byte level) {
                        var initialCount = spatialIndex.size();
                        var entityCount = node.getEntityCount();

                        // Only subdivide if still over threshold
                        if (entityCount > maxEntitiesPerNode && level < maxDepth) {
                            handleNodeSubdivision(nodeIndex, level, node);
                            var newNodes = spatialIndex.size() - initialCount;
                            return new Result(true, newNodes, entityCount);
                        }
                        return new Result(false, 0, 0);
                    }
                });

                // Log deferred subdivision results
                if (result.nodesProcessed > 0) {
                    log.debug("Deferred subdivisions: {} processed, {} subdivided, {} new nodes in {}ms",
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
            var collisions = ObjectPools.<CollisionPair<ID, Content>>borrowArrayList();
            var checkedPairs = ObjectPools.<UnorderedPair<ID>>borrowHashSet();
            try {
                // Perform four phases of collision detection
                findIntraNodeCollisions(collisions, checkedPairs);
                findBoundedEntityCollisions(collisions, checkedPairs);
                findAdjacentNodeCollisions(collisions, checkedPairs);
                findPointBoundedCollisions(collisions, checkedPairs);

                // Sort by penetration depth (deepest first)
                Collections.sort(collisions);
                
                // Return a copy to avoid returning pooled object
                return new ArrayList<>(collisions);
            } finally {
                ObjectPools.returnArrayList(collisions);
                ObjectPools.returnHashSet(checkedPairs);
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    public List<CollisionPair<ID, Content>> findCollisions(ID entityId) {
        lock.readLock().lock();
        try {
            var locations = entityManager.getEntityLocations(entityId);
            if (locations.isEmpty()) {
                return Collections.emptyList();
            }

            var collisions = ObjectPools.<CollisionPair<ID, Content>>borrowArrayList();
            var checkedEntities = ObjectPools.<ID>borrowHashSet();
            try {
                checkedEntities.add(entityId);

                var entityBounds = entityManager.getEntityBounds(entityId);
                if (entityBounds != null) {
                    findBoundedEntityCollisions(entityId, entityBounds, checkedEntities, collisions);
                } else {
                    findPointEntityCollisions(entityId, locations, checkedEntities, collisions);
                }

                Collections.sort(collisions);
                // Return a copy to avoid returning pooled object
                return new ArrayList<>(collisions);
            } finally {
                ObjectPools.returnArrayList(collisions);
                ObjectPools.returnHashSet(checkedEntities);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find collisions for a bounded entity
     */
    private void findBoundedEntityCollisions(ID entityId, EntityBounds entityBounds, 
                                           Set<ID> checkedEntities, List<CollisionPair<ID, Content>> collisions) {
        var nodesToCheck = findNodesIntersectingBounds(entityBounds);
        
        for (var nodeIndex : nodesToCheck) {
            checkNodeEntitiesForCollisions(entityId, nodeIndex, checkedEntities, collisions);
        }
    }

    /**
     * Find collisions for a point entity
     */
    private void findPointEntityCollisions(ID entityId, Set<Key> locations,
                                         Set<ID> checkedEntities, List<CollisionPair<ID, Content>> collisions) {
        for (var nodeIndex : locations) {
            // Check entities in the same node
            checkNodeEntitiesForCollisions(entityId, nodeIndex, checkedEntities, collisions);
            
            // Check entities in neighboring nodes
            checkNeighborNodesForCollisions(entityId, nodeIndex, checkedEntities, collisions);
        }
    }

    /**
     * Check all entities in a node for collisions
     */
    private void checkNodeEntitiesForCollisions(ID entityId, Key nodeIndex,
                                              Set<ID> checkedEntities, List<CollisionPair<ID, Content>> collisions) {
        var node = spatialIndex.get(nodeIndex);
        if (node == null || node.isEmpty()) {
            return;
        }

        for (ID otherId : node.getEntityIds()) {
            if (!checkedEntities.add(otherId)) {
                continue;
            }
            checkAndAddCollision(entityId, otherId, collisions);
        }
    }

    /**
     * Check neighboring nodes for collisions
     */
    private void checkNeighborNodesForCollisions(ID entityId, Key nodeIndex,
                                               Set<ID> checkedEntities, List<CollisionPair<ID, Content>> collisions) {
        var neighbors = new LinkedList<Key>();
        var visitedNeighbors = new HashSet<Key>();
        addNeighboringNodes(nodeIndex, neighbors, visitedNeighbors);

        while (!neighbors.isEmpty()) {
            var neighborIndex = neighbors.poll();
            checkNodeEntitiesForCollisions(entityId, neighborIndex, checkedEntities, collisions);
        }
    }

    // ===== Common Insert Operations =====

    @Override
    public List<CollisionPair<ID, Content>> findCollisionsInRegion(Spatial region) {
        lock.readLock().lock();
        try {
            var collisions = new ArrayList<CollisionPair<ID, Content>>();
            var checkedPairs = new HashSet<UnorderedPair<ID>>();

            // Find all nodes intersecting the region
            var bounds = getVolumeBounds(region);
            if (bounds == null) {
                return collisions;
            }

            var nodesInRegion = spatialRangeQuery(bounds, true);
            var nodeList = nodesInRegion.collect(Collectors.toList());

            // Check entities within and between nodes
            for (int i = 0; i < nodeList.size(); i++) {
                List<ID> nodeEntities = new ArrayList<>(nodeList.get(i).getValue().getEntityIds());

                // Check within node
                for (int j = 0; j < nodeEntities.size(); j++) {
                    for (int k = j + 1; k < nodeEntities.size(); k++) {
                        var id1 = nodeEntities.get(j);
                        var id2 = nodeEntities.get(k);

                        // Check if both entities are actually in the region
                        if (isEntityInRegion(id1, region) && isEntityInRegion(id2, region)) {
                            var pair = new UnorderedPair<>(id1, id2);
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

                            var pair = new UnorderedPair<>(id1, id2);
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
            var intersections = ObjectPools.<FrustumIntersection<ID, Content>>borrowArrayList();
            var visitedEntities = ObjectPools.<ID>borrowHashSet();
            try {
                // Traverse nodes that could intersect with the frustum
                getFrustumTraversalOrder(frustum, cameraPosition).forEach(nodeIndex -> {
                    var node = spatialIndex.get(nodeIndex);
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

                        var content = entityManager.getEntityContent(entityId);
                        if (content == null) {
                            continue;
                        }

                        var entityPos = getCachedEntityPosition(entityId);
                        var bounds = getCachedEntityBounds(entityId);

                        // Calculate frustum-entity intersection
                        var intersection = calculateFrustumEntityIntersection(frustum, cameraPosition, entityId, content,
                                                                              entityPos, bounds);

                        if (intersection != null && intersection.isVisible()) {
                            intersections.add(intersection);
                        }
                    }
                });

                Collections.sort(intersections);
                // Return a copy to avoid returning pooled object
                return new ArrayList<>(intersections);
            } finally {
                ObjectPools.returnArrayList(intersections);
                ObjectPools.returnHashSet(visitedEntities);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<ID> frustumCullVisible(Frustum3D frustum) {
        // For the simple ID-only version, we don't need camera position or distance sorting
        // We just need to find which entities are visible
        lock.readLock().lock();
        try {
            var visibleEntities = new ArrayList<ID>();
            var visitedEntities = new HashSet<ID>();

            // Traverse all nodes that could intersect with the frustum
            spatialIndex.forEach((nodeIndex, node) -> {
                if (node == null || node.isEmpty()) {
                    return;
                }

                // Check if frustum intersects this node
                if (!doesFrustumIntersectNode(nodeIndex, frustum)) {
                    return;
                }

                // Add all entities in visible nodes
                for (ID entityId : node.getEntityIds()) {
                    // Skip if already processed (for spanning entities)
                    if (visitedEntities.add(entityId)) {
                        // For the simple version, we just check if the entity position is in the frustum
                        var entityPos = getCachedEntityPosition(entityId);
                        if (entityPos != null && frustum.containsPoint(entityPos)) {
                            visibleEntities.add(entityId);
                        }
                    }
                }
            });

            return visibleEntities;
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
            var totalNodes = 0;
            var underpopulatedNodes = 0;
            var overpopulatedNodes = 0;
            var emptyNodes = 0;
            var maxDepth = 0;
            var totalEntities = 0L;

            // SFC-optimized balancing stats: Process nodes in spatial order for better cache locality
            // This improves memory access patterns during statistics calculation
            for (var nodeIndex : sortedSpatialIndices) {
                var node = spatialIndex.get(nodeIndex);
                if (node == null) {
                    continue;
                }

                var level = nodeIndex.getLevel();
                maxDepth = Math.max(maxDepth, level);

                if (node.isEmpty()) {
                    emptyNodes++;
                    totalNodes++;
                } else {
                    totalNodes++;
                    var entityCount = node.getEntityCount();
                    totalEntities += entityCount;

                    var mergeThreshold = balancingStrategy.getMergeThreshold(level, maxEntitiesPerNode);
                    var splitThreshold = balancingStrategy.getSplitThreshold(level, maxEntitiesPerNode);

                    if (entityCount < mergeThreshold) {
                        underpopulatedNodes++;
                    } else if (entityCount > splitThreshold) {
                        overpopulatedNodes++;
                    }
                }
            }

            var averageLoad = totalNodes > 0 ? (double) totalEntities / totalNodes : 0;

            // Calculate variance using SFC ordering for improved cache performance
            var variance = 0.0;
            if (totalNodes > 0) {
                for (var nodeIndex : sortedSpatialIndices) {
                    var node = spatialIndex.get(nodeIndex);
                    if (node != null) {
                        var diff = node.getEntityCount() - averageLoad;
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

    /**
     * Get content for multiple entities in a single operation.
     *
     * <p>Batch retrieval with thread-safe locking. The read lock ensures consistency across
     * all entity retrievals, preventing partial updates where some entities might be modified during the batch
     * operation.</p>
     *
     * <p><b>Atomicity guarantee:</b> All entities are retrieved under the same lock, ensuring
     * a consistent snapshot of the entity state at a single point in time.</p>
     *
     * @param entityIds list of entity IDs to retrieve
     * @return list of content objects corresponding to the entity IDs
     */
    @Override
    public List<Content> getEntities(List<ID> entityIds) {
        lock.readLock().lock();
        try {
            return entityManager.getEntitiesContent(entityIds);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get all entities with their current positions.
     *
     * <p>Returns a consistent snapshot of all entity positions. The read lock prevents
     * entities from being added, removed, or moved during the operation, ensuring the returned map accurately
     * represents the spatial state at a single moment.</p>
     *
     * <p><b>Use case:</b> This method is particularly useful for visualization, debugging,
     * or algorithms that need a complete spatial snapshot without interference from concurrent modifications.</p>
     *
     * @return map of entity IDs to their current positions
     */
    @Override
    public Map<ID, Point3f> getEntitiesWithPositions() {
        lock.readLock().lock();
        try {
            return entityManager.getEntitiesWithPositions();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the content associated with an entity.
     *
     * <p>Provides thread-safe access to entity content. The read lock ensures that the entity
     * won't be removed or modified while retrieving its content, preventing null pointer exceptions or returning stale
     * data.</p>
     *
     * <p><b>Concurrency consideration:</b> In a multi-threaded environment, an entity could be
     * removed between checking its existence and retrieving its content. The read lock prevents this race
     * condition.</p>
     *
     * @param entityId the ID of the entity
     * @return the content associated with the entity, or null if not found
     */
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
            // Check cache first
            var cachedBounds = entityCache.getBounds(entityId);
            if (cachedBounds != null) {
                return cachedBounds;
            }
            
            // Cache miss - get from entity manager
            var bounds = entityManager.getEntityBounds(entityId);
            if (bounds != null) {
                // Update cache
                var position = entityManager.getEntityPosition(entityId);
                entityCache.put(entityId, position, bounds);
            }
            return bounds;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Common Query Operations =====

    @Override
    public Point3f getEntityPosition(ID entityId) {
        lock.readLock().lock();
        try {
            // Check cache first
            var cachedPosition = entityCache.getPosition(entityId);
            if (cachedPosition != null) {
                return cachedPosition;
            }
            
            // Cache miss - get from entity manager
            var position = entityManager.getEntityPosition(entityId);
            if (position != null) {
                // Update cache
                var bounds = entityManager.getEntityBounds(entityId);
                entityCache.put(entityId, position, bounds);
            }
            return position;
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

    @Override
    public List<ID> lookup(Point3f position, byte level) {
        validateSpatialConstraints(position);
        
        lock.readLock().lock();
        try {
            var spatialIndex = calculateSpatialIndex(position, level);
            var node = this.spatialIndex.get(spatialIndex);

            if (node == null) {
                return Collections.emptyList();
            }

            // If the node has been subdivided, look in child nodes
            if (hasChildren(spatialIndex) || node.isEmpty()) {
                var childLevel = (byte) (level + 1);
                if (childLevel <= maxDepth) {
                    return lookup(position, childLevel);
                }
            }

            return new ArrayList<>(node.getEntityIds());
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
            var nodeCount = spatialIndex.size();
            var totalEntities = (long) entityManager.getEntityCount();
            var avgEntitiesPerNode = nodeCount > 0 ? (float) totalEntities / nodeCount : 0;

            var estimatedMemory = NodeEstimator.estimateMemoryUsage(nodeCount, (int) Math.ceil(avgEntitiesPerNode));

            return new MemoryStats(nodeCount, totalEntities, avgEntitiesPerNode, estimatedMemory);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get a stream of all non-empty nodes in the spatial index.
     *
     * @return Stream of spatial nodes
     */
    public Stream<NodeType> nodeStream() {
        lock.readLock().lock();
        try {
            return spatialIndex.values().stream().filter(node -> !node.isEmpty());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get a stream of leaf nodes (nodes with no children).
     *
     * @return Stream of leaf nodes
     */
    public Stream<NodeType> leafStream() {
        lock.readLock().lock();
        try {
            return spatialIndex.entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty() && !hasChildren(entry.getKey()))
                    .map(Map.Entry::getValue);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get a stream of nodes at a specific level.
     *
     * @param level The spatial level
     * @return Stream of nodes at the specified level
     */
    public Stream<NodeType> levelStream(byte level) {
        lock.readLock().lock();
        try {
            return sortedSpatialIndices.stream()
                    .filter(index -> index.getLevel() == level)
                    .map(spatialIndex::get)
                    .filter(node -> node != null && !node.isEmpty());
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

    @Override
    public EntityStats getStats() {
        lock.readLock().lock();
        try {
            var nodeCount = 0;
            var entityCount = entityManager.getEntityCount();
            var totalEntityReferences = 0;
            var maxDepth = 0;

            for (var entry : getSpatialIndex().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    nodeCount++;
                }
                totalEntityReferences += entry.getValue().getEntityCount();

                // Calculate depth from spatial index
                maxDepth = Math.max(maxDepth, entry.getKey().getLevel());
            }

            return new EntityStats(nodeCount, entityCount, totalEntityReferences, maxDepth);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get current subdivision strategy
     */
    public SubdivisionStrategy<Key, ID, Content> getSubdivisionStrategy() {
        return subdivisionStrategy;
    }

    @Override
    public boolean hasNode(Key spatialIndex) {
        lock.readLock().lock();
        try {
            var node = getSpatialIndex().get(spatialIndex);
            return node != null && !node.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public ID insert(Point3f position, byte level, Content content) {
        lock.writeLock().lock();
        try {
            var entityId = entityManager.generateEntityId();
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

    // ===== Common k-NN Search Implementation =====

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

    /**
     * Insert multiple entities at once with their data
     * @param entities List of entity data to insert
     */
    public void insertAll(List<EntityData<ID, Content>> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        
        // Use bulk mode if enough entities
        if (entities.size() >= bulkConfig.getBatchSize()) {
            enableBulkLoading();
            try {
                for (var data : entities) {
                    if (data.bounds() != null) {
                        insert(data.id(), data.position(), data.level(), data.content(), data.bounds());
                    } else {
                        insert(data.id(), data.position(), data.level(), data.content());
                    }
                }
            } finally {
                finalizeBulkLoading();
            }
        } else {
            // Insert individually for small batches
            for (var data : entities) {
                if (data.bounds() != null) {
                    insert(data.id(), data.position(), data.level(), data.content(), data.bounds());
                } else {
                    insert(data.id(), data.position(), data.level(), data.content());
                }
            }
        }
    }

    @Override
    public List<ID> insertBatch(List<Point3f> positions, List<Content> contents, byte level) {
        validateBatchInputs(positions, contents);
        if (positions.isEmpty()) {
            return Collections.emptyList();
        }

        var effectiveLevel = determineBatchInsertionLevel(positions, level);
        var startTime = System.nanoTime();
        var insertedIds = new ArrayList<ID>(positions.size());

        lock.writeLock().lock();
        try {
            // Check if we should use stack-based builder for this bulk operation
            if (shouldUseStackBasedBuilder(positions.size())) {
                return performStackBasedBulkInsert(positions, contents, effectiveLevel);
            }

            // Enable bulk loading mode if configured
            var wasInBulkMode = bulkLoadingMode;
            if (bulkConfig.isDeferSubdivision() && !bulkLoadingMode) {
                enableBulkLoading();
            }

            // Preprocess entities with spatial optimization
            var mortonEntities = preprocessBatchEntities(positions, contents, effectiveLevel);

            // Insert entities using appropriate strategy
            if (positions.size() > bulkConfig.getBatchSize()) {
                insertGroupedEntities(mortonEntities, effectiveLevel, insertedIds);
            } else {
                insertDirectEntities(mortonEntities, level, insertedIds);
            }

            // Restore bulk mode state
            if (!wasInBulkMode && bulkConfig.isDeferSubdivision()) {
                finalizeBulkLoading();
            }

        } finally {
            lock.writeLock().unlock();
        }

        logBatchPerformance(positions.size(), startTime);
        return insertedIds;
    }

    /**
     * Validate batch insertion inputs
     */
    private void validateBatchInputs(List<Point3f> positions, List<Content> contents) {
        if (positions == null || contents == null) {
            throw new IllegalArgumentException("Positions and contents cannot be null");
        }
        if (positions.size() != contents.size()) {
            throw new IllegalArgumentException("Positions and contents must have the same size");
        }
    }

    /**
     * Determine the effective level for batch insertion
     */
    private byte determineBatchInsertionLevel(List<Point3f> positions, byte level) {
        if (!bulkConfig.isUseDynamicLevelSelection()) {
            return level;
        }

        var optimalLevel = LevelSelector.selectOptimalLevel(positions, maxEntitiesPerNode);
        if (optimalLevel != level) {
            log.debug("Dynamic level selection: changing from level {} to {} for {} entities", 
                      level, optimalLevel, positions.size());
        }
        return optimalLevel;
    }

    /**
     * Check if stack-based builder should be used
     */
    private boolean shouldUseStackBasedBuilder(int batchSize) {
        return bulkConfig.isUseStackBasedBuilder() && batchSize >= bulkConfig.getStackBuilderThreshold();
    }

    /**
     * Perform bulk insert using stack-based builder
     */
    private List<ID> performStackBasedBulkInsert(List<Point3f> positions, List<Content> contents, byte level) {
        configureTreeBuilder(bulkConfig.getStackBuilderConfig());
        
        var buildResult = treeBuilder.buildTree(this, positions, contents, level);
        
        log.debug("Stack-based bulk insertion completed: {} entities in {}ms, {} nodes created",
                  buildResult.entitiesProcessed, buildResult.timeTaken, buildResult.nodesCreated);

        if (buildResult.insertedIds.isEmpty() && buildResult.entitiesProcessed > 0) {
            log.warn("StackBasedTreeBuilder was configured not to track IDs but caller expects ID list. "
                     + "This can cause memory issues for large datasets. Consider using entityCount() instead of tracking individual IDs.");
            return Collections.emptyList();
        }
        return buildResult.insertedIds;
    }

    /**
     * Preprocess batch entities with spatial optimization
     */
    private List<BulkOperationProcessor.SfcEntity<Key, Content>> preprocessBatchEntities(
            List<Point3f> positions, List<Content> contents, byte level) {
        
        var useParallel = bulkConfig.isEnableParallel() && positions.size() >= bulkConfig.getParallelThreshold();
        var shouldUseMortonSort = determineMortonSortStrategy(positions, level);

        if (useParallel) {
            return bulkProcessor.preprocessBatchParallel(positions, contents, level,
                                                        shouldUseMortonSort,
                                                        bulkConfig.getParallelThreshold());
        } else {
            return bulkProcessor.preprocessBatch(positions, contents, level, shouldUseMortonSort);
        }
    }

    /**
     * Determine if Morton sorting should be used
     */
    private boolean determineMortonSortStrategy(List<Point3f> positions, byte level) {
        var shouldUseMortonSort = bulkConfig.isPreSortByMorton();
        if (bulkConfig.isUseDynamicLevelSelection()) {
            shouldUseMortonSort = shouldUseMortonSort && LevelSelector.shouldUseMortonSort(positions, level);
        }
        return shouldUseMortonSort;
    }

    /**
     * Insert entities grouped by spatial node
     */
    private void insertGroupedEntities(List<BulkOperationProcessor.SfcEntity<Key, Content>> mortonEntities,
                                      byte level, List<ID> insertedIds) {
        var grouped = bulkProcessor.groupByNode(mortonEntities, level);

        for (var entry : grouped.getGroups().entrySet()) {
            for (var entity : entry.getValue()) {
                var entityId = entityManager.generateEntityId();
                insertedIds.add(entityId);
                entityManager.createOrUpdateEntity(entityId, entity.content, entity.position, null);
                insertAtPosition(entityId, entity.position, level);
            }
        }
    }

    /**
     * Insert entities directly without grouping
     */
    private void insertDirectEntities(List<BulkOperationProcessor.SfcEntity<Key, Content>> mortonEntities,
                                     byte level, List<ID> insertedIds) {
        for (var entity : mortonEntities) {
            var entityId = entityManager.generateEntityId();
            insertedIds.add(entityId);
            entityManager.createOrUpdateEntity(entityId, entity.content, entity.position, null);
            insertAtPosition(entityId, entity.position, level);
        }
    }

    /**
     * Log batch insertion performance metrics
     */
    private void logBatchPerformance(int batchSize, long startTime) {
        if (batchSize > 1000) {
            var elapsedTime = System.nanoTime() - startTime;
            var rate = batchSize * 1_000_000_000.0 / elapsedTime;
            log.debug("Bulk inserted {} entities in {}ms ({} entities/sec)", batchSize,
                      String.format("%.2f", elapsedTime / 1_000_000.0), String.format("%.0f", rate));
        }
    }

    /**
     * Perform parallel bulk insertion for large datasets
     */
    public ParallelBulkOperations.ParallelOperationResult<ID> insertBatchParallel(List<Point3f> positions,
                                                                                  List<Content> contents, byte level)
    throws InterruptedException {
        return parallelOperations.insertBatchParallel(positions, contents, level);
    }

    // ===== Common Spatial Query Base =====

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

        var startTime = System.nanoTime();
        List<ID> insertedIds = new ArrayList<>(bounds.size());

        lock.writeLock().lock();
        try {
            // Enable bulk loading mode if configured
            var wasInBulkMode = bulkLoadingMode;
            if (bulkConfig.isDeferSubdivision() && !bulkLoadingMode) {
                enableBulkLoading();
            }

            // Process each entity with bounds
            for (int i = 0; i < bounds.size(); i++) {
                var entityBounds = bounds.get(i);
                var content = contents.get(i);

                // Calculate center position
                var center = new Point3f((entityBounds.getMinX() + entityBounds.getMaxX()) / 2,
                                         (entityBounds.getMinY() + entityBounds.getMaxY()) / 2,
                                         (entityBounds.getMinZ() + entityBounds.getMaxZ()) / 2);

                // Generate ID and store entity
                var entityId = entityManager.generateEntityId();
                insertedIds.add(entityId);
                entityManager.createOrUpdateEntity(entityId, content, center, entityBounds);

                // Handle spanning if configured
                var entitySize = Math.max(entityBounds.getMaxX() - entityBounds.getMinX(),
                                          Math.max(entityBounds.getMaxY() - entityBounds.getMinY(),
                                                   entityBounds.getMaxZ() - entityBounds.getMinZ()));
                var nodeSize = getCellSizeAtLevel(level);
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

        var elapsedTime = System.nanoTime() - startTime;

        // Log performance if significant batch
        if (bounds.size() > 1000) {
            double rate = bounds.size() * 1_000_000_000.0 / elapsedTime;
            log.debug("Bulk inserted {} entities with spanning in {}ms ({} entities/sec)", bounds.size(),
                      String.format("%.2f", elapsedTime / 1_000_000.0), String.format("%.0f", rate));
        }

        return insertedIds;
    }

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
            var candidates = new PriorityQueue<EntityDistance<ID>>(EntityDistance.maxHeapComparator());
            var addedToCandidates = new HashSet<ID>();

            // Try expanding radius search first
            if (!performKNNExpandingRadiusSearch(queryPoint, k, maxDistance, candidates, addedToCandidates)) {
                // If expanding search didn't find enough, try SFC-based search
                performKNNSFCBasedSearch(queryPoint, k, maxDistance, candidates, addedToCandidates);
            }

            // Convert to sorted list (closest first)
            return convertKNNCandidatesToList(candidates);
        });
    }

    public int nodeCount() {
        lock.readLock().lock();
        try {
            return (int) getSpatialIndex().values().stream().filter(node -> !node.isEmpty()).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the total number of nodes in this spatial index.
     *
     * @return the number of nodes
     */
    public int getNodeCount() {
        lock.readLock().lock();
        try {
            return spatialIndex.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the number of non-empty nodes in this spatial index.
     *
     * @return the number of non-empty nodes
     */
    public int size() {
        return (int) spatialIndex.values().stream().filter(node -> !node.isEmpty()).count();
    }

    @Override
    public Stream<SpatialNode<Key, ID>> nodes() {
        lock.readLock().lock();
        try {
            // Must collect results inside lock to avoid concurrent modification
            var results = getSpatialIndex().entrySet().stream().filter(entry -> !entry.getValue().isEmpty()).map(
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
            var intersections = new ArrayList<PlaneIntersection<ID, Content>>();

            // Traverse nodes that could intersect with the plane
            getPlaneTraversalOrder(plane).forEach(nodeIndex -> {
                var node = spatialIndex.get(nodeIndex);
                if (node == null || node.isEmpty()) {
                    return;
                }

                // Check if plane intersects this node
                if (!doesPlaneIntersectNode(nodeIndex, plane)) {
                    return;
                }

                // Check each entity in the node
                for (ID entityId : node.getEntityIds()) {
                    var content = entityManager.getEntityContent(entityId);
                    if (content == null) {
                        continue;
                    }

                    var entityPos = getCachedEntityPosition(entityId);
                    var bounds = entityManager.getEntityBounds(entityId);

                    // Calculate plane-entity intersection
                    var intersection = calculatePlaneEntityIntersection(plane, entityId, content, entityPos, bounds,
                                                                        tolerance);

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

    // ===== Ray Intersection Abstract Methods =====

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

    // ===== Ray Intersection Implementation =====

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
            var uniqueMortonCodes = new HashSet<Key>();
            for (var pos : samplePositions) {
                var morton = calculateSpatialIndex(pos, level);
                uniqueMortonCodes.add(morton);
            }

            // Estimate total nodes needed
            var estimatedNodes = NodeEstimator.estimateFromSamples(totalExpectedCount, samplePositions.size(),
                                                                   uniqueMortonCodes.size(), maxEntitiesPerNode);

            // Pre-allocate the unique nodes found in sample
            var created = 0;
            for (var morton : uniqueMortonCodes) {
                if (!spatialIndex.containsKey(morton)) {
                    var node = createNode();
                    spatialIndex.put(morton, node);
                    sortedSpatialIndices.add(morton);
                    created++;
                }
            }

            // Note: HashMap pre-sizing would require recreating the map with initial capacity
            // For now, just log the recommendation
            var remainingCapacity = estimatedNodes - created;
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
            var estimatedNodes = NodeEstimator.estimateNodeCount(expectedEntityCount, maxEntitiesPerNode, maxDepth,
                                                                 distribution);

            // Pre-size the HashMap to avoid rehashing
            // Note: We would need to recreate the HashMap with initial capacity since
            // Java's HashMap doesn't have ensureCapacity. For now, we'll just log the recommendation.
            var recommendedCapacity = (int) (estimatedNodes / 0.75f) + 1; // Account for load factor

            // Pre-allocate TreeSet capacity if possible
            // Note: TreeSet doesn't have pre-allocation, but we can optimize by
            // using a more efficient set implementation if needed

            log.debug("Pre-allocated capacity for {} nodes (estimated from {} entities)", estimatedNodes,
                      expectedEntityCount);

        } finally {
            lock.writeLock().unlock();
        }
    }

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
            var totalNodes = NodeEstimator.estimateUniformGridNodes(level, nodesPerDimension);

            // Pre-create nodes in a grid pattern
            var cellSize = getCellSizeAtLevel(level);
            var created = 0;

            for (int x = 0; x < nodesPerDimension && created < totalNodes; x++) {
                for (int y = 0; y < nodesPerDimension && created < totalNodes; y++) {
                    for (int z = 0; z < nodesPerDimension && created < totalNodes; z++) {
                        var position = new Point3f(x * cellSize + cellSize / 2, y * cellSize + cellSize / 2,
                                                   z * cellSize + cellSize / 2);

                        var mortonIndex = calculateSpatialIndex(position, level);

                        // Only create if doesn't exist
                        if (!spatialIndex.containsKey(mortonIndex)) {
                            var node = createNode();
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
            var intersections = new ArrayList<RayIntersection<ID, Content>>();
            var visitedEntities = new HashSet<ID>();

            // Get nodes in traversal order
            getRayTraversalOrder(ray).forEach(nodeIndex -> {
                var node = spatialIndex.get(nodeIndex);
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
                    var entityPos = getCachedEntityPosition(entityId);
                    if (entityPos == null) {
                        continue;
                    }

                    // Check ray-entity intersection
                    var distance = getRayEntityDistance(ray, entityId, entityPos);
                    if (distance >= 0 && ray.isWithinDistance(distance)) {
                        var content = entityManager.getEntityContent(entityId);
                        var bounds = getCachedEntityBounds(entityId);

                        // Calculate intersection point
                        var intersectionPoint = ray.pointAt(distance);

                        // Calculate normal (simplified - towards ray origin)
                        var normal = new Vector3f();
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
            var visitedEntities = new HashSet<ID>();
            RayIntersection<ID, Content> closest = null;
            var closestDistance = Float.MAX_VALUE;

            // Traverse nodes in order until we find an intersection
            var nodeIterator = getRayTraversalOrder(ray).iterator();
            while (nodeIterator.hasNext()) {
                var nodeIndex = nodeIterator.next();

                // Early termination if node is beyond closest found
                var nodeDistance = getRayNodeIntersectionDistance(nodeIndex, ray);
                if (nodeDistance > closestDistance) {
                    break;
                }

                var node = spatialIndex.get(nodeIndex);
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

                    var entityPos = getCachedEntityPosition(entityId);
                    if (entityPos == null) {
                        continue;
                    }

                    var distance = getRayEntityDistance(ray, entityId, entityPos);
                    if (distance >= 0 && distance < closestDistance && ray.isWithinDistance(distance)) {
                        var content = entityManager.getEntityContent(entityId);
                        var bounds = getCachedEntityBounds(entityId);
                        var intersectionPoint = ray.pointAt(distance);

                        var normal = new Vector3f();
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

    // ===== Plane Intersection Abstract Methods =====

    @Override
    public List<RayIntersection<ID, Content>> rayIntersectWithin(Ray3D ray, float maxDistance) {
        validateSpatialConstraints(ray.origin());

        if (maxDistance <= 0) {
            return Collections.emptyList();
        }

        // Create a bounded ray with the minimum of ray's max distance and provided max distance
        var boundedRay = ray.withMaxDistance(Math.min(ray.maxDistance(), maxDistance));

        lock.readLock().lock();
        try {
            var intersections = new ArrayList<RayIntersection<ID, Content>>();
            var visitedEntities = new HashSet<ID>();

            getRayTraversalOrder(boundedRay).forEach(nodeIndex -> {
                // Early termination if node is beyond max distance
                float nodeDistance = getRayNodeIntersectionDistance(nodeIndex, boundedRay);
                if (nodeDistance > maxDistance) {
                    return;
                }

                var node = spatialIndex.get(nodeIndex);
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

                    var entityPos = getCachedEntityPosition(entityId);
                    if (entityPos == null) {
                        continue;
                    }

                    float distance = getRayEntityDistance(boundedRay, entityId, entityPos);
                    if (distance >= 0 && distance <= maxDistance) {
                        var content = entityManager.getEntityContent(entityId);
                        var bounds = getCachedEntityBounds(entityId);
                        var intersectionPoint = boundedRay.pointAt(distance);

                        var normal = new Vector3f();
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
            var locations = entityManager.getEntityLocations(entityId);

            // Remove from entity storage
            var removed = entityManager.removeEntity(entityId);
            if (removed == null) {
                return false;
            }
            
            // Invalidate cache
            entityCache.remove(entityId);

            if (!locations.isEmpty()) {
                // Remove from each node
                for (var spatialIndex : locations) {
                    var node = getSpatialIndex().get(spatialIndex);
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

    // ===== Plane Intersection Implementation =====

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

    // Removed ensureAncestorNodes - not needed in pointerless SFC implementation

    /**
     * Shutdown parallel operations (cleanup resources)
     */
    public void shutdownParallelOperations() {
        if (parallelOperations != null) {
            parallelOperations.shutdown();
        }
    }

    @Override
    public void traverse(TreeVisitor<Key, ID, Content> visitor, TraversalStrategy strategy) {
        lock.readLock().lock();
        try {
            // Count total nodes and entities
            var totalNodes = nodeCount();
            var totalEntities = entityCount();

            visitor.beginTraversal(totalNodes, totalEntities);

            var context = new TraversalContext<Key, ID>();

            // Get root nodes based on implementation
            var rootNodes = getRootNodes();

            for (var rootIndex : rootNodes) {
                if (context.isCancelled()) {
                    break;
                }
                traverseNode(rootIndex, visitor, strategy, context, null, 0);
            }

            visitor.endTraversal(context.getNodesVisited(), context.getEntitiesVisited());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void traverseFrom(TreeVisitor<Key, ID, Content> visitor, TraversalStrategy strategy, Key startNodeIndex) {
        lock.readLock().lock();
        try {
            if (!hasNode(startNodeIndex)) {
                visitor.beginTraversal(0, 0);
                visitor.endTraversal(0, 0);
                return;
            }

            visitor.beginTraversal(-1, -1); // Unknown totals

            var context = new TraversalContext<Key, ID>();
            traverseNode(startNodeIndex, visitor, strategy, context, null, 0);

            visitor.endTraversal(context.getNodesVisited(), context.getEntitiesVisited());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void traverseRegion(TreeVisitor<Key, ID, Content> visitor, Spatial region, TraversalStrategy strategy) {
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
            var nodesInRegion = spatialRangeQuery(bounds, true).map(Map.Entry::getKey).collect(Collectors.toList());

            visitor.beginTraversal(nodesInRegion.size(), -1);

            var context = new TraversalContext<Key, ID>();

            for (var nodeIndex : nodesInRegion) {
                if (context.isCancelled()) {
                    break;
                }

                if (!context.isVisited(nodeIndex)) {
                    traverseNode(nodeIndex, visitor, strategy, context, null, 0);
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
            var emptyNodes = new ArrayList<Key>();

            // Find empty nodes
            for (var entry : spatialIndex.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    emptyNodes.add(entry.getKey());
                }
            }

            // Remove empty nodes
            for (var key : emptyNodes) {
                spatialIndex.remove(key);
                sortedSpatialIndices.remove(key);
            }

            if (!emptyNodes.isEmpty()) {
                log.debug("Trimmed {} empty pre-allocated nodes", emptyNodes.size());
            }

        } finally {
            lock.writeLock().unlock();
        }
    }

    // ===== Frustum Culling Implementation =====

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
            var oldPosition = entityManager.getEntityPosition(entityId);
            if (oldPosition == null) {
                throw new IllegalArgumentException("Entity not found: " + entityId);
            }

            // Calculate movement delta
            var delta = new Vector3f();
            delta.sub(newPosition, oldPosition);

            // Update entity position
            entityManager.updateEntityPosition(entityId, newPosition);
            
            // Invalidate cache
            entityCache.remove(entityId);

            // Update collision shape position if present
            var shape = entityManager.getEntityCollisionShape(entityId);
            if (shape != null) {
                shape.translate(delta);
                // Update bounds from the translated collision shape
                entityManager.setEntityCollisionShape(entityId, shape);
            } else {
                // Update entity bounds if no collision shape
                var oldBounds = entityManager.getEntityBounds(entityId);
                if (oldBounds != null) {
                    // Translate the bounds
                    var newMin = new Point3f(oldBounds.getMinX() + delta.x, oldBounds.getMinY() + delta.y,
                                             oldBounds.getMinZ() + delta.z);
                    var newMax = new Point3f(oldBounds.getMaxX() + delta.x, oldBounds.getMaxY() + delta.y,
                                             oldBounds.getMaxZ() + delta.z);
                    var newBounds = new EntityBounds(newMin, newMax);
                    entityManager.setEntityBounds(entityId, newBounds);
                }
            }

            // Remove from all current locations
            var oldLocations = entityManager.getEntityLocations(entityId);
            for (var spatialIndex : oldLocations) {
                var node = getSpatialIndex().get(spatialIndex);
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
    protected abstract void addNeighboringNodes(Key nodeIndex, Queue<Key> toVisit, Set<Key> visitedNodes);

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

    /**
     * Calculate the spatial index for a position at a given level
     */
    protected abstract Key calculateSpatialIndex(Point3f position, byte level);

    /**
     * Check and perform automatic balancing if needed.
     */
    protected void checkAutoBalance() {
        if (!autoBalancingEnabled) {
            return;
        }

        var currentTime = System.currentTimeMillis();
        if (currentTime - lastBalancingTime < balancingStrategy.getMinRebalancingInterval()) {
            return;
        }

        var stats = getBalancingStats();
        if (balancingStrategy.shouldRebalanceTree(stats)) {
            lastBalancingTime = currentTime;
            treeBalancer.rebalanceTree();
        }
    }

    /**
     * Clean up empty nodes from the spatial index
     */
    protected void cleanupEmptyNode(Key spatialIndex, NodeType node) {
        if (node.isEmpty() && !hasChildren(spatialIndex)) {
            getSpatialIndex().remove(spatialIndex);
            onNodeRemoved(spatialIndex);
            // Return node to pool for reuse
            nodePool.release(node);
        }
    }

    // ===== Collision Detection Implementation =====

    /**
     * Create the default subdivision strategy for this spatial index. Subclasses should override to provide their
     * specific strategy.
     */
    protected abstract SubdivisionStrategy<Key, ID, Content> createDefaultSubdivisionStrategy();

    /**
     * Create a new node instance
     */
    protected abstract NodeType createNode();

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
    protected TreeBalancer<Key, ID> createTreeBalancer() {
        return new DefaultTreeBalancer();
    }

    /**
     * Check if a frustum intersects with the given node
     *
     * @param nodeIndex the node's spatial index
     * @param frustum   the frustum to test
     * @return true if the frustum intersects the node
     */
    protected abstract boolean doesFrustumIntersectNode(Key nodeIndex, Frustum3D frustum);

    /**
     * Check if a node's bounds intersect with a volume
     */
    protected abstract boolean doesNodeIntersectVolume(Key nodeIndex, Spatial volume);

    /**
     * Test if a plane intersects with a node
     *
     * @param nodeIndex the node's spatial index
     * @param plane     the plane to test
     * @return true if the plane intersects the node
     */
    protected abstract boolean doesPlaneIntersectNode(Key nodeIndex, Plane3D plane);

    /**
     * Test if a ray intersects with a node
     *
     * @param nodeIndex the node's spatial index
     * @param ray       the ray to test
     * @return true if the ray intersects the node
     */
    protected abstract boolean doesRayIntersectNode(Key nodeIndex, Ray3D ray);

    /**
     * Estimate the distance from a query point to the center of a spatial node. This is used for k-NN search
     * optimization to find the nearest starting nodes.
     *
     * @param nodeIndex  the spatial node index
     * @param queryPoint the query point
     * @return estimated distance from query point to node center
     */
    protected abstract float estimateNodeDistance(Key nodeIndex, Point3f queryPoint);

    /**
     * Filter nodes in SFC order using the given predicate and collect them into a list. This utility method combines
     * spatial ordering with filtering for better performance.
     *
     * @param nodePredicate predicate to test each node
     * @return list of node indices that match the predicate, in SFC order
     */
    protected List<Key> filterNodesInSFCOrder(java.util.function.BiPredicate<Key, NodeType> nodePredicate) {
        var filteredNodes = new ArrayList<Key>();
        for (var nodeIndex : sortedSpatialIndices) {
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
        var x = Math.max(bounds.getMinX(), Math.min(cameraPosition.x, bounds.getMaxX()));
        var y = Math.max(bounds.getMinY(), Math.min(cameraPosition.y, bounds.getMaxY()));
        var z = Math.max(bounds.getMinZ(), Math.min(cameraPosition.z, bounds.getMaxZ()));

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
        var normal = plane.getNormal();

        // Find the point on the AABB closest to the plane
        var x = (normal.x >= 0) ? bounds.getMinX() : bounds.getMaxX();
        var y = (normal.y >= 0) ? bounds.getMinY() : bounds.getMaxY();
        var z = (normal.z >= 0) ? bounds.getMinZ() : bounds.getMaxZ();

        return new Point3f(x, y, z);
    }

    /**
     * Find minimum containing level for bounds
     */
    protected byte findMinimumContainingLevel(VolumeBounds bounds) {
        var maxExtent = bounds.maxExtent();

        // Find the level where cell size >= maxExtent
        for (var level = (byte) 0; level <= maxDepth; level++) {
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
    protected Set<Key> findNodesIntersectingBounds(EntityBounds bounds) {
        var intersectingNodes = new HashSet<Key>();

        // Convert EntityBounds to VolumeBounds for spatial query
        var volumeBounds = new VolumeBounds(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(), bounds.getMaxX(),
                                            bounds.getMaxY(), bounds.getMaxZ());

        // Use spatial range query to find intersecting nodes
        spatialRangeQuery(volumeBounds, true).forEach(entry -> intersectingNodes.add(entry.getKey()));

        return intersectingNodes;
    }

    /**
     * Find all nodes that intersect with the given bounds. This method should be implemented efficiently by subclasses
     * using their specific spatial data structures (e.g., using sorted indices for range queries).
     *
     * @param bounds the volume bounds to check
     * @return set of node keys that intersect with the bounds
     */
    protected abstract Set<Key> findNodesIntersectingBounds(VolumeBounds bounds);

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
        var minX = bounds.getMinX();
        var minY = bounds.getMinY();
        var minZ = bounds.getMinZ();
        var maxX = bounds.getMaxX();
        var maxY = bounds.getMaxY();
        var maxZ = bounds.getMaxZ();

        // Check if AABB is completely inside frustum
        var completelyInside = frustum.containsAABB(minX, minY, minZ, maxX, maxY, maxZ);

        // Check if AABB intersects frustum
        var intersects = frustum.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);

        if (!intersects) {
            // Outside frustum
            return null;
        }

        // Determine visibility type
        var visibilityType = (completelyInside) ? FrustumIntersection.VisibilityType.INSIDE
                                                : FrustumIntersection.VisibilityType.INTERSECTING;
        if (completelyInside) {
            visibilityType = FrustumIntersection.VisibilityType.INSIDE;
        } else {
            visibilityType = FrustumIntersection.VisibilityType.INTERSECTING;
        }

        // Calculate distance from camera to entity center
        var entityCenter = bounds.getCenter();
        var distanceFromCamera = cameraPosition.distance(entityCenter);

        // Calculate closest point on AABB to camera
        var closestPoint = findClosestPointOnAABBToCamera(cameraPosition, bounds);

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
        var isInside = frustum.containsPoint(point);

        if (!isInside) {
            return null;
        }

        // Calculate distance from camera to point
        var distanceFromCamera = cameraPosition.distance(point);

        // Point is always completely inside if it passes the containment test
        var visibilityType = FrustumIntersection.VisibilityType.INSIDE;

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
    protected List<Key> getChildNodes(Key nodeIndex) {
        return Collections.emptyList();
    }

    /**
     * Get nodes that should be traversed for frustum culling, ordered by distance from camera
     *
     * @param frustum        the frustum to test
     * @param cameraPosition the camera position for distance sorting
     * @return stream of node indices ordered by distance from camera
     */
    protected abstract Stream<Key> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition);

    /**
     * Get the spatial bounds of a node
     */
    protected abstract Spatial getNodeBounds(Key index);

    /**
     * Get nodes that should be traversed for plane intersection, ordered by distance from plane
     *
     * @param plane the plane to test
     * @return stream of node indices ordered by distance from plane
     */
    protected abstract Stream<Key> getPlaneTraversalOrder(Plane3D plane);

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
    protected abstract float getRayNodeIntersectionDistance(Key nodeIndex, Ray3D ray);

    /**
     * Get nodes that should be traversed for ray intersection, ordered by distance
     *
     * @param ray the ray to test
     * @return stream of node indices ordered by ray intersection distance
     */
    protected abstract Stream<Key> getRayTraversalOrder(Ray3D ray);

    /**
     * Get root nodes for traversal. Default implementation returns all nodes at the minimum level. Subclasses can
     * override for specific root node logic.
     */
    protected Set<Key> getRootNodes() {
        var roots = new HashSet<Key>();
        var minLevel = Byte.MAX_VALUE;

        // Find minimum level
        for (Key index : spatialIndex.keySet()) {
            var level = index.getLevel();
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
    protected NavigableSet<Key> getSortedSpatialIndices() {
        return sortedSpatialIndices;
    }

    /**
     * Get the spatial index storage map
     */
    protected Map<Key, NodeType> getSpatialIndex() {
        return spatialIndex;
    }

    /**
     * Get the range of spatial indices that could intersect with the given bounds This method should be overridden by
     * subclasses for specific optimizations
     *
     * @param bounds the volume bounds
     * @return navigable set of spatial indices
     */
    protected NavigableSet<Key> getSpatialIndexRange(VolumeBounds bounds) {
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

    /**
     * Hook for subclasses to handle node subdivision
     */
    protected void handleNodeSubdivision(Key spatialIndex, byte level, NodeType node) {
        // Default: no subdivision. Subclasses can override
    }

    /**
     * Check if a node has children (to be implemented by subclasses if needed)
     */
    protected boolean hasChildren(Key spatialIndex) {
        return false; // Default: no children tracking
    }

    /**
     * Insert entity at a single position (no spanning)
     */
    protected void insertAtPosition(ID entityId, Point3f position, byte level) {
        var spatialIndex = calculateSpatialIndex(position, level);

        // Get or create node directly - no need for ancestor nodes in SFC-based implementation
        var node = getSpatialIndex().computeIfAbsent(spatialIndex, k -> {
            sortedSpatialIndices.add(spatialIndex);
            return nodePool.acquire();
        });

        // If the node has been subdivided, we need to insert into the appropriate child
        if (hasChildren(spatialIndex) && !node.isEmpty()) {
            var childLevel = (byte) (level + 1);
            if (childLevel <= maxDepth) {
                insertAtPosition(entityId, position, childLevel);
                return;
            }
        }

        // Add entity to node
        var shouldSplit = node.addEntity(entityId);

        // Track entity location
        entityManager.addEntityLocation(entityId, spatialIndex);

        // Handle subdivision if needed
        if (shouldSplit && level < maxDepth && !hasChildren(spatialIndex)) {
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

    // ===== Tree Traversal Implementation =====

    /**
     * Adaptive spanning implementation
     */
    protected void insertWithAdaptiveSpanning(ID entityId, EntityBounds bounds, byte level, int maxSpanNodes) {
        // Adapt spanning strategy based on current system state
        var currentNodeCount = spatialIndex.size();
        var entityCount = entityManager.getEntityCount();

        if (entityCount > 0) {
            var avgNodesPerEntity = (float) currentNodeCount / entityCount;

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
        var entitySize = Math.max(Math.max(bounds.getMaxX() - bounds.getMinX(), bounds.getMaxY() - bounds.getMinY()),
                                  bounds.getMaxZ() - bounds.getMinZ());
        var nodeSize = getCellSizeAtLevel(level);

        // Calculate maximum span nodes based on policy
        var maxSpanNodes = spanningPolicy.calculateMaxSpanNodes(entitySize, nodeSize, spatialIndex.size());

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
        var center = bounds.getCenter();

        // Start with center node
        insertAtPosition(entityId, center, level);

        // Only span to immediately adjacent nodes if entity is very large
        var entitySize = Math.max(Math.max(bounds.getMaxX() - bounds.getMinX(), bounds.getMaxY() - bounds.getMinY()),
                                  bounds.getMaxZ() - bounds.getMinZ());
        var nodeSize = getCellSizeAtLevel(level);

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
        var position = entityManager.getEntityPosition(entityId);
        if (position != null) {
            insertAtPosition(entityId, position, level);
        }
    }

    /**
     * Check if a node's bounds are contained within a volume
     */
    protected abstract boolean isNodeContainedInVolume(Key nodeIndex, Spatial volume);

    /**
     * Hook for subclasses when a node is removed
     */
    protected void onNodeRemoved(Key spatialIndex) {
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
        var minX = bounds.getMinX();
        var minY = bounds.getMinY();
        var minZ = bounds.getMinZ();
        var maxX = bounds.getMaxX();
        var maxY = bounds.getMaxY();
        var maxZ = bounds.getMaxZ();

        // Calculate distances to all 8 corners of the AABB
        var corners = new Point3f[] { new Point3f(minX, minY, minZ), new Point3f(maxX, minY, minZ), new Point3f(minX,
                                                                                                                maxY,
                                                                                                                minZ),
                                      new Point3f(maxX, maxY, minZ), new Point3f(minX, minY, maxZ), new Point3f(maxX,
                                                                                                                minY,
                                                                                                                maxZ),
                                      new Point3f(minX, maxY, maxZ), new Point3f(maxX, maxY, maxZ) };

        var minDistance = Float.POSITIVE_INFINITY;
        var maxDistance = Float.NEGATIVE_INFINITY;
        var closestPoint = new Point3f();

        // Find min and max distances to plane
        for (var corner : corners) {
            var distance = plane.distanceToPoint(corner);
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
        var distance = plane.distanceToPoint(point);

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
    protected void processEntitiesInSFCOrder(java.util.function.BiConsumer<Key, ID> entityProcessor) {
        for (var nodeIndex : sortedSpatialIndices) {
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
    protected void processNodesInSFCOrder(java.util.function.BiConsumer<Key, NodeType> nodeProcessor) {
        for (var nodeIndex : sortedSpatialIndices) {
            NodeType node = spatialIndex.get(nodeIndex);
            if (node != null && !node.isEmpty()) {
                nodeProcessor.accept(nodeIndex, node);
            }
        }
    }

    /**
     * Ray-AABB intersection test
     *
     * @param ray    the ray
     * @param bounds the axis-aligned bounding box
     * @return distance to intersection, or -1 if no intersection
     */
    protected float rayAABBIntersection(Ray3D ray, EntityBounds bounds) {
        var tmin = 0.0f;
        var tmax = ray.maxDistance();

        // For each axis
        for (int i = 0; i < 3; i++) {
            var origin = getComponent(ray.origin(), i);
            var direction = getComponent(ray.direction(), i);
            var min = getComponent(bounds, i, true);
            var max = getComponent(bounds, i, false);

            if (Math.abs(direction) < 1e-6f) {
                // Ray is parallel to slab
                if (origin < min || origin > max) {
                    return -1;
                }
            } else {
                // Compute intersection distances
                var t1 = (min - origin) / direction;
                var t2 = (max - origin) / direction;

                if (t1 > t2) {
                    var temp = t1;
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
        var distFromOrigin = ray.origin().distance(center);
        if (distFromOrigin <= radius) {
            // Ray starts inside sphere, return 0
            return 0.0f;
        }

        // Find the closest point on the ray to the sphere center
        var toCenter = new Vector3f();
        toCenter.sub(center, ray.origin());

        var t = toCenter.dot(ray.direction());  // Parameter for closest point
        if (t < 0) {
            // Sphere is behind ray origin
            return -1;
        }

        // Calculate closest point on ray
        var closestPoint = new Point3f(ray.origin().x + t * ray.direction().x, ray.origin().y + t * ray.direction().y,
                                       ray.origin().z + t * ray.direction().z);

        var distToCenter = closestPoint.distance(center);

        if (distToCenter > radius) {
            // Ray misses sphere
            return -1;
        }

        // Calculate intersection points
        var halfChordLength = (float) Math.sqrt(radius * radius - distToCenter * distToCenter);

        var t1 = t - halfChordLength;
        var t2 = t + halfChordLength;

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
    protected abstract boolean shouldContinueKNNSearch(Key nodeIndex, Point3f queryPoint,
                                                       PriorityQueue<EntityDistance<ID>> candidates);

    // ===== Common Region Query Implementation =====

    /**
     * Check if an entity should span multiple nodes using advanced policies
     */
    protected boolean shouldSpanEntity(EntityBounds bounds, byte level) {
        if (bounds == null || !spanningPolicy.isSpanningEnabled()) {
            return false;
        }

        // Calculate entity size
        var entitySize = Math.max(Math.max(bounds.getMaxX() - bounds.getMinX(), bounds.getMaxY() - bounds.getMinY()),
                                  bounds.getMaxZ() - bounds.getMinZ());

        // Get node size at this level
        var nodeSize = getCellSizeAtLevel(level);

        // Use advanced spanning logic
        return spanningPolicy.shouldSpanAdvanced(entitySize, nodeSize, spatialIndex.size(),
                                                 entityManager.getEntityCount(), level);
    }

    // ===== Bulk Operations Implementation =====

    /**
     * Perform spatial range query with optimization
     *
     * @param bounds              the volume bounds to query
     * @param includeIntersecting whether to include intersecting nodes
     * @return stream of node entries that match the query
     */
    protected Stream<Map.Entry<Key, NodeType>> spatialRangeQuery(VolumeBounds bounds, boolean includeIntersecting) {
        // Get range of spatial indices that could contain or intersect the bounds
        var candidateIndices = getSpatialIndexRange(bounds);

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
     * Validate spatial constraints (e.g., positive coordinates for Tetree).
     * Default implementation does no validation.
     */
    protected void validateSpatialConstraints(Point3f position) {
        // Default: no spatial constraints
    }

    /**
     * Validate spatial constraints for volumes.
     * Default implementation does no validation.
     */
    protected void validateSpatialConstraints(Spatial volume) {
        // Default: no spatial constraints
    }

    ID generateId() {
        return entityManager.generateEntityId();
    }

    // Package-private accessors for StackBasedTreeBuilder
    EntityManager<Key, ID, Content> getEntityManager() {
        return entityManager;
    }

    byte getMaxDepth() {
        return maxDepth;
    }

    int getMaxEntitiesPerNode() {
        return maxEntitiesPerNode;
    }

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
                var closestX = Math.max(bounds.getMinX(), Math.min(sphere.centerX(), bounds.getMaxX()));
                var closestY = Math.max(bounds.getMinY(), Math.min(sphere.centerY(), bounds.getMaxY()));
                var closestZ = Math.max(bounds.getMinZ(), Math.min(sphere.centerZ(), bounds.getMaxZ()));

                var dx = closestX - sphere.centerX();
                var dy = closestY - sphere.centerY();
                var dz = closestZ - sphere.centerZ();
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
        var penetrations = new float[6];
        penetrations[0] = b1.getMaxX() - b2.getMinX(); // +X
        penetrations[1] = b2.getMaxX() - b1.getMinX(); // -X
        penetrations[2] = b1.getMaxY() - b2.getMinY(); // +Y
        penetrations[3] = b2.getMaxY() - b1.getMinY(); // -Y
        penetrations[4] = b1.getMaxZ() - b2.getMinZ(); // +Z
        penetrations[5] = b2.getMaxZ() - b1.getMinZ(); // -Z

        var minAxis = 0;
        var minPenetration = penetrations[0];
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
        var minX = Math.max(b1.getMinX(), b2.getMinX());
        var minY = Math.max(b1.getMinY(), b2.getMinY());
        var minZ = Math.max(b1.getMinZ(), b2.getMinZ());
        var maxX = Math.min(b1.getMaxX(), b2.getMaxX());
        var maxY = Math.min(b1.getMaxY(), b2.getMaxY());
        var maxZ = Math.min(b1.getMaxZ(), b2.getMaxZ());

        return new Point3f((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }

    /**
     * Calculate penetration depth between two AABBs
     */
    private float calculatePenetrationDepth(EntityBounds b1, EntityBounds b2) {
        var xPenetration = Math.min(b1.getMaxX() - b2.getMinX(), b2.getMaxX() - b1.getMinX());
        var yPenetration = Math.min(b1.getMaxY() - b2.getMinY(), b2.getMaxY() - b1.getMinY());
        var zPenetration = Math.min(b1.getMaxZ() - b2.getMinZ(), b2.getMaxZ() - b1.getMinZ());

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

        var collision = performDetailedCollisionCheck(id1, id2);
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
        var pos = entityManager.getEntityPosition(entityId);
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
                var dx = point.x - sphere.centerX();
                var dy = point.y - sphere.centerY();
                var dz = point.z - sphere.centerZ();
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
        var bounds1 = entityManager.getEntityBounds(id1);
        var bounds2 = entityManager.getEntityBounds(id2);
        var content1 = entityManager.getEntityContent(id1);
        var content2 = entityManager.getEntityContent(id2);

        // Check if we have collision shapes for narrow-phase detection
        var shape1 = entityManager.getEntityCollisionShape(id1);
        var shape2 = entityManager.getEntityCollisionShape(id2);

        if (shape1 != null && shape2 != null) {
            return checkShapeCollision(id1, content1, bounds1, shape1, id2, content2, bounds2, shape2);
        }

        // Fall back to AABB collision detection
        if (bounds1 != null && bounds2 != null) {
            return checkAABBCollision(id1, content1, bounds1, id2, content2, bounds2);
        } else if (bounds1 != null || bounds2 != null) {
            return checkMixedCollision(id1, content1, bounds1, id2, content2, bounds2);
        } else {
            return checkPointCollision(id1, content1, bounds1, id2, content2, bounds2);
        }
    }

    /**
     * Check collision between two entities with custom collision shapes
     */
    private Optional<CollisionPair<ID, Content>> checkShapeCollision(ID id1, Content content1, EntityBounds bounds1,
                                                                     CollisionShape shape1, ID id2, Content content2,
                                                                     EntityBounds bounds2, CollisionShape shape2) {
        var result = shape1.collidesWith(shape2);
        if (result.collides) {
            return Optional.of(
                CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, result.contactPoint,
                                     result.contactNormal, result.penetrationDepth));
        }
        return Optional.empty();
    }

    /**
     * Check collision between two entities with AABB bounds
     */
    private Optional<CollisionPair<ID, Content>> checkAABBCollision(ID id1, Content content1, EntityBounds bounds1,
                                                                    ID id2, Content content2, EntityBounds bounds2) {
        if (boundsIntersect(bounds1, bounds2)) {
            var contactPoint = calculateContactPoint(bounds1, bounds2);
            var contactNormal = calculateContactNormal(bounds1, bounds2);
            var penetrationDepth = calculatePenetrationDepth(bounds1, bounds2);

            return Optional.of(
                CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, contactPoint, contactNormal,
                                     penetrationDepth));
        }
        return Optional.empty();
    }

    /**
     * Check collision between a bounded entity and a point entity
     */
    private Optional<CollisionPair<ID, Content>> checkMixedCollision(ID id1, Content content1, EntityBounds bounds1,
                                                                     ID id2, Content content2, EntityBounds bounds2) {
        var bounds = bounds1 != null ? bounds1 : bounds2;
        var pointEntityId = bounds1 != null ? id2 : id1;
        var pointPos = entityManager.getEntityPosition(pointEntityId);

        if (pointPos == null) {
            return Optional.empty();
        }

        float threshold = 0.1f;
        if (isPointInBoundsWithThreshold(pointPos, bounds, threshold)) {
            var contactPoint = new Point3f(pointPos);
            var contactNormal = calculatePointBoundsNormal(pointPos, bounds);
            var penetrationDepth = calculatePointBoundsPenetration(pointPos, bounds, threshold);

            return Optional.of(
                CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, contactPoint, contactNormal,
                                     penetrationDepth));
        }
        return Optional.empty();
    }

    /**
     * Check collision between two point entities
     */
    private Optional<CollisionPair<ID, Content>> checkPointCollision(ID id1, Content content1, EntityBounds bounds1,
                                                                    ID id2, Content content2, EntityBounds bounds2) {
        var pos1 = entityManager.getEntityPosition(id1);
        var pos2 = entityManager.getEntityPosition(id2);

        if (pos1 == null || pos2 == null) {
            return Optional.empty();
        }

        var distance = pos1.distance(pos2);
        var threshold = 0.1f;

        if (distance <= threshold) {
            var contactPoint = new Point3f();
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
            var penetrationDepth = (distance == 0) ? 0 : Math.max(0, threshold - distance);

            return Optional.of(
                CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, contactPoint, contactNormal,
                                     penetrationDepth));
        }
        return Optional.empty();
    }

    /**
     * Check if a point is inside bounds with a threshold
     */
    private boolean isPointInBoundsWithThreshold(Point3f point, EntityBounds bounds, float threshold) {
        var inBounds = point.x >= bounds.getMinX() && point.x <= bounds.getMaxX()
                    && point.y >= bounds.getMinY() && point.y <= bounds.getMaxY()
                    && point.z >= bounds.getMinZ() && point.z <= bounds.getMaxZ();

        // For zero-size bounds, check distance to bounds center
        if (!inBounds && bounds.getMinX() == bounds.getMaxX() && bounds.getMinY() == bounds.getMaxY()
            && bounds.getMinZ() == bounds.getMaxZ()) {
            Point3f boundsPoint = new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());
            float distance = point.distance(boundsPoint);
            inBounds = distance <= threshold;
        }

        return inBounds;
    }

    /**
     * Calculate contact normal between a point and bounds
     */
    private Vector3f calculatePointBoundsNormal(Point3f point, EntityBounds bounds) {
        var boundsCenter = new Point3f((bounds.getMinX() + bounds.getMaxX()) / 2,
                                       (bounds.getMinY() + bounds.getMaxY()) / 2,
                                       (bounds.getMinZ() + bounds.getMaxZ()) / 2);

        Vector3f contactNormal = new Vector3f();
        contactNormal.sub(point, boundsCenter);
        if (contactNormal.length() > 0) {
            contactNormal.normalize();
        } else {
            contactNormal.set(1, 0, 0); // Default normal
        }
        return contactNormal;
    }

    /**
     * Calculate penetration depth between a point and bounds
     */
    private float calculatePointBoundsPenetration(Point3f point, EntityBounds bounds, float threshold) {
        if (bounds.getMinX() == bounds.getMaxX() && bounds.getMinY() == bounds.getMaxY()
            && bounds.getMinZ() == bounds.getMaxZ()) {
            // Zero-size bounds - use distance
            Point3f boundsPoint = new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());
            float distance = point.distance(boundsPoint);
            return (distance == 0) ? 0 : Math.max(0, threshold - distance);
        } else {
            // Regular bounds - distance from point to nearest surface
            return Math.min(
                Math.min(point.x - bounds.getMinX(), bounds.getMaxX() - point.x),
                Math.min(Math.min(point.y - bounds.getMinY(), bounds.getMaxY() - point.y),
                         Math.min(point.z - bounds.getMinZ(), bounds.getMaxZ() - point.z)));
        }
    }

    /**
     * Process nodes in breadth-first order
     */
    private void processBreadthFirstQueue(TreeVisitor<Key, ID, Content> visitor, TraversalStrategy strategy,
                                          TraversalContext<Key, ID> context) {
        Key nodeIndex;
        while ((nodeIndex = context.popNode()) != null) {
            var level = context.getNodeLevel(nodeIndex);
            traverseNode(nodeIndex, visitor, strategy, context, null, level);
        }
    }

    /**
     * Traverse a single node and its children recursively
     */
    private void traverseNode(Key nodeIndex, TreeVisitor<Key, ID, Content> visitor, TraversalStrategy strategy,
                              TraversalContext<Key, ID> context, Key parentIndex, int level) {

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
        var spatialNode = new SpatialNode<>(nodeIndex, new HashSet<>(node.getEntityIds()));

        // Mark as visited
        context.markVisited(nodeIndex);

        // Pre-order visit
        var shouldContinue = visitor.visitNode(spatialNode, level, parentIndex);

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
            var children = getChildNodes(nodeIndex);
            var childCount = 0;

            for (Key childIndex : children) {
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
     * Default tree balancer implementation.
     */
    protected class DefaultTreeBalancer implements TreeBalancer<Key, ID> {

        @Override
        public BalancingAction checkNodeBalance(Key nodeIndex) {
            NodeType node = spatialIndex.get(nodeIndex);
            if (node == null) {
                return BalancingAction.NONE;
            }

            byte level = nodeIndex.getLevel();
            int entityCount = node.getEntityCount();

            // Check split condition
            if (balancingStrategy.shouldSplit(entityCount, level, maxEntitiesPerNode)) {
                return BalancingAction.SPLIT;
            }

            // Check merge condition
            var siblingCounts = getSiblingEntityCounts(nodeIndex);
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
        public boolean mergeNodes(Set<Key> nodeIndices, Key parentIndex) {
            // Default implementation does not support merging
            // Subclasses should override for actual merging logic
            return false;
        }

        @Override
        public int rebalanceSubtree(Key rootNodeIndex) {
            return rebalanceSubtreeImpl(rootNodeIndex, new HashSet<>());
        }

        @Override
        public RebalancingResult rebalanceTree() {
            var startTime = System.currentTimeMillis();
            var nodesCreated = 0;
            var nodesRemoved = 0;
            var nodesMerged = 0;
            var nodesSplit = 0;
            var entitiesRelocated = 0;

            try {
                // Get root nodes and rebalance each subtree
                var roots = getRootNodes();
                for (var root : roots) {
                    var modifications = rebalanceSubtree(root);
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
        public List<Key> splitNode(Key nodeIndex, byte nodeLevel) {
            // Default implementation does not support splitting
            // Subclasses should override for actual splitting logic
            return Collections.emptyList();
        }

        /**
         * Find sibling nodes for the given node.
         */
        protected Set<Key> findSiblings(Key nodeIndex) {
            // Default implementation: no siblings
            // Subclasses should override based on their structure
            return Collections.emptySet();
        }

        /**
         * Get entity counts of sibling nodes.
         */
        protected int[] getSiblingEntityCounts(Key nodeIndex) {
            var siblings = findSiblings(nodeIndex);
            var counts = new int[siblings.size()];
            var i = 0;
            for (var sibling : siblings) {
                NodeType node = spatialIndex.get(sibling);
                counts[i++] = node != null ? node.getEntityCount() : 0;
            }
            return counts;
        }

        private int rebalanceSubtreeImpl(Key rootNodeIndex, Set<Key> visited) {
            // Prevent infinite recursion
            if (!visited.add(rootNodeIndex)) {
                return 0; // Already processed this node
            }

            int modifications = 0;

            NodeType node = spatialIndex.get(rootNodeIndex);
            if (node == null) {
                return 0;
            }

            byte level = rootNodeIndex.getLevel();
            int entityCount = node.getEntityCount();

            // Check if node needs balancing
            var action = checkNodeBalance(rootNodeIndex);

            switch (action) {
                case SPLIT -> {
                    if (level < maxDepth) {
                        var children = splitNode(rootNodeIndex, level);
                        modifications += children.size();
                    }
                }
                case MERGE -> {
                    // Find siblings for merging
                    var siblings = findSiblings(rootNodeIndex);
                    if (!siblings.isEmpty()) {
                        var parent = rootNodeIndex.parent();
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
            var children = getChildNodes(rootNodeIndex);
            for (var child : children) {
                modifications += rebalanceSubtreeImpl(child, visited);
            }

            return modifications;
        }
    }
    
    // K-NN Search Helper Methods
    
    /**
     * Perform expanding radius search for k-NN
     * @return true if enough candidates were found
     */
    private boolean performKNNExpandingRadiusSearch(Point3f queryPoint, int k, float maxDistance,
                                                   PriorityQueue<EntityDistance<ID>> candidates,
                                                   Set<ID> addedToCandidates) {
        var searchRadius = Math.min(maxDistance, getCellSizeAtLevel(maxDepth));
        var searchExpansions = 0;
        final var maxExpansions = 5; // Limit search expansion
        
        while (candidates.size() < k && searchRadius <= maxDistance && searchExpansions < maxExpansions) {
            var foundNewEntities = searchKNNInRadius(queryPoint, searchRadius, maxDistance, k, 
                                                     candidates, addedToCandidates);
            
            if (candidates.size() < k && searchRadius < maxDistance) {
                searchRadius *= 2.0f;
                searchExpansions++;
            } else {
                break;
            }
            
            // If we didn't find any new entities in this expansion, stop
            if (!foundNewEntities && searchExpansions > 1) {
                break;
            }
        }
        
        return candidates.size() >= k;
    }
    
    /**
     * Search for entities within a specific radius
     */
    private boolean searchKNNInRadius(Point3f queryPoint, float searchRadius, float maxDistance, int k,
                                     PriorityQueue<EntityDistance<ID>> candidates,
                                     Set<ID> addedToCandidates) {
        var visitedThisExpansion = new HashSet<ID>();
        var searchBounds = new VolumeBounds(
            queryPoint.x - searchRadius, queryPoint.y - searchRadius, queryPoint.z - searchRadius,
            queryPoint.x + searchRadius, queryPoint.y + searchRadius, queryPoint.z + searchRadius
        );
        
        var candidateNodes = findNodesIntersectingBounds(searchBounds);
        var foundNewEntities = false;
        
        for (Key nodeKey : candidateNodes) {
            var node = spatialIndex.get(nodeKey);
            if (node == null || node.isEmpty()) {
                continue;
            }
            
            // Check all entities in this node
            for (ID entityId : node.getEntityIds()) {
                if (visitedThisExpansion.add(entityId)) {
                    var entityPos = getCachedEntityPosition(entityId);
                    if (entityPos != null) {
                        var distance = queryPoint.distance(entityPos);
                        
                        // Only consider entities within current search radius
                        if (distance <= searchRadius && distance <= maxDistance && 
                            !addedToCandidates.contains(entityId)) {
                            candidates.add(new EntityDistance<>(entityId, distance));
                            addedToCandidates.add(entityId);
                            foundNewEntities = true;
                            
                            // Keep only k elements
                            if (candidates.size() > k) {
                                var removed = candidates.poll();
                                addedToCandidates.remove(removed.entityId());
                            }
                        }
                    }
                }
            }
        }
        
        return foundNewEntities;
    }
    
    /**
     * Perform SFC-based search starting from the nearest node
     */
    private void performKNNSFCBasedSearch(Point3f queryPoint, int k, float maxDistance,
                                         PriorityQueue<EntityDistance<ID>> candidates,
                                         Set<ID> addedToCandidates) {
        if (sortedSpatialIndices.isEmpty()) {
            return;
        }
        
        var nearestNodeIndex = findNearestNodeToPoint(queryPoint);
        if (nearestNodeIndex == null) {
            return;
        }
        
        // Breadth-first search from nearest node
        var toVisit = new LinkedList<Key>();
        var visitedNodes = new HashSet<Key>();
        toVisit.add(nearestNodeIndex);
        
        while (!toVisit.isEmpty() && candidates.size() < k) {
            var current = toVisit.poll();
            if (!visitedNodes.add(current)) {
                continue;
            }
            
            var node = spatialIndex.get(current);
            if (node == null) {
                continue;
            }
            
            // Process entities in current node
            for (var entityId : node.getEntityIds()) {
                if (!addedToCandidates.contains(entityId)) {
                    var entityPos = getCachedEntityPosition(entityId);
                    if (entityPos != null) {
                        var distance = queryPoint.distance(entityPos);
                        if (distance <= maxDistance) {
                            candidates.add(new EntityDistance<>(entityId, distance));
                            addedToCandidates.add(entityId);
                            if (candidates.size() > k) {
                                var removed = candidates.poll();
                                addedToCandidates.remove(removed.entityId());
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
    
    /**
     * Find the nearest node to a query point
     */
    private Key findNearestNodeToPoint(Point3f queryPoint) {
        var bestDistance = Float.MAX_VALUE;
        Key nearestNodeIndex = null;
        var cellSize = getCellSizeAtLevel(maxDepth);
        
        for (var nodeIndex : sortedSpatialIndices) {
            var node = spatialIndex.get(nodeIndex);
            if (node == null || node.isEmpty()) {
                continue;
            }
            
            var nodeDistance = estimateNodeDistance(nodeIndex, queryPoint);
            if (nodeDistance < bestDistance) {
                bestDistance = nodeDistance;
                nearestNodeIndex = nodeIndex;
            }
            
            // Early termination
            if (nodeDistance < cellSize) {
                break;
            }
        }
        
        return nearestNodeIndex;
    }
    
    /**
     * Convert k-NN candidates to sorted list
     */
    private List<ID> convertKNNCandidatesToList(PriorityQueue<EntityDistance<ID>> candidates) {
        var sorted = new ArrayList<>(candidates);
        sorted.sort(EntityDistance.minHeapComparator());
        
        var result = new ArrayList<ID>(sorted.size());
        for (var entry : sorted) {
            result.add(entry.entityId());
        }
        return result;
    }
    
    // Collision Detection Helper Methods
    
    /**
     * Phase 1: Find collisions between entities within the same node
     */
    private void findIntraNodeCollisions(List<CollisionPair<ID, Content>> collisions,
                                       Set<UnorderedPair<ID>> checkedPairs) {
        for (var nodeIndex : sortedSpatialIndices) {
            var node = spatialIndex.get(nodeIndex);
            if (node == null || node.isEmpty()) {
                continue;
            }
            
            var nodeEntities = new ArrayList<>(node.getEntityIds());
            if (nodeEntities.size() < 2) {
                continue;
            }
            
            // Check all pairs within this node
            for (int i = 0; i < nodeEntities.size() - 1; i++) {
                for (int j = i + 1; j < nodeEntities.size(); j++) {
                    var id1 = nodeEntities.get(i);
                    var id2 = nodeEntities.get(j);
                    var pair = new UnorderedPair<>(id1, id2);
                    if (checkedPairs.add(pair)) {
                        checkAndAddCollision(id1, id2, collisions);
                    }
                }
            }
        }
    }
    
    /**
     * Phase 2: Find collisions for bounded entities that span multiple nodes
     */
    private void findBoundedEntityCollisions(List<CollisionPair<ID, Content>> collisions,
                                           Set<UnorderedPair<ID>> checkedPairs) {
        var boundedEntities = new ArrayList<ID>();
        for (var entityId : entityManager.getAllEntityIds()) {
            if (entityManager.getEntityBounds(entityId) != null) {
                boundedEntities.add(entityId);
            }
        }
        
        // Check bounded entities against each other
        for (int i = 0; i < boundedEntities.size() - 1; i++) {
            for (int j = i + 1; j < boundedEntities.size(); j++) {
                var id1 = boundedEntities.get(i);
                var id2 = boundedEntities.get(j);
                var pair = new UnorderedPair<>(id1, id2);
                if (checkedPairs.add(pair)) {
                    checkAndAddCollision(id1, id2, collisions);
                }
            }
        }
    }
    
    /**
     * Phase 3: Find collisions between adjacent nodes
     */
    private void findAdjacentNodeCollisions(List<CollisionPair<ID, Content>> collisions,
                                          Set<UnorderedPair<ID>> checkedPairs) {
        for (var nodeIndex : sortedSpatialIndices) {
            var node = spatialIndex.get(nodeIndex);
            if (node == null || node.isEmpty()) {
                continue;
            }
            
            var nodeEntities = new ArrayList<>(node.getEntityIds());
            
            // Find neighboring nodes
            var neighbors = new LinkedList<Key>();
            var visitedNeighbors = new HashSet<Key>();
            addNeighboringNodes(nodeIndex, neighbors, visitedNeighbors);
            
            // Check entities between this node and its neighbors
            while (!neighbors.isEmpty()) {
                var neighborIndex = neighbors.poll();
                
                // Skip already processed nodes (SFC ordering optimization)
                if (neighborIndex.compareTo(nodeIndex) < 0) {
                    continue;
                }
                
                var neighborNode = spatialIndex.get(neighborIndex);
                if (neighborNode == null || neighborNode.isEmpty()) {
                    continue;
                }
                
                // Check entities between adjacent nodes
                for (ID id1 : nodeEntities) {
                    // Skip bounded entities (already handled)
                    if (entityManager.getEntityBounds(id1) != null) {
                        continue;
                    }
                    
                    for (ID id2 : neighborNode.getEntityIds()) {
                        var pair = new UnorderedPair<>(id1, id2);
                        if (checkedPairs.add(pair)) {
                            checkAndAddCollision(id1, id2, collisions);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Phase 4: Find collisions between point entities and bounded entities
     */
    private void findPointBoundedCollisions(List<CollisionPair<ID, Content>> collisions,
                                          Set<UnorderedPair<ID>> checkedPairs) {
        // First, collect all bounded entities and their expanded search regions
        var boundedEntitySearchNodes = new HashMap<ID, Set<Key>>();
        
        for (var nodeIndex : sortedSpatialIndices) {
            var node = spatialIndex.get(nodeIndex);
            if (node == null || node.isEmpty()) {
                continue;
            }
            
            for (ID entityId : node.getEntityIds()) {
                var bounds = entityManager.getEntityBounds(entityId);
                if (bounds != null) {
                    // Calculate nodes that could contain point entities that might collide
                    var threshold = 0.1f; // Collision threshold for point entities
                    var expandedBounds = new EntityBounds(
                        new Point3f(bounds.getMinX() - threshold, bounds.getMinY() - threshold,
                                    bounds.getMinZ() - threshold),
                        new Point3f(bounds.getMaxX() + threshold, bounds.getMaxY() + threshold,
                                    bounds.getMaxZ() + threshold));
                    var searchNodes = findNodesIntersectingBounds(expandedBounds);
                    boundedEntitySearchNodes.put(entityId, searchNodes);
                }
            }
        }
        
        // Now check point entities against bounded entities in their search regions
        for (var entry : boundedEntitySearchNodes.entrySet()) {
            var boundedId = entry.getKey();
            var searchNodes = entry.getValue();
            
            for (var searchNodeIndex : searchNodes) {
                var searchNode = spatialIndex.get(searchNodeIndex);
                if (searchNode == null || searchNode.isEmpty()) {
                    continue;
                }
                
                for (ID pointId : searchNode.getEntityIds()) {
                    if (entityManager.getEntityBounds(pointId) == null) { // Only check point entities
                        var pair = new UnorderedPair<>(boundedId, pointId);
                        if (checkedPairs.add(pair)) {
                            checkAndAddCollision(boundedId, pointId, collisions);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Configure fine-grained locking strategy
     */
    public void configureFineGrainedLocking(LockingConfig config) {
        lock.writeLock().lock();
        try {
            this.lockingStrategy = new FineGrainedLockingStrategy<>(this, config);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Find collisions using fine-grained locking for better concurrency
     * This is an alternative to findCollisions that can be used when high read concurrency is needed
     */
    public List<CollisionPair<ID, Content>> findCollisionsFineGrained(ID entityId) {
        var locations = entityManager.getEntityLocations(entityId);
        if (locations.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Use fine-grained locking for each node access
        return lockingStrategy.executeRead(0L, () -> {
            var collisions = ObjectPools.<CollisionPair<ID, Content>>borrowArrayList();
            var checkedEntities = ObjectPools.<ID>borrowHashSet();
            try {
                checkedEntities.add(entityId);
                
                var entityBounds = getCachedEntityBounds(entityId);
                if (entityBounds != null) {
                    findBoundedEntityCollisions(entityId, entityBounds, checkedEntities, collisions);
                } else {
                    findPointEntityCollisions(entityId, locations, checkedEntities, collisions);
                }
                
                Collections.sort(collisions);
                return new ArrayList<>(collisions);
            } finally {
                ObjectPools.returnArrayList(collisions);
                ObjectPools.returnHashSet(checkedEntities);
            }
        });
    }
    
    /**
     * Get cache statistics for monitoring
     */
    public EntityCache.CacheStats getCacheStats() {
        return entityCache.getStats();
    }
    
    /**
     * Clear the entity cache (useful for benchmarking or memory pressure)
     */
    public void clearCache() {
        lock.writeLock().lock();
        try {
            entityCache.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Enable optimistic concurrency for batch read operations
     * Reduces contention for read-heavy workloads
     */
    public void enableOptimisticConcurrency() {
        configureFineGrainedLocking(FineGrainedLockingStrategy.highConcurrencyConfig());
    }
    
    /**
     * Disable optimistic concurrency and use conservative locking
     * Better for write-heavy workloads or when consistency is critical
     */
    public void disableOptimisticConcurrency() {
        configureFineGrainedLocking(FineGrainedLockingStrategy.conservativeConfig());
    }
    
    /**
     * Internal method to get entity bounds with caching
     */
    private EntityBounds getCachedEntityBounds(ID entityId) {
        var bounds = entityCache.getBounds(entityId);
        if (bounds == null) {
            bounds = entityManager.getEntityBounds(entityId);
            if (bounds != null) {
                var position = entityManager.getEntityPosition(entityId);
                entityCache.put(entityId, position, bounds);
            }
        }
        return bounds;
    }
    
    /**
     * Internal method to get entity position with caching
     */
    private Point3f getCachedEntityPosition(ID entityId) {
        var position = entityCache.getPosition(entityId);
        if (position == null) {
            position = entityManager.getEntityPosition(entityId);
            if (position != null) {
                var bounds = entityManager.getEntityBounds(entityId);
                entityCache.put(entityId, position, bounds);
            }
        }
        return position;
    }
    
}
