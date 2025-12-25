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
package com.hellblazer.luciferase.lucien.sfc;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.*;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.octree.MortonKey;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import java.util.*;
import java.util.stream.Stream;

/**
 * Space-Filling Curve Array Index - A flat sorted array implementation of SpatialIndex.
 *
 * This is a simplified fork of Octree that removes hierarchical tree operations in favor
 * of a flat array structure sorted by Morton codes. The key optimization is the cells(Q)
 * algorithm for range queries, which computes contiguous SFC intervals for query regions.
 *
 * <h2>Key Differences from Octree</h2>
 * <ul>
 *   <li>No tree subdivision - flat array structure</li>
 *   <li>No balancing operations - not needed for flat structure</li>
 *   <li>Uses cells(Q) algorithm for efficient range queries (≤8 intervals for 3D)</li>
 *   <li>Simpler insertion - direct SFC mapping without subdivision</li>
 * </ul>
 *
 * <h2>Performance Hypothesis</h2>
 * By removing tree overhead, we expect:
 * <ul>
 *   <li>Faster insertions (no subdivision/balancing)</li>
 *   <li>Comparable query performance via cells(Q)</li>
 *   <li>Lower memory footprint (no tree metadata)</li>
 * </ul>
 *
 * Based on de Berg et al. 2025 "Simpler is Faster: Practical Distance Reporting
 * by Sorting Along a Space-Filling Curve".
 *
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class SFCArrayIndex<ID extends EntityID, Content> extends AbstractSpatialIndex<MortonKey, ID, Content> {

    private static final int DEFAULT_MAX_ENTITIES_PER_NODE = 10;
    private static final int NEIGHBOR_SEARCH_RADIUS        = 1;

    /**
     * Create an SFCArrayIndex with default configuration
     */
    public SFCArrayIndex(EntityIDGenerator<ID> idGenerator) {
        this(idGenerator, DEFAULT_MAX_ENTITIES_PER_NODE, Constants.getMaxRefinementLevel());
    }

    /**
     * Create an SFCArrayIndex with custom configuration
     */
    public SFCArrayIndex(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth) {
        this(idGenerator, maxEntitiesPerNode, maxDepth, new EntitySpanningPolicy());
    }

    /**
     * Create an SFCArrayIndex with custom configuration and spanning policy
     */
    public SFCArrayIndex(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                         EntitySpanningPolicy spanningPolicy) {
        super(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy);
    }

    // ===== Abstract Method Implementations =====

    @Override
    public MortonKey calculateSpatialIndex(Point3f position, byte level) {
        return calculateMortonCode(position, level);
    }

    @Override
    public SpatialIndex.SpatialNode<MortonKey, ID> enclosing(Tuple3i point, byte level) {
        var position = new Point3f(point.x, point.y, point.z);
        var mortonIndex = new MortonKey(Constants.calculateMortonIndex(position, level), level);
        var node = spatialIndex.get(mortonIndex);
        if (node != null && !node.isEmpty()) {
            return new SpatialIndex.SpatialNode<>(mortonIndex, new HashSet<>(node.getEntityIds()));
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

        var mortonIndex = new MortonKey(Constants.calculateMortonIndex(center, level), level);
        var node = spatialIndex.get(mortonIndex);
        if (node != null && !node.isEmpty()) {
            return new SpatialIndex.SpatialNode<>(mortonIndex, new HashSet<>(node.getEntityIds()));
        }
        return null;
    }

    // ===== cells(Q) Algorithm - Core Innovation =====

    /**
     * Compute the SFC intervals that cover a query region Q.
     *
     * For a 3D query box, this algorithm produces at most 2^3 = 8 intervals
     * due to the RSFC (Recursive Space-Filling Curve) property: axis-aligned
     * hypercubes map to contiguous intervals.
     *
     * @param queryBounds the query region bounds
     * @param level       the refinement level for the query
     * @return list of MortonKey intervals (start, end pairs)
     */
    public List<MortonKeyInterval> cellsQ(VolumeBounds queryBounds, byte level) {
        var intervals = new ArrayList<MortonKeyInterval>();
        var cellSize = Constants.lengthAtLevel(level);

        // Calculate the grid cell range that covers the query bounds
        var minCellX = (int) Math.floor(queryBounds.minX() / cellSize);
        var minCellY = (int) Math.floor(queryBounds.minY() / cellSize);
        var minCellZ = (int) Math.floor(queryBounds.minZ() / cellSize);

        var maxCellX = (int) Math.floor(queryBounds.maxX() / cellSize);
        var maxCellY = (int) Math.floor(queryBounds.maxY() / cellSize);
        var maxCellZ = (int) Math.floor(queryBounds.maxZ() / cellSize);

        // Collect all Morton codes in the query region
        var mortonCodes = new TreeSet<Long>();
        for (var x = minCellX; x <= maxCellX; x++) {
            for (var y = minCellY; y <= maxCellY; y++) {
                for (var z = minCellZ; z <= maxCellZ; z++) {
                    // Clamp to valid coordinate range
                    var clampedX = Math.max(0, Math.min(x * cellSize, Constants.MAX_COORD));
                    var clampedY = Math.max(0, Math.min(y * cellSize, Constants.MAX_COORD));
                    var clampedZ = Math.max(0, Math.min(z * cellSize, Constants.MAX_COORD));
                    mortonCodes.add(MortonCurve.encode(clampedX, clampedY, clampedZ));
                }
            }
        }

        // Convert sorted Morton codes to contiguous intervals
        if (!mortonCodes.isEmpty()) {
            var iterator = mortonCodes.iterator();
            var intervalStart = iterator.next();
            var intervalEnd = intervalStart;

            while (iterator.hasNext()) {
                var current = iterator.next();
                if (current == intervalEnd + 1) {
                    // Extend current interval
                    intervalEnd = current;
                } else {
                    // Close current interval and start new one
                    intervals.add(new MortonKeyInterval(
                        new MortonKey(intervalStart, level),
                        new MortonKey(intervalEnd, level)
                    ));
                    intervalStart = current;
                    intervalEnd = current;
                }
            }
            // Add final interval
            intervals.add(new MortonKeyInterval(
                new MortonKey(intervalStart, level),
                new MortonKey(intervalEnd, level)
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

    // ===== Simplified Range Query Using cells(Q) =====

    /**
     * Find all entities in a region using the cells(Q) algorithm.
     * This is the primary range query method for SFCArrayIndex.
     */
    public List<ID> entitiesInRegionSFC(VolumeBounds bounds, byte level) {
        var intervals = cellsQ(bounds, level);
        var result = new ArrayList<ID>();
        var seen = new HashSet<ID>();

        for (var interval : intervals) {
            // Use the sorted spatial index for efficient range query
            var subMap = spatialIndex.subMap(interval.start(), true, interval.end(), true);
            for (var entry : subMap.entrySet()) {
                var node = entry.getValue();
                if (node != null) {
                    for (var entityId : node.getEntityIds()) {
                        if (seen.add(entityId)) {
                            // Filter false positives: entity must actually intersect bounds
                            var entityPos = entityManager.getEntityPosition(entityId);
                            if (entityPos != null && bounds.contains(entityPos.x, entityPos.y, entityPos.z)) {
                                result.add(entityId);
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    // ===== Neighbor Finding (Simplified) =====

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
                        continue;
                    }

                    var nx = coords[0] + dx * cellSize;
                    var ny = coords[1] + dy * cellSize;
                    var nz = coords[2] + dz * cellSize;

                    if (nx >= 0 && ny >= 0 && nz >= 0 && nx <= Constants.MAX_COORD && ny <= Constants.MAX_COORD
                    && nz <= Constants.MAX_COORD) {
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

    // ===== Tree Operations - Simplified/Not Applicable =====

    @Override
    protected SubdivisionStrategy<MortonKey, ID, Content> createDefaultSubdivisionStrategy() {
        // No subdivision for flat array - return null or no-op strategy
        return null;
    }

    @Override
    protected TreeBalancer<MortonKey, ID> createTreeBalancer() {
        // No balancing for flat array
        return null;
    }

    @Override
    protected void handleNodeSubdivision(MortonKey parentMorton, byte parentLevel, SpatialNodeImpl<ID> parentNode) {
        // No subdivision in flat array structure - entities stay where inserted
    }

    @Override
    protected boolean hasChildren(MortonKey spatialIndex) {
        // Flat structure has no children concept
        return false;
    }

    @Override
    protected List<MortonKey> getChildNodes(MortonKey nodeIndex) {
        // Flat structure has no children
        return Collections.emptyList();
    }

    // ===== Frustum/Volume Operations =====

    @Override
    protected boolean doesFrustumIntersectNode(MortonKey nodeIndex, Frustum3D frustum) {
        var nodeCube = getCubeFromIndex(nodeIndex);
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

    @Override
    protected boolean doesRayIntersectNode(MortonKey nodeIndex, Ray3D ray) {
        var coords = MortonCurve.decode(nodeIndex.getMortonCode());
        var level = nodeIndex.getLevel();
        var cellSize = Constants.lengthAtLevel(level);

        return SpatialDistanceCalculator.rayIntersectsAABB(ray, coords[0], coords[1], coords[2],
                                                           coords[0] + cellSize, coords[1] + cellSize,
                                                           coords[2] + cellSize) >= 0;
    }

    @Override
    protected float estimateNodeDistance(MortonKey nodeIndex, Point3f queryPoint) {
        var nodeBounds = getNodeBounds(nodeIndex);
        return SpatialDistanceCalculator.distanceToCenter(nodeBounds, queryPoint);
    }

    @Override
    protected Set<MortonKey> findNodesIntersectingBounds(VolumeBounds bounds) {
        // Use cells(Q) for efficient interval-based search
        var intersectingNodes = new HashSet<MortonKey>();
        var intervals = cellsQ(bounds, maxDepth);

        for (var interval : intervals) {
            var subMap = spatialIndex.subMap(interval.start(), true, interval.end(), true);
            for (var nodeKey : subMap.keySet()) {
                if (spatialIndex.containsKey(nodeKey)) {
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
    protected Stream<MortonKey> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition) {
        return spatialIndex.keySet().stream().filter(nodeIndex -> {
            var node = spatialIndex.get(nodeIndex);
            return node != null && !node.isEmpty();
        }).sorted((n1, n2) -> {
            var dist1 = estimateNodeDistance(n1, cameraPosition);
            var dist2 = estimateNodeDistance(n2, cameraPosition);
            return Float.compare(dist1, dist2);
        });
    }

    @Override
    public Spatial getNodeBounds(MortonKey mortonIndex) {
        var coords = MortonCurve.decode(mortonIndex.getMortonCode());
        var level = mortonIndex.getLevel();
        var cellSize = Constants.lengthAtLevel(level);
        return new Spatial.Cube(coords[0], coords[1], coords[2], cellSize);
    }

    @Override
    protected Stream<MortonKey> getPlaneTraversalOrder(Plane3D plane) {
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
        var coords = MortonCurve.decode(nodeIndex.getMortonCode());
        var level = nodeIndex.getLevel();
        var cellSize = Constants.lengthAtLevel(level);

        var distance = SpatialDistanceCalculator.rayIntersectsAABB(ray, coords[0], coords[1], coords[2],
                                                                   coords[0] + cellSize, coords[1] + cellSize,
                                                                   coords[2] + cellSize);

        return distance >= 0 ? distance : Float.MAX_VALUE;
    }

    @Override
    protected Stream<MortonKey> getRayTraversalOrder(Ray3D ray) {
        // Collect and sort nodes by ray intersection distance
        return spatialIndex.keySet().stream()
            .filter(nodeIndex -> doesRayIntersectNode(nodeIndex, ray))
            .filter(nodeIndex -> {
                var distance = getRayNodeIntersectionDistance(nodeIndex, ray);
                return distance >= 0 && distance <= ray.maxDistance();
            })
            .sorted(Comparator.comparing(nodeIndex -> getRayNodeIntersectionDistance(nodeIndex, ray)));
    }

    @Override
    protected NavigableSet<MortonKey> getSpatialIndexRange(VolumeBounds bounds) {
        return getMortonCodeRange(bounds);
    }

    @Override
    protected void insertWithSpanning(ID entityId, EntityBounds bounds, byte level) {
        var intersectingNodes = findIntersectingNodesForEntity(bounds, level);

        for (var mortonCode : intersectingNodes) {
            var node = spatialIndex.computeIfAbsent(mortonCode, k -> nodePool.acquire());
            node.addEntity(entityId);
            entityManager.addEntityLocation(entityId, mortonCode);
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

        var furthest = candidates.peek();
        if (furthest == null) {
            return true;
        }

        var coords = MortonCurve.decode(nodeIndex.getMortonCode());
        var level = nodeIndex.getLevel();
        var cellSize = Constants.lengthAtLevel(level);

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

        return nodeDistance <= furthest.distance();
    }

    @Override
    protected Map<MortonKey, SpatialNodeImpl<ID>> getSpatialIndex() {
        return spatialIndex;
    }

    @Override
    protected NavigableSet<MortonKey> getSortedSpatialIndices() {
        return (NavigableSet<MortonKey>) spatialIndex.navigableKeySet();
    }

    // ===== Private Helper Methods =====

    private MortonKey calculateMortonCode(Point3f position, byte level) {
        return new MortonKey(Constants.calculateMortonIndex(position, level), level);
    }

    private Spatial.Cube getCubeFromIndex(MortonKey nodeIndex) {
        var coords = MortonCurve.decode(nodeIndex.getMortonCode());
        var level = nodeIndex.getLevel();
        var cellSize = Constants.lengthAtLevel(level);
        return new Spatial.Cube(coords[0], coords[1], coords[2], cellSize);
    }

    private float getPlaneNodeDistance(MortonKey nodeIndex, Plane3D plane) {
        var nodeBounds = getNodeBounds(nodeIndex);
        return SpatialDistanceCalculator.distanceToPlane(nodeBounds, plane);
    }

    private NavigableSet<MortonKey> getMortonCodeRange(VolumeBounds bounds) {
        var minMorton = calculateMortonCode(new Point3f(bounds.minX(), bounds.minY(), bounds.minZ()), maxDepth);
        var maxMorton = calculateMortonCode(new Point3f(bounds.maxX(), bounds.maxY(), bounds.maxZ()), maxDepth);

        if (minMorton.compareTo(maxMorton) > 0) {
            var temp = minMorton;
            minMorton = maxMorton;
            maxMorton = temp;
        }

        NavigableSet<MortonKey> candidateCodes;
        if (minMorton == maxMorton) {
            candidateCodes = new TreeSet<>();
            if (spatialIndex.containsKey(minMorton)) {
                candidateCodes.add(minMorton);
            }
        } else {
            candidateCodes = spatialIndex.navigableKeySet().subSet(minMorton, true, maxMorton, true);
        }

        var extendedCodes = new TreeSet<>(candidateCodes);
        var lower = spatialIndex.lowerKey(minMorton);
        if (lower != null) {
            extendedCodes.add(lower);
        }
        var higher = spatialIndex.higherKey(maxMorton);
        if (higher != null) {
            extendedCodes.add(higher);
        }

        return extendedCodes;
    }

    private Set<MortonKey> findIntersectingNodesForEntity(EntityBounds bounds, byte level) {
        var result = new HashSet<MortonKey>();
        var cellSize = Constants.lengthAtLevel(level);

        var minX = (int) Math.floor(bounds.getMinX() / cellSize);
        var minY = (int) Math.floor(bounds.getMinY() / cellSize);
        var minZ = (int) Math.floor(bounds.getMinZ() / cellSize);

        var maxX = (int) Math.floor(bounds.getMaxX() / cellSize);
        var maxY = (int) Math.floor(bounds.getMaxY() / cellSize);
        var maxZ = (int) Math.floor(bounds.getMaxZ() / cellSize);

        for (var x = minX; x <= maxX; x++) {
            for (var y = minY; y <= maxY; y++) {
                for (var z = minZ; z <= maxZ; z++) {
                    var cellOriginX = x * cellSize;
                    var cellOriginY = y * cellSize;
                    var cellOriginZ = z * cellSize;

                    if (bounds.intersectsCube(cellOriginX, cellOriginY, cellOriginZ, cellSize)) {
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

    private boolean doesCubeIntersectVolume(Spatial.Cube cube, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube other ->
            cube.originX() < other.originX() + other.extent() && cube.originX() + cube.extent() > other.originX()
            && cube.originY() < other.originY() + other.extent() && cube.originY() + cube.extent() > other.originY()
            && cube.originZ() < other.originZ() + other.extent() && cube.originZ() + cube.extent() > other.originZ();

            case Spatial.Sphere sphere -> {
                var closestX = Math.max(cube.originX(), Math.min(sphere.centerX(), cube.originX() + cube.extent()));
                var closestY = Math.max(cube.originY(), Math.min(sphere.centerY(), cube.originY() + cube.extent()));
                var closestZ = Math.max(cube.originZ(), Math.min(sphere.centerZ(), cube.originZ() + cube.extent()));

                var dx = closestX - sphere.centerX();
                var dy = closestY - sphere.centerY();
                var dz = closestZ - sphere.centerZ();
                yield (dx * dx + dy * dy + dz * dz) <= (sphere.radius() * sphere.radius());
            }

            default -> true;
        };
    }

    private boolean isCubeContainedInVolume(Spatial.Cube cube, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube other ->
            cube.originX() >= other.originX() && cube.originY() >= other.originY() && cube.originZ() >= other.originZ()
            && cube.originX() + cube.extent() <= other.originX() + other.extent()
            && cube.originY() + cube.extent() <= other.originY() + other.extent()
            && cube.originZ() + cube.extent() <= other.originZ() + other.extent();

            case Spatial.Sphere sphere -> {
                for (var i = 0; i < 8; i++) {
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

            default -> false;
        };
    }
}
