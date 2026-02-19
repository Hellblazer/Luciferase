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
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancingStrategy;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.neighbor.MortonNeighborDetector;
import com.hellblazer.luciferase.lucien.octree.internal.NodeDistance;

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
public class Octree<ID extends EntityID, Content> extends AbstractSpatialIndex<MortonKey, ID, Content> {

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
        
        // Initialize Morton-based neighbor detector for ghost layer support
        setNeighborDetector(new MortonNeighborDetector(this));
    }

    // ===== Abstract Method Implementations =====

    @Override
    public MortonKey calculateSpatialIndex(Point3f position, byte level) {
        return calculateMortonCode(position, level);
    }

    @Override
    public SpatialIndex.SpatialNode<MortonKey, ID> enclosing(Tuple3i point, byte level) {
        var position = new Point3f(point.x, point.y, point.z);

        // Start at the specified level and search down to find entities
        for (var searchLevel = level; searchLevel <= maxDepth; searchLevel++) {
            var mortonIndex = new MortonKey(Constants.calculateMortonIndex(position, searchLevel), searchLevel);
            var node = spatialIndex.get(mortonIndex);
            if (node != null && !node.isEmpty()) {
                return new SpatialIndex.SpatialNode<>(mortonIndex, new HashSet<>(node.getEntityIds()));
            }
        }

        return null;
    }

    @Override
    public SpatialIndex.SpatialNode<MortonKey, ID> enclosing(Spatial volume) {
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return null;
        }

        var level = findMinimumContainingLevel(bounds);
        var midX = (bounds.minX() + bounds.maxX()) / 2.0f;
        var midY = (bounds.minY() + bounds.maxY()) / 2.0f;
        var midZ = (bounds.minZ() + bounds.maxZ()) / 2.0f;
        var center = new Point3f(midX, midY, midZ);

        // Start at the calculated level and search down to find entities
        for (var searchLevel = level; searchLevel <= maxDepth; searchLevel++) {
            var mortonIndex = new MortonKey(Constants.calculateMortonIndex(center, searchLevel), searchLevel);
            var node = spatialIndex.get(mortonIndex);
            if (node != null && !node.isEmpty()) {
                return new SpatialIndex.SpatialNode<>(mortonIndex, new HashSet<>(node.getEntityIds()));
            }
        }

        return null;
    }

    /**
     * Find all entities within a bounding box
     */
    // entitiesInRegion is now implemented in AbstractSpatialIndex

    // getNodeCount() and size() are now implemented in AbstractSpatialIndex

    @Override
    protected void addNeighboringNodes(MortonKey nodeCode, Queue<MortonKey> toVisit, Set<MortonKey> visitedNodes) {
        var coords = MortonCurve.decode(nodeCode.getMortonCode());
        var level = nodeCode.getLevel();
        var cellSize = Constants.lengthAtLevel(level);

        // Check all 26 neighbors (3x3x3 cube minus center)
        for (var dx = -NEIGHBOR_SEARCH_RADIUS; dx <= NEIGHBOR_SEARCH_RADIUS; dx++) {
            for (var dy = -NEIGHBOR_SEARCH_RADIUS; dy <= NEIGHBOR_SEARCH_RADIUS; dy++) {
                for (var dz = -NEIGHBOR_SEARCH_RADIUS; dz <= NEIGHBOR_SEARCH_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue; // Skip center (current node)
                    }

                    var nx = coords[0] + dx * cellSize;
                    var ny = coords[1] + dy * cellSize;
                    var nz = coords[2] + dz * cellSize;

                    // Check bounds (must be within valid coordinate range)
                    if (nx >= 0 && ny >= 0 && nz >= 0 && nx <= Constants.MAX_COORD && ny <= Constants.MAX_COORD
                    && nz <= Constants.MAX_COORD) {
                        // Use level-aware encoding
                        var neighborPos = new Point3f(nx + cellSize / 2.0f, ny + cellSize / 2.0f, nz + cellSize / 2.0f);
                        var neighborCode = new MortonKey(Constants.calculateMortonIndex(neighborPos, level), level);
                        if (!visitedNodes.contains(neighborCode)) {
                            toVisit.add(neighborCode);
                        }
                    }
                }
            }
        }
    }

    @Override
    protected SubdivisionStrategy<MortonKey, ID, Content> createDefaultSubdivisionStrategy() {
        return OctreeSubdivisionStrategy.balanced();
    }

    @Override
    protected TreeBalancer<MortonKey, ID> createTreeBalancer() {
        return new OctreeBalancer<>(this, entityManager, maxDepth, maxEntitiesPerNode);
    }

    @Override
    protected boolean doesFrustumIntersectNode(MortonKey nodeIndex, Frustum3D frustum) {
        // Get the node's cube bounds
        var nodeCube = getCubeFromIndex(nodeIndex);

        // Use Frustum3D's intersectsCube method for efficient frustum-AABB intersection
        return frustum.intersectsCube(nodeCube);
    }

    @Override
    protected boolean doesNodeIntersectVolume(MortonKey mortonIndex, Spatial volume) {
        var nodeBounds = getNodeBounds(mortonIndex);
        if (nodeBounds instanceof Spatial.Cube cube) {
            return doesCubeIntersectVolume(cube, volume);
        }
        return false;
    }

    @Override
    protected boolean doesPlaneIntersectNode(MortonKey nodeIndex, Plane3D plane) {
        var cube = getCubeFromIndex(nodeIndex);
        return plane.intersectsCube(cube);
    }

    // Helper methods for spatial range queries

    @Override
    protected boolean doesRayIntersectNode(MortonKey nodeIndex, Ray3D ray) {
        // Get node bounds
        var coords = MortonCurve.decode(nodeIndex.getMortonCode());
        var level = nodeIndex.getLevel();
        var cellSize = Constants.lengthAtLevel(level);

        // Perform ray-AABB intersection test
        return SpatialDistanceCalculator.rayIntersectsAABB(ray, coords[0], coords[1], coords[2], 
                                                           coords[0] + cellSize, coords[1] + cellSize,
                                                           coords[2] + cellSize) >= 0;
    }

    // ===== Plane Intersection Implementation =====

    @Override
    protected float estimateNodeDistance(MortonKey nodeIndex, Point3f queryPoint) {
        var nodeBounds = getNodeBounds(nodeIndex);
        return SpatialDistanceCalculator.distanceToCenter(nodeBounds, queryPoint);
    }

    // Removed ensureAncestorNodes - not needed in pointerless SFC implementation


    // ===== LITMAX/BIGMIN Range Query Optimization =====

    /**
     * Compute the SFC intervals that cover a query region Q using LITMAX/BIGMIN.
     *
     * For a 3D query box, this algorithm produces optimal contiguous Morton intervals
     * by exploiting the Z-order curve structure.
     *
     * This method delegates to the unified {@link com.hellblazer.luciferase.lucien.sfc.LitmaxBigmin}
     * algorithm for optimal interval computation.
     *
     * @param queryBounds the query region bounds
     * @param level       the refinement level for the query
     * @return list of MortonKey intervals (start, end pairs)
     */
    public List<MortonKeyInterval> cellsQ(VolumeBounds queryBounds, byte level) {
        var cellSize = Constants.lengthAtLevel(level);

        // Compute integer bounds (grid cell coordinates)
        var minX = Math.max(0, (int) Math.floor(queryBounds.minX() / cellSize));
        var minY = Math.max(0, (int) Math.floor(queryBounds.minY() / cellSize));
        var minZ = Math.max(0, (int) Math.floor(queryBounds.minZ() / cellSize));
        var maxX = Math.max(0, (int) Math.floor(queryBounds.maxX() / cellSize));
        var maxY = Math.max(0, (int) Math.floor(queryBounds.maxY() / cellSize));
        var maxZ = Math.max(0, (int) Math.floor(queryBounds.maxZ() / cellSize));

        // Use unified LITMAX/BIGMIN algorithm
        var sfcIntervals = com.hellblazer.luciferase.lucien.sfc.LitmaxBigmin.computeIntervals(
            minX, minY, minZ, maxX, maxY, maxZ);

        // Convert to MortonKeyInterval for backward compatibility
        var intervals = new ArrayList<MortonKeyInterval>(sfcIntervals.size());
        for (var interval : sfcIntervals) {
            intervals.add(new MortonKeyInterval(
                new MortonKey(interval.start(), level),
                new MortonKey(interval.end(), level)
            ));
        }

        return intervals;
    }

    /**
     * Represents a contiguous interval of Morton codes.
     */
    public record MortonKeyInterval(MortonKey start, MortonKey end) {
        public boolean contains(MortonKey key) {
            return key.compareTo(start) >= 0 && key.compareTo(end) <= 0;
        }
    }

    // ===== Frustum Intersection Implementation =====

    @Override
    protected Set<MortonKey> findNodesIntersectingBounds(VolumeBounds bounds) {
        var intersectingNodes = new HashSet<MortonKey>();

        // Use LITMAX/BIGMIN for efficient range query
        var intervals = cellsQ(bounds, maxDepth);

        for (var interval : intervals) {
            // Use the sorted spatial index for efficient range query within each interval
            var subMap = spatialIndex.subMap(interval.start(), true, interval.end(), true);
            for (var entry : subMap.entrySet()) {
                var nodeKey = entry.getKey();
                // Verify the node actually intersects bounds (filter false positives from level differences)
                var coords = MortonCurve.decode(nodeKey.getMortonCode());
                var level = nodeKey.getLevel();
                var cellSize = Constants.lengthAtLevel(level);

                var cellMinX = coords[0];
                var cellMinY = coords[1];
                var cellMinZ = coords[2];
                var cellMaxX = cellMinX + cellSize;
                var cellMaxY = cellMinY + cellSize;
                var cellMaxZ = cellMinZ + cellSize;

                var intersects = !(cellMaxX < bounds.minX() || cellMinX > bounds.maxX()
                                || cellMaxY < bounds.minY() || cellMinY > bounds.maxY()
                                || cellMaxZ < bounds.minZ() || cellMinZ > bounds.maxZ());

                if (intersects) {
                    intersectingNodes.add(nodeKey);
                }
            }
        }

        return intersectingNodes;
    }

    @Override
    protected int getCellSizeAtLevel(byte level) {
        return Constants.lengthAtLevel(level);
    }

    @Override
    protected List<MortonKey> getChildNodes(MortonKey nodeIndex) {
        var children = new ArrayList<MortonKey>();
        var level = nodeIndex.getLevel();

        if (level >= maxDepth) {
            return children; // No children possible at max depth
        }

        var parentCoords = MortonCurve.decode(nodeIndex.getMortonCode());
        var parentCellSize = Constants.lengthAtLevel(level);
        var childCellSize = parentCellSize / 2;
        var childLevel = (byte) (level + 1);

        // Check all 8 potential children
        for (var i = 0; i < OCTREE_CHILDREN; i++) {
            var childX = parentCoords[0] + ((i & 1) != 0 ? childCellSize : 0);
            var childY = parentCoords[1] + ((i & 2) != 0 ? childCellSize : 0);
            var childZ = parentCoords[2] + ((i & 4) != 0 ? childCellSize : 0);

            var childIndex = new MortonKey(MortonCurve.encode(childX, childY, childZ), childLevel);

            // Only add if child exists in spatial index
            if (spatialIndex.containsKey(childIndex)) {
                children.add(childIndex);
            }
        }

        return children;
    }

    @Override
    protected Stream<MortonKey> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition) {
        // For octree, use spatial ordering to traverse nodes that could intersect with the frustum
        // Order by distance from camera to node center for optimal culling traversal
        return spatialIndex.keySet().stream().filter(nodeIndex -> {
            var node = spatialIndex.get(nodeIndex);
            return node != null && !node.isEmpty();
        }).sorted((n1, n2) -> {
            var dist1 = getFrustumNodeDistance(n1, cameraPosition);
            var dist2 = getFrustumNodeDistance(n2, cameraPosition);
            return Float.compare(dist1, dist2);
        });
    }

    /**
     * Get the spatial bounds of a node at the given Morton index.
     * Made public to support visualization and external processing.
     * 
     * @param mortonIndex the Morton index
     * @return the spatial bounds as a Cube
     */
    @Override
    public Spatial getNodeBounds(MortonKey mortonIndex) {
        var coords = MortonCurve.decode(mortonIndex.getMortonCode());
        var level = mortonIndex.getLevel();
        var cellSize = Constants.lengthAtLevel(level);
        return new Spatial.Cube(coords[0], coords[1], coords[2], cellSize);
    }

    // Private helper methods

    @Override
    protected Stream<MortonKey> getPlaneTraversalOrder(Plane3D plane) {
        // For octree, use spatial ordering to traverse nodes that could intersect with the plane
        // Order by distance from plane to node center for better early termination
        return spatialIndex.keySet().stream().filter(nodeIndex -> {
            var node = spatialIndex.get(nodeIndex);
            return node != null && !node.isEmpty();
        }).sorted((n1, n2) -> {
            var dist1 = getPlaneNodeDistance(n1, plane);
            var dist2 = getPlaneNodeDistance(n2, plane);
            return Float.compare(Math.abs(dist1), Math.abs(dist2));
        });
    }

    @Override
    protected float getRayNodeIntersectionDistance(MortonKey nodeIndex, Ray3D ray) {
        // Get node bounds
        var coords = MortonCurve.decode(nodeIndex.getMortonCode());
        var level = nodeIndex.getLevel();
        var cellSize = Constants.lengthAtLevel(level);

        // Calculate ray-AABB intersection distance
        var distance = SpatialDistanceCalculator.rayIntersectsAABB(ray, coords[0], coords[1], coords[2], 
                                                                   coords[0] + cellSize, coords[1] + cellSize, 
                                                                   coords[2] + cellSize);

        return distance >= 0 ? distance : Float.MAX_VALUE;
    }

    @Override
    protected Stream<MortonKey> getRayTraversalOrder(Ray3D ray) {
        // First approach: check all existing nodes in the spatial index
        // This ensures we don't miss any nodes that actually contain entities
        var nodeDistances = new ArrayList<NodeDistance>();

        for (var nodeIndex : spatialIndex.keySet()) {
            // Check if ray intersects this node
            if (doesRayIntersectNode(nodeIndex, ray)) {
                var distance = getRayNodeIntersectionDistance(nodeIndex, ray);
                if (distance >= 0 && distance <= ray.maxDistance()) {
                    nodeDistances.add(new NodeDistance(nodeIndex, distance));
                }
            }
        }

        // Sort by distance to get traversal order
        Collections.sort(nodeDistances);

        // Return stream of node indices in order
        return nodeDistances.stream().map(nd -> nd.getNodeIndex());
    }

    // ===== SpatialIndex Interface Implementation =====

    @Override
    protected NavigableSet<MortonKey> getSpatialIndexRange(VolumeBounds bounds) {
        return getMortonCodeRange(bounds);
    }



    @Override
    protected void handleNodeSubdivision(MortonKey parentMorton, byte parentLevel, SpatialNodeImpl<ID> parentNode) {
        // Can't subdivide beyond max depth
        if (parentLevel >= maxDepth) {
            return;
        }

        var childLevel = (byte) (parentLevel + 1);

        // Get entities to redistribute
        var parentEntities = new ArrayList<>(parentNode.getEntityIds());
        if (parentEntities.isEmpty()) {
            return; // Nothing to subdivide
        }

        // Create map to group entities by their child node
        var childEntityMap = new HashMap<MortonKey, List<ID>>();

        // Determine which child each entity belongs to
        for (var entityId : parentEntities) {
            var entityPos = entityManager.getEntityPosition(entityId);
            if (entityPos != null) {
                // Calculate which child this entity belongs to at the finer level
                var childMorton = calculateMortonCode(entityPos, childLevel);
                childEntityMap.computeIfAbsent(childMorton, k -> new ArrayList<>()).add(entityId);
            }
        }

        // Check if subdivision would actually distribute entities
        if (childEntityMap.size() == 1) {
            // All entities map to the same cell at the child level
            // This happens when entities are very close together
            // Don't subdivide - it won't help distribute the load
            return;
        }

        // Create child nodes and redistribute entities
        // Track which entities we need to remove from parent
        var entitiesToRemoveFromParent = new HashSet<ID>();

        // Add entities to child nodes
        for (var entry : childEntityMap.entrySet()) {
            var childMorton = entry.getKey();
            var childEntities = entry.getValue();

            if (!childEntities.isEmpty()) {
                SpatialNodeImpl<ID> childNode;

                if (childMorton == parentMorton) {
                    // Special case: child has same Morton code as parent
                    // The entities can stay in the parent node - don't redistribute them
                    // Don't mark these entities for removal from parent
                    continue;
                } else {
                    // Create or get child node
                    childNode = spatialIndex.computeIfAbsent(childMorton, k -> {
                        // childMorton is already added to spatialIndex above
                        return nodePool.acquire();
                    });

                    // Add entities to child and mark for removal from parent
                    for (var entityId : childEntities) {
                        childNode.addEntity(entityId);
                        // Update entity locations - add child location
                        entityManager.addEntityLocation(entityId, childMorton);
                        entitiesToRemoveFromParent.add(entityId);
                    }
                }
            }
        }

        // Remove only the entities that were moved to different child nodes
        for (var entityId : entitiesToRemoveFromParent) {
            parentNode.removeEntity(entityId);
            entityManager.removeEntityLocation(entityId, parentMorton);
        }

        // Mark that this node has been subdivided
        parentNode.setHasChildren(true);
    }

    @Override
    protected boolean hasChildren(MortonKey spatialIndex) {
        var node = this.spatialIndex.get(spatialIndex);
        return node != null && node.hasChildren();
    }

    // insertAtPosition is now handled by AbstractSpatialIndex with proper subdivided node handling

    /**
     * Insert entity with spanning across multiple nodes
     */
    @Override
    protected void insertWithSpanning(ID entityId, EntityBounds bounds, byte level) {
        // Find all nodes that the entity's bounds intersect
        var intersectingNodes = findIntersectingNodes(bounds, level);

        // Add entity to all intersecting nodes
        for (var mortonCode : intersectingNodes) {
            var node = spatialIndex.computeIfAbsent(mortonCode, k -> {
                // mortonCode is already added to spatialIndex above
                return nodePool.acquire();
            });

            node.addEntity(entityId);
            entityManager.addEntityLocation(entityId, mortonCode);

            // Note: We don't trigger subdivision for spanning entities
            // to avoid cascading subdivisions
        }
    }

    @Override
    protected boolean isNodeContainedInVolume(MortonKey mortonIndex, Spatial volume) {
        var nodeBounds = getNodeBounds(mortonIndex);
        if (nodeBounds instanceof Spatial.Cube cube) {
            return isCubeContainedInVolume(cube, volume);
        }
        return false;
    }

    @Override
    protected boolean shouldContinueKNNSearch(MortonKey nodeIndex, Point3f queryPoint,
                                              PriorityQueue<EntityDistance<ID>> candidates) {
        if (candidates.isEmpty()) {
            return true;
        }

        // Get the furthest candidate distance
        var furthest = candidates.peek();
        if (furthest == null) {
            return true;
        }

        // Calculate distance from query point to node bounds
        var coords = MortonCurve.decode(nodeIndex.getMortonCode());
        var level = nodeIndex.getLevel();
        var cellSize = Constants.lengthAtLevel(level);

        // Calculate closest point on node to query
        var nodeMinX = coords[0];
        var nodeMinY = coords[1];
        var nodeMinZ = coords[2];
        var nodeMaxX = nodeMinX + cellSize;
        var nodeMaxY = nodeMinY + cellSize;
        var nodeMaxZ = nodeMinZ + cellSize;

        var closestX = Math.max(nodeMinX, Math.min(queryPoint.x, nodeMaxX));
        var closestY = Math.max(nodeMinY, Math.min(queryPoint.y, nodeMaxY));
        var closestZ = Math.max(nodeMinZ, Math.min(queryPoint.z, nodeMaxZ));

        var nodeDistance = new Point3f(closestX, closestY, closestZ).distance(queryPoint);

        // If the closest point on this node is further than our furthest candidate,
        // we don't need to explore further
        return nodeDistance <= furthest.distance();
    }


    // ===== Ray Intersection Implementation =====

    // Package-private getters for OctreeBalancer
    @Override
    protected Map<MortonKey, SpatialNodeImpl<ID>> getSpatialIndex() {
        return spatialIndex;
    }
    
    @Override
    protected NavigableSet<MortonKey> getSortedSpatialIndices() {
        return (NavigableSet<MortonKey>) spatialIndex.navigableKeySet();
    }

    boolean doesCubeIntersectVolume(Spatial.Cube cube, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube other ->
            cube.originX() < other.originX() + other.extent() && cube.originX() + cube.extent() > other.originX()
            && cube.originY() < other.originY() + other.extent() && cube.originY() + cube.extent() > other.originY()
            && cube.originZ() < other.originZ() + other.extent() && cube.originZ() + cube.extent() > other.originZ();

            case Spatial.Sphere sphere -> {
                // Find closest point on cube to sphere center
                var closestX = Math.max(cube.originX(), Math.min(sphere.centerX(), cube.originX() + cube.extent()));
                var closestY = Math.max(cube.originY(), Math.min(sphere.centerY(), cube.originY() + cube.extent()));
                var closestZ = Math.max(cube.originZ(), Math.min(sphere.centerZ(), cube.originZ() + cube.extent()));

                // Check if closest point is within sphere radius
                var dx = closestX - sphere.centerX();
                var dy = closestY - sphere.centerY();
                var dz = closestZ - sphere.centerZ();
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
                for (var i = 0; i < OCTREE_CHILDREN; i++) {
                    var x = cube.originX() + ((i & 1) != 0 ? cube.extent() : 0);
                    var y = cube.originY() + ((i & 2) != 0 ? cube.extent() : 0);
                    var z = cube.originZ() + ((i & 4) != 0 ? cube.extent() : 0);

                    var dx = x - sphere.centerX();
                    var dy = y - sphere.centerY();
                    var dz = z - sphere.centerZ();

                    if ((dx * dx + dy * dy + dz * dz) > (sphere.radius() * sphere.radius())) {
                        yield false;
                    }
                }
                yield true;
            }

            default -> false; // Conservative: exclude for other volume types
        };
    }

    /**
     * Helper method to add intersecting child nodes to the traversal queue
     */
    private void addIntersectingChildren(MortonKey parentIndex, byte parentLevel, Ray3D ray,
                                         PriorityQueue<NodeDistance> nodeQueue, Set<Long> visitedNodes) {
        var parentCoords = MortonCurve.decode(parentIndex.getMortonCode());
        var parentCellSize = Constants.lengthAtLevel(parentLevel);
        var childCellSize = parentCellSize / 2;
        var childLevel = (byte) (parentLevel + 1);

        // Check all 8 children
        for (var i = 0; i < OCTREE_CHILDREN; i++) {
            var childX = parentCoords[0] + ((i & 1) != 0 ? childCellSize : 0);
            var childY = parentCoords[1] + ((i & 2) != 0 ? childCellSize : 0);
            var childZ = parentCoords[2] + ((i & 4) != 0 ? childCellSize : 0);

            // Calculate child morton code at the child level
            var childCenter = new Point3f(childX + childCellSize / 2.0f, childY + childCellSize / 2.0f,
                                          childZ + childCellSize / 2.0f);
            var childIndex = new MortonKey(Constants.calculateMortonIndex(childCenter, childLevel));

            if (!visitedNodes.contains(childIndex)) {
                var distance = SpatialDistanceCalculator.rayIntersectsAABB(ray, childX, childY, childZ, 
                                                                           childX + childCellSize,
                                                                           childY + childCellSize, 
                                                                           childZ + childCellSize);
                if (distance >= 0 && distance <= ray.maxDistance()) {
                    nodeQueue.add(new NodeDistance(childIndex, distance));
                }
            }
        }
    }

    private MortonKey calculateMortonCode(Point3f position, byte level) {
        // Use the level-aware morton index calculation from Constants
        return new MortonKey(Constants.calculateMortonIndex(position, level), level);
    }

    /**
     * Find all nodes at the given level that intersect with the bounds
     */
    private Set<MortonKey> findIntersectingNodes(EntityBounds bounds, byte level) {
        var result = new HashSet<MortonKey>();

        var cellSize = Constants.lengthAtLevel(level);

        // Calculate the range of grid cells that might intersect
        var minX = (int) Math.floor(bounds.getMinX() / cellSize);
        var minY = (int) Math.floor(bounds.getMinY() / cellSize);
        var minZ = (int) Math.floor(bounds.getMinZ() / cellSize);

        var maxX = (int) Math.floor(bounds.getMaxX() / cellSize);
        var maxY = (int) Math.floor(bounds.getMaxY() / cellSize);
        var maxZ = (int) Math.floor(bounds.getMaxZ() / cellSize);

        // Check each potential cell
        for (var x = minX; x <= maxX; x++) {
            for (var y = minY; y <= maxY; y++) {
                for (var z = minZ; z <= maxZ; z++) {
                    // Check if this cell actually intersects the bounds
                    var cellOriginX = x * cellSize;
                    var cellOriginY = y * cellSize;
                    var cellOriginZ = z * cellSize;

                    if (bounds.intersectsCube(cellOriginX, cellOriginY, cellOriginZ, cellSize)) {
                        // Use level-aware morton encoding
                        var cellCenter = new Point3f(cellOriginX + cellSize / 2.0f, cellOriginY + cellSize / 2.0f,
                                                     cellOriginZ + cellSize / 2.0f);
                        var mortonCode = new MortonKey(Constants.calculateMortonIndex(cellCenter, level), level);
                        result.add(mortonCode);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Helper method to get cube from node index
     */
    private Spatial.Cube getCubeFromIndex(MortonKey nodeIndex) {
        var coords = MortonCurve.decode(nodeIndex.getMortonCode());
        var level = nodeIndex.getLevel();
        var cellSize = Constants.lengthAtLevel(level);
        return new Spatial.Cube(coords[0], coords[1], coords[2], cellSize);
    }

    /**
     * Calculate distance from camera position to node center for frustum culling traversal order
     */
    private float getFrustumNodeDistance(MortonKey nodeIndex, Point3f cameraPosition) {
        var nodeBounds = getNodeBounds(nodeIndex);
        return SpatialDistanceCalculator.distanceToCenter(nodeBounds, cameraPosition);
    }

    /**
     * Get Morton code range for spatial bounds, including boundary codes
     */
    private NavigableSet<MortonKey> getMortonCodeRange(VolumeBounds bounds) {
        // With level-aware compareTo, subSet() bounds must be at the same level as stored keys.
        // Collect the set of distinct levels present in the index and issue one subSet query
        // per level, then union the results.
        var storageLevels = new java.util.LinkedHashSet<Byte>();
        for (var key : spatialIndex.keySet()) {
            storageLevels.add(key.getLevel());
        }

        if (storageLevels.isEmpty()) {
            return new TreeSet<>();
        }

        var extendedCodes = new TreeSet<MortonKey>();

        for (byte level : storageLevels) {
            var minMorton = calculateMortonCode(new Point3f(bounds.minX(), bounds.minY(), bounds.minZ()), level);
            var maxMorton = calculateMortonCode(new Point3f(bounds.maxX(), bounds.maxY(), bounds.maxZ()), level);

            // Ensure min <= max
            if (minMorton.compareTo(maxMorton) > 0) {
                var temp = minMorton;
                minMorton = maxMorton;
                maxMorton = temp;
            }

            NavigableSet<MortonKey> candidateCodes;
            if (minMorton.getMortonCode() == maxMorton.getMortonCode()) {
                // Special case: single point or very small bounds at this level
                candidateCodes = new TreeSet<>();
                if (spatialIndex.containsKey(minMorton)) {
                    candidateCodes.add(minMorton);
                }
            } else {
                candidateCodes = spatialIndex.navigableKeySet().subSet(minMorton, true, maxMorton, true);
            }

            extendedCodes.addAll(candidateCodes);

            // Also check codes just outside the range as Morton curve can be non-contiguous
            var lower = spatialIndex.lowerKey(minMorton);
            if (lower != null && lower.getLevel() == level) {
                extendedCodes.add(lower);
            }
            var higher = spatialIndex.higherKey(maxMorton);
            if (higher != null && higher.getLevel() == level) {
                extendedCodes.add(higher);
            }
        }

        return extendedCodes;
    }

    // ===== Tree Balancing Implementation =====

    /**
     * Calculate distance from plane to node center
     */
    private float getPlaneNodeDistance(MortonKey nodeIndex, Plane3D plane) {
        var nodeBounds = getNodeBounds(nodeIndex);
        return SpatialDistanceCalculator.distanceToPlane(nodeBounds, plane);
    }




}
