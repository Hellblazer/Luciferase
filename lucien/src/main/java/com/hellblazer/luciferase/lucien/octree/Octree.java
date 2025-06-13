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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.*;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Octree implementation with C++-style entity storage system. Uses dual storage architecture: - Spatial index: Morton
 * code → Entity IDs - Entity storage: Entity ID → Content
 *
 * Supports multiple entities per node and entities spanning multiple nodes.
 *
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class Octree<ID extends EntityID, Content> extends AbstractSpatialIndex<ID, Content, OctreeNode<ID>> {

    // Default configuration constants
    private static final int DEFAULT_MAX_ENTITIES_PER_NODE = 10;
    private static final int NEIGHBOR_SEARCH_RADIUS        = 1; // For k-NN neighbor search
    private static final int OCTREE_CHILDREN               = 8;

    /**
     * Create an octree with default configuration
     */
    public Octree(EntityIDGenerator<ID> idGenerator) {
        this(idGenerator, DEFAULT_MAX_ENTITIES_PER_NODE, Constants.getMaxRefinementLevel());
    }

    /**
     * Create an octree with custom configuration
     */
    public Octree(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth) {
        this(idGenerator, maxEntitiesPerNode, maxDepth, new EntitySpanningPolicy());
    }

    /**
     * Create an octree with custom configuration and spanning policy
     */
    public Octree(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                  EntitySpanningPolicy spanningPolicy) {
        super(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy);
    }

    // ===== Abstract Method Implementations =====

    @Override
    public SpatialNode<ID> enclosing(Spatial volume) {
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return null;
        }

        byte level = findMinimumContainingLevel(bounds);
        float midX = (bounds.minX() + bounds.maxX()) / 2.0f;
        float midY = (bounds.minY() + bounds.maxY()) / 2.0f;
        float midZ = (bounds.minZ() + bounds.maxZ()) / 2.0f;
        Point3f center = new Point3f(midX, midY, midZ);

        long mortonIndex = Constants.calculateMortonIndex(center, level);
        var node = spatialIndex.get(mortonIndex);
        if (node != null && !node.isEmpty()) {
            return new SpatialNode<>(mortonIndex, new HashSet<>(node.getEntityIds()));
        }
        return null;
    }

    @Override
    public SpatialNode<ID> enclosing(Tuple3i point, byte level) {
        Point3f position = new Point3f(point.x, point.y, point.z);
        long mortonIndex = Constants.calculateMortonIndex(position, level);
        var node = spatialIndex.get(mortonIndex);
        if (node != null && !node.isEmpty()) {
            return new SpatialNode<>(mortonIndex, new HashSet<>(node.getEntityIds()));
        }
        return null;
    }

    /**
     * Find all entities within a bounding box
     */
    @Override
    public List<ID> entitiesInRegion(Spatial.Cube region) {
        var bounds = getVolumeBounds(region);
        if (bounds == null) {
            return Collections.emptyList();
        }

        // Use Morton code range optimization
        NavigableSet<Long> extendedCodes = getMortonCodeRange(bounds);

        Set<ID> uniqueEntities = new HashSet<>();

        // Check only candidate nodes
        for (Long mortonCode : extendedCodes) {
            OctreeNode<ID> node = spatialIndex.get(mortonCode);
            if (node == null || node.isEmpty()) {
                continue;
            }

            // Decode Morton code to check if node intersects region
            int[] coords = MortonCurve.decode(mortonCode);
            byte level = Constants.toLevel(mortonCode);
            int cellSize = Constants.lengthAtLevel(level);

            // Check if node cube intersects with region
            Spatial.Cube nodeCube = new Spatial.Cube(coords[0], coords[1], coords[2], cellSize);
            if (doesCubeIntersectVolume(nodeCube, region)) {
                uniqueEntities.addAll(node.getEntityIds());
            }
        }

        // Filter entities based on their actual positions
        return uniqueEntities.stream().filter(entityId -> {
            Point3f pos = entityManager.getEntityPosition(entityId);
            if (pos == null) {
                return false;
            }
            return pos.x >= region.originX() && pos.x <= region.originX() + region.extent() && pos.y >= region.originY()
            && pos.y <= region.originY() + region.extent() && pos.z >= region.originZ()
            && pos.z <= region.originZ() + region.extent();
        }).collect(Collectors.toList());
    }

    /**
     * Bulk insert multiple entities efficiently
     *
     * @param entities collection of entity data to insert
     */
    public void insertAll(Collection<EntityData<ID, Content>> entities) {
        // Pre-sort by Morton code for better cache locality
        List<EntityData<ID, Content>> sorted = entities.stream().sorted((e1, e2) -> {
            long morton1 = calculateMortonCode(e1.position(), e1.level());
            long morton2 = calculateMortonCode(e2.position(), e2.level());
            return Long.compare(morton1, morton2);
        }).collect(Collectors.toList());

        // Batch insert with optimized node access
        for (EntityData<ID, Content> data : sorted) {
            if (data.bounds() != null) {
                insert(data.id(), data.position(), data.level(), data.content(), data.bounds());
            } else {
                insert(data.id(), data.position(), data.level(), data.content());
            }
        }
    }

    // k-NN search is now provided by AbstractSpatialIndex

    /**
     * Lookup entities at a specific position
     *
     * @return list of entity IDs at the position
     */
    @Override
    public List<ID> lookup(Point3f position, byte level) {
        long mortonCode = calculateMortonCode(position, level);
        OctreeNode<ID> node = spatialIndex.get(mortonCode);

        if (node == null) {
            return Collections.emptyList();
        }

        // If the node has been subdivided, look in child nodes
        if (node.hasChildren() || node.isEmpty()) {
            // Calculate which child contains this position
            byte childLevel = (byte) (level + 1);
            if (childLevel <= maxDepth) {
                return lookup(position, childLevel);
            }
        }

        return new ArrayList<>(node.getEntityIds());
    }

    @Override
    protected void addNeighboringNodes(long nodeCode, Queue<Long> toVisit, Set<Long> visitedNodes) {
        int[] coords = MortonCurve.decode(nodeCode);
        byte level = Constants.toLevel(nodeCode);
        int cellSize = Constants.lengthAtLevel(level);

        // Check all 26 neighbors (3x3x3 cube minus center)
        for (int dx = -NEIGHBOR_SEARCH_RADIUS; dx <= NEIGHBOR_SEARCH_RADIUS; dx++) {
            for (int dy = -NEIGHBOR_SEARCH_RADIUS; dy <= NEIGHBOR_SEARCH_RADIUS; dy++) {
                for (int dz = -NEIGHBOR_SEARCH_RADIUS; dz <= NEIGHBOR_SEARCH_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue; // Skip center (current node)
                    }

                    int nx = coords[0] + dx * cellSize;
                    int ny = coords[1] + dy * cellSize;
                    int nz = coords[2] + dz * cellSize;

                    // Check bounds
                    if (nx >= 0 && ny >= 0 && nz >= 0) {
                        // Use level-aware encoding
                        Point3f neighborPos = new Point3f(nx + cellSize/2.0f, ny + cellSize/2.0f, nz + cellSize/2.0f);
                        long neighborCode = Constants.calculateMortonIndex(neighborPos, level);
                        if (!visitedNodes.contains(neighborCode)) {
                            toVisit.add(neighborCode);
                        }
                    }
                }
            }
        }
    }

    @Override
    public long calculateSpatialIndex(Point3f position, byte level) {
        return calculateMortonCode(position, level);
    }

    @Override
    protected OctreeNode<ID> createNode() {
        return new OctreeNode<>(maxEntitiesPerNode);
    }

    @Override
    protected boolean doesNodeIntersectVolume(long mortonIndex, Spatial volume) {
        Spatial nodeBounds = getNodeBounds(mortonIndex);
        if (nodeBounds instanceof Spatial.Cube cube) {
            return doesCubeIntersectVolume(cube, volume);
        }
        return false;
    }

    @Override
    protected boolean doesRayIntersectNode(long nodeIndex, Ray3D ray) {
        // Get node bounds
        int[] coords = MortonCurve.decode(nodeIndex);
        byte level = Constants.toLevel(nodeIndex);
        int cellSize = Constants.lengthAtLevel(level);

        // Perform ray-AABB intersection test
        return rayIntersectsAABB(ray, coords[0], coords[1], coords[2], coords[0] + cellSize, coords[1] + cellSize,
                                 coords[2] + cellSize) >= 0;
    }

    @Override
    protected int getCellSizeAtLevel(byte level) {
        return Constants.lengthAtLevel(level);
    }

    @Override
    public byte getLevelFromIndex(long index) {
        return Constants.toLevel(index);
    }

    @Override
    protected Spatial getNodeBounds(long mortonIndex) {
        int[] coords = MortonCurve.decode(mortonIndex);
        byte level = Constants.toLevel(mortonIndex);
        int cellSize = Constants.lengthAtLevel(level);
        return new Spatial.Cube(coords[0], coords[1], coords[2], cellSize);
    }

    // Helper methods for spatial range queries

    @Override
    protected float getRayNodeIntersectionDistance(long nodeIndex, Ray3D ray) {
        // Get node bounds
        int[] coords = MortonCurve.decode(nodeIndex);
        byte level = Constants.toLevel(nodeIndex);
        int cellSize = Constants.lengthAtLevel(level);

        // Calculate ray-AABB intersection distance
        float distance = rayIntersectsAABB(ray, coords[0], coords[1], coords[2], coords[0] + cellSize,
                                           coords[1] + cellSize, coords[2] + cellSize);

        return distance >= 0 ? distance : Float.MAX_VALUE;
    }

    @Override
    protected Stream<Long> getRayTraversalOrder(Ray3D ray) {
        // Use a priority queue to order nodes by ray intersection distance
        PriorityQueue<NodeDistance> nodeQueue = new PriorityQueue<>();
        Set<Long> visitedNodes = new HashSet<>();

        // Start with root node (level 0)
        long rootIndex = 0L;
        float rootDistance = getRayNodeIntersectionDistance(rootIndex, ray);
        if (rootDistance < Float.MAX_VALUE && rootDistance <= ray.maxDistance()) {
            nodeQueue.add(new NodeDistance(rootIndex, rootDistance));
        }

        // Build ordered stream of nodes
        List<Long> orderedNodes = new ArrayList<>();

        while (!nodeQueue.isEmpty()) {
            NodeDistance current = nodeQueue.poll();
            if (!visitedNodes.add(current.nodeIndex)) {
                continue;
            }

            // Check if node exists in spatial index
            if (spatialIndex.containsKey(current.nodeIndex)) {
                orderedNodes.add(current.nodeIndex);
            }

            // Add children if they intersect the ray
            byte currentLevel = Constants.toLevel(current.nodeIndex);
            if (currentLevel < maxDepth) {
                addIntersectingChildren(current.nodeIndex, currentLevel, ray, nodeQueue, visitedNodes);
            }
        }

        return orderedNodes.stream();
    }

    @Override
    protected NavigableSet<Long> getSpatialIndexRange(VolumeBounds bounds) {
        return getMortonCodeRange(bounds);
    }

    // Private helper methods

    @Override
    protected void handleNodeSubdivision(long parentMorton, byte parentLevel, OctreeNode<ID> parentNode) {
        // Can't subdivide beyond max depth
        if (parentLevel >= maxDepth) {
            return;
        }

        byte childLevel = (byte) (parentLevel + 1);

        // Get entities to redistribute
        List<ID> parentEntities = new ArrayList<>(parentNode.getEntityIds());
        if (parentEntities.isEmpty()) {
            return; // Nothing to subdivide
        }

        // Create map to group entities by their child node
        Map<Long, List<ID>> childEntityMap = new HashMap<>();

        // Determine which child each entity belongs to
        for (ID entityId : parentEntities) {
            Point3f entityPos = entityManager.getEntityPosition(entityId);
            if (entityPos != null) {
                // Calculate which child this entity belongs to at the finer level
                long childMorton = calculateMortonCode(entityPos, childLevel);
                childEntityMap.computeIfAbsent(childMorton, k -> new ArrayList<>()).add(entityId);
            }
        }

        // Check if subdivision would actually distribute entities
        if (childEntityMap.size() == 1 && childEntityMap.containsKey(parentMorton)) {
            // All entities map to the same cell even at the child level
            // This happens when all entities are at the same position
            // Don't subdivide - it won't help distribute the load
            return;
        }

        // Create child nodes and redistribute entities
        
        // First, remove parent locations from all entities
        for (ID entityId : parentEntities) {
            entityManager.removeEntityLocation(entityId, parentMorton);
        }
        
        // Then add entities to child nodes
        for (Map.Entry<Long, List<ID>> entry : childEntityMap.entrySet()) {
            long childMorton = entry.getKey();
            List<ID> childEntities = entry.getValue();

            if (!childEntities.isEmpty()) {
                // Create or get child node
                OctreeNode<ID> childNode = spatialIndex.computeIfAbsent(childMorton, k -> {
                    sortedSpatialIndices.add(childMorton);
                    return new OctreeNode<>(maxEntitiesPerNode);
                });

                // Add entities to child
                for (ID entityId : childEntities) {
                    childNode.addEntity(entityId);
                    // Update entity locations - add child location
                    entityManager.addEntityLocation(entityId, childMorton);
                }
            }
        }

        // Clear entities from parent node (they've been redistributed to children)
        parentNode.clearEntities();
        parentNode.setHasChildren(true); // Mark that this node has been subdivided
    }

    @Override
    protected boolean hasChildren(long spatialIndex) {
        OctreeNode<ID> node = this.spatialIndex.get(spatialIndex);
        return node != null && node.hasChildren();
    }
    

    /**
     * Insert entity at a single position (no spanning) - override to add Morton code tracking
     */
    @Override
    protected void insertAtPosition(ID entityId, Point3f position, byte level) {
        // Calculate Morton code for position
        long mortonCode = calculateMortonCode(position, level);

        // Get or create node
        OctreeNode<ID> node = spatialIndex.computeIfAbsent(mortonCode, k -> {
            sortedSpatialIndices.add(mortonCode);
            return new OctreeNode<>(maxEntitiesPerNode);
        });

        // Add entity to node
        boolean shouldSplit = node.addEntity(entityId);

        // Track entity location
        entityManager.addEntityLocation(entityId, mortonCode);

        // Handle subdivision if needed
        if (shouldSplit && level < maxDepth) {
            handleNodeSubdivision(mortonCode, level, node);
        }
    }

    // ===== SpatialIndex Interface Implementation =====

    /**
     * Insert entity with spanning across multiple nodes
     */
    @Override
    protected void insertWithSpanning(ID entityId, EntityBounds bounds, byte level) {
        // Find all nodes that the entity's bounds intersect
        Set<Long> intersectingNodes = findIntersectingNodes(bounds, level);

        // Add entity to all intersecting nodes
        for (Long mortonCode : intersectingNodes) {
            OctreeNode<ID> node = spatialIndex.computeIfAbsent(mortonCode, k -> {
                sortedSpatialIndices.add(mortonCode);
                return new OctreeNode<>(maxEntitiesPerNode);
            });

            node.addEntity(entityId);
            entityManager.addEntityLocation(entityId, mortonCode);

            // Note: We don't trigger subdivision for spanning entities
            // to avoid cascading subdivisions
        }
    }

    @Override
    protected boolean isNodeContainedInVolume(long mortonIndex, Spatial volume) {
        Spatial nodeBounds = getNodeBounds(mortonIndex);
        if (nodeBounds instanceof Spatial.Cube cube) {
            return isCubeContainedInVolume(cube, volume);
        }
        return false;
    }

    @Override
    protected boolean shouldContinueKNNSearch(long nodeIndex, Point3f queryPoint,
                                              PriorityQueue<EntityDistance<ID>> candidates) {
        if (candidates.isEmpty()) {
            return true;
        }

        // Get the furthest candidate distance
        EntityDistance<ID> furthest = candidates.peek();
        if (furthest == null) {
            return true;
        }

        // Calculate distance from query point to node bounds
        int[] coords = MortonCurve.decode(nodeIndex);
        byte level = Constants.toLevel(nodeIndex);
        int cellSize = Constants.lengthAtLevel(level);

        // Calculate closest point on node to query
        float nodeMinX = coords[0];
        float nodeMinY = coords[1];
        float nodeMinZ = coords[2];
        float nodeMaxX = nodeMinX + cellSize;
        float nodeMaxY = nodeMinY + cellSize;
        float nodeMaxZ = nodeMinZ + cellSize;

        float closestX = Math.max(nodeMinX, Math.min(queryPoint.x, nodeMaxX));
        float closestY = Math.max(nodeMinY, Math.min(queryPoint.y, nodeMaxY));
        float closestZ = Math.max(nodeMinZ, Math.min(queryPoint.z, nodeMaxZ));

        float nodeDistance = new Point3f(closestX, closestY, closestZ).distance(queryPoint);

        // If the closest point on this node is further than our furthest candidate,
        // we don't need to explore further
        return nodeDistance <= furthest.distance();
    }

    @Override
    protected void validateSpatialConstraints(Point3f position) {
        // Octree doesn't have specific spatial constraints
    }

    @Override
    protected void validateSpatialConstraints(Spatial volume) {
        // Octree doesn't have specific spatial constraints
    }

    boolean doesCubeIntersectVolume(Spatial.Cube cube, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube other ->
            cube.originX() < other.originX() + other.extent() && cube.originX() + cube.extent() > other.originX()
            && cube.originY() < other.originY() + other.extent() && cube.originY() + cube.extent() > other.originY()
            && cube.originZ() < other.originZ() + other.extent() && cube.originZ() + cube.extent() > other.originZ();

            case Spatial.Sphere sphere -> {
                // Find closest point on cube to sphere center
                float closestX = Math.max(cube.originX(), Math.min(sphere.centerX(), cube.originX() + cube.extent()));
                float closestY = Math.max(cube.originY(), Math.min(sphere.centerY(), cube.originY() + cube.extent()));
                float closestZ = Math.max(cube.originZ(), Math.min(sphere.centerZ(), cube.originZ() + cube.extent()));

                // Check if closest point is within sphere radius
                float dx = closestX - sphere.centerX();
                float dy = closestY - sphere.centerY();
                float dz = closestZ - sphere.centerZ();
                yield (dx * dx + dy * dy + dz * dz) <= (sphere.radius() * sphere.radius());
            }

            default -> true; // Conservative: include for other volume types
        };
    }

    boolean isCubeContainedInVolume(Spatial.Cube cube, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube other ->
            cube.originX() >= other.originX() && cube.originY() >= other.originY() && cube.originZ() >= other.originZ()
            && cube.originX() + cube.extent() <= other.originX() + other.extent()
            && cube.originY() + cube.extent() <= other.originY() + other.extent()
            && cube.originZ() + cube.extent() <= other.originZ() + other.extent();

            case Spatial.Sphere sphere -> {
                // Check all 8 corners of the cube
                for (int i = 0; i < OCTREE_CHILDREN; i++) {
                    float x = cube.originX() + ((i & 1) != 0 ? cube.extent() : 0);
                    float y = cube.originY() + ((i & 2) != 0 ? cube.extent() : 0);
                    float z = cube.originZ() + ((i & 4) != 0 ? cube.extent() : 0);

                    float dx = x - sphere.centerX();
                    float dy = y - sphere.centerY();
                    float dz = z - sphere.centerZ();

                    if ((dx * dx + dy * dy + dz * dz) > (sphere.radius() * sphere.radius())) {
                        yield false;
                    }
                }
                yield true;
            }

            default -> false; // Conservative: exclude for other volume types
        };
    }

    // ===== Ray Intersection Implementation =====

    /**
     * Helper method to add intersecting child nodes to the traversal queue
     */
    private void addIntersectingChildren(long parentIndex, byte parentLevel, Ray3D ray,
                                         PriorityQueue<NodeDistance> nodeQueue, Set<Long> visitedNodes) {
        int[] parentCoords = MortonCurve.decode(parentIndex);
        int parentCellSize = Constants.lengthAtLevel(parentLevel);
        int childCellSize = parentCellSize / 2;
        byte childLevel = (byte) (parentLevel + 1);

        // Check all 8 children
        for (int i = 0; i < OCTREE_CHILDREN; i++) {
            int childX = parentCoords[0] + ((i & 1) != 0 ? childCellSize : 0);
            int childY = parentCoords[1] + ((i & 2) != 0 ? childCellSize : 0);
            int childZ = parentCoords[2] + ((i & 4) != 0 ? childCellSize : 0);

            long childIndex = MortonCurve.encode(childX, childY, childZ);
            if (!visitedNodes.contains(childIndex)) {
                float distance = rayIntersectsAABB(ray, childX, childY, childZ, childX + childCellSize,
                                                   childY + childCellSize, childZ + childCellSize);
                if (distance >= 0 && distance <= ray.maxDistance()) {
                    nodeQueue.add(new NodeDistance(childIndex, distance));
                }
            }
        }
    }

    private long calculateMortonCode(Point3f position, byte level) {
        // Use the level-aware morton index calculation from Constants
        return Constants.calculateMortonIndex(position, level);
    }

    /**
     * Find all nodes at the given level that intersect with the bounds
     */
    private Set<Long> findIntersectingNodes(EntityBounds bounds, byte level) {
        Set<Long> result = new HashSet<>();

        int cellSize = Constants.lengthAtLevel(level);

        // Calculate the range of grid cells that might intersect
        int minX = (int) Math.floor(bounds.getMinX() / cellSize);
        int minY = (int) Math.floor(bounds.getMinY() / cellSize);
        int minZ = (int) Math.floor(bounds.getMinZ() / cellSize);

        int maxX = (int) Math.floor(bounds.getMaxX() / cellSize);
        int maxY = (int) Math.floor(bounds.getMaxY() / cellSize);
        int maxZ = (int) Math.floor(bounds.getMaxZ() / cellSize);

        // Check each potential cell
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    // Check if this cell actually intersects the bounds
                    float cellOriginX = x * cellSize;
                    float cellOriginY = y * cellSize;
                    float cellOriginZ = z * cellSize;

                    if (bounds.intersectsCube(cellOriginX, cellOriginY, cellOriginZ, cellSize)) {
                        long mortonCode = MortonCurve.encode(x, y, z);
                        result.add(mortonCode);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Get Morton code range for spatial bounds, including boundary codes
     */
    private NavigableSet<Long> getMortonCodeRange(VolumeBounds bounds) {
        long minMorton = calculateMortonCode(new Point3f(bounds.minX(), bounds.minY(), bounds.minZ()), maxDepth);
        long maxMorton = calculateMortonCode(new Point3f(bounds.maxX(), bounds.maxY(), bounds.maxZ()), maxDepth);

        // Use sorted Morton codes for efficient range query
        NavigableSet<Long> candidateCodes = sortedSpatialIndices.subSet(minMorton, true, maxMorton, true);

        // Also check codes just outside the range as Morton curve can be non-contiguous
        NavigableSet<Long> extendedCodes = new TreeSet<>(candidateCodes);
        Long lower = sortedSpatialIndices.lower(minMorton);
        if (lower != null) {
            extendedCodes.add(lower);
        }
        Long higher = sortedSpatialIndices.higher(maxMorton);
        if (higher != null) {
            extendedCodes.add(higher);
        }

        return extendedCodes;
    }

    /**
     * Ray-AABB intersection test
     *
     * @param ray  the ray to test
     * @param minX minimum X coordinate
     * @param minY minimum Y coordinate
     * @param minZ minimum Z coordinate
     * @param maxX maximum X coordinate
     * @param maxY maximum Y coordinate
     * @param maxZ maximum Z coordinate
     * @return distance to intersection, or -1 if no intersection
     */
    private float rayIntersectsAABB(Ray3D ray, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float tmin = 0.0f;
        float tmax = ray.maxDistance();

        // X axis
        if (Math.abs(ray.direction().x) < 1e-6f) {
            if (ray.origin().x < minX || ray.origin().x > maxX) {
                return -1;
            }
        } else {
            float t1 = (minX - ray.origin().x) / ray.direction().x;
            float t2 = (maxX - ray.origin().x) / ray.direction().x;

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

        // Y axis
        if (Math.abs(ray.direction().y) < 1e-6f) {
            if (ray.origin().y < minY || ray.origin().y > maxY) {
                return -1;
            }
        } else {
            float t1 = (minY - ray.origin().y) / ray.direction().y;
            float t2 = (maxY - ray.origin().y) / ray.direction().y;

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

        // Z axis
        if (Math.abs(ray.direction().z) < 1e-6f) {
            if (ray.origin().z < minZ || ray.origin().z > maxZ) {
                return -1;
            }
        } else {
            float t1 = (minZ - ray.origin().z) / ray.direction().z;
            float t2 = (maxZ - ray.origin().z) / ray.direction().z;

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

        return tmin;
    }

    /**
     * Helper class to store node index with distance for priority queue ordering
     */
    private static class NodeDistance implements Comparable<NodeDistance> {
        final long  nodeIndex;
        final float distance;

        NodeDistance(long nodeIndex, float distance) {
            this.nodeIndex = nodeIndex;
            this.distance = distance;
        }

        @Override
        public int compareTo(NodeDistance other) {
            return Float.compare(this.distance, other.distance);
        }
    }
    
    // ===== Tree Balancing Implementation =====
    
    @Override
    protected TreeBalancer<ID> createTreeBalancer() {
        return new OctreeBalancer();
    }
    
    @Override
    protected List<Long> getChildNodes(long nodeIndex) {
        List<Long> children = new ArrayList<>();
        byte level = Constants.toLevel(nodeIndex);
        
        if (level >= maxDepth) {
            return children; // No children possible at max depth
        }
        
        int[] parentCoords = MortonCurve.decode(nodeIndex);
        int parentCellSize = Constants.lengthAtLevel(level);
        int childCellSize = parentCellSize / 2;
        byte childLevel = (byte) (level + 1);
        
        // Check all 8 potential children
        for (int i = 0; i < OCTREE_CHILDREN; i++) {
            int childX = parentCoords[0] + ((i & 1) != 0 ? childCellSize : 0);
            int childY = parentCoords[1] + ((i & 2) != 0 ? childCellSize : 0);
            int childZ = parentCoords[2] + ((i & 4) != 0 ? childCellSize : 0);
            
            long childIndex = MortonCurve.encode(childX, childY, childZ);
            
            // Only add if child exists in spatial index
            if (spatialIndex.containsKey(childIndex)) {
                children.add(childIndex);
            }
        }
        
        return children;
    }
    
    /**
     * Octree-specific tree balancer implementation
     */
    protected class OctreeBalancer extends DefaultTreeBalancer {
        
        @Override
        public List<Long> splitNode(long nodeIndex, byte nodeLevel) {
            if (nodeLevel >= maxDepth) {
                return Collections.emptyList();
            }
            
            OctreeNode<ID> node = spatialIndex.get(nodeIndex);
            if (node == null || node.isEmpty()) {
                return Collections.emptyList();
            }
            
            // Get entities to redistribute
            Set<ID> entities = new HashSet<>(node.getEntityIds());
            
            // Calculate child coordinates
            int[] parentCoords = MortonCurve.decode(nodeIndex);
            int parentCellSize = Constants.lengthAtLevel(nodeLevel);
            int childCellSize = parentCellSize / 2;
            byte childLevel = (byte) (nodeLevel + 1);
            
            // Create child nodes
            List<Long> createdChildren = new ArrayList<>();
            Map<Long, Set<ID>> childEntityMap = new HashMap<>();
            
            // Distribute entities to children based on their positions
            for (ID entityId : entities) {
                Point3f pos = entityManager.getEntityPosition(entityId);
                if (pos == null) continue;
                
                // Determine which child octant this entity belongs to
                int octant = 0;
                if (pos.x >= parentCoords[0] + childCellSize) octant |= 1;
                if (pos.y >= parentCoords[1] + childCellSize) octant |= 2;
                if (pos.z >= parentCoords[2] + childCellSize) octant |= 4;
                
                int childX = parentCoords[0] + ((octant & 1) != 0 ? childCellSize : 0);
                int childY = parentCoords[1] + ((octant & 2) != 0 ? childCellSize : 0);
                int childZ = parentCoords[2] + ((octant & 4) != 0 ? childCellSize : 0);
                
                long childIndex = MortonCurve.encode(childX, childY, childZ);
                childEntityMap.computeIfAbsent(childIndex, k -> new HashSet<>()).add(entityId);
            }
            
            // Create child nodes and add entities
            for (Map.Entry<Long, Set<ID>> entry : childEntityMap.entrySet()) {
                long childIndex = entry.getKey();
                Set<ID> childEntities = entry.getValue();
                
                if (!childEntities.isEmpty()) {
                    OctreeNode<ID> childNode = spatialIndex.computeIfAbsent(childIndex, k -> {
                        sortedSpatialIndices.add(childIndex);
                        return new OctreeNode<>(maxEntitiesPerNode);
                    });
                    
                    for (ID entityId : childEntities) {
                        childNode.addEntity(entityId);
                        entityManager.addEntityLocation(entityId, childIndex);
                    }
                    
                    createdChildren.add(childIndex);
                }
            }
            
            // Clear parent node and update entity locations
            node.clearEntities();
            node.setHasChildren(true);
            for (ID entityId : entities) {
                entityManager.removeEntityLocation(entityId, nodeIndex);
            }
            
            return createdChildren;
        }
        
        @Override
        public boolean mergeNodes(Set<Long> nodeIndices, long parentIndex) {
            if (nodeIndices.isEmpty()) {
                return false;
            }
            
            // Collect all entities from nodes to be merged
            Set<ID> allEntities = new HashSet<>();
            for (Long nodeIndex : nodeIndices) {
                OctreeNode<ID> node = spatialIndex.get(nodeIndex);
                if (node != null && !node.isEmpty()) {
                    allEntities.addAll(node.getEntityIds());
                }
            }
            
            if (allEntities.isEmpty()) {
                // Just remove empty nodes
                for (Long nodeIndex : nodeIndices) {
                    spatialIndex.remove(nodeIndex);
                    sortedSpatialIndices.remove(nodeIndex);
                }
                return true;
            }
            
            // Get or create parent node
            OctreeNode<ID> parentNode = spatialIndex.computeIfAbsent(parentIndex, k -> {
                sortedSpatialIndices.add(parentIndex);
                return new OctreeNode<>(maxEntitiesPerNode);
            });
            
            // Move all entities to parent
            for (ID entityId : allEntities) {
                // Remove from child locations
                for (Long nodeIndex : nodeIndices) {
                    entityManager.removeEntityLocation(entityId, nodeIndex);
                }
                
                // Add to parent
                parentNode.addEntity(entityId);
                entityManager.addEntityLocation(entityId, parentIndex);
            }
            
            // Remove child nodes
            for (Long nodeIndex : nodeIndices) {
                spatialIndex.remove(nodeIndex);
                sortedSpatialIndices.remove(nodeIndex);
            }
            
            // Parent no longer has children after merge
            parentNode.setHasChildren(false);
            
            return true;
        }
        
        @Override
        protected Set<Long> findSiblings(long nodeIndex) {
            byte level = Constants.toLevel(nodeIndex);
            if (level == 0) {
                return Collections.emptySet(); // Root has no siblings
            }
            
            // Calculate parent coordinates
            int[] coords = MortonCurve.decode(nodeIndex);
            int cellSize = Constants.lengthAtLevel(level);
            int parentCellSize = cellSize * 2;
            
            // Find parent cell coordinates
            int parentX = (coords[0] / parentCellSize) * parentCellSize;
            int parentY = (coords[1] / parentCellSize) * parentCellSize;
            int parentZ = (coords[2] / parentCellSize) * parentCellSize;
            
            Set<Long> siblings = new HashSet<>();
            
            // Check all 8 positions in parent cell
            for (int i = 0; i < OCTREE_CHILDREN; i++) {
                int siblingX = parentX + ((i & 1) != 0 ? cellSize : 0);
                int siblingY = parentY + ((i & 2) != 0 ? cellSize : 0);
                int siblingZ = parentZ + ((i & 4) != 0 ? cellSize : 0);
                
                long siblingIndex = MortonCurve.encode(siblingX, siblingY, siblingZ);
                
                // Add if it's not the current node and exists
                if (siblingIndex != nodeIndex && spatialIndex.containsKey(siblingIndex)) {
                    siblings.add(siblingIndex);
                }
            }
            
            return siblings;
        }
        
        @Override
        protected long getParentIndex(long nodeIndex) {
            byte level = Constants.toLevel(nodeIndex);
            if (level == 0) {
                return -1; // Root has no parent
            }
            
            int[] coords = MortonCurve.decode(nodeIndex);
            int cellSize = Constants.lengthAtLevel(level);
            int parentCellSize = cellSize * 2;
            
            // Calculate parent coordinates
            int parentX = (coords[0] / parentCellSize) * parentCellSize;
            int parentY = (coords[1] / parentCellSize) * parentCellSize;
            int parentZ = (coords[2] / parentCellSize) * parentCellSize;
            
            return MortonCurve.encode(parentX, parentY, parentZ);
        }
    }
}
