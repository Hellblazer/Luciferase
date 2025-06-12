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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.Geometry;
import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.EntityDistance;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tetree implementation with multi-entity support per tetrahedral location. This class provides a tetrahedral spatial
 * index using space-filling curves that supports multiple entities per location.
 *
 * Key constraints: - All coordinates must be positive (tetrahedral SFC requirement) - Points must be within the S0
 * tetrahedron domain - Each grid cell contains 6 tetrahedra (types 0-5)
 *
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class Tetree<ID extends EntityID, Content> extends AbstractSpatialIndex<ID, Content, TetreeNodeImpl<ID>> {

    // Spatial index: Tetrahedral index → Node containing entity IDs
    private final NavigableMap<Long, TetreeNodeImpl<ID>> spatialIndex;

    /**
     * Create a Tetree with default configuration
     */
    public Tetree(EntityIDGenerator<ID> idGenerator) {
        this(idGenerator, 10, Constants.getMaxRefinementLevel());
    }

    /**
     * Create a Tetree with custom configuration
     */
    public Tetree(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth) {
        this(idGenerator, maxEntitiesPerNode, maxDepth, new EntitySpanningPolicy());
    }

    /**
     * Create a Tetree with full configuration
     */
    public Tetree(EntityIDGenerator<ID> idGenerator, int maxEntitiesPerNode, byte maxDepth,
                  EntitySpanningPolicy spanningPolicy) {
        super(idGenerator, maxEntitiesPerNode, maxDepth, spanningPolicy);
        this.spatialIndex = new TreeMap<>();
    }

    // ===== Abstract Method Implementations =====

    @Override
    public SpatialNode<ID> enclosing(Spatial volume) {
        validatePositiveCoordinates(volume);

        // Extract bounding box of the volume
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return null;
        }

        // Find the minimum level that can contain the volume
        byte level = findMinimumContainingLevel(bounds);

        // Find a tetrahedron at that level that contains the volume
        var centerPoint = new Point3f((bounds.minX() + bounds.maxX()) / 2, (bounds.minY() + bounds.maxY()) / 2,
                                      (bounds.minZ() + bounds.maxZ()) / 2);

        var tet = locate(centerPoint, level);
        TetreeNodeImpl<ID> node = spatialIndex.get(tet.index());
        if (node != null && !node.isEmpty()) {
            return new SpatialNode<>(tet.index(), new HashSet<>(node.getEntityIds()));
        }
        return null;
    }

    @Override
    public SpatialNode<ID> enclosing(Tuple3i point, byte level) {
        validatePositiveCoordinates(point);

        var tet = locate(new Point3f(point.x, point.y, point.z), level);
        TetreeNodeImpl<ID> node = spatialIndex.get(tet.index());
        if (node != null && !node.isEmpty()) {
            return new SpatialNode<>(tet.index(), new HashSet<>(node.getEntityIds()));
        }
        return null;
    }

    @Override
    public List<ID> entitiesInRegion(Spatial.Cube region) {
        validatePositiveCoordinates(region);

        Set<ID> uniqueEntities = new HashSet<>();

        // Simple approach: Check all entities directly
        // This is more reliable than trying to use boundedBy on a reconstructed Tetree
        Map<ID, Point3f> allEntitiesWithPositions = entityManager.getEntitiesWithPositions();
        for (Map.Entry<ID, Point3f> entry : allEntitiesWithPositions.entrySet()) {
            Point3f pos = entry.getValue();

            // Check if position is within the region
            if (pos.x >= region.originX() && pos.x <= region.originX() + region.extent() && pos.y >= region.originY()
            && pos.y <= region.originY() + region.extent() && pos.z >= region.originZ()
            && pos.z <= region.originZ() + region.extent()) {
                uniqueEntities.add(entry.getKey());
            }
        }

        return new ArrayList<>(uniqueEntities);
    }

    /**
     * Get all entities at the given tetrahedral index (direct access)
     *
     * @param tetIndex the tetrahedral index
     * @return list of content at the index, or empty list if no entities
     */
    public List<Content> get(long tetIndex) {
        TetreeNodeImpl<ID> node = spatialIndex.get(tetIndex);
        if (node != null && !node.isEmpty()) {
            // Get all entity IDs at this location
            List<ID> entityIds = new ArrayList<>(node.getEntityIds());
            // Return the content of all entities
            return getEntities(entityIds);
        }
        return Collections.emptyList();
    }

    /**
     * Find a single node intersecting with a volume Note: This returns the first intersecting node found
     *
     * @param volume the volume to test
     * @return a single intersecting node, or null if none found
     */
    public SpatialNode<ID> intersecting(Spatial volume) {
        validatePositiveCoordinates(volume);

        // Find the first intersecting node
        return bounding(volume).findFirst().orElse(null);
    }

    /**
     * Find k nearest neighbors to a query point within tetrahedral space
     *
     * @param queryPoint  the point to search from (must have positive coordinates)
     * @param k           the number of neighbors to find
     * @param maxDistance maximum search distance
     * @return list of entity IDs sorted by distance (closest first)
     */
    public List<ID> kNearestNeighbors(Point3f queryPoint, int k, float maxDistance) {
        validatePositiveCoordinates(queryPoint);

        if (k <= 0) {
            return Collections.emptyList();
        }

        // Priority queue to keep track of k nearest entities (max heap)
        PriorityQueue<EntityDistance<ID>> candidates = new PriorityQueue<>(EntityDistance.maxHeapComparator()
                                                                           // Max heap
        );

        // Track visited entities to avoid duplicates
        Set<ID> visited = new HashSet<>();

        // Start with the tetrahedron containing the query point
        Tet initialTet = locate(queryPoint, maxDepth);
        long initialIndex = initialTet.index();

        // Use a queue for breadth-first search through tetrahedral space
        Queue<Long> toVisit = new LinkedList<>();
        Set<Long> visitedNodes = new HashSet<>();

        // Start from the initial tetrahedron
        TetreeNodeImpl<ID> initialNode = spatialIndex.get(initialIndex);
        if (initialNode != null) {
            toVisit.add(initialIndex);
        } else {
            // If exact node doesn't exist, search all nodes
            // This is less efficient but ensures we find entities
            toVisit.addAll(spatialIndex.keySet());
        }

        while (!toVisit.isEmpty()) {
            Long current = toVisit.poll();
            if (!visitedNodes.add(current)) {
                continue; // Already visited this node
            }

            TetreeNodeImpl<ID> node = spatialIndex.get(current);
            if (node == null) {
                continue;
            }

            // Check all entities in this tetrahedron
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

            // Add neighboring tetrahedra if we haven't found enough candidates
            if (candidates.size() < k || shouldContinueSearch(current, candidates)) {
                addNeighboringTetrahedra(current, toVisit, visitedNodes);
            }
        }

        // Convert to sorted list (closest first)
        List<EntityDistance<ID>> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(EntityDistance::distance));

        return sorted.stream().map(EntityDistance::entityId).collect(Collectors.toList());
    }

    /**
     * Public access to locate method for finding containing tetrahedron
     *
     * @param point the point to locate (must have positive coordinates)
     * @param level the refinement level
     * @return the Tet containing the point
     */
    public Tet locateTetrahedron(Tuple3f point, byte level) {
        validatePositiveCoordinates(new Point3f(point.x, point.y, point.z));
        return locate(point, level);
    }

    @Override
    public List<ID> lookup(Point3f position, byte level) {
        validatePositiveCoordinates(position);

        // Find the containing tetrahedron
        Tet tet = locate(position, level);
        long tetIndex = tet.index();

        TetreeNodeImpl<ID> node = spatialIndex.get(tetIndex);
        if (node == null) {
            return Collections.emptyList();
        }

        return new ArrayList<>(node.getEntityIds());
    }

    /**
     * Get the size of the spatial index (number of non-empty nodes)
     *
     * @return the number of non-empty tetrahedral nodes
     */
    public int size() {
        return (int) spatialIndex.values().stream().filter(node -> !node.isEmpty()).count();
    }

    @Override
    protected long calculateSpatialIndex(Point3f position, byte level) {
        Tet tet = locate(position, level);
        return tet.index();
    }

    @Override
    protected TetreeNodeImpl<ID> createNode() {
        return new TetreeNodeImpl<>(maxEntitiesPerNode);
    }

    @Override
    protected boolean doesNodeIntersectVolume(long tetIndex, Spatial volume) {
        Tet tet = Tet.tetrahedron(tetIndex);
        return tetrahedronIntersectsVolume(tet, volume);
    }

    @Override
    protected int getCellSizeAtLevel(byte level) {
        return Constants.lengthAtLevel(level);
    }

    @Override
    protected byte getLevelFromIndex(long index) {
        return Tet.tetLevelFromIndex(index);
    }

    // These methods are now handled by AbstractSpatialIndex

    @Override
    protected Spatial getNodeBounds(long tetIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        // Return the bounding cube of the tetrahedron
        int cellSize = Constants.lengthAtLevel(tet.l());
        return new Spatial.Cube(tet.x(), tet.y(), tet.z(), cellSize);
    }

    @Override
    protected Map<Long, TetreeNodeImpl<ID>> getSpatialIndex() {
        return spatialIndex;
    }

    @Override
    protected boolean isNodeContainedInVolume(long tetIndex, Spatial volume) {
        Tet tet = Tet.tetrahedron(tetIndex);
        return tetrahedronContainedInVolume(tet, volume);
    }

    @Override
    protected Stream<Map.Entry<Long, TetreeNodeImpl<ID>>> spatialRangeQuery(VolumeBounds bounds,
                                                                            boolean includeIntersecting) {
        // Use SFC properties to find ranges of indices that could intersect the volume
        var sfcRanges = computeSFCRanges(bounds, includeIntersecting);

        return sfcRanges.stream().flatMap(range -> {
            // Use NavigableMap.subMap for efficient range queries
            return spatialIndex.subMap(range.start, true, range.end, true).entrySet().stream();
        }).filter(entry -> {
            // Final precise filtering for elements that passed SFC range test
            var tet = Tet.tetrahedron(entry.getKey());
            if (includeIntersecting) {
                return tetrahedronIntersectsVolume(tet, createSpatialFromBounds(bounds));
            } else {
                return tetrahedronContainedInVolume(tet, createSpatialFromBounds(bounds));
            }
        });
    }

    @Override
    protected void validateSpatialConstraints(Point3f position) {
        validatePositiveCoordinates(position);
    }

    @Override
    protected void validateSpatialConstraints(Spatial volume) {
        validatePositiveCoordinates(volume);
    }

    // These methods are now handled by AbstractSpatialIndex

    /**
     * Add neighboring tetrahedra to the search queue Tetrahedral neighborhoods are more complex than cubic ones
     */
    private void addNeighboringTetrahedra(long tetIndex, Queue<Long> toVisit, Set<Long> visitedNodes) {
        Tet currentTet = Tet.tetrahedron(tetIndex);

        // For tetrahedral mesh, neighbors include:
        // 1. Other tetrahedra in the same grid cell (6 types per cell)
        // 2. Tetrahedra in adjacent grid cells

        // Add other types in the same grid cell
        for (byte type = 0; type < 6; type++) {
            if (type != currentTet.type()) {
                Tet neighbor = new Tet(currentTet.x(), currentTet.y(), currentTet.z(), currentTet.l(), type);
                long neighborIndex = neighbor.index();
                if (!visitedNodes.contains(neighborIndex) && spatialIndex.containsKey(neighborIndex)) {
                    toVisit.add(neighborIndex);
                }
            }
        }

        // Add tetrahedra in adjacent grid cells
        // This is simplified - a full implementation would consider
        // the complex tetrahedral adjacency relationships
        int cellSize = 1 << (Constants.getMaxRefinementLevel() - currentTet.l());
        int[] offsets = { -cellSize, 0, cellSize };

        for (int dx : offsets) {
            for (int dy : offsets) {
                for (int dz : offsets) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }

                    int nx = currentTet.x() + dx;
                    int ny = currentTet.y() + dy;
                    int nz = currentTet.z() + dz;

                    // Check bounds (must be positive for tetrahedral SFC)
                    if (nx >= 0 && ny >= 0 && nz >= 0) {
                        // Check all 6 types in the neighboring cell
                        for (byte type = 0; type < 6; type++) {
                            Tet neighbor = new Tet(nx, ny, nz, currentTet.l(), type);
                            long neighborIndex = neighbor.index();
                            if (!visitedNodes.contains(neighborIndex) && spatialIndex.containsKey(neighborIndex)) {
                                toVisit.add(neighborIndex);
                            }
                        }
                    }
                }
            }
        }
    }

    // Compute SFC ranges for all tetrahedra in a grid cell
    private List<SFCRange> computeCellSFCRanges(Point3f cellOrigin, byte level) {
        List<SFCRange> ranges = new ArrayList<>();

        // For a grid cell, there can be multiple tetrahedra (6 types)
        // Find the SFC indices for all tetrahedron types at this location
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet((int) cellOrigin.x, (int) cellOrigin.y, (int) cellOrigin.z, level, type);
            long index = tet.index();
            ranges.add(new SFCRange(index, index));
        }

        return ranges;
    }

    // Compute SFC ranges that could contain tetrahedra intersecting the volume
    private List<SFCRange> computeSFCRanges(VolumeBounds bounds, boolean includeIntersecting) {
        List<SFCRange> ranges = new ArrayList<>();

        // Find appropriate refinement levels for the query volume
        byte minLevel = (byte) Math.max(0, findMinimumContainingLevel(bounds) - 2);
        byte maxLevel = (byte) Math.min(Constants.getMaxRefinementLevel(), findMinimumContainingLevel(bounds) + 3);

        // Limit the number of levels to prevent memory exhaustion
        if (maxLevel - minLevel > 3) {
            maxLevel = (byte) (minLevel + 3);
        }

        for (byte level = minLevel; level <= maxLevel; level++) {
            int length = Constants.lengthAtLevel(level);

            // Calculate grid bounds at this level
            int minX = (int) Math.floor(bounds.minX() / length);
            int maxX = (int) Math.ceil(bounds.maxX() / length);
            int minY = (int) Math.floor(bounds.minY() / length);
            int maxY = (int) Math.ceil(bounds.maxY() / length);
            int minZ = (int) Math.floor(bounds.minZ() / length);
            int maxZ = (int) Math.ceil(bounds.maxZ() / length);

            // Skip this level if it would create too many cells
            int numCells = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (numCells > 100) {
                // For very large volumes, use the entire range at this level
                // This is an approximation but prevents memory exhaustion
                Point3f minPoint = new Point3f(minX * length, minY * length, minZ * length);
                Point3f maxPoint = new Point3f(maxX * length, maxY * length, maxZ * length);
                var minRanges = computeCellSFCRanges(minPoint, level);
                var maxRanges = computeCellSFCRanges(maxPoint, level);
                if (!minRanges.isEmpty() && !maxRanges.isEmpty()) {
                    ranges.add(new SFCRange(minRanges.getFirst().start, maxRanges.getLast().end));
                }
                continue;
            }

            // For reasonable volumes, compute more precise ranges
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Point3f cellPoint = new Point3f(x * length, y * length, z * length);

                        // Check if this grid cell could intersect our bounds
                        if (gridCellIntersectsBounds(cellPoint, length, bounds, includeIntersecting)) {
                            // Find the SFC ranges for all tetrahedra in this grid cell
                            var cellRanges = computeCellSFCRanges(cellPoint, level);
                            ranges.addAll(cellRanges);
                        }
                    }
                }
            }
        }

        // Merge overlapping ranges for efficiency
        return mergeRanges(ranges);
    }

    // Create a spatial volume from bounds for final filtering
    private Spatial createSpatialFromBounds(VolumeBounds bounds) {
        return new Spatial.aabb(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(),
                                bounds.maxZ());
    }

    // These methods are inherited from AbstractSpatialIndex

    // Check if a grid cell intersects with the query bounds
    private boolean gridCellIntersectsBounds(Point3f cellOrigin, int cellSize, VolumeBounds bounds,
                                             boolean includeIntersecting) {
        float cellMaxX = cellOrigin.x + cellSize;
        float cellMaxY = cellOrigin.y + cellSize;
        float cellMaxZ = cellOrigin.z + cellSize;

        if (includeIntersecting) {
            // Check for any intersection
            return !(cellMaxX < bounds.minX() || cellOrigin.x > bounds.maxX() || cellMaxY < bounds.minY()
                     || cellOrigin.y > bounds.maxY() || cellMaxZ < bounds.minZ() || cellOrigin.z > bounds.maxZ());
        } else {
            // Check for complete containment within bounds
            return cellOrigin.x >= bounds.minX() && cellMaxX <= bounds.maxX() && cellOrigin.y >= bounds.minY()
            && cellMaxY <= bounds.maxY() && cellOrigin.z >= bounds.minZ() && cellMaxZ <= bounds.maxZ();
        }
    }

    /**
     * Locate the tetrahedron containing a point at a given level
     */
    private Tet locate(Tuple3f point, byte level) {
        var length = Constants.lengthAtLevel(level);
        var c0 = new Point3i((int) (Math.floor(point.x / length) * length),
                             (int) (Math.floor(point.y / length) * length),
                             (int) (Math.floor(point.z / length) * length));
        var c7 = new Point3i(c0.x + length, c0.y + length, c0.z + length);

        var c1 = new Point3i(c0.x + length, c0.y, c0.z);

        if (Geometry.leftOfPlaneFast(c0.x, c0.y, c0.z, c7.x, c7.y, c7.z, c1.x, c1.y, c1.z, point.x, point.y, point.z)
        > 0.0) {
            var c5 = new Point3i(c0.x + length, c0.y + length, c0.z + length);
            if (Geometry.leftOfPlaneFast(c7.x, c7.y, c7.z, c5.x, c5.y, c5.z, c0.x, c0.y, c0.z, point.x, point.y,
                                         point.z) > 0.0) {
                var c4 = new Point3i(c0.x, c0.y, c0.z + length);
                if (Geometry.leftOfPlaneFast(c7.x, c7.y, c7.z, c4.x, c4.y, c4.z, c1.x, c1.y, c1.z, point.x, point.y,
                                             point.z) > 0.0) {
                    return new Tet(c0, level, 4);
                }
                return new Tet(c0, level, 5);
            } else {
                return new Tet(c0, level, 0);
            }
        } else {
            var c3 = new Point3i(c0.x + length, c0.y + length, c0.z);
            if (Geometry.leftOfPlaneFast(c7.x, c7.y, c7.z, c0.x, c0.y, c0.z, c3.x, c3.y, c3.z, point.x, point.y,
                                         point.z) > 0.0) {
                var c2 = new Point3i(c0.x, c0.y + length, c0.z);
                if (Geometry.leftOfPlaneFast(c7.x, c7.y, c7.z, c0.x, c0.y, c0.z, c2.x, c2.y, c2.z, point.x, point.y,
                                             point.z) > 0.0) {
                    return new Tet(c0, level, 2);
                } else {
                    return new Tet(c0, level, 3);
                }
            } else {
                return new Tet(c0, level, 1);
            }
        }
    }

    // Merge overlapping SFC ranges for efficiency
    private List<SFCRange> mergeRanges(List<SFCRange> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }

        ranges.sort(Comparator.comparingLong(a -> a.start));
        List<SFCRange> merged = new ArrayList<>();
        SFCRange current = ranges.getFirst();

        for (int i = 1; i < ranges.size(); i++) {
            SFCRange next = ranges.get(i);
            if (current.end + 1 >= next.start) {
                // Merge overlapping ranges
                current = new SFCRange(current.start, Math.max(current.end, next.end));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    /**
     * Check if we should continue searching based on current candidates
     */
    private boolean shouldContinueSearch(long tetIndex, PriorityQueue<EntityDistance<ID>> candidates) {
        if (candidates.isEmpty()) {
            return true;
        }

        // Get the furthest candidate distance
        EntityDistance<ID> furthest = candidates.peek();
        if (furthest == null) {
            return true;
        }

        // For tetrahedral geometry, this is more complex than cubic
        // For now, use a simple heuristic based on level
        Tet tet = Tet.tetrahedron(tetIndex);
        byte level = tet.l();
        float cellSize = Constants.lengthAtLevel(level);

        // Conservative estimate: if we're within 2x the cell size of the furthest candidate,
        // continue searching
        return cellSize < furthest.distance() * 2;
    }

    // This method has been moved to the override section

    // Check if a tetrahedron is completely contained within a volume
    private boolean tetrahedronContainedInVolume(Tet tet, Spatial volume) {
        var vertices = tet.coordinates();
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return false;
        }

        // Simple AABB containment test - all vertices must be within bounds
        for (var vertex : vertices) {
            if (vertex.x < bounds.minX() || vertex.x > bounds.maxX() || vertex.y < bounds.minY()
            || vertex.y > bounds.maxY() || vertex.z < bounds.minZ() || vertex.z > bounds.maxZ()) {
                return false;
            }
        }
        return true;
    }

    // Check if a tetrahedron intersects with a volume
    private boolean tetrahedronIntersectsVolume(Tet tet, Spatial volume) {
        var vertices = tet.coordinates();
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return false;
        }

        // Simple AABB intersection test - any vertex within bounds indicates intersection
        for (var vertex : vertices) {
            if (vertex.x >= bounds.minX() && vertex.x <= bounds.maxX() && vertex.y >= bounds.minY()
            && vertex.y <= bounds.maxY() && vertex.z >= bounds.minZ() && vertex.z <= bounds.maxZ()) {
                return true;
            }
        }

        // Also check if the volume center is inside the tetrahedron
        var centerPoint = new Point3f((bounds.minX() + bounds.maxX()) / 2, (bounds.minY() + bounds.maxY()) / 2,
                                      (bounds.minZ() + bounds.maxZ()) / 2);
        return tet.contains(centerPoint);
    }

    /**
     * Validate that coordinates are positive (tetrahedral SFC requirement)
     */
    private void validatePositiveCoordinates(Point3f point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException("Tetree requires positive coordinates. Got: " + point);
        }
    }

    private void validatePositiveCoordinates(Tuple3i point) {
        if (point.x < 0 || point.y < 0 || point.z < 0) {
            throw new IllegalArgumentException(
            "Tetree requires positive coordinates. Got: (" + point.x + ", " + point.y + ", " + point.z + ")");
        }
    }

    private void validatePositiveCoordinates(Spatial volume) {
        // Check origin coordinates based on volume type
        switch (volume) {
            case Spatial.Cube cube -> {
                if (cube.originX() < 0 || cube.originY() < 0 || cube.originZ() < 0) {
                    throw new IllegalArgumentException(
                    "Tetree requires positive coordinates. Cube origin: (" + cube.originX() + ", " + cube.originY()
                    + ", " + cube.originZ() + ")");
                }
            }
            case Spatial.Sphere sphere -> {
                // For sphere, check if any part extends into negative space
                if (sphere.centerX() - sphere.radius() < 0 || sphere.centerY() - sphere.radius() < 0
                || sphere.centerZ() - sphere.radius() < 0) {
                    throw new IllegalArgumentException(
                    "Tetree requires positive coordinates. Sphere extends into negative space");
                }
            }
            // Add other spatial types as needed
            default -> {
                // For now, allow other types but log warning
            }
        }
    }

    // Record to represent SFC index ranges
    private record SFCRange(long start, long end) {
    }

}
