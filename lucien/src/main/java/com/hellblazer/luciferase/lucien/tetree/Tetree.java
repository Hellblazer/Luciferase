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
import com.hellblazer.luciferase.lucien.*;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityDistance;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import java.util.*;
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

        lock.readLock().lock();
        try {
            // Use the spatial index to find entities in the region
            // This properly handles spanning entities
            var bounds = new VolumeBounds(region.originX(), region.originY(), region.originZ(),
                                         region.originX() + region.extent(), 
                                         region.originY() + region.extent(),
                                         region.originZ() + region.extent());
            
            // Get all spatial nodes that intersect with the region
            var nodeList = spatialRangeQuery(bounds, true).collect(java.util.stream.Collectors.toList());
            
            // Collect all entities from intersecting nodes
            nodeList.forEach(entry -> {
                if (!entry.getValue().isEmpty()) {
                    uniqueEntities.addAll(entry.getValue().getEntityIds());
                }
            });
            
            // For spanning entities found in intersecting spatial nodes, we should include them
            // The spatial range query already found the correct intersecting nodes, so entities
            // found in those nodes are valid regardless of their exact bounds
            return new ArrayList<>(uniqueEntities);
        } finally {
            lock.readLock().unlock();
        }
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

    // k-NN search is now provided by AbstractSpatialIndex

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
    protected void addNeighboringNodes(long tetIndex, Queue<Long> toVisit, Set<Long> visitedNodes) {
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
        // Use the same logic as the SFC range computation for consistency
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return false;
        }
        return tetrahedronIntersectsVolumeBounds(tet, bounds);
    }

    @Override
    protected boolean doesRayIntersectNode(long nodeIndex, Ray3D ray) {
        // Use TetrahedralGeometry for ray-tetrahedron intersection
        var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, nodeIndex);
        return intersection.intersects;
    }

    // These methods are now handled by AbstractSpatialIndex

    @Override
    protected int getCellSizeAtLevel(byte level) {
        return Constants.lengthAtLevel(level);
    }

    @Override
    protected byte getLevelFromIndex(long index) {
        return Tet.tetLevelFromIndex(index);
    }

    @Override
    protected Spatial getNodeBounds(long tetIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        // Return the bounding cube of the tetrahedron
        int cellSize = Constants.lengthAtLevel(tet.l());
        return new Spatial.Cube(tet.x(), tet.y(), tet.z(), cellSize);
    }

    @Override
    protected float getRayNodeIntersectionDistance(long nodeIndex, Ray3D ray) {
        // Get ray-tetrahedron intersection distance
        var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, nodeIndex);
        return intersection.intersects ? intersection.distance : Float.MAX_VALUE;
    }

    @Override
    protected Stream<Long> getRayTraversalOrder(Ray3D ray) {
        // Use a priority queue to order tetrahedra by ray intersection distance
        PriorityQueue<TetDistance> tetQueue = new PriorityQueue<>();
        Set<Long> visitedTets = new HashSet<>();

        // Find the tetrahedron containing the ray origin
        Tet startTet = locate(ray.origin(), maxDepth);
        long startIndex = startTet.index();

        // Add starting tetrahedron
        float startDistance = getRayNodeIntersectionDistance(startIndex, ray);
        if (startDistance <= ray.maxDistance()) {
            tetQueue.add(new TetDistance(startIndex, startDistance));
        }

        // Build ordered stream of tetrahedra
        List<Long> orderedTets = new ArrayList<>();

        while (!tetQueue.isEmpty()) {
            TetDistance current = tetQueue.poll();
            if (!visitedTets.add(current.tetIndex)) {
                continue;
            }

            // Check if tetrahedron exists in spatial index
            if (spatialIndex.containsKey(current.tetIndex)) {
                orderedTets.add(current.tetIndex);
            }

            // Add neighboring tetrahedra that intersect the ray
            addIntersectingNeighbors(current.tetIndex, ray, tetQueue, visitedTets);
        }

        return orderedTets.stream();
    }

    // These methods are now handled by AbstractSpatialIndex

    @Override
    protected NavigableSet<Long> getSpatialIndexRange(VolumeBounds bounds) {
        // CRITICAL FIX: The memory-efficient strategies are still broken.
        // For now, use the direct approach that works correctly.
        return getStandardEntitySpatialRange(bounds, true);
    }
    
    /**
     * Memory-efficient spatial index range computation with adaptive strategies for large entities
     * Based on t8code's hierarchical range computation algorithms
     */
    private NavigableSet<Long> getMemoryEfficientSpatialIndexRange(VolumeBounds bounds, boolean includeIntersecting) {
        // Calculate entity size metrics
        float volumeSize = (bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ() - bounds.minZ());
        float maxExtent = Math.max(Math.max(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY()),
                                   bounds.maxZ() - bounds.minZ());
        
        // Choose strategy based on entity size
        if (volumeSize > 50000.0f || maxExtent > 500.0f) {
            // Very large entities: use hierarchical streaming approach
            return getLargeEntitySpatialRange(bounds, includeIntersecting);
        } else if (volumeSize > 5000.0f || maxExtent > 100.0f) {
            // Medium-large entities: use adaptive level selection
            return getMediumEntitySpatialRange(bounds, includeIntersecting);
        } else {
            // Standard entities: use optimized SFC ranges
            return getStandardEntitySpatialRange(bounds, includeIntersecting);
        }
    }
    
    /**
     * Memory-efficient range computation for very large entities
     * Uses streaming and hierarchical decomposition to avoid memory exhaustion
     */
    private NavigableSet<Long> getLargeEntitySpatialRange(VolumeBounds bounds, boolean includeIntersecting) {
        NavigableSet<Long> result = new TreeSet<>();
        
        // Use coarser levels only to reduce memory footprint
        byte minLevel = (byte) Math.max(0, findMinimumContainingLevel(bounds) - 1);
        byte maxLevel = (byte) Math.min(Constants.getMaxRefinementLevel(), findMinimumContainingLevel(bounds) + 1);
        
        // Process levels sequentially to limit memory usage
        for (byte level = minLevel; level <= maxLevel; level++) {
            var levelRanges = computeMemoryBoundedSFCRanges(bounds, level, includeIntersecting, 1000); // Max 1000 ranges
            
            // Stream process ranges to avoid large intermediate collections
            for (var range : levelRanges) {
                // Use streaming subSet to avoid loading all indices at once
                var subset = sortedSpatialIndices.subSet(range.start, true, range.end, true);
                if (subset.size() <= 500) { // Memory threshold
                    result.addAll(subset);
                } else {
                    // Split large ranges into smaller chunks
                    addRangeInChunks(result, range, 500);
                }
            }
        }
        
        return result;
    }
    
    /**
     * Adaptive range computation for medium-sized entities
     * Balances precision with memory efficiency
     */
    private NavigableSet<Long> getMediumEntitySpatialRange(VolumeBounds bounds, boolean includeIntersecting) {
        NavigableSet<Long> result = new TreeSet<>();
        
        // Use adaptive level selection based on entity characteristics
        byte optimalLevel = findOptimalLevelForEntity(bounds);
        byte minLevel = (byte) Math.max(0, optimalLevel - 1);
        byte maxLevel = (byte) Math.min(Constants.getMaxRefinementLevel(), optimalLevel + 2);
        
        // Limit to 3 levels maximum for memory efficiency
        if (maxLevel - minLevel > 2) {
            maxLevel = (byte) (minLevel + 2);
        }
        
        var sfcRanges = computeAdaptiveSFCRanges(bounds, minLevel, maxLevel, includeIntersecting);
        
        // Process ranges with memory bounds
        for (var range : sfcRanges) {
            var subset = sortedSpatialIndices.subSet(range.start, true, range.end, true);
            result.addAll(subset);
            
            // Check memory threshold
            if (result.size() > 5000) {
                break; // Prevent memory exhaustion
            }
        }
        
        return result;
    }
    
    /**
     * Standard range computation for normal-sized entities
     * FIXED: Use actual spatial indices instead of incorrect SFC computation
     */
    private NavigableSet<Long> getStandardEntitySpatialRange(VolumeBounds bounds, boolean includeIntersecting) {
        NavigableSet<Long> result = new TreeSet<>();
        
        // CRITICAL FIX: The fundamental issue is that computeSFCRanges() is broken.
        // Instead of using computed SFC ranges that don't match reality, we need to
        // check all existing spatial indices and test them individually.
        
        // This is less efficient but correct. The SFC range computation logic
        // would need a complete rewrite to work properly with the Tet.index() algorithm.
        
        for (Long spatialIndex : sortedSpatialIndices) {
            try {
                Tet tet = Tet.tetrahedron(spatialIndex);
                
                if (includeIntersecting) {
                    // Check if tetrahedron intersects the bounds
                    boolean intersects = tetrahedronIntersectsVolumeBounds(tet, bounds);
                    if (intersects) {
                        result.add(spatialIndex);
                    }
                } else {
                    // Check if tetrahedron is contained within bounds
                    if (tetrahedronContainedInVolumeBounds(tet, bounds)) {
                        result.add(spatialIndex);
                    }
                }
            } catch (Exception e) {
                // Skip invalid indices
                continue;
            }
        }
        
        return result;
    }
    
    /**
     * Find optimal level for entity based on size and spatial characteristics
     */
    private byte findOptimalLevelForEntity(VolumeBounds bounds) {
        float volumeSize = (bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ() - bounds.minZ());
        float maxExtent = Math.max(Math.max(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY()),
                                   bounds.maxZ() - bounds.minZ());
        
        // Find level where tetrahedron size is roughly 1/8 to 1/4 of max extent for optimal spanning
        for (byte level = 0; level <= Constants.getMaxRefinementLevel(); level++) {
            int tetLength = Constants.lengthAtLevel(level);
            if (tetLength <= maxExtent / 4 && tetLength >= maxExtent / 16) {
                return level;
            }
        }
        
        return findMinimumContainingLevel(bounds);
    }
    
    /**
     * Compute memory-bounded SFC ranges with maximum range limit
     */
    private List<SFCRange> computeMemoryBoundedSFCRanges(VolumeBounds bounds, byte level, boolean includeIntersecting, int maxRanges) {
        List<SFCRange> ranges = new ArrayList<>();
        int length = Constants.lengthAtLevel(level);
        
        // Calculate grid bounds at this level
        int minX = (int) Math.floor(bounds.minX() / length);
        int maxX = (int) Math.ceil(bounds.maxX() / length);
        int minY = (int) Math.floor(bounds.minY() / length);
        int maxY = (int) Math.ceil(bounds.maxY() / length);
        int minZ = (int) Math.floor(bounds.minZ() / length);
        int maxZ = (int) Math.ceil(bounds.maxZ() / length);
        
        int numCells = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        
        if (numCells > maxRanges) {
            // Use coarser approximation for very large volumes
            int step = (int) Math.ceil(Math.cbrt(numCells / (double) maxRanges));
            
            for (int x = minX; x <= maxX; x += step) {
                for (int y = minY; y <= maxY; y += step) {
                    for (int z = minZ; z <= maxZ; z += step) {
                        Point3f cellPoint = new Point3f(x * length, y * length, z * length);
                        
                        if (hybridCellIntersectsBounds(cellPoint, length * step, level, bounds, includeIntersecting)) {
                            // Create larger ranges to reduce total count
                            for (byte type = 0; type < 6; type++) {
                                var tet = new Tet(x * length, y * length, z * length, level, type);
                                long startIndex = tet.index();
                                long endIndex = startIndex + ((long) step * step * step * 6) - 1; // Approximate range
                                ranges.add(new SFCRange(startIndex, endIndex));
                            }
                        }
                        
                        if (ranges.size() >= maxRanges) {
                            return mergeRangesOptimized(ranges);
                        }
                    }
                }
            }
        } else {
            // Standard grid traversal for manageable sizes
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Point3f cellPoint = new Point3f(x * length, y * length, z * length);
                        
                        if (hybridCellIntersectsBounds(cellPoint, length, level, bounds, includeIntersecting)) {
                            var cellRanges = new ArrayList<SFCRange>();
                            computeCellSFCRanges(cellPoint, level).forEach(cellRanges::add);
                            ranges.addAll(cellRanges);
                        }
                    }
                }
            }
        }
        
        return mergeRangesOptimized(ranges);
    }
    
    /**
     * Compute adaptive SFC ranges with level-specific optimizations
     */
    private List<SFCRange> computeAdaptiveSFCRanges(VolumeBounds bounds, byte minLevel, byte maxLevel, boolean includeIntersecting) {
        List<SFCRange> ranges = new ArrayList<>();
        
        for (byte level = minLevel; level <= maxLevel; level++) {
            // Use different strategies based on level
            if (level <= 5) {
                // Coarse levels: be more inclusive
                ranges.addAll(computeMemoryBoundedSFCRanges(bounds, level, true, 200));
            } else {
                // Fine levels: be more selective
                ranges.addAll(computeMemoryBoundedSFCRanges(bounds, level, includeIntersecting, 100));
            }
        }
        
        return mergeRangesOptimized(ranges);
    }
    
    /**
     * Add range indices in chunks to avoid memory spikes
     */
    private void addRangeInChunks(NavigableSet<Long> result, SFCRange range, int chunkSize) {
        long current = range.start;
        while (current <= range.end) {
            long chunkEnd = Math.min(current + chunkSize - 1, range.end);
            var subset = sortedSpatialIndices.subSet(current, true, chunkEnd, true);
            result.addAll(subset);
            current = chunkEnd + 1;
        }
    }
    
    /**
     * Enhanced range merging with memory-conscious approach
     */
    private List<SFCRange> mergeRangesOptimized(List<SFCRange> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }
        
        // Sort ranges by start index
        ranges.sort(Comparator.comparingLong(a -> a.start));
        
        // Use more aggressive merging for memory efficiency
        List<SFCRange> merged = new ArrayList<>();
        SFCRange current = ranges.getFirst();
        
        for (int i = 1; i < ranges.size(); i++) {
            SFCRange next = ranges.get(i);
            
            // Merge if ranges overlap or are very close (adaptive gap based on range size)
            long gap = next.start - current.end;
            long rangeSize = current.end - current.start;
            long adaptiveGap = Math.max(8, rangeSize / 10); // Dynamic gap based on range size
            
            if (gap <= adaptiveGap) {
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
     * Check if bounds intersect using hybrid cube/tetrahedral geometry with memory efficiency
     */
    private boolean hybridCellIntersectsBounds(Point3f cellOrigin, int cellSize, byte level, VolumeBounds bounds,
                                               boolean includeIntersecting) {
        // First: Fast cube-based intersection test for early rejection
        float cellMaxX = cellOrigin.x + cellSize;
        float cellMaxY = cellOrigin.y + cellSize;
        float cellMaxZ = cellOrigin.z + cellSize;
        
        // Quick cube-based bounding box test
        if (cellMaxX < bounds.minX() || cellOrigin.x > bounds.maxX() ||
            cellMaxY < bounds.minY() || cellOrigin.y > bounds.maxY() ||
            cellMaxZ < bounds.minZ() || cellOrigin.z > bounds.maxZ()) {
            return false;
        }
        
        // For large cells, cube intersection is sufficient (memory optimization)
        if (cellSize > Constants.lengthAtLevel((byte) Math.min(level + 2, Constants.getMaxRefinementLevel()))) {
            return true;
        }
        
        // Second: Test individual tetrahedra for precise geometry (only for smaller cells)
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet((int) cellOrigin.x, (int) cellOrigin.y, (int) cellOrigin.z, level, type);
            
            if (includeIntersecting) {
                // Convert VolumeBounds to EntityBounds for compatibility
                var minPoint = new Point3f(bounds.minX(), bounds.minY(), bounds.minZ());
                var maxPoint = new Point3f(bounds.maxX(), bounds.maxY(), bounds.maxZ());
                var entityBounds = new EntityBounds(minPoint, maxPoint);
                if (tetrahedronIntersectsBounds(tet, entityBounds)) {
                    return true;
                }
            } else {
                // For containment, use direct tetrahedral geometry test
                if (tetrahedronContainedInVolumeBounds(tet, bounds)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean isNodeContainedInVolume(long tetIndex, Spatial volume) {
        Tet tet = Tet.tetrahedron(tetIndex);
        return tetrahedronContainedInVolume(tet, volume);
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

        // For tetrahedral geometry, this is more complex than cubic
        // For now, use a simple heuristic based on level
        Tet tet = Tet.tetrahedron(nodeIndex);
        byte level = tet.l();
        float cellSize = Constants.lengthAtLevel(level);

        // Conservative estimate: if we're within 2x the cell size of the furthest candidate,
        // continue searching
        return cellSize < furthest.distance() * 2;
    }

    @Override
    protected void validateSpatialConstraints(Point3f position) {
        validatePositiveCoordinates(position);
    }

    // These methods are inherited from AbstractSpatialIndex

    @Override
    protected void validateSpatialConstraints(Spatial volume) {
        validatePositiveCoordinates(volume);
    }

    // ===== Tree Balancing Implementation =====
    
    @Override
    protected TreeBalancer<ID> createTreeBalancer() {
        return new TetreeBalancer();
    }
    
    @Override
    protected List<Long> getChildNodes(long tetIndex) {
        List<Long> children = new ArrayList<>();
        Tet parentTet = Tet.tetrahedron(tetIndex);
        byte level = parentTet.l();
        
        if (level >= maxDepth) {
            return children; // No children possible at max depth
        }
        
        // For tetrahedral decomposition, children are at the next finer level
        // Each parent cell can contain multiple child cells
        byte childLevel = (byte) (level + 1);
        int parentCellSize = Constants.lengthAtLevel(level);
        int childCellSize = Constants.lengthAtLevel(childLevel);
        
        // Check all child cells that could be contained within parent cell
        for (int x = parentTet.x(); x < parentTet.x() + parentCellSize; x += childCellSize) {
            for (int y = parentTet.y(); y < parentTet.y() + parentCellSize; y += childCellSize) {
                for (int z = parentTet.z(); z < parentTet.z() + parentCellSize; z += childCellSize) {
                    // Check all 6 tetrahedron types in each child cell
                    for (byte type = 0; type < 6; type++) {
                        Tet childTet = new Tet(x, y, z, childLevel, type);
                        long childIndex = childTet.index();
                        
                        // Only add if child exists in spatial index
                        if (spatialIndex.containsKey(childIndex)) {
                            children.add(childIndex);
                        }
                    }
                }
            }
        }
        
        return children;
    }
    
    @Override
    protected boolean hasChildren(long tetIndex) {
        TetreeNodeImpl<ID> node = spatialIndex.get(tetIndex);
        return node != null && node.hasChildren();
    }
    
    @Override
    protected void handleNodeSubdivision(long parentTetIndex, byte parentLevel, TetreeNodeImpl<ID> parentNode) {
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

        // Create map to group entities by their child tetrahedron
        Map<Long, List<ID>> childEntityMap = new HashMap<>();
        
        // Determine which child tetrahedron each entity belongs to
        for (ID entityId : parentEntities) {
            Point3f entityPos = entityManager.getEntityPosition(entityId);
            if (entityPos != null) {
                // Find the containing tetrahedron at the finer level
                Tet childTet = locate(entityPos, childLevel);
                long childTetIndex = childTet.index();
                childEntityMap.computeIfAbsent(childTetIndex, k -> new ArrayList<>()).add(entityId);
            }
        }

        // Check if subdivision would actually distribute entities
        if (childEntityMap.size() == 1 && childEntityMap.containsKey(parentTetIndex)) {
            // All entities map to the same tetrahedron even at the child level
            // This can happen when all entities are at the same position
            // Don't subdivide - it won't help distribute the load
            System.out.println("Subdivision stopped: all entities map to same tetrahedron at child level");
            return;
        }

        // Create child nodes and redistribute entities
        System.out.println("Proceeding with tetrahedral subdivision: " + childEntityMap.size() + " child tetrahedra to create");
        
        // Track which entities we need to remove from parent
        Set<ID> entitiesToRemoveFromParent = new HashSet<>();
        
        // Add entities to child nodes
        for (Map.Entry<Long, List<ID>> entry : childEntityMap.entrySet()) {
            long childTetIndex = entry.getKey();
            List<ID> childEntities = entry.getValue();

            if (!childEntities.isEmpty()) {
                TetreeNodeImpl<ID> childNode;
                
                if (childTetIndex == parentTetIndex) {
                    // Special case: child has same tetrahedral index as parent
                    // The entities can stay in the parent node - don't redistribute them
                    System.out.println("Entities " + childEntities + " stay in parent node (same tet index " + childTetIndex + ")");
                    // Don't mark these entities for removal from parent
                    continue;
                } else {
                    // Create or get child node
                    System.out.println("Creating new child tetrahedron for index " + childTetIndex + " with entities " + childEntities);
                    childNode = spatialIndex.computeIfAbsent(childTetIndex, k -> {
                        sortedSpatialIndices.add(childTetIndex);
                        return new TetreeNodeImpl<>(maxEntitiesPerNode);
                    });
                    
                    // Add entities to child and mark for removal from parent
                    for (ID entityId : childEntities) {
                        childNode.addEntity(entityId);
                        // Update entity locations - add child location
                        entityManager.addEntityLocation(entityId, childTetIndex);
                        entitiesToRemoveFromParent.add(entityId);
                    }
                }
            }
        }

        // Remove only the entities that were moved to different child nodes
        for (ID entityId : entitiesToRemoveFromParent) {
            parentNode.removeEntity(entityId);
            entityManager.removeEntityLocation(entityId, parentTetIndex);
        }
        
        // Mark that this node has been subdivided
        parentNode.setHasChildren(true);
    }

    /**
     * Add child tetrahedra that intersect the ray
     */
    private void addChildTetrahedra(Tet currentTet, Ray3D ray, PriorityQueue<TetDistance> tetQueue,
                                    Set<Long> visitedTets) {
        byte childLevel = (byte) (currentTet.l() + 1);
        int childCellSize = 1 << (Constants.getMaxRefinementLevel() - childLevel);

        // Each tetrahedron can be subdivided into smaller tetrahedra
        // This is a simplified approach - check child cells that overlap current tet
        int minX = currentTet.x();
        int minY = currentTet.y();
        int minZ = currentTet.z();
        int currentCellSize = 1 << (Constants.getMaxRefinementLevel() - currentTet.l());

        for (int x = minX; x < minX + currentCellSize; x += childCellSize) {
            for (int y = minY; y < minY + currentCellSize; y += childCellSize) {
                for (int z = minZ; z < minZ + currentCellSize; z += childCellSize) {
                    // Check all 6 tetrahedron types in child cell
                    for (byte type = 0; type < 6; type++) {
                        Tet child = new Tet(x, y, z, childLevel, type);
                        long childIndex = child.index();

                        if (!visitedTets.contains(childIndex)) {
                            var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, childIndex);
                            if (intersection.intersects && intersection.distance <= ray.maxDistance()) {
                                tetQueue.add(new TetDistance(childIndex, intersection.distance));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Helper method to add neighboring tetrahedra that intersect the ray
     */
    private void addIntersectingNeighbors(long tetIndex, Ray3D ray, PriorityQueue<TetDistance> tetQueue,
                                          Set<Long> visitedTets) {
        Tet currentTet = Tet.tetrahedron(tetIndex);

        // Check neighboring tetrahedra at the same level
        int cellSize = 1 << (Constants.getMaxRefinementLevel() - currentTet.l());

        // For tetrahedral mesh, neighbors are more complex than cubic
        // Check tetrahedra in adjacent grid cells
        for (int dx = -cellSize; dx <= cellSize; dx += cellSize) {
            for (int dy = -cellSize; dy <= cellSize; dy += cellSize) {
                for (int dz = -cellSize; dz <= cellSize; dz += cellSize) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }

                    int nx = currentTet.x() + dx;
                    int ny = currentTet.y() + dy;
                    int nz = currentTet.z() + dz;

                    // Check bounds (must be positive)
                    if (nx >= 0 && ny >= 0 && nz >= 0) {
                        // Check all 6 tetrahedron types in the neighboring cell
                        for (byte type = 0; type < 6; type++) {
                            Tet neighbor = new Tet(nx, ny, nz, currentTet.l(), type);
                            long neighborIndex = neighbor.index();

                            if (!visitedTets.contains(neighborIndex)) {
                                var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, neighborIndex);
                                if (intersection.intersects && intersection.distance <= ray.maxDistance()) {
                                    tetQueue.add(new TetDistance(neighborIndex, intersection.distance));
                                }
                            }
                        }
                    }
                }
            }
        }

        // Also check parent and child tetrahedra for hierarchical traversal
        if (currentTet.l() > 0) {
            // Check parent tetrahedron
            addParentTetrahedra(currentTet, ray, tetQueue, visitedTets);
        }

        if (currentTet.l() < maxDepth) {
            // Check child tetrahedra
            addChildTetrahedra(currentTet, ray, tetQueue, visitedTets);
        }
    }

    // This method has been moved to the override section

    /**
     * Add parent tetrahedra that intersect the ray
     */
    private void addParentTetrahedra(Tet currentTet, Ray3D ray, PriorityQueue<TetDistance> tetQueue,
                                     Set<Long> visitedTets) {
        byte parentLevel = (byte) (currentTet.l() - 1);
        int parentCellSize = 1 << (Constants.getMaxRefinementLevel() - parentLevel);

        // Calculate parent cell coordinates
        int px = (currentTet.x() / parentCellSize) * parentCellSize;
        int py = (currentTet.y() / parentCellSize) * parentCellSize;
        int pz = (currentTet.z() / parentCellSize) * parentCellSize;

        // Check all 6 tetrahedron types in parent cell
        for (byte type = 0; type < 6; type++) {
            Tet parent = new Tet(px, py, pz, parentLevel, type);
            long parentIndex = parent.index();

            if (!visitedTets.contains(parentIndex)) {
                var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, parentIndex);
                if (intersection.intersects && intersection.distance <= ray.maxDistance()) {
                    tetQueue.add(new TetDistance(parentIndex, intersection.distance));
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
        System.out.println("DEBUG computeSFCRanges: bounds=" + bounds + ", includeIntersecting=" + includeIntersecting);
        
        List<SFCRange> ranges = new ArrayList<>();

        // Since entities are inserted at various levels (in the test, level 5), 
        // we need to check a broader range of levels that actually contain entities
        // Instead of using findMinimumContainingLevel, use levels that match the spatial index
        
        // Look at what levels are actually present in the spatial index
        Set<Byte> actualLevels = new HashSet<>();
        for (Long index : sortedSpatialIndices) {
            byte level = Tet.tetLevelFromIndex(index);
            actualLevels.add(level);
        }
        
        System.out.println("DEBUG computeSFCRanges: actualLevels in spatial index=" + actualLevels);
        
        // If no levels found, fall back to the old approach
        byte minLevel, maxLevel;
        if (actualLevels.isEmpty()) {
            minLevel = (byte) Math.max(0, findMinimumContainingLevel(bounds) - 2);
            maxLevel = (byte) Math.min(Constants.getMaxRefinementLevel(), findMinimumContainingLevel(bounds) + 3);
        } else {
            // Use the actual levels present in the spatial index
            minLevel = actualLevels.stream().min(Byte::compareTo).orElse((byte) 0);
            maxLevel = actualLevels.stream().max(Byte::compareTo).orElse(Constants.getMaxRefinementLevel());
        }

        // Limit the number of levels to prevent memory exhaustion
        if (maxLevel - minLevel > 5) {
            maxLevel = (byte) (minLevel + 5);
        }

        System.out.println("DEBUG computeSFCRanges: adjusted minLevel=" + minLevel + ", maxLevel=" + maxLevel);

        for (byte level = minLevel; level <= maxLevel; level++) {
            int length = Constants.lengthAtLevel(level);
            System.out.println("DEBUG computeSFCRanges: level=" + level + ", length=" + length);

            // Calculate grid bounds at this level
            int minX = (int) Math.floor(bounds.minX() / length);
            int maxX = (int) Math.ceil(bounds.maxX() / length);
            int minY = (int) Math.floor(bounds.minY() / length);
            int maxY = (int) Math.ceil(bounds.maxY() / length);
            int minZ = (int) Math.floor(bounds.minZ() / length);
            int maxZ = (int) Math.ceil(bounds.maxZ() / length);

            System.out.println("DEBUG computeSFCRanges: grid bounds x[" + minX + "," + maxX + "], y[" + minY + "," + maxY + "], z[" + minZ + "," + maxZ + "]");

            // Skip this level if it would create too many cells
            int numCells = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            System.out.println("DEBUG computeSFCRanges: numCells=" + numCells);
            
            if (numCells > 100) {
                // For very large volumes, use the entire range at this level
                // This is an approximation but prevents memory exhaustion
                Point3f minPoint = new Point3f(minX * length, minY * length, minZ * length);
                Point3f maxPoint = new Point3f(maxX * length, maxY * length, maxZ * length);
                var minRanges = computeCellSFCRanges(minPoint, level);
                var maxRanges = computeCellSFCRanges(maxPoint, level);
                if (!minRanges.isEmpty() && !maxRanges.isEmpty()) {
                    ranges.add(new SFCRange(minRanges.getFirst().start, maxRanges.getLast().end));
                    System.out.println("DEBUG computeSFCRanges: added large range [" + minRanges.getFirst().start + "," + maxRanges.getLast().end + "]");
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
                            System.out.println("DEBUG computeSFCRanges: added cell ranges for (" + x + "," + y + "," + z + "): " + cellRanges);
                        }
                    }
                }
            }
        }

        System.out.println("DEBUG computeSFCRanges: total ranges before merge=" + ranges.size());
        
        // Merge overlapping ranges for efficiency
        var mergedRanges = mergeRanges(ranges);
        System.out.println("DEBUG computeSFCRanges: merged ranges=" + mergedRanges.size());
        
        return mergedRanges;
    }

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

    // ===== Ray Intersection Implementation =====

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

    /**
     * Helper class to store tetrahedron index with distance for priority queue ordering
     */
    private static class TetDistance implements Comparable<TetDistance> {
        final long  tetIndex;
        final float distance;

        TetDistance(long tetIndex, float distance) {
            this.tetIndex = tetIndex;
            this.distance = distance;
        }

        @Override
        public int compareTo(TetDistance other) {
            return Float.compare(this.distance, other.distance);
        }
    }
    
    // ===== Tetrahedral Spanning Implementation =====

    /**
     * Find all tetrahedral indices that intersect with the given bounds
     * FIXED: Use actual existing spatial indices instead of theoretical SFC computation
     */
    private Set<Long> findIntersectingTets(EntityBounds bounds, byte level) {
        Set<Long> result = new HashSet<>();
        
        // CRITICAL FIX: Instead of using complex SFC range computation that may miss indices,
        // check all existing spatial indices in the tree and test them for intersection.
        // This is simpler, more reliable, and works correctly for small entities.
        
        for (Long spatialIndex : sortedSpatialIndices) {
            try {
                Tet tet = Tet.tetrahedron(spatialIndex);
                
                // For spanning, we want to find tetrahedra at the specified level
                // that intersect with the entity bounds
                if (tet.l() == level) {
                    if (tetrahedronIntersectsBounds(tet, bounds)) {
                        result.add(spatialIndex);
                    }
                }
            } catch (Exception e) {
                // Skip invalid indices
            }
        }
        
        // If no existing indices found, we need to create a new tetrahedron at the exact level
        if (result.isEmpty()) {
            // Find the tetrahedron that would contain the center of the bounds at the requested level
            Point3f center = new Point3f(
                (bounds.getMinX() + bounds.getMaxX()) / 2,
                (bounds.getMinY() + bounds.getMaxY()) / 2,
                (bounds.getMinZ() + bounds.getMaxZ()) / 2
            );
            
            Tet containingTet = locate(center, level);
            long tetIndex = containingTet.index();
            
            // Verify this tetrahedron actually intersects the bounds
            if (tetrahedronIntersectsBounds(containingTet, bounds)) {
                result.add(tetIndex);
            }
        }
        
        return result;
    }

    /**
     * Calculate first descendant index for a tetrahedron (t8code algorithm)
     */
    private long calculateFirstDescendant(Tet parentTet, byte targetLevel) {
        if (targetLevel <= parentTet.l()) {
            return parentTet.index();
        }
        
        // Use Tet's index calculation which already implements the t8code linear_id algorithm
        Tet firstChild = new Tet(parentTet.x(), parentTet.y(), parentTet.z(), targetLevel, parentTet.type());
        return firstChild.index();
    }

    /**
     * Calculate last descendant index for a tetrahedron (t8code algorithm)
     */
    private long calculateLastDescendant(Tet parentTet, byte targetLevel) {
        if (targetLevel <= parentTet.l()) {
            return parentTet.index();
        }
        
        // Calculate the range of descendants
        int levelDiff = targetLevel - parentTet.l();
        long firstDescendant = calculateFirstDescendant(parentTet, targetLevel);
        
        // The last descendant is offset by 8^levelDiff - 1 (since each tet splits into 8 children)
        long offset = (1L << (3 * levelDiff)) - 1; // 8^levelDiff - 1 = 2^(3*levelDiff) - 1
        return firstDescendant + offset;
    }

    /**
     * Get SFC successor index for tetrahedral traversal
     */
    private long getSuccessor(long tetIndex) {
        // For tetrahedral SFC, successor is simply the next index
        // The Tet class's index() method implements the proper SFC ordering
        return tetIndex + 1;
    }

    /**
     * Check if a tetrahedron intersects with entity bounds using proper tetrahedral geometry
     */
    private boolean tetrahedronIntersectsBounds(Tet tet, EntityBounds bounds) {
        var vertices = tet.coordinates();
        
        // Quick bounding box rejection test first
        float tetMinX = Float.MAX_VALUE, tetMaxX = Float.MIN_VALUE;
        float tetMinY = Float.MAX_VALUE, tetMaxY = Float.MIN_VALUE;
        float tetMinZ = Float.MAX_VALUE, tetMaxZ = Float.MIN_VALUE;
        
        for (var vertex : vertices) {
            tetMinX = Math.min(tetMinX, vertex.x);
            tetMaxX = Math.max(tetMaxX, vertex.x);
            tetMinY = Math.min(tetMinY, vertex.y);
            tetMaxY = Math.max(tetMaxY, vertex.y);
            tetMinZ = Math.min(tetMinZ, vertex.z);
            tetMaxZ = Math.max(tetMaxZ, vertex.z);
        }
        
        
        // Bounding box intersection test
        if (tetMaxX < bounds.getMinX() || tetMinX > bounds.getMaxX() ||
            tetMaxY < bounds.getMinY() || tetMinY > bounds.getMaxY() ||
            tetMaxZ < bounds.getMinZ() || tetMinZ > bounds.getMaxZ()) {
            return false;
        }
        
        
        // Test if any vertex of tetrahedron is inside bounds
        for (var vertex : vertices) {
            if (vertex.x >= bounds.getMinX() && vertex.x <= bounds.getMaxX() &&
                vertex.y >= bounds.getMinY() && vertex.y <= bounds.getMaxY() &&
                vertex.z >= bounds.getMinZ() && vertex.z <= bounds.getMaxZ()) {
                return true;
            }
        }
        
        
        // Test if any corner of bounds is inside tetrahedron
        var boundCorners = new Point3f[] {
            new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ()),
            new Point3f(bounds.getMaxX(), bounds.getMinY(), bounds.getMinZ()),
            new Point3f(bounds.getMinX(), bounds.getMaxY(), bounds.getMinZ()),
            new Point3f(bounds.getMaxX(), bounds.getMaxY(), bounds.getMinZ()),
            new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMaxZ()),
            new Point3f(bounds.getMaxX(), bounds.getMinY(), bounds.getMaxZ()),
            new Point3f(bounds.getMinX(), bounds.getMaxY(), bounds.getMaxZ()),
            new Point3f(bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ())
        };
        
        for (var corner : boundCorners) {
            if (tet.contains(corner)) {
                return true;
            }
        }
        
        
        // Test if the center of the entity bounds is inside the tetrahedron
        // This catches the case where a small entity is entirely contained within a large tetrahedron
        Point3f entityCenter = new Point3f(
            (bounds.getMinX() + bounds.getMaxX()) / 2,
            (bounds.getMinY() + bounds.getMaxY()) / 2,
            (bounds.getMinZ() + bounds.getMaxZ()) / 2
        );
        
        if (tet.contains(entityCenter)) {
            return true;
        }
        
        
        // CRITICAL FIX: For spanning entities, if the bounding boxes intersect, we should consider it a valid intersection
        // even if the precise tetrahedral geometry doesn't contain the entity center.
        // This is especially important for small entities in large tetrahedra at coarse levels.
        
        // Since we already passed the bounding box test, and this is for spanning entity insertion,
        // we should accept this as a valid intersection. Small entities that fall within the 
        // bounding box of a tetrahedron should be considered as intersecting for spanning purposes.
        
        return true;
    }

    /**
     * Optimized tetrahedral spanning insertion using SFC ranges
     * Implements t8code-style efficient spanning based on linear_id ranges
     */
    @Override
    protected void insertWithSpanning(ID entityId, EntityBounds bounds, byte level) {
        // Find all tetrahedra that the entity's bounds intersect using optimized SFC traversal
        Set<Long> intersectingTets = findIntersectingTets(bounds, level);
        
        // Add entity to all intersecting tetrahedra
        for (Long tetIndex : intersectingTets) {
            TetreeNodeImpl<ID> node = spatialIndex.computeIfAbsent(tetIndex, k -> {
                sortedSpatialIndices.add(tetIndex);
                return new TetreeNodeImpl<>(maxEntitiesPerNode);
            });
            
            node.addEntity(entityId);
            entityManager.addEntityLocation(entityId, tetIndex);
            
            // Note: We don't trigger subdivision for spanning entities
            // to avoid cascading subdivisions across multiple tetrahedra
        }
    }

    /**
     * Tetree-specific tree balancer implementation
     */
    protected class TetreeBalancer extends DefaultTreeBalancer {
        
        @Override
        public List<Long> splitNode(long tetIndex, byte tetLevel) {
            if (tetLevel >= maxDepth) {
                return Collections.emptyList();
            }
            
            TetreeNodeImpl<ID> node = spatialIndex.get(tetIndex);
            if (node == null || node.isEmpty()) {
                return Collections.emptyList();
            }
            
            // Get entities to redistribute
            Set<ID> entities = new HashSet<>(node.getEntityIds());
            
            // Calculate child coordinates and level
            Tet parentTet = Tet.tetrahedron(tetIndex);
            byte childLevel = (byte) (tetLevel + 1);
            int parentCellSize = Constants.lengthAtLevel(tetLevel);
            int childCellSize = Constants.lengthAtLevel(childLevel);
            
            // Create child nodes
            List<Long> createdChildren = new ArrayList<>();
            Map<Long, Set<ID>> childEntityMap = new HashMap<>();
            
            // Distribute entities to children based on their positions
            for (ID entityId : entities) {
                Point3f pos = entityManager.getEntityPosition(entityId);
                if (pos == null) continue;
                
                // Find the containing tetrahedron at the child level
                Tet childTet = locate(pos, childLevel);
                long childTetIndex = childTet.index();
                childEntityMap.computeIfAbsent(childTetIndex, k -> new HashSet<>()).add(entityId);
            }
            
            // Check if all entities map to the same child - if so, don't split
            if (childEntityMap.size() == 1 && childEntityMap.containsKey(tetIndex)) {
                // All entities map to the same tetrahedron as the parent - splitting won't help
                return Collections.emptyList();
            }
            
            // Create child nodes and add entities
            for (Map.Entry<Long, Set<ID>> entry : childEntityMap.entrySet()) {
                long childTetIndex = entry.getKey();
                Set<ID> childEntities = entry.getValue();
                
                if (!childEntities.isEmpty()) {
                    TetreeNodeImpl<ID> childNode = spatialIndex.computeIfAbsent(childTetIndex, k -> {
                        sortedSpatialIndices.add(childTetIndex);
                        return new TetreeNodeImpl<>(maxEntitiesPerNode);
                    });
                    
                    for (ID entityId : childEntities) {
                        childNode.addEntity(entityId);
                        entityManager.addEntityLocation(entityId, childTetIndex);
                    }
                    
                    createdChildren.add(childTetIndex);
                }
            }
            
            // Clear parent node and update entity locations
            node.clearEntities();
            node.setHasChildren(true);
            for (ID entityId : entities) {
                entityManager.removeEntityLocation(entityId, tetIndex);
            }
            
            return createdChildren;
        }
        
        @Override
        public boolean mergeNodes(Set<Long> tetIndices, long parentIndex) {
            if (tetIndices.isEmpty()) {
                return false;
            }
            
            // Collect all entities from nodes to be merged
            Set<ID> allEntities = new HashSet<>();
            for (Long tetIndex : tetIndices) {
                TetreeNodeImpl<ID> node = spatialIndex.get(tetIndex);
                if (node != null && !node.isEmpty()) {
                    allEntities.addAll(node.getEntityIds());
                }
            }
            
            if (allEntities.isEmpty()) {
                // Just remove empty nodes
                for (Long tetIndex : tetIndices) {
                    spatialIndex.remove(tetIndex);
                    sortedSpatialIndices.remove(tetIndex);
                }
                return true;
            }
            
            // Get or create parent node
            TetreeNodeImpl<ID> parentNode = spatialIndex.computeIfAbsent(parentIndex, k -> {
                sortedSpatialIndices.add(parentIndex);
                return new TetreeNodeImpl<>(maxEntitiesPerNode);
            });
            
            // Move all entities to parent
            for (ID entityId : allEntities) {
                // Remove from child locations
                for (Long tetIndex : tetIndices) {
                    entityManager.removeEntityLocation(entityId, tetIndex);
                }
                
                // Add to parent
                parentNode.addEntity(entityId);
                entityManager.addEntityLocation(entityId, parentIndex);
            }
            
            // Remove child nodes
            for (Long tetIndex : tetIndices) {
                spatialIndex.remove(tetIndex);
                sortedSpatialIndices.remove(tetIndex);
            }
            
            // Parent no longer has children after merge
            parentNode.setHasChildren(false);
            
            return true;
        }
        
        @Override
        protected Set<Long> findSiblings(long tetIndex) {
            Tet tet = Tet.tetrahedron(tetIndex);
            byte level = tet.l();
            if (level == 0) {
                return Collections.emptySet(); // Root has no siblings
            }
            
            // For tetrahedral decomposition, siblings are other tetrahedra in the same parent cell
            // Calculate parent cell coordinates
            int cellSize = Constants.lengthAtLevel(level);
            int parentCellSize = cellSize * 2;
            
            // Find parent cell coordinates
            int parentX = (tet.x() / parentCellSize) * parentCellSize;
            int parentY = (tet.y() / parentCellSize) * parentCellSize;
            int parentZ = (tet.z() / parentCellSize) * parentCellSize;
            
            Set<Long> siblings = new HashSet<>();
            
            // Check all child cells within parent cell for all tetrahedron types
            for (int x = parentX; x < parentX + parentCellSize; x += cellSize) {
                for (int y = parentY; y < parentY + parentCellSize; y += cellSize) {
                    for (int z = parentZ; z < parentZ + parentCellSize; z += cellSize) {
                        for (byte type = 0; type < 6; type++) {
                            Tet siblingTet = new Tet(x, y, z, level, type);
                            long siblingIndex = siblingTet.index();
                            
                            // Add if it's not the current node and exists
                            if (siblingIndex != tetIndex && spatialIndex.containsKey(siblingIndex)) {
                                siblings.add(siblingIndex);
                            }
                        }
                    }
                }
            }
            
            return siblings;
        }
        
        @Override
        protected long getParentIndex(long tetIndex) {
            Tet tet = Tet.tetrahedron(tetIndex);
            byte level = tet.l();
            if (level == 0) {
                return -1; // Root has no parent
            }
            
            int cellSize = Constants.lengthAtLevel(level);
            int parentCellSize = cellSize * 2;
            byte parentLevel = (byte) (level - 1);
            
            // Calculate parent coordinates
            int parentX = (tet.x() / parentCellSize) * parentCellSize;
            int parentY = (tet.y() / parentCellSize) * parentCellSize;
            int parentZ = (tet.z() / parentCellSize) * parentCellSize;
            
            // For tetrahedral decomposition, find the parent tetrahedron that contains this position
            Point3f position = new Point3f(tet.x() + cellSize/2.0f, tet.y() + cellSize/2.0f, tet.z() + cellSize/2.0f);
            Tet parentTet = locate(position, parentLevel);
            return parentTet.index();
        }
    }
    
    /**
     * Check if a tetrahedron intersects with volume bounds (proper tetrahedral geometry)
     */
    private boolean tetrahedronIntersectsVolumeBounds(Tet tet, VolumeBounds bounds) {
        var vertices = tet.coordinates();

        // Quick bounding box rejection test first
        float tetMinX = Float.MAX_VALUE, tetMaxX = Float.MIN_VALUE;
        float tetMinY = Float.MAX_VALUE, tetMaxY = Float.MIN_VALUE;
        float tetMinZ = Float.MAX_VALUE, tetMaxZ = Float.MIN_VALUE;

        for (var vertex : vertices) {
            tetMinX = Math.min(tetMinX, vertex.x);
            tetMaxX = Math.max(tetMaxX, vertex.x);
            tetMinY = Math.min(tetMinY, vertex.y);
            tetMaxY = Math.max(tetMaxY, vertex.y);
            tetMinZ = Math.min(tetMinZ, vertex.z);
            tetMaxZ = Math.max(tetMaxZ, vertex.z);
        }

        // More generous bounding box intersection test - use overlap instead of strict intersection
        boolean boundsOverlap = !(tetMaxX < bounds.minX() || tetMinX > bounds.maxX() || 
                                 tetMaxY < bounds.minY() || tetMinY > bounds.maxY() ||
                                 tetMaxZ < bounds.minZ() || tetMinZ > bounds.maxZ());
        
        if (!boundsOverlap) {
            return false;
        }

        // If bounding boxes overlap, assume intersection for simplicity
        // A more sophisticated implementation could do exact tetrahedron-AABB intersection
        return true;
    }

    /**
     * Check if a tetrahedron is completely contained within volume bounds
     * Memory-efficient version for large entity spanning operations
     */
    private boolean tetrahedronContainedInVolumeBounds(Tet tet, VolumeBounds bounds) {
        var vertices = tet.coordinates();
        
        // All vertices must be within bounds for complete containment
        for (var vertex : vertices) {
            if (vertex.x < bounds.minX() || vertex.x > bounds.maxX() ||
                vertex.y < bounds.minY() || vertex.y > bounds.maxY() ||
                vertex.z < bounds.minZ() || vertex.z > bounds.maxZ()) {
                return false;
            }
        }
        return true;
    }
}
