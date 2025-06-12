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
import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.*;

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

        // If the node is empty (has been subdivided), look in child nodes
        if (node.isEmpty()) {
            // Calculate which child contains this position
            byte childLevel = (byte) (level + 1);
            if (childLevel <= maxDepth) {
                return lookup(position, childLevel);
            }
        }

        return new ArrayList<>(node.getEntityIds());
    }

    @Override
    protected long calculateSpatialIndex(Point3f position, byte level) {
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
    protected int getCellSizeAtLevel(byte level) {
        return Constants.lengthAtLevel(level);
    }

    @Override
    protected byte getLevelFromIndex(long index) {
        return Constants.toLevel(index);
    }

    @Override
    protected Spatial getNodeBounds(long mortonIndex) {
        int[] coords = MortonCurve.decode(mortonIndex);
        byte level = Constants.toLevel(mortonIndex);
        int cellSize = Constants.lengthAtLevel(level);
        return new Spatial.Cube(coords[0], coords[1], coords[2], cellSize);
    }


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

        // Create child nodes and redistribute entities
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

                // Parent knows it has children because its entities have been redistributed
            }
        }

        // Clear entities from parent node (they've been redistributed to children)
        parentNode.clearEntities();

        // Remove parent from entity locations
        for (ID entityId : parentEntities) {
            entityManager.removeEntityLocation(entityId, parentMorton);
        }
    }

    @Override
    protected boolean hasChildren(long spatialIndex) {
        OctreeNode<ID> node = this.spatialIndex.get(spatialIndex);
        return node != null && node.hasChildren();
    }

    // Helper methods for spatial range queries

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

    // Private helper methods


    @Override
    protected NavigableSet<Long> getSpatialIndexRange(VolumeBounds bounds) {
        return getMortonCodeRange(bounds);
    }

    @Override
    protected void validateSpatialConstraints(Point3f position) {
        // Octree doesn't have specific spatial constraints
    }

    @Override
    protected void validateSpatialConstraints(Spatial volume) {
        // Octree doesn't have specific spatial constraints
    }

    // ===== SpatialIndex Interface Implementation =====

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
                        long neighborCode = MortonCurve.encode(nx, ny, nz);
                        if (!visitedNodes.contains(neighborCode)) {
                            toVisit.add(neighborCode);
                        }
                    }
                }
            }
        }
    }

    private long calculateMortonCode(Point3f position, byte level) {
        // Scale coordinates to the appropriate level of the hierarchy
        // At level 0 (coarsest), we have large cells
        // At maxLevel (finest), we have small cells
        int scale = 1 << (Constants.getMaxRefinementLevel() - level);
        int x = (int) (position.x / scale);
        int y = (int) (position.y / scale);
        int z = (int) (position.z / scale);

        // The Morton code itself represents the hierarchical position
        return MortonCurve.encode(x, y, z);
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


}
