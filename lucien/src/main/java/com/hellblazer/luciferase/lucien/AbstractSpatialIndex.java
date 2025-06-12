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

import com.hellblazer.luciferase.lucien.entity.*;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
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

    // Common fields
    protected final EntityManager<ID, Content> entityManager;
    protected final int                        maxEntitiesPerNode;
    protected final byte                       maxDepth;
    protected final EntitySpanningPolicy       spanningPolicy;
    // Spatial index: Long index -> Node containing entity IDs
    protected final Map<Long, NodeType>        spatialIndex;
    // Sorted spatial indices for efficient range queries
    protected final NavigableSet<Long>         sortedSpatialIndices;
    // Read-write lock for thread safety
    private final   ReadWriteLock              lock;

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
    }

    // ===== Abstract Methods for Subclasses =====

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
    public boolean containsEntity(ID entityId) {
        lock.readLock().lock();
        try {
            return entityManager.containsEntity(entityId);
        } finally {
            lock.readLock().unlock();
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

    @Override
    public EntityBounds getEntityBounds(ID entityId) {
        lock.readLock().lock();
        try {
            return entityManager.getEntityBounds(entityId);
        } finally {
            lock.readLock().unlock();
        }
    }

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

    // ===== Common Entity Management Methods =====

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
                insertWithSpanning(entityId, bounds, level);
            } else {
                // Standard single-node insertion
                insertAtPosition(entityId, position, level);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Find k nearest neighbors to a query point
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

        lock.readLock().lock();
        try {
            // Priority queue to keep track of k nearest entities (max heap)
            PriorityQueue<EntityDistance<ID>> candidates = new PriorityQueue<>(EntityDistance.maxHeapComparator());

            // Track visited entities to avoid duplicates
            Set<ID> visited = new HashSet<>();

            // Start with the containing spatial cell
            long initialIndex = calculateSpatialIndex(queryPoint, maxDepth);

            // Use a queue for breadth-first search
            Queue<Long> toVisit = new LinkedList<>();
            Set<Long> visitedNodes = new HashSet<>();

            // Initialize search
            NodeType initialNode = getSpatialIndex().get(initialIndex);
            if (initialNode != null) {
                toVisit.add(initialIndex);
            } else {
                // If exact node doesn't exist, start from all existing nodes
                toVisit.addAll(getSpatialIndex().keySet());
            }

            while (!toVisit.isEmpty()) {
                Long current = toVisit.poll();
                if (!visitedNodes.add(current)) {
                    continue; // Already visited this node
                }

                NodeType node = getSpatialIndex().get(current);
                if (node == null) {
                    continue;
                }

                // Check all entities in this node
                for (ID entityId : node.getEntityIds()) {
                    if (visited.add(entityId)) {
                        Point3f entityPos = entityManager.getEntityPosition(entityId);
                        if (entityPos != null) {
                            float distance = queryPoint.distance(entityPos);
                            if (distance <= maxDistance) {
                                candidates.add(new EntityDistance<>(entityId, distance));

                                // Keep only k elements
                                if (candidates.size() > k) {
                                    candidates.poll();
                                }
                            }
                        }
                    }
                }

                // Add neighboring nodes if we haven't found enough candidates
                if (candidates.size() < k || shouldContinueKNNSearch(current, queryPoint, candidates)) {
                    addNeighboringNodes(current, toVisit, visitedNodes);
                }
            }

            // Convert to sorted list (closest first)
            List<EntityDistance<ID>> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparingDouble(EntityDistance::distance));

            return sorted.stream().map(EntityDistance::entityId).collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int nodeCount() {
        lock.readLock().lock();
        try {
            return (int) getSpatialIndex().values().stream().filter(node -> !node.isEmpty()).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ===== Common Insert Operations =====

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

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void updateEntity(ID entityId, Point3f newPosition, byte level) {
        lock.writeLock().lock();
        try {
            validateSpatialConstraints(newPosition);

            // Update entity position
            entityManager.updateEntityPosition(entityId, newPosition);

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

    // ===== Common Remove Operations =====

    /**
     * Add neighboring nodes to the k-NN search queue
     *
     * @param nodeIndex    current node index
     * @param toVisit      queue of nodes to visit
     * @param visitedNodes set of already visited nodes
     */
    protected abstract void addNeighboringNodes(long nodeIndex, Queue<Long> toVisit, Set<Long> visitedNodes);

    /**
     * Calculate the spatial index for a position at a given level
     */
    protected abstract long calculateSpatialIndex(Point3f position, byte level);

    /**
     * Clean up empty nodes from the spatial index
     */
    protected void cleanupEmptyNode(long spatialIndex, NodeType node) {
        if (node.isEmpty() && !hasChildren(spatialIndex)) {
            getSpatialIndex().remove(spatialIndex);
            onNodeRemoved(spatialIndex);
        }
    }

    /**
     * Create a new node instance
     */
    protected abstract NodeType createNode();

    // ===== Common Update Operations =====

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

    // ===== Common Query Operations =====

    /**
     * Check if a node's bounds intersect with a volume
     */
    protected abstract boolean doesNodeIntersectVolume(long nodeIndex, Spatial volume);

    /**
     * Test if a ray intersects with a node
     *
     * @param nodeIndex the node's spatial index
     * @param ray       the ray to test
     * @return true if the ray intersects the node
     */
    protected abstract boolean doesRayIntersectNode(long nodeIndex, Ray3D ray);

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
     * Get the cell size at a given level (to be implemented by subclasses)
     */
    protected abstract int getCellSizeAtLevel(byte level);

    // ===== Common Statistics =====

    /**
     * Extract the level from a spatial index
     */
    protected abstract byte getLevelFromIndex(long index);

    // ===== Common Utility Methods =====

    /**
     * Get the spatial bounds of a node
     */
    protected abstract Spatial getNodeBounds(long index);

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
     * Get the spatial index storage map
     */
    protected Map<Long, NodeType> getSpatialIndex() {
        return spatialIndex;
    }

    // ===== Common k-NN Search Implementation =====

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
     * Get volume bounds helper
     */
    protected VolumeBounds getVolumeBounds(Spatial volume) {
        return VolumeBounds.from(volume);
    }

    /**
     * Hook for subclasses to handle node subdivision
     */
    protected void handleNodeSubdivision(long spatialIndex, byte level, NodeType node) {
        // Default: no subdivision. Subclasses can override
    }

    // ===== Common Spatial Query Base =====

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

        // Get or create node
        NodeType node = getSpatialIndex().computeIfAbsent(spatialIndex, k -> {
            sortedSpatialIndices.add(spatialIndex);
            return createNode();
        });

        // Add entity to node
        boolean shouldSplit = node.addEntity(entityId);

        // Track entity location
        entityManager.addEntityLocation(entityId, spatialIndex);

        // Handle subdivision if needed (delegated to subclasses)
        if (shouldSplit && level < maxDepth) {
            handleNodeSubdivision(spatialIndex, level, node);
        }
    }

    // ===== Ray Intersection Abstract Methods =====

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

    // ===== Ray Intersection Implementation =====

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
        Vector3f oc = new Vector3f();
        oc.sub(ray.origin(), center);

        float a = ray.direction().dot(ray.direction());
        float b = 2.0f * oc.dot(ray.direction());
        float c = oc.dot(oc) - radius * radius;

        float discriminant = b * b - 4 * a * c;
        if (discriminant < 0) {
            return -1;
        }

        float sqrtDiscriminant = (float) Math.sqrt(discriminant);
        float t1 = (-b - sqrtDiscriminant) / (2 * a);
        float t2 = (-b + sqrtDiscriminant) / (2 * a);

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
}
