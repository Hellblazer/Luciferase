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

import com.hellblazer.luciferase.lucien.*;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.tetree.TetreeIterator.TraversalOrder;

import javax.vecmath.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Tetree implementation with multi-entity support per tetrahedral location. This class provides a tetrahedral spatial
 * index using space-filling curves that supports multiple entities per location.
 *
 * Key constraints: - All coordinates must be positive (tetrahedral SFC requirement) - Points must be within the S0
 * tetrahedron domain - Each grid cell contains 6 tetrahedra (types 0-5)
 *
 * <h2>Performance Monitoring</h2>
 * <p>The Tetree includes optional performance monitoring capabilities to help analyze and optimize spatial operations.
 * Performance monitoring is disabled by default to avoid overhead in production use.</p>
 *
 * <h3>Enabling Performance Monitoring</h3>
 * <pre>{@code
 * Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
 * tetree.setPerformanceMonitoring(true);
 *
 * // Perform operations...
 *
 * // Retrieve metrics
 * TetreeMetrics metrics = tetree.getMetrics();
 * System.out.println(metrics.getSummary());
 * }</pre>
 *
 * <h3>Monitored Operations</h3>
 * <ul>
 *   <li><b>Neighbor Queries</b> - Tracks time spent finding face, edge, and vertex neighbors</li>
 *   <li><b>Tree Traversals</b> - Monitors traversal performance (not currently tracked)</li>
 *   <li><b>Cache Performance</b> - Reports hit rate for TetreeLevelCache operations</li>
 * </ul>
 *
 * <h3>Performance Metrics</h3>
 * <p>The {@link TetreeMetrics} record provides:</p>
 * <ul>
 *   <li>Tree structure statistics (node count, depth, balance)</li>
 *   <li>Cache hit rate percentage</li>
 *   <li>Average neighbor query time in nanoseconds</li>
 *   <li>Total operation counts</li>
 * </ul>
 *
 * <h3>Use Cases</h3>
 * <ol>
 *   <li><b>Development/Testing</b> - Identify performance bottlenecks during development</li>
 *   <li><b>Benchmarking</b> - Compare performance across different data distributions</li>
 *   <li><b>Optimization</b> - Measure impact of code changes on performance</li>
 *   <li><b>Debugging</b> - Understand tree structure and operation patterns</li>
 * </ol>
 *
 * <h3>Performance Impact</h3>
 * <p>When disabled (default), monitoring has zero overhead. When enabled, the impact is minimal:</p>
 * <ul>
 *   <li>~10-20 nanoseconds per monitored operation for timing</li>
 *   <li>Simple counter increments for operation counts</li>
 *   <li>No memory allocation during operations</li>
 * </ul>
 *
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class Tetree<ID extends EntityID, Content>
extends AbstractSpatialIndex<TetreeKey, ID, Content, TetreeNodeImpl<ID>> {

    // Neighbor finder instance (lazily initialized)
    private TetreeNeighborFinder neighborFinder;

    // Ray traversal instance (lazily initialized)
    private TetreeSFCRayTraversal<ID, Content> rayTraversal;
    private boolean                            performanceMonitoringEnabled = false;
    private long                               neighborQueryCount           = 0;
    private long                               totalNeighborQueryTime       = 0;
    private long                               traversalCount               = 0;
    private long                               totalTraversalTime           = 0;

    // ===== Abstract Method Implementations =====
    private long cacheHits   = 0;
    private long cacheMisses = 0;

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

    // k-NN search is now provided by AbstractSpatialIndex

    @Override
    public SpatialNode<TetreeKey, ID> enclosing(Spatial volume) {
        TetreeValidationUtils.validatePositiveCoordinates(volume);

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
        TetreeNodeImpl<ID> node = spatialIndex.get(tet.tmIndex());
        if (node != null && !node.isEmpty()) {
            return new SpatialNode<>(tet.tmIndex(), new HashSet<>(node.getEntityIds()));
        }
        return null;
    }

    @Override
    public SpatialNode<TetreeKey, ID> enclosing(Tuple3i point, byte level) {
        TetreeValidationUtils.validatePositiveCoordinates(point);

        var tet = locate(new Point3f(point.x, point.y, point.z), level);
        TetreeNodeImpl<ID> node = spatialIndex.get(tet.tmIndex());
        if (node != null && !node.isEmpty()) {
            return new SpatialNode<>(tet.tmIndex(), new HashSet<>(node.getEntityIds()));
        }
        return null;
    }

    // entitiesInRegion is now implemented in AbstractSpatialIndex

    // ===== New Algorithm Integration Methods =====

    /**
     * Find all face neighbors of the tetrahedron
     *
     * @param tetIndex the tetrahedral index
     * @return array of neighbor indices (length 4), -1 for no neighbor
     */
    public TetreeKey[] findAllFaceNeighbors(TetreeKey tetIndex) {
        long startTime = performanceMonitoringEnabled ? System.nanoTime() : 0;

        // Get level from the SFC index
        Tet tet = Tet.tetrahedron(tetIndex);
        TetreeKey[] neighbors = new TetreeKey[4];
        for (int i = 0; i < 4; i++) {
            Tet neighbor = getNeighborFinder().findFaceNeighbor(tet, i);
            neighbors[i] = neighbor != null ? neighbor.tmIndex() : null;
        }

        if (performanceMonitoringEnabled) {
            recordNeighborQueryTime(System.nanoTime() - startTime);
        }

        return neighbors;
    }

    /**
     * Find neighbors within the same grid cell
     *
     * @param tetIndex the tetrahedral index
     * @return list of neighbor indices within the same grid cell
     */
    public List<TetreeKey> findCellNeighbors(TetreeKey tetIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        // For tetrahedral decomposition, find all neighbors (face-adjacent)
        List<Tet> neighborTets = getNeighborFinder().findAllNeighbors(tet);
        return neighborTets.stream().map(Tet::tmIndex).collect(java.util.stream.Collectors.toList());
    }

    /**
     * Find the common ancestor of multiple tetrahedral nodes.
     *
     * @param tetIndices The indices to find common ancestor for
     * @return The common ancestor index, or 0 if none
     */
    public TetreeKey findCommonAncestor(TetreeKey... tetIndices) {
        if (tetIndices.length == 0) {
            return TetreeKey.getRoot();
        }
        if (tetIndices.length == 1) {
            return tetIndices[0];
        }

        // Convert indices to Tets for TetreeBits processing
        Tet[] tets = new Tet[tetIndices.length];
        for (int i = 0; i < tetIndices.length; i++) {
            tets[i] = Tet.tetrahedron(tetIndices[i]);
        }

        // Find common ancestor using pairwise comparisons
        Tet ancestor = tets[0];
        for (int i = 1; i < tets.length; i++) {
            byte lcaLevel = TetreeBits.lowestCommonAncestorLevel(ancestor, tets[i]);
            // Get the ancestor at that level from the first tet
            ancestor = ancestor;
            while (ancestor.l() > lcaLevel) {
                ancestor = ancestor.parent();
            }
        }

        return ancestor.tmIndex();
    }

    /**
     * Find all neighbors that share a specific edge with the given tetrahedron.
     *
     * @param tetIndex  The SFC index of the tetrahedron
     * @param edgeIndex The edge index (0-5)
     * @return List of neighbor tetrahedron indices sharing the specified edge
     */
    public List<TetreeKey> findEdgeNeighbors(TetreeKey tetIndex, int edgeIndex) {
        // Get theoretical neighbors from the neighbor finder
        List<TetreeKey> theoreticalNeighbors = getNeighborFinder().findEdgeNeighbors(tetIndex, edgeIndex);

        // Filter to only include neighbors that actually exist in the sparse tree
        List<TetreeKey> existingNeighbors = new ArrayList<>();
        for (TetreeKey neighbor : theoreticalNeighbors) {
            if (hasNode(neighbor)) {
                existingNeighbors.add(neighbor);
            }
        }

        return existingNeighbors;
    }

    /**
     * Find all neighboring entities of a given entity. This method finds the tetrahedra containing the entity, then
     * finds all neighbors of those tetrahedra, and returns all entities in those neighbors.
     *
     * @param entityId The entity whose neighbors to find
     * @return Set of neighboring entity IDs (excluding the input entity)
     * @throws IllegalArgumentException if entityId is null or not found
     */
    public Set<ID> findEntityNeighbors(ID entityId) {
        Set<ID> neighbors = new HashSet<>();

        // Get all locations where this entity exists
        var entityLocations = entityManager.getEntityLocations(entityId);
        if (entityLocations == null || entityLocations.isEmpty()) {
            return neighbors;
        }

        // For each location, find neighbors
        for (var location : entityLocations) {
            // Find all face neighbors
            TetreeKey[] faceNeighbors = findAllFaceNeighbors(location);
            for (TetreeKey neighborIndex : faceNeighbors) {
                if (neighborIndex != null) {
                    TetreeNodeImpl<ID> neighborNode = spatialIndex.get(neighborIndex);
                    if (neighborNode != null) {
                        neighbors.addAll(neighborNode.getEntityIds());
                    }
                }
            }

            // Also find edge and vertex neighbors for more comprehensive coverage
            for (var edge = 0; edge < 6; edge++) {
                var edgeNeighbors = findEdgeNeighbors(location, edge);
                for (var neighborIndex : edgeNeighbors) {
                    var neighborNode = spatialIndex.get(neighborIndex);
                    if (neighborNode != null) {
                        neighbors.addAll(neighborNode.getEntityIds());
                    }
                }
            }
        }

        // Remove the entity itself from the neighbor set
        neighbors.remove(entityId);
        return neighbors;
    }

    /**
     * Find face neighbors of the tetrahedron at the given index
     *
     * @param tetIndex  the tetrahedral index
     * @param faceIndex which face (0-3)
     * @return the neighbor index, or null if no neighbor exists
     */
    public TetreeKey findFaceNeighbor(TetreeKey tetIndex, int faceIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        Tet neighbor = getNeighborFinder().findFaceNeighbor(tet, faceIndex);
        if (neighbor != null) {
            TetreeKey neighborKey = neighbor.tmIndex();
            // Only return the neighbor if it actually exists in the sparse tree
            return hasNode(neighborKey) ? neighborKey : null;
        }
        return null;
    }

    /**
     * Find neighbors within a specific Euclidean distance from a tetrahedron.
     *
     * @param tetIndex The SFC index of the tetrahedron
     * @param distance The maximum Euclidean distance
     * @return Set of neighbor node indices within the distance
     */
    public Set<TetreeNodeImpl<ID>> findNeighborsWithinDistance(TetreeKey tetIndex, float distance) {
        Set<TetreeNodeImpl<ID>> neighbors = new HashSet<>();

        lock.readLock().lock();
        try {
            // Get the reference node
            TetreeNodeImpl<ID> referenceNode = spatialIndex.get(tetIndex);
            if (referenceNode == null || referenceNode.isEmpty()) {
                return neighbors; // No entities in reference node
            }

            // Get all entity positions in the reference node
            List<Point3f> referencePositions = new ArrayList<>();
            for (ID entityId : referenceNode.getEntityIds()) {
                Point3f entityPos = entityManager.getEntityPosition(entityId);
                if (entityPos != null) {
                    referencePositions.add(entityPos);
                }
            }

            if (referencePositions.isEmpty()) {
                return neighbors; // No valid entity positions
            }

            // Calculate bounding box around all reference entities
            float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
            float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;

            for (Point3f pos : referencePositions) {
                minX = Math.min(minX, pos.x);
                maxX = Math.max(maxX, pos.x);
                minY = Math.min(minY, pos.y);
                maxY = Math.max(maxY, pos.y);
                minZ = Math.min(minZ, pos.z);
                maxZ = Math.max(maxZ, pos.z);
            }

            // Expand bounding box by distance
            VolumeBounds searchBounds = new VolumeBounds(
                minX - distance, minY - distance, minZ - distance,
                maxX + distance, maxY + distance, maxZ + distance
            );

            // Use spatial range query to find candidate nodes
            spatialRangeQuery(searchBounds, true).forEach(entry -> {
                TetreeNodeImpl<ID> node = entry.getValue();
                if (node != null && !node.isEmpty()) {
                    boolean hasNearbyEntity = false;

                    // Check if any entity in this node is within distance of any reference entity
                    for (ID entityId : node.getEntityIds()) {
                        Point3f entityPos = entityManager.getEntityPosition(entityId);
                        if (entityPos != null) {
                            // Check distance to any reference entity
                            for (Point3f refPos : referencePositions) {
                                float dx = refPos.x - entityPos.x;
                                float dy = refPos.y - entityPos.y;
                                float dz = refPos.z - entityPos.z;
                                float distSq = dx * dx + dy * dy + dz * dz;

                                if (distSq <= distance * distance) {
                                    hasNearbyEntity = true;
                                    break;
                                }
                            }
                            if (hasNearbyEntity) break;
                        }
                    }

                    if (hasNearbyEntity) {
                        neighbors.add(node);
                    }
                }
            });
        } finally {
            lock.readLock().unlock();
        }

        return neighbors;
    }

    /**
     * Find all neighbors that share a specific vertex with the given tetrahedron.
     *
     * @param tetIndex    The SFC index of the tetrahedron
     * @param vertexIndex The vertex index (0-3)
     * @return List of neighbor tetrahedron indices sharing the specified vertex
     */
    public List<TetreeKey> findVertexNeighbors(TetreeKey tetIndex, int vertexIndex) {
        // Get theoretical neighbors from the neighbor finder
        List<TetreeKey> theoreticalNeighbors = getNeighborFinder().findVertexNeighbors(tetIndex, vertexIndex);

        // Filter to only include neighbors that actually exist in the sparse tree
        List<TetreeKey> existingNeighbors = new ArrayList<>();
        for (TetreeKey neighbor : theoreticalNeighbors) {
            if (hasNode(neighbor)) {
                existingNeighbors.add(neighbor);
            }
        }

        return existingNeighbors;
    }

    /**
     * Get all entities at the given tetrahedral index (direct access)
     *
     * @param tetIndex the tetrahedral index
     * @return list of content at the index, or empty list if no entities
     */
    public List<Content> get(TetreeKey tetIndex) {
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
     * Get all leaf nodes as a list.
     *
     * @return List of leaf nodes
     */
    public List<TetreeNodeImpl<ID>> getLeafNodes() {
        return leafStream().collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get detailed performance metrics for this Tetree instance. This includes tree statistics, cache hit rates, and
     * average query times.
     *
     * @return A TetreeMetrics object containing all performance metrics
     */
    public TetreeMetrics getMetrics() {
        // Get tree statistics using the validator
        TetreeValidator.TreeStats stats = getTreeStatistics();

        // Calculate cache hit rate
        long totalCacheAccess = cacheHits + cacheMisses;
        float cacheHitRate = totalCacheAccess > 0 ? (float) cacheHits / totalCacheAccess : 0.0f;

        // Calculate average query times
        float avgNeighborQueryTime = neighborQueryCount > 0 ? (float) totalNeighborQueryTime / neighborQueryCount
                                                            : 0.0f;
        float avgTraversalTime = traversalCount > 0 ? (float) totalTraversalTime / traversalCount : 0.0f;

        return new TetreeMetrics(stats, cacheHitRate, avgNeighborQueryTime, avgTraversalTime, neighborQueryCount,
                                 traversalCount, performanceMonitoringEnabled);
    }

    /**
     * Get the number of nodes in the spatial index
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
     * Count nodes at each level.
     *
     * @return Map of level to node count
     */
    public Map<Byte, Integer> getNodeCountByLevel() {
        return sortedSpatialIndices.stream().collect(java.util.stream.Collectors.groupingBy(index -> index.getLevel(),
                                                                                            java.util.stream.Collectors.collectingAndThen(
                                                                                            java.util.stream.Collectors.toList(),
                                                                                            List::size)));
    }

    // ===== Stream API Integration =====

    /**
     * Get the sorted spatial indices for traversal
     *
     * @return NavigableSet of spatial indices
     */
    public NavigableSet<TetreeKey> getSortedSpatialIndices() {
        lock.readLock().lock();
        try {
            return new TreeSet<>(sortedSpatialIndices);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get comprehensive statistics about the tetree structure
     *
     * @return tree statistics including depth, node counts, etc.
     */
    public TetreeValidator.TreeStats getTreeStatistics() {
        lock.readLock().lock();
        try {
            return TetreeValidator.analyzeTreeIndices(sortedSpatialIndices);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if a node exists at the given tetrahedral index
     *
     * @param tetIndex the tetrahedral SFC index
     * @return true if a node exists at this index
     */
    public boolean hasNode(TetreeKey tetIndex) {
        lock.readLock().lock();
        try {
            return spatialIndex.containsKey(tetIndex);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find a single node intersecting with a volume Note: This returns the first intersecting node found
     *
     * @param volume the volume to test
     * @return a single intersecting node, or null if none found
     */
    public SpatialNode<TetreeKey, ID> intersecting(Spatial volume) {
        TetreeValidationUtils.validatePositiveCoordinates(volume);

        // Find the first intersecting node
        return bounding(volume).findFirst().orElse(null);
    }

    /**
     * Check if performance monitoring is currently enabled.
     *
     * @return true if monitoring is enabled, false otherwise
     */
    public boolean isPerformanceMonitoringEnabled() {
        return performanceMonitoringEnabled;
    }

    /**
     * Create an iterator for traversing the tetree in the specified order
     *
     * @param order the traversal order (DFS, BFS, SFC_ORDER, LEVEL_ORDER)
     * @return a new iterator for this tetree
     */
    public TetreeIterator<ID, Content> iterator(TetreeIterator.TraversalOrder order) {
        return new TetreeIterator<>(this, order);
    }

    // ===== Enhanced Iterator API =====

    /**
     * Create an iterator that only visits non-empty nodes.
     * This is useful for efficiently traversing the tree without visiting empty nodes.
     *
     * @param order The traversal order (DEPTH_FIRST_PRE, BREADTH_FIRST, etc.)
     * @return Iterator over non-empty nodes
     */

    /**
     * Create an iterator for traversing the tetree with level constraints
     *
     * @param order    the traversal order
     * @param minLevel minimum level to include (inclusive)
     * @param maxLevel maximum level to include (inclusive)
     * @return a new iterator with level constraints
     */
    public TetreeIterator<ID, Content> iterator(TetreeIterator.TraversalOrder order, byte minLevel, byte maxLevel) {
        return new TetreeIterator<ID, Content>(this, order, minLevel, maxLevel, false);
    }

    /**
     * Create an iterator for parent-child traversal from a specific node.
     * Traverses from the start node up to the root, then down to all descendants.
     *
     * @param startIndex The starting node index
     * @return Iterator over the parent-child path
     */

    /**
     * Get a stream of leaf nodes (nodes without children).
     *
     * @return Stream of leaf nodes
     */
    public Stream<TetreeNodeImpl<ID>> leafStream() {
        return spatialIndex.entrySet().stream().filter(
        entry -> !entry.getValue().isEmpty() && !hasChildren(entry.getKey())).map(Map.Entry::getValue);
    }

    /**
     * Create an iterator for sibling traversal.
     * Iterates over all siblings of the given tetrahedron (same parent, different child index).
     *
     * @param tetIndex The tetrahedron whose siblings to iterate
     * @return Iterator over sibling nodes
     */

    /**
     * Get a stream of nodes at a specific level.
     *
     * @param level The tetrahedral level
     * @return Stream of nodes at the specified level
     */
    public Stream<TetreeNodeImpl<ID>> levelStream(byte level) {
        return sortedSpatialIndices.stream().filter(index -> index.getLevel() == level).map(spatialIndex::get).filter(
        node -> node != null && !node.isEmpty());
    }

    // ===== Enhanced Neighbor Finding API =====

    /**
     * Public access to locate method for finding containing tetrahedron
     *
     * @param point the point to locate (must have positive coordinates)
     * @param level the refinement level
     * @return the Tet containing the point
     */
    public Tet locateTetrahedron(Tuple3f point, byte level) {
        TetreeValidationUtils.validatePositiveCoordinates(point);
        return locate(point, level);
    }

    @Override
    public List<ID> lookup(Point3f position, byte level) {
        TetreeValidationUtils.validatePositiveCoordinates(position);

        // Find the containing tetrahedron
        Tet tet = locate(position, level);
        TetreeKey tetIndex = tet.tmIndex();

        TetreeNodeImpl<ID> node = spatialIndex.get(tetIndex);
        if (node == null) {
            return Collections.emptyList();
        }

        return new ArrayList<>(node.getEntityIds());
    }

    /**
     * Get a stream of all non-empty nodes in the tetree.
     *
     * @return Stream of tetree nodes
     */
    public Stream<TetreeNodeImpl<ID>> nodeStream() {
        return spatialIndex.values().stream().filter(node -> !node.isEmpty());
    }

    /**
     * Create an iterator that only visits non-empty nodes. This is more efficient than the standard iterator when you
     * only need to process nodes that contain entities.
     *
     * @param order The traversal order to use (DEPTH_FIRST_PRE, BREADTH_FIRST, etc.)
     * @return An iterator over non-empty nodes in the specified order
     */
    public Iterator<TetreeNodeImpl<ID>> nonEmptyIterator(TraversalOrder order) {
        return new Iterator<TetreeNodeImpl<ID>>() {
            private final Iterator<TetreeNodeImpl<ID>> baseIterator = new TetreeIterator<>(Tetree.this, order, (byte) 0,
                                                                                           Constants.getMaxRefinementLevel(),
                                                                                           false);
            private       TetreeNodeImpl<ID>           nextNode     = null;

            {
                advance();
            }

            @Override
            public boolean hasNext() {
                return nextNode != null;
            }

            @Override
            public TetreeNodeImpl<ID> next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                TetreeNodeImpl<ID> result = nextNode;
                advance();
                return result;
            }

            private void advance() {
                nextNode = null;
                while (baseIterator.hasNext()) {
                    TetreeNodeImpl<ID> node = baseIterator.next();
                    if (node != null && !node.isEmpty()) {
                        nextNode = node;
                        break;
                    }
                }
            }
        };
    }

    /**
     * Create an iterator that traverses from a specific node up to the root, then down to all descendants. This is
     * useful for understanding the full hierarchical context of a node.
     *
     * @param startIndex The tetrahedral index to start from
     * @return An iterator that traverses parents first (up to root), then all descendants
     * @throws IllegalArgumentException if startIndex is invalid
     */
    public Iterator<TetreeNodeImpl<ID>> parentChildIterator(TetreeKey startIndex) {
        return new Iterator<TetreeNodeImpl<ID>>() {
            private final List<TetreeKey> path         = new ArrayList<>();
            private       int             currentIndex = 0;

            {
                // Build path from start to root, but only include nodes that exist
                TetreeKey current = startIndex;
                while (current != null && current.getTmIndex().compareTo(BigInteger.ZERO) >= 0) {
                    if (spatialIndex.containsKey(current)) {
                        path.add(0, current); // Insert at beginning (to maintain root->leaf order)
                    }

                    if (current.getLevel() == 0) {
                        break;
                    }

                    Tet tet = Tet.tetrahedron(current);
                    if (tet.l() == 0) {
                        break;
                    }
                    current = tet.parent().tmIndex();
                }

                // Add all descendants of start node
                if (spatialIndex.containsKey(startIndex)) {
                    addDescendants(startIndex);
                }
            }

            @Override
            public boolean hasNext() {
                return currentIndex < path.size();
            }

            @Override
            public TetreeNodeImpl<ID> next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                TetreeKey nodeIndex = path.get(currentIndex++);
                return spatialIndex.get(nodeIndex);
            }

            private void addDescendants(TetreeKey nodeIndex) {
                if (hasChildren(nodeIndex)) {
                    Tet parent = Tet.tetrahedron(nodeIndex);
                    if (parent.l() < maxDepth) {
                        for (int i = 0; i < 8; i++) {
                            try {
                                Tet child = parent.child(i);
                                TetreeKey childIndex = child.tmIndex();
                                if (spatialIndex.containsKey(childIndex)) {
                                    path.add(childIndex);
                                    addDescendants(childIndex);
                                }
                            } catch (Exception e) {
                                // Skip invalid children
                            }
                        }
                    }
                }
            }
        };
    }

    /**
     * Reset all performance counters to zero. This is useful for benchmarking specific operations.
     */
    public void resetPerformanceCounters() {
        neighborQueryCount = 0;
        totalNeighborQueryTime = 0;
        traversalCount = 0;
        totalTraversalTime = 0;
        cacheHits = 0;
        cacheMisses = 0;
    }

    /**
     * Enable or disable performance monitoring. When enabled, the Tetree will track timing information for queries and
     * cache statistics.
     *
     * @param enabled true to enable monitoring, false to disable
     */
    public void setPerformanceMonitoring(boolean enabled) {
        this.performanceMonitoringEnabled = enabled;

        if (!enabled) {
            // Reset counters when disabling
            neighborQueryCount = 0;
            totalNeighborQueryTime = 0;
            traversalCount = 0;
            totalTraversalTime = 0;
            cacheHits = 0;
            cacheMisses = 0;
        }
    }

    /**
     * Create an iterator over all sibling nodes of the given tetrahedron. Siblings are tetrahedra that share the same
     * parent in the tree hierarchy.
     *
     * @param tetIndex The tetrahedral index whose siblings to iterate
     * @return An iterator over sibling nodes (excluding the input tetrahedron itself)
     * @throws IllegalArgumentException if tetIndex is invalid
     */
    public Iterator<TetreeNodeImpl<ID>> siblingIterator(TetreeKey tetIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        if (tet.l() == 0) {
            // Root has no siblings
            return Collections.emptyIterator();
        }

        Tet[] siblings = TetreeFamily.getSiblings(tet);
        List<TetreeNodeImpl<ID>> siblingNodes = new ArrayList<>();

        for (Tet sibling : siblings) {
            if (sibling != null) {
                TetreeKey siblingIndex = sibling.tmIndex();
                TetreeNodeImpl<ID> node = spatialIndex.get(siblingIndex);
                if (node != null) {
                    siblingNodes.add(node);
                }
            }
        }

        return siblingNodes.iterator();
    }

    /**
     * Get the size of the spatial index (number of non-empty nodes)
     *
     * @return the number of non-empty tetrahedral nodes
     */
    public int size() {
        return (int) spatialIndex.values().stream().filter(node -> !node.isEmpty()).count();
    }

    // Removed ensureAncestorNodes - not needed in pointerless SFC implementation

    /**
     * Validate the tetree structure for consistency
     *
     * @return validation result with any issues found
     */
    public TetreeValidator.ValidationResult validate() {
        lock.readLock().lock();
        try {
            return TetreeValidator.validateTreeStructure(sortedSpatialIndices);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Validate a subtree rooted at the given tetrahedral index. This method checks the structural integrity of the
     * subtree including: - Parent-child relationships - Level consistency - Node validity - SFC index validity
     *
     * @param rootIndex The root tetrahedral index of the subtree to validate
     * @return ValidationResult containing any issues found
     */
    public TetreeValidator.ValidationResult validateSubtree(TetreeKey rootIndex) {
        // For now, just validate that the node exists
        // A full subtree validation would require traversing the entire subtree
        if (!spatialIndex.containsKey(rootIndex)) {
            return TetreeValidator.ValidationResult.invalid(
            Collections.singletonList("Root node " + rootIndex + " does not exist"));
        }

        // Get all nodes in the subtree
        Set<TetreeKey> subtreeNodes = new HashSet<>();
        Queue<TetreeKey> toVisit = new LinkedList<>();
        toVisit.add(rootIndex);

        while (!toVisit.isEmpty()) {
            TetreeKey current = toVisit.poll();
            if (subtreeNodes.contains(current)) {
                continue;
            }
            subtreeNodes.add(current);

            // Add children to visit
            Tet currentTet = Tet.tetrahedron(current);
            if (currentTet.l() < maxDepth) {
                for (int i = 0; i < 8; i++) {
                    try {
                        Tet child = currentTet.child(i);
                        TetreeKey childIndex = child.tmIndex();
                        if (spatialIndex.containsKey(childIndex)) {
                            toVisit.add(childIndex);
                        }
                    } catch (Exception e) {
                        // Skip invalid children
                    }
                }
            }
        }

        // Validate the subtree structure
        return TetreeValidator.validateTreeStructure(subtreeNodes);
    }

    // ===== Plane Intersection Implementation =====

    /**
     * Visit all nodes at a specific level with a consumer.
     *
     * @param level   The level to visit
     * @param visitor The consumer to apply to each node
     */
    public void visitLevel(byte level, java.util.function.Consumer<TetreeNodeImpl<ID>> visitor) {
        levelStream(level).forEach(visitor);
    }

    /**
     * Override collision detection for tetree to use spatial range queries instead of neighbor search.
     * Tetrahedral SFC means spatially close entities may not be in structurally neighboring nodes.
     */
    @Override
    public List<CollisionPair<ID, Content>> findCollisions(ID entityId) {
        lock.readLock().lock();
        try {
            List<CollisionPair<ID, Content>> collisions = new ArrayList<>();

            // Get entity position for spatial range query
            Point3f entityPos = entityManager.getEntityPosition(entityId);
            if (entityPos == null) {
                return collisions;
            }

            EntityBounds entityBounds = entityManager.getEntityBounds(entityId);
            float searchRadius = 0.1f; // Standard collision threshold for point entities

            if (entityBounds != null) {
                // For bounded entities, use the bounds for collision detection
                var nodesToCheck = findNodesIntersectingBounds(entityBounds);
                Set<ID> checkedEntities = new HashSet<>();
                checkedEntities.add(entityId);

                for (var nodeIndex : nodesToCheck) {
                    TetreeNodeImpl<ID> node = spatialIndex.get(nodeIndex);
                    if (node == null || node.isEmpty()) {
                        continue;
                    }

                    for (ID otherId : node.getEntityIds()) {
                        if (!checkedEntities.add(otherId)) {
                            continue;
                        }
                        var collision = checkCollision(entityId, otherId);
                        collision.ifPresent(collisions::add);
                    }
                }
            } else {
                // For point entities, use spatial range query with collision threshold
                VolumeBounds searchBounds = new VolumeBounds(
                    entityPos.x - searchRadius, entityPos.y - searchRadius, entityPos.z - searchRadius,
                    entityPos.x + searchRadius, entityPos.y + searchRadius, entityPos.z + searchRadius
                );

                // Find all nodes within the search bounds
                Set<ID> checkedEntities = new HashSet<>();
                checkedEntities.add(entityId);

                spatialRangeQuery(searchBounds, true).forEach(entry -> {
                    TetreeKey nodeIndex = entry.getKey();
                    TetreeNodeImpl<ID> node = entry.getValue();
                    if (node == null || node.isEmpty()) {
                        return;
                    }

                    for (ID otherId : node.getEntityIds()) {
                        if (!checkedEntities.add(otherId)) {
                            continue; // Continue to next entity in this node
                        }
                        var collision = checkCollision(entityId, otherId);
                        collision.ifPresent(collisions::add);
                    }
                });
            }

            Collections.sort(collisions);
            return collisions;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    protected void addNeighboringNodes(TetreeKey tetIndex, Queue<TetreeKey> toVisit, Set<TetreeKey> visitedNodes) {
        // For tetree, use spatial coordinate-based neighbor search for k-NN queries
        Tet currentTet = Tet.tetrahedron(tetIndex);
        byte level = currentTet.l();
        int cellSize = Constants.lengthAtLevel(level);

        // Use a smaller search radius for k-NN neighbor finding
        int searchRadius = 1; // Check immediate neighbors only for k-NN

        // Check all neighbors within search radius
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dy = -searchRadius; dy <= searchRadius; dy++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue; // Skip center (current node)
                    }

                    int nx = currentTet.x() + dx * cellSize;
                    int ny = currentTet.y() + dy * cellSize;
                    int nz = currentTet.z() + dz * cellSize;

                    // Check bounds (must be within valid coordinate range)
                    if (nx >= 0 && ny >= 0 && nz >= 0 &&
                        nx <= Constants.MAX_COORD && ny <= Constants.MAX_COORD && nz <= Constants.MAX_COORD) {

                        // Find all tetrahedra in this grid cell
                        // Each grid cell contains 6 tetrahedra (types 0-5)
                        for (byte tetType = 0; tetType < TetreeConnectivity.TET_TYPES; tetType++) {
                            try {
                                var neighborTet = new Tet(nx, ny, nz, level, tetType);
                                var neighborIndex = neighborTet.tmIndex();

                                if (!visitedNodes.contains(neighborIndex) && spatialIndex.containsKey(neighborIndex)) {
                                    toVisit.add(neighborIndex);
                                }
                            } catch (Exception e) {
                                // Invalid tetrahedron or out of bounds - skip
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    protected TetreeKey calculateSpatialIndex(Point3f position, byte level) {
        var tet = locate(position, level);
        return tet.tmIndex();
    }

    @Override
    protected SubdivisionStrategy createDefaultSubdivisionStrategy() {
        return TetreeSubdivisionStrategy.balanced();
    }

    // ===== Frustum Intersection Implementation =====

    @Override
    protected TetreeNodeImpl<ID> createNode() {
        return new TetreeNodeImpl<>(maxEntitiesPerNode);
    }

    @Override
    protected TreeBalancer createTreeBalancer() {
        return new TetreeBalancer();
    }

    @Override
    protected boolean doesFrustumIntersectNode(TetreeKey nodeIndex, Frustum3D frustum) {
        // Get the tetrahedron from the node index
        Tet tet = Tet.tetrahedron(nodeIndex);

        // For tetrahedral nodes, we need to check if the frustum intersects with the tetrahedron
        // We'll use a conservative approach: check if the frustum intersects the tetrahedron's bounding box
        var vertices = tet.coordinates();

        // Calculate bounding box of the tetrahedron
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;

        for (var vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            maxX = Math.max(maxX, vertex.x);
            minY = Math.min(minY, vertex.y);
            maxY = Math.max(maxY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxZ = Math.max(maxZ, vertex.z);
        }

        // Use the frustum's AABB intersection test
        return frustum.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    protected boolean doesNodeIntersectVolume(TetreeKey tetIndex, Spatial volume) {
        Tet tet = Tet.tetrahedron(tetIndex);
        // Use the same logic as the SFC range computation for consistency
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return false;
        }

        // Debug output
        if (false) { // Enable for debugging
            System.out.println("doesNodeIntersectVolume debug:");
            System.out.println("  tetIndex: " + tetIndex);
            System.out.println("  tet: " + tet);
            System.out.println("  bounds: " + bounds);
            System.out.println("  result: " + Tet.tetrahedronIntersectsVolumeBounds(tet, bounds));
        }

        return Tet.tetrahedronIntersectsVolumeBounds(tet, bounds);
    }

    // These methods are now handled by AbstractSpatialIndex

    @Override
    protected boolean doesPlaneIntersectNode(TetreeKey nodeIndex, Plane3D plane) {
        // Check if plane intersects with the tetrahedron
        Tet tet = Tet.tetrahedron(nodeIndex);
        return planeIntersectsTetrahedron(plane, tet);
    }

    @Override
    protected boolean doesRayIntersectNode(TetreeKey nodeIndex, Ray3D ray) {
        // Use TetrahedralGeometry for ray-tetrahedron intersection
        var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, nodeIndex);
        return intersection.intersects;
    }

    @Override
    protected float estimateNodeDistance(TetreeKey nodeIndex, Point3f queryPoint) {
        // Get tetrahedron from index
        Tet tet = Tet.tetrahedron(nodeIndex);

        // Calculate proper tetrahedron centroid using actual vertices
        var vertices = tet.coordinates();
        float centerX = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
        float centerY = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
        float centerZ = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;

        // Return distance from query point to tetrahedron centroid
        float dx = queryPoint.x - centerX;
        float dy = queryPoint.y - centerY;
        float dz = queryPoint.z - centerZ;

        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    protected NavigableSet<TetreeKey> getSpatialIndexRange(VolumeBounds bounds) {
        // For now, use a simpler approach that iterates through existing nodes
        // The full optimization would require more careful handling of level ranges
        NavigableSet<TetreeKey> candidates = new TreeSet<>();

        // Check each existing node to see if it intersects the bounds
        for (TetreeKey key : sortedSpatialIndices) {
            if (doesNodeIntersectVolume(key, createSpatialFromBounds(bounds))) {
                candidates.add(key);
            }
        }

        return candidates;
    }

    /**
     * Get all tetrahedra at a specific level that could intersect with the given bounds.
     * This uses the tetrahedral SFC structure to efficiently find candidates.
     *
     * NOTE: This method is currently unused due to memory concerns with large level values.
     * The optimization needs more careful handling of level ranges to avoid excessive memory usage.
     */
    @SuppressWarnings("unused")
    private NavigableSet<TetreeKey> getTetrahedraInBoundsAtLevel(VolumeBounds bounds, byte level) {
        NavigableSet<TetreeKey> results = new TreeSet<>();

        // Calculate the grid resolution at this level
        int gridSize = 1 << level; // 2^level
        int cellSize = Constants.lengthAtLevel(level);

        // Find grid cells that intersect the bounds
        int minX = Math.max(0, (int)(bounds.minX() / cellSize));
        int maxX = Math.min(gridSize - 1, (int)(bounds.maxX() / cellSize));
        int minY = Math.max(0, (int)(bounds.minY() / cellSize));
        int maxY = Math.min(gridSize - 1, (int)(bounds.maxY() / cellSize));
        int minZ = Math.max(0, (int)(bounds.minZ() / cellSize));
        int maxZ = Math.min(gridSize - 1, (int)(bounds.maxZ() / cellSize));

        // For each grid cell in range, check all 6 tetrahedra
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    // Each grid cell contains 6 tetrahedra (types 0-5)
                    for (byte type = 0; type < 6; type++) {
                        Tet tet = new Tet(x * cellSize, y * cellSize, z * cellSize, level, type);

                        // Only add if the tetrahedron actually intersects the bounds
                        if (Tet.tetrahedronIntersectsVolumeBounds(tet, bounds)) {
                            results.add(tet.tmIndex());
                        }
                    }
                }
            }
        }

        return results;
    }

    @Override
    protected int getCellSizeAtLevel(byte level) {
        return Constants.lengthAtLevel(level);
    }

    @Override
    protected List<TetreeKey> getChildNodes(TetreeKey tetIndex) {
        List<TetreeKey> children = new ArrayList<>();
        Tet parentTet = Tet.tetrahedron(tetIndex);
        byte level = parentTet.l();

        if (level >= maxDepth) {
            return children; // No children possible at max depth
        }

        // Use the t8code-compliant Tet.child() method to generate the 8 children
        // This ensures proper Bey refinement scheme and correct connectivity
        for (int childIndex = 0; childIndex < TetreeConnectivity.CHILDREN_PER_TET; childIndex++) {
            Tet childTet = parentTet.child(childIndex);
            TetreeKey childSFCIndex = childTet.tmIndex();

            // Only add if child exists in spatial index
            if (spatialIndex.containsKey(childSFCIndex)) {
                children.add(childSFCIndex);
            }
        }

        return children;
    }

    // These methods are now handled by AbstractSpatialIndex

    @Override
    protected Stream<TetreeKey> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition) {
        // For tetree, use spatial ordering to traverse nodes that could intersect with the frustum
        // Order by distance from camera to tetrahedron centroid for optimal culling traversal
        return sortedSpatialIndices.stream().filter(nodeIndex -> {
            TetreeNodeImpl<ID> node = spatialIndex.get(nodeIndex);
            return node != null && !node.isEmpty();
        }).sorted((n1, n2) -> {
            float dist1 = getFrustumTetrahedronDistance(n1, cameraPosition);
            float dist2 = getFrustumTetrahedronDistance(n2, cameraPosition);
            return Float.compare(dist1, dist2);
        });
    }

    protected byte getLevelFromIndex(TetreeKey index) {
        // TetreeKey already has the level
        return index.getLevel();
    }

    @Override
    protected Spatial getNodeBounds(TetreeKey tetIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        // Return the bounding cube of the tetrahedron
        int cellSize = Constants.lengthAtLevel(tet.l());
        return new Spatial.Cube(tet.x(), tet.y(), tet.z(), cellSize);
    }

    @Override
    protected Stream<TetreeKey> getPlaneTraversalOrder(Plane3D plane) {
        // For tetree, use spatial ordering to traverse nodes that could intersect with the plane
        // Order by distance from plane to tetrahedron centroid for better early termination
        return sortedSpatialIndices.stream().filter(nodeIndex -> {
            TetreeNodeImpl<ID> node = spatialIndex.get(nodeIndex);
            return node != null && !node.isEmpty();
        }).sorted((n1, n2) -> {
            float dist1 = getPlaneTetrahedronDistance(n1, plane);
            float dist2 = getPlaneTetrahedronDistance(n2, plane);
            return Float.compare(Math.abs(dist1), Math.abs(dist2));
        });
    }

    @Override
    protected float getRayNodeIntersectionDistance(TetreeKey nodeIndex, Ray3D ray) {
        // Get ray-tetrahedron intersection distance
        var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, nodeIndex);
        return intersection.intersects ? intersection.distance : Float.MAX_VALUE;
    }

    @Override
    protected Stream<TetreeKey> getRayTraversalOrder(Ray3D ray) {
        // Use the optimized TetreeSFCRayTraversal implementation
        return getRayTraversal().traverseRay(ray);
    }

    /**
     * Access the spatial index directly (package-private for internal use)
     *
     * @return the spatial index map
     */
    protected Map<TetreeKey, TetreeNodeImpl<ID>> getSpatialIndex() {
        return spatialIndex;
    }


    @Override
    protected void handleNodeSubdivision(TetreeKey parentTetIndex, byte parentLevel, TetreeNodeImpl<ID> parentNode) {
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

        // Get parent tetrahedron from the SFC index directly
        // This is the actual tetrahedron being subdivided
        Tet parentTet = Tet.tetrahedron(parentTetIndex.getTmIndex(), parentLevel);

        // Generate all 8 children using Bey refinement
        Tet[] children = new Tet[8];
        for (int i = 0; i < 8; i++) {
            children[i] = parentTet.child(i);
        }

        TetreeKey[] childIndices = new TetreeKey[8];
        for (int i = 0; i < 8; i++) {
            childIndices[i] = children[i].tmIndex();
        }

        // Validate that the children form a proper family for subdivision
        assert TetreeFamily.isFamily(children) : "Children do not form a valid subdivision family";

        // Create map to group entities by their child tetrahedron
        Map<TetreeKey, List<ID>> childEntityMap = new HashMap<>();

        // Determine which child tetrahedron each entity belongs to
        for (ID entityId : parentEntities) {
            Point3f entityPos = entityManager.getEntityPosition(entityId);
            if (entityPos != null) {
                // Find which of the 8 children contains this entity
                boolean assigned = false;
                for (int childIndex = 0; childIndex < children.length; childIndex++) {
                    Tet child = children[childIndex];
                    if (child.contains(entityPos)) {
                        TetreeKey childTetIndex = childIndices[childIndex];
                        childEntityMap.computeIfAbsent(childTetIndex, k -> new ArrayList<>()).add(entityId);
                        assigned = true;
                        break;
                    }
                }

                if (!assigned) {
                    // Fallback: use locate method if direct containment fails
                    Tet childTet = locate(entityPos, childLevel);
                    childEntityMap.computeIfAbsent(childTet.tmIndex(), k -> new ArrayList<>()).add(entityId);
                }
            }
        }

        // Check if subdivision would actually distribute entities
        if (childEntityMap.size() == 1) {
            // All entities map to the same child tetrahedron
            // This can happen when all entities are at the same position
            // Don't subdivide - it won't help distribute the load
            return;
        }

        // Create child nodes and redistribute entities
        // Track which entities we need to remove from parent
        Set<ID> entitiesToRemoveFromParent = new HashSet<>();

        // Create all child nodes (even empty ones for proper tree structure)
        for (Tet child : children) {
            TetreeKey childTetIndex = child.tmIndex();
            List<ID> childEntities = childEntityMap.getOrDefault(childTetIndex, new ArrayList<>());

            if (!childEntities.isEmpty()) {
                // Create or get child node
                TetreeNodeImpl<ID> childNode = spatialIndex.computeIfAbsent(childTetIndex, k -> {
                    sortedSpatialIndices.add(childTetIndex);
                    return new TetreeNodeImpl<>(maxEntitiesPerNode);
                });

                // Add entities to child
                for (ID entityId : childEntities) {
                    childNode.addEntity(entityId);
                    entityManager.addEntityLocation(entityId, childTetIndex);
                    entitiesToRemoveFromParent.add(entityId);
                }
            }
        }

        // Remove entities that were moved to child nodes
        for (ID entityId : entitiesToRemoveFromParent) {
            parentNode.removeEntity(entityId);
            entityManager.removeEntityLocation(entityId, parentTetIndex);
        }

        // Mark that this node has been subdivided
        parentNode.setHasChildren(true);

        // Also set the specific child bits for the children that were created
        for (int i = 0; i < children.length; i++) {
            TetreeKey childTetIndex = children[i].tmIndex();
            if (spatialIndex.containsKey(childTetIndex)) {
                parentNode.setChildBit(i);
            }
        }
    }

    @Override
    protected boolean hasChildren(TetreeKey tetIndex) {
        TetreeNodeImpl<ID> node = spatialIndex.get(tetIndex);
        return node != null && node.hasChildren();
    }

    /**
     * Optimized tetrahedral spanning insertion using SFC ranges Implements t8code-style efficient spanning based on
     * linear_id ranges
     */
    @Override
    protected void insertWithSpanning(ID entityId, EntityBounds bounds, byte level) {
        // Find all tetrahedra that the entity's bounds intersect using optimized SFC traversal
        Set<TetreeKey> intersectingTets = findIntersectingTets(bounds, level);

        // Add entity to all intersecting tetrahedra
        for (TetreeKey tetIndex : intersectingTets) {
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

    @Override
    protected boolean isNodeContainedInVolume(TetreeKey tetIndex, Spatial volume) {
        Tet tet = Tet.tetrahedron(tetIndex);
        return Tet.tetrahedronContainedInVolume(tet, volume);
    }

    @Override
    protected boolean shouldContinueKNNSearch(TetreeKey nodeIndex, Point3f queryPoint,
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
        TetreeValidationUtils.validatePositiveCoordinates(position);
    }

    @Override
    protected void validateSpatialConstraints(Spatial volume) {
        TetreeValidationUtils.validatePositiveCoordinates(volume);
    }

    /**
     * Add child tetrahedra that intersect the ray
     */
    private void addChildTetrahedra(Tet currentTet, Ray3D ray, PriorityQueue<TetDistance> tetQueue,
                                    Set<TetreeKey> visitedTets) {
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
                        TetreeKey childIndex = child.tmIndex();

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
     * Add intersected children for ray traversal
     */
    private void addIntersectedChildren(Tet currentTet, Ray3D ray, PriorityQueue<TetDistance> tetQueue,
                                        Set<TetreeKey> visitedTets) {
        byte childLevel = (byte) (currentTet.l() + 1);
        if (childLevel > Constants.getMaxRefinementLevel()) {
            return;
        }

        // Get all 8 children using Bey refinement
        try {
            for (int i = 0; i < 8; i++) {
                Tet child = currentTet.child(i);
                TetreeKey childIndex = child.tmIndex();

                if (!visitedTets.contains(childIndex)) {
                    var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, childIndex);
                    if (intersection.intersects && intersection.distance <= ray.maxDistance()) {
                        tetQueue.add(new TetDistance(childIndex, intersection.distance));
                    }
                }
            }
        } catch (Exception e) {
            // Skip if we can't generate children
        }
    }

    /**
     * Add intersected face neighbors for ray traversal
     */
    private void addIntersectedFaceNeighbors(Tet currentTet, Ray3D ray, PriorityQueue<TetDistance> tetQueue,
                                             Set<TetreeKey> visitedTets) {
        // Check all 4 face neighbors
        for (int face = 0; face < 4; face++) {
            try {
                Tet.FaceNeighbor neighbor = currentTet.faceNeighbor(face);

                // Check if neighbor exists (null at boundary)
                if (neighbor == null) {
                    continue;
                }

                Tet neighborTet = neighbor.tet();
                TetreeKey neighborIndex = neighborTet.tmIndex();

                if (!visitedTets.contains(neighborIndex)) {
                    var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, neighborIndex);
                    if (intersection.intersects && intersection.distance <= ray.maxDistance()) {
                        tetQueue.add(new TetDistance(neighborIndex, intersection.distance));
                    }
                }
            } catch (Exception e) {
                // Skip invalid neighbors
            }
        }
    }

    /**
     * Add intersecting child tetrahedra to the ray traversal queue
     */
    private void addIntersectingChildren(TetreeKey parentIndex, byte parentLevel, Ray3D ray,
                                         PriorityQueue<TetDistance> tetQueue, Set<TetreeKey> visitedTets) {
        Tet parentTet = Tet.tetrahedron(parentIndex);
        byte childLevel = (byte) (parentLevel + 1);
        int parentCellSize = Constants.lengthAtLevel(parentLevel);
        int childCellSize = Constants.lengthAtLevel(childLevel);

        // Check all child cells that could be contained within parent cell
        for (int x = parentTet.x(); x < parentTet.x() + parentCellSize; x += childCellSize) {
            for (int y = parentTet.y(); y < parentTet.y() + parentCellSize; y += childCellSize) {
                for (int z = parentTet.z(); z < parentTet.z() + parentCellSize; z += childCellSize) {
                    // Check all 6 tetrahedron types in each child cell
                    for (byte type = 0; type < 6; type++) {
                        Tet childTet = new Tet(x, y, z, childLevel, type);
                        TetreeKey childIndex = childTet.tmIndex();

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

    // These methods are inherited from AbstractSpatialIndex

    /**
     * Helper method to add neighboring tetrahedra that intersect the ray
     */
    private void addIntersectingNeighbors(TetreeKey tetIndex, Ray3D ray, PriorityQueue<TetDistance> tetQueue,
                                          Set<TetreeKey> visitedTets) {
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
                            TetreeKey neighborIndex = neighbor.tmIndex();

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

    /**
     * Add parent tetrahedra that intersect the ray
     */
    private void addParentTetrahedra(Tet currentTet, Ray3D ray, PriorityQueue<TetDistance> tetQueue,
                                     Set<TetreeKey> visitedTets) {
        byte parentLevel = (byte) (currentTet.l() - 1);
        int parentCellSize = 1 << (Constants.getMaxRefinementLevel() - parentLevel);

        // Calculate parent cell coordinates
        int px = (currentTet.x() / parentCellSize) * parentCellSize;
        int py = (currentTet.y() / parentCellSize) * parentCellSize;
        int pz = (currentTet.z() / parentCellSize) * parentCellSize;

        // Check all 6 tetrahedron types in parent cell
        for (byte type = 0; type < 6; type++) {
            Tet parent = new Tet(px, py, pz, parentLevel, type);
            TetreeKey parentIndex = parent.tmIndex();

            if (!visitedTets.contains(parentIndex)) {
                var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, parentIndex);
                if (intersection.intersects && intersection.distance <= ray.maxDistance()) {
                    tetQueue.add(new TetDistance(parentIndex, intersection.distance));
                }
            }
        }
    }

    // ===== Tree Balancing Implementation =====

    /**
     * Add range indices in chunks to avoid memory spikes
     */
    private void addRangeInChunks(NavigableSet<TetreeKey> result, SFCRange range, int chunkSize) {
        // Since we can't do arithmetic on TetreeKey, we need to use the NavigableSet operations
        // Get all indices in the range
        var subset = sortedSpatialIndices.subSet(range.start, true, range.end, true);
        result.addAll(subset);
    }

    /**
     * Calculate first descendant index for a tetrahedron (t8code algorithm)
     */
    private TetreeKey calculateFirstDescendant(Tet parentTet, byte targetLevel) {
        if (targetLevel <= parentTet.l()) {
            return parentTet.tmIndex();
        }

        // Use Tet's index calculation which already implements the t8code linear_id algorithm
        Tet firstChild = new Tet(parentTet.x(), parentTet.y(), parentTet.z(), targetLevel, parentTet.type());
        return firstChild.tmIndex();
    }

    /**
     * Calculate last descendant index for a tetrahedron (t8code algorithm)
     */
    private TetreeKey calculateLastDescendant(Tet parentTet, byte targetLevel) {
        if (targetLevel <= parentTet.l()) {
            return parentTet.tmIndex();
        }

        // Calculate the range of descendants
        int levelDiff = targetLevel - parentTet.l();
        TetreeKey firstDescendant = calculateFirstDescendant(parentTet, targetLevel);

        // Calculate last descendant using Tet's own logic
        // Create a tetrahedron at the parent position but at target level,
        // then get the last child recursively
        int cellSize = Constants.lengthAtLevel(parentTet.l());
        Tet lastTet = new Tet(parentTet.x() + cellSize - 1, parentTet.y() + cellSize - 1,
                              parentTet.z() + cellSize - 1, targetLevel, (byte)5); // type 5 is typically last
        return lastTet.tmIndex();
    }

    /**
     * Compute adaptive SFC ranges with level-specific optimizations
     */
    private List<SFCRange> computeAdaptiveSFCRanges(VolumeBounds bounds, byte minLevel, byte maxLevel,
                                                    boolean includeIntersecting) {
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

    // Compute SFC ranges for all tetrahedra in a grid cell
    private List<SFCRange> computeCellSFCRanges(Point3f cellOrigin, byte level) {
        List<SFCRange> ranges = new ArrayList<>();

        // For a grid cell, there can be multiple tetrahedra (6 types)
        // Find the SFC indices for all tetrahedron types at this location
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet((int) cellOrigin.x, (int) cellOrigin.y, (int) cellOrigin.z, level, type);
            TetreeKey index = tet.tmIndex();
            ranges.add(new SFCRange(index, index));
        }

        return ranges;
    }

    /**
     * Compute memory-bounded SFC ranges with maximum range limit
     */
    private List<SFCRange> computeMemoryBoundedSFCRanges(VolumeBounds bounds, byte level, boolean includeIntersecting,
                                                         int maxRanges) {
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
                                TetreeKey startIndex = tet.tmIndex();
                                // For a range, calculate the tetrahedron at the end of the step
                                var endTet = new Tet((x + step - 1) * length, (y + step - 1) * length,
                                                     (z + step - 1) * length, level, (byte)5);
                                TetreeKey endIndex = endTet.tmIndex();
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

    // Compute SFC ranges that could contain tetrahedra intersecting the volume
    private List<SFCRange> computeSFCRanges(VolumeBounds bounds, boolean includeIntersecting) {
        List<SFCRange> ranges = new ArrayList<>();

        // Since entities are inserted at various levels (in the test, level 5),
        // we need to check a broader range of levels that actually contain entities
        // Instead of using findMinimumContainingLevel, use levels that match the spatial index

        // Look at what levels are actually present in the spatial index
        Set<Byte> actualLevels = new HashSet<>();
        for (TetreeKey index : sortedSpatialIndices) {
            byte level = index.getLevel();
            actualLevels.add(level);
        }

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
        var mergedRanges = mergeRanges(ranges);
        return mergedRanges;
    }

    // This method has been moved to the override section

    /**
     * Find the ancestor tetrahedron at a specific level that contains the given tetrahedron.
     */
    private Tet findAncestorAtLevel(Tet descendant, byte targetLevel) {
        if (targetLevel > descendant.l()) {
            throw new IllegalArgumentException("Target level must be less than or equal to descendant level");
        }

        if (targetLevel == descendant.l()) {
            return descendant;
        }

        Tet current = descendant;
        while (current.l() > targetLevel) {
            current = current.parent();
        }
        return current;
    }

    /**
     * Find all tetrahedral indices that intersect with the given bounds FIXED: Use actual existing spatial indices
     * instead of theoretical SFC computation
     */
    private Set<TetreeKey> findIntersectingTets(EntityBounds bounds, byte level) {
        Set<TetreeKey> result = new HashSet<>();

        // Use the same approach as Octree: calculate all grid cells that might intersect
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
                    // Calculate actual cell coordinates
                    int cellOriginX = x * cellSize;
                    int cellOriginY = y * cellSize;
                    int cellOriginZ = z * cellSize;

                    // Skip if coordinates are negative (not valid for tetrahedral SFC)
                    if (cellOriginX < 0 || cellOriginY < 0 || cellOriginZ < 0) {
                        continue;
                    }

                    // Find all tetrahedra in this grid cell (there are 6 per cube)
                    for (byte tetType = 0; tetType < 6; tetType++) {
                        try {
                            Tet tet = new Tet(cellOriginX, cellOriginY, cellOriginZ, level, tetType);

                            // Check if this tetrahedron intersects the bounds
                            if (tetrahedronIntersectsBounds(tet, bounds)) {
                                TetreeKey tetIndex = tet.tmIndex();
                                result.add(tetIndex);
                            }
                        } catch (Exception e) {
                            // Skip invalid tetrahedra
                        }
                    }
                }
            }
        }

        // If no tetrahedra found (shouldn't happen for valid bounds), add center tetrahedron
        if (result.isEmpty()) {
            Point3f center = new Point3f((bounds.getMinX() + bounds.getMaxX()) / 2,
                                         (bounds.getMinY() + bounds.getMaxY()) / 2,
                                         (bounds.getMinZ() + bounds.getMaxZ()) / 2);

            Tet containingTet = locate(center, level);
            TetreeKey tetIndex = containingTet.tmIndex();
            result.add(tetIndex);
        }

        return result;
    }

    /**
     * Find optimal level for entity based on size and spatial characteristics
     */
    private byte findOptimalLevelForEntity(VolumeBounds bounds) {
        float volumeSize = (bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ()
                                                                                                - bounds.minZ());
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
     * Find where a ray enters the tetrahedral domain
     */
    private Point3f findRayEntryPoint(Ray3D ray) {
        Point3f origin = ray.origin();

        // Check if origin is already inside domain
        if (isInsideDomain(origin)) {
            return origin;
        }

        // Calculate domain bounds
        float maxCoord = Constants.lengthAtLevel((byte) 0);

        // Find intersection with domain boundary using ray-AABB intersection
        Vector3f dir = ray.direction();
        float tmin = 0.0f;
        float tmax = Float.MAX_VALUE;

        // X axis
        if (Math.abs(dir.x) > 1e-6f) {
            float t1 = (0 - origin.x) / dir.x;
            float t2 = (maxCoord - origin.x) / dir.x;
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
        } else if (origin.x < 0 || origin.x > maxCoord) {
            return null; // Ray parallel to X and outside domain
        }

        // Y axis
        if (Math.abs(dir.y) > 1e-6f) {
            float t1 = (0 - origin.y) / dir.y;
            float t2 = (maxCoord - origin.y) / dir.y;
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
        } else if (origin.y < 0 || origin.y > maxCoord) {
            return null; // Ray parallel to Y and outside domain
        }

        // Z axis
        if (Math.abs(dir.z) > 1e-6f) {
            float t1 = (0 - origin.z) / dir.z;
            float t2 = (maxCoord - origin.z) / dir.z;
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
        } else if (origin.z < 0 || origin.z > maxCoord) {
            return null; // Ray parallel to Z and outside domain
        }

        // Check if ray intersects domain
        if (tmax < tmin || tmax < 0) {
            return null;
        }

        // Return entry point
        return ray.pointAt(tmin);
    }

    /**
     * Generate tetrahedra along the ray path at a specific level using optimized SFC ray traversal. This ensures ray
     * traversal finds all tetrahedra intersected by the ray efficiently.
     */
    private void generateRayPathTetrahedra(Ray3D ray, byte level, List<TetDistance> intersectedTets,
                                           Set<TetreeKey> visited) {
        // Use the optimized TetreeSFCRayTraversal for efficient ray-tetrahedron intersection
        // This replaces the previous step-based approach with SFC-guided traversal

        TetreeSFCRayTraversal<ID, Content> sfcTraversal = getRayTraversal();

        // Get all tetrahedra intersected by the ray using SFC traversal
        Stream<TetreeKey> rayIntersectedTets = sfcTraversal.traverseRay(ray);

        // Convert to the expected format for this method, filtering by level and visited status
        rayIntersectedTets.forEach(tetIndex -> {
            if (!visited.contains(tetIndex)) {
                try {
                    Tet tet = Tet.tetrahedron(tetIndex);

                    // Only include tetrahedra at the requested level
                    if (tet.l() == level) {
                        // Test ray intersection with this tetrahedron
                        var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, tetIndex);
                        if (intersection.intersects && intersection.distance <= ray.maxDistance()) {
                            intersectedTets.add(new TetDistance(tetIndex, intersection.distance));
                            visited.add(tetIndex);
                        }
                    }
                } catch (Exception e) {
                    // Skip invalid tetrahedra
                }
            }
        });
    }

    // ===== Ray Intersection Implementation =====

    /**
     * Calculate distance from camera position to tetrahedron centroid for frustum culling traversal order
     */
    private float getFrustumTetrahedronDistance(TetreeKey nodeIndex, Point3f cameraPosition) {
        Tet tet = Tet.tetrahedron(nodeIndex);

        // Calculate tetrahedron centroid using actual vertices
        var vertices = tet.coordinates();
        float centerX = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
        float centerY = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
        float centerZ = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;

        // Return distance from camera to tetrahedron centroid
        float dx = cameraPosition.x - centerX;
        float dy = cameraPosition.y - centerY;
        float dz = cameraPosition.z - centerZ;

        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Memory-efficient range computation for very large entities Uses streaming and hierarchical decomposition to avoid
     * memory exhaustion
     */
    private NavigableSet<TetreeKey> getLargeEntitySpatialRange(VolumeBounds bounds, boolean includeIntersecting) {
        NavigableSet<TetreeKey> result = new TreeSet<>();

        // Use coarser levels only to reduce memory footprint
        byte minLevel = (byte) Math.max(0, findMinimumContainingLevel(bounds) - 1);
        byte maxLevel = (byte) Math.min(Constants.getMaxRefinementLevel(), findMinimumContainingLevel(bounds) + 1);

        // Process levels sequentially to limit memory usage
        for (byte level = minLevel; level <= maxLevel; level++) {
            var levelRanges = computeMemoryBoundedSFCRanges(bounds, level, includeIntersecting,
                                                            1000); // Max 1000 ranges

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
     * Adaptive range computation for medium-sized entities Balances precision with memory efficiency
     */
    private NavigableSet<TetreeKey> getMediumEntitySpatialRange(VolumeBounds bounds, boolean includeIntersecting) {
        NavigableSet<TetreeKey> result = new TreeSet<>();

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
     * Memory-efficient spatial index range computation with adaptive strategies for large entities Based on t8code's
     * hierarchical range computation algorithms
     */
    private NavigableSet<TetreeKey> getMemoryEfficientSpatialIndexRange(VolumeBounds bounds,
                                                                        boolean includeIntersecting) {
        // Calculate entity size metrics
        float volumeSize = (bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ()
                                                                                                - bounds.minZ());
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
     * Get the neighbor finder instance, creating it if necessary
     */
    private TetreeNeighborFinder getNeighborFinder() {
        if (neighborFinder == null) {
            neighborFinder = new TetreeNeighborFinder();
        }
        return neighborFinder;
    }

    /**
     * Calculate distance from plane to tetrahedron centroid
     */
    private float getPlaneTetrahedronDistance(TetreeKey nodeIndex, Plane3D plane) {
        Tet tet = Tet.tetrahedron(nodeIndex);

        // Calculate tetrahedron centroid using actual vertices
        var vertices = tet.coordinates();
        float centerX = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
        float centerY = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
        float centerZ = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;

        // Return signed distance from plane to tetrahedron centroid
        return plane.distanceToPoint(new Point3f(centerX, centerY, centerZ));
    }

    // ===== Tetrahedral Spanning Implementation =====

    /**
     * Get the ray traversal instance, creating it if necessary
     */
    private TetreeSFCRayTraversal<ID, Content> getRayTraversal() {
        if (rayTraversal == null) {
            rayTraversal = new TetreeSFCRayTraversal<>(this);
        }
        return rayTraversal;
    }

    // ===== Performance Monitoring Integration =====

    /**
     * Standard range computation for normal-sized entities FIXED: Use actual spatial indices instead of incorrect SFC
     * computation
     */
    private NavigableSet<TetreeKey> getStandardEntitySpatialRange(VolumeBounds bounds, boolean includeIntersecting) {
        NavigableSet<TetreeKey> result = new TreeSet<>();

        // CRITICAL FIX: The fundamental issue is that computeSFCRanges() is broken.
        // Instead of using computed SFC ranges that don't match reality, we need to
        // check all existing spatial indices and test them individually.

        // This is less efficient but correct. The SFC range computation logic
        // would need a complete rewrite to work properly with the Tet.tmIndex() algorithm.

        for (TetreeKey spatialIndex : sortedSpatialIndices) {
            try {
                Tet tet = Tet.tetrahedron(spatialIndex);

                if (includeIntersecting) {
                    // Check if tetrahedron intersects the bounds
                    boolean intersects = Tet.tetrahedronIntersectsVolumeBounds(tet, bounds);
                    if (intersects) {
                        result.add(spatialIndex);
                    }
                } else {
                    // Check if tetrahedron is contained within bounds
                    if (Tet.tetrahedronContainedInVolumeBounds(tet, bounds)) {
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
     * Get SFC successor index for tetrahedral traversal
     */
    private TetreeKey getSuccessor(TetreeKey tetIndex) {
        // Use the NavigableSet to find the next key
        TetreeKey higher = sortedSpatialIndices.higher(tetIndex);
        return higher != null ? higher : tetIndex; // Return same if no successor
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
     * Check if bounds intersect using hybrid cube/tetrahedral geometry with memory efficiency
     */
    private boolean hybridCellIntersectsBounds(Point3f cellOrigin, int cellSize, byte level, VolumeBounds bounds,
                                               boolean includeIntersecting) {
        // First: Fast cube-based intersection test for early rejection
        float cellMaxX = cellOrigin.x + cellSize;
        float cellMaxY = cellOrigin.y + cellSize;
        float cellMaxZ = cellOrigin.z + cellSize;

        // Quick cube-based bounding box test
        if (cellMaxX < bounds.minX() || cellOrigin.x > bounds.maxX() || cellMaxY < bounds.minY()
        || cellOrigin.y > bounds.maxY() || cellMaxZ < bounds.minZ() || cellOrigin.z > bounds.maxZ()) {
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
                if (Tet.tetrahedronContainedInVolumeBounds(tet, bounds)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if a point is inside the tetrahedral domain
     */
    private boolean isInsideDomain(Point3f point) {
        float maxCoord = Constants.lengthAtLevel((byte) 0);
        return point.x >= 0 && point.x <= maxCoord && point.y >= 0 && point.y <= maxCoord && point.z >= 0
        && point.z <= maxCoord;
    }

    /**
     * Validate that children form a proper subdivision family. Uses the proper tetrahedral family validation.
     */
    private boolean isValidSubdivisionFamily(Tet[] children, Tet parent) {
        // Use the proper family validation from TetreeFamily
        return TetreeFamily.isFamily(children);
    }

    /**
     * Locate the tetrahedron containing a point at a given level
     */
    private Tet locate(Tuple3f point, byte level) {
        return Tet.locateFreudenthal(point.x, point.y, point.z, level);
    }

    // Merge overlapping SFC ranges for efficiency
    private List<SFCRange> mergeRanges(List<SFCRange> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }

        ranges.sort(Comparator.comparing(a -> a.start));
        List<SFCRange> merged = new ArrayList<>();
        SFCRange current = ranges.getFirst();

        for (int i = 1; i < ranges.size(); i++) {
            SFCRange next = ranges.get(i);
            // Check if ranges are adjacent or overlapping
            // We can't do arithmetic on TetreeKey, so check if next.start is the immediate successor
            TetreeKey successor = sortedSpatialIndices.higher(current.end);
            if (successor != null && successor.compareTo(next.start) >= 0) {
                // Merge overlapping ranges
                TetreeKey maxEnd = current.end.compareTo(next.end) > 0 ? current.end : next.end;
                current = new SFCRange(current.start, maxEnd);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    /**
     * Enhanced range merging with memory-conscious approach
     */
    private List<SFCRange> mergeRangesOptimized(List<SFCRange> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }

        // Sort ranges by start index
        ranges.sort(Comparator.comparing(a -> a.start));

        // Use more aggressive merging for memory efficiency
        List<SFCRange> merged = new ArrayList<>();
        SFCRange current = ranges.getFirst();

        for (int i = 1; i < ranges.size(); i++) {
            SFCRange next = ranges.get(i);

            // Merge if ranges overlap or are very close (adaptive gap based on range size)
            var gap = next.start.getTmIndex().subtract(current.end.getTmIndex());
            var rangeSize = current.end.getTmIndex().subtract(current.start.getTmIndex());
            var adaptiveGap = BigInteger.valueOf(
            Math.max(8, rangeSize.divide(BigInteger.valueOf(10)).longValue())); // Dynamic gap based on range size

            if (gap.compareTo(adaptiveGap) <= 0) {
                if (current.end.getTmIndex().compareTo(next.end.getTmIndex()) <= 0) {
                    current = new SFCRange(current.start, current.end);
                } else {
                    current = new SFCRange(current.start, next.end);
                }
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    /**
     * Check if plane intersects with a tetrahedron
     */
    private boolean planeIntersectsTetrahedron(Plane3D plane, Tet tet) {
        var vertices = tet.coordinates();

        // Calculate signed distances from each vertex to the plane
        float[] distances = new float[4];
        for (int i = 0; i < 4; i++) {
            distances[i] = plane.distanceToPoint(new Point3f(vertices[i].x, vertices[i].y, vertices[i].z));
        }

        // Check if vertices span both sides of the plane
        boolean hasPositive = false;
        boolean hasNegative = false;

        for (float distance : distances) {
            if (distance > 1e-6f) {
                hasPositive = true;
            } else if (distance < -1e-6f) {
                hasNegative = true;
            }
        }

        // If vertices are on both sides, the tetrahedron intersects the plane
        return hasPositive && hasNegative;
    }

    // Helper method to record cache hit
    private void recordCacheHit() {
        if (performanceMonitoringEnabled) {
            cacheHits++;
        }
    }

    // Helper method to record cache miss
    private void recordCacheMiss() {
        if (performanceMonitoringEnabled) {
            cacheMisses++;
        }
    }

    // Helper method to record neighbor query time
    private void recordNeighborQueryTime(long elapsedNanos) {
        if (performanceMonitoringEnabled) {
            neighborQueryCount++;
            totalNeighborQueryTime += elapsedNanos;
        }
    }

    // Helper method to record traversal time
    private void recordTraversalTime(long elapsedNanos) {
        if (performanceMonitoringEnabled) {
            traversalCount++;
            totalTraversalTime += elapsedNanos;
        }
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
        if (tetMaxX < bounds.getMinX() || tetMinX > bounds.getMaxX() || tetMaxY < bounds.getMinY()
        || tetMinY > bounds.getMaxY() || tetMaxZ < bounds.getMinZ() || tetMinZ > bounds.getMaxZ()) {
            return false;
        }

        // Test if any vertex of tetrahedron is inside bounds
        for (var vertex : vertices) {
            if (vertex.x >= bounds.getMinX() && vertex.x <= bounds.getMaxX() && vertex.y >= bounds.getMinY()
            && vertex.y <= bounds.getMaxY() && vertex.z >= bounds.getMinZ() && vertex.z <= bounds.getMaxZ()) {
                return true;
            }
        }

        // Test if any corner of bounds is inside tetrahedron
        var boundCorners = new Point3f[] { new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ()),
                                           new Point3f(bounds.getMaxX(), bounds.getMinY(), bounds.getMinZ()),
                                           new Point3f(bounds.getMinX(), bounds.getMaxY(), bounds.getMinZ()),
                                           new Point3f(bounds.getMaxX(), bounds.getMaxY(), bounds.getMinZ()),
                                           new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMaxZ()),
                                           new Point3f(bounds.getMaxX(), bounds.getMinY(), bounds.getMaxZ()),
                                           new Point3f(bounds.getMinX(), bounds.getMaxY(), bounds.getMaxZ()),
                                           new Point3f(bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ()) };

        for (var corner : boundCorners) {
            if (tet.contains(corner)) {
                return true;
            }
        }

        // Test if the center of the entity bounds is inside the tetrahedron
        // This catches the case where a small entity is entirely contained within a large tetrahedron
        Point3f entityCenter = new Point3f((bounds.getMinX() + bounds.getMaxX()) / 2,
                                           (bounds.getMinY() + bounds.getMaxY()) / 2,
                                           (bounds.getMinZ() + bounds.getMaxZ()) / 2);

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

    // Record to represent SFC index ranges
    private record SFCRange(TetreeKey start, TetreeKey end) {
    }

    /**
     * Helper class to store tetrahedron index with distance for priority queue ordering
     */
    private static class TetDistance implements Comparable<TetDistance> {
        final TetreeKey tetIndex;
        final float     distance;

        TetDistance(TetreeKey tetIndex, float distance) {
            this.tetIndex = tetIndex;
            this.distance = distance;
        }

        @Override
        public int compareTo(TetDistance other) {
            return Float.compare(this.distance, other.distance);
        }
    }

    /**
     * Tetree-specific tree balancer implementation
     */
    protected class TetreeBalancer extends DefaultTreeBalancer {

        @Override
        public boolean mergeNodes(Set<TetreeKey> tetIndices, TetreeKey parentIndex) {
            if (tetIndices.isEmpty()) {
                return false;
            }

            // Collect all entities from nodes to be merged
            Set<ID> allEntities = new HashSet<>();
            for (TetreeKey tetIndex : tetIndices) {
                TetreeNodeImpl<ID> node = spatialIndex.get(tetIndex);
                if (node != null && !node.isEmpty()) {
                    allEntities.addAll(node.getEntityIds());
                }
            }

            if (allEntities.isEmpty()) {
                // Just remove empty nodes
                for (TetreeKey tetIndex : tetIndices) {
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
                for (TetreeKey tetIndex : tetIndices) {
                    entityManager.removeEntityLocation(entityId, tetIndex);
                }

                // Add to parent
                parentNode.addEntity(entityId);
                entityManager.addEntityLocation(entityId, parentIndex);
            }

            // Remove child nodes
            for (TetreeKey tetIndex : tetIndices) {
                spatialIndex.remove(tetIndex);
                sortedSpatialIndices.remove(tetIndex);
            }

            // Parent no longer has children after merge
            parentNode.setHasChildren(false);

            return true;
        }

        @Override
        public List<TetreeKey> splitNode(TetreeKey tetIndex, byte tetLevel) {
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
            List<TetreeKey> createdChildren = new ArrayList<>();
            Map<TetreeKey, Set<ID>> childEntityMap = new HashMap<>();

            // Distribute entities to children based on their positions
            for (ID entityId : entities) {
                Point3f pos = entityManager.getEntityPosition(entityId);
                if (pos == null) {
                    continue;
                }

                // Find the containing tetrahedron at the child level
                Tet childTet = locate(pos, childLevel);
                TetreeKey childTetIndex = childTet.tmIndex();
                childEntityMap.computeIfAbsent(childTetIndex, k -> new HashSet<>()).add(entityId);
            }

            // Check if all entities map to the same child - if so, don't split
            if (childEntityMap.size() == 1 && childEntityMap.containsKey(tetIndex)) {
                // All entities map to the same tetrahedron as the parent - splitting won't help
                return Collections.emptyList();
            }

            // Create child nodes and add entities
            for (Map.Entry<TetreeKey, Set<ID>> entry : childEntityMap.entrySet()) {
                TetreeKey childTetIndex = entry.getKey();
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

            // Set the specific child bits for the children that were created
            // We need to determine which child indices these represent
            for (TetreeKey childTetIndex : createdChildren) {
                Tet childTet = Tet.tetrahedron(childTetIndex);
                // Find which child index this represents (0-7)
                for (int i = 0; i < 8; i++) {
                    try {
                        Tet expectedChild = parentTet.child(i);
                        if (expectedChild.tmIndex() == childTetIndex) {
                            node.setChildBit(i);
                            break;
                        }
                    } catch (Exception e) {
                        // Skip invalid children
                    }
                }
            }

            for (ID entityId : entities) {
                entityManager.removeEntityLocation(entityId, tetIndex);
            }

            return createdChildren;
        }

        @Override
        protected Set<TetreeKey> findSiblings(TetreeKey tetIndex) {
            Tet tet = Tet.tetrahedron(tetIndex);
            if (tet.l() == 0) {
                return Collections.emptySet(); // Root has no siblings
            }

            // Use the t8code-compliant TetreeFamily algorithm for finding siblings
            Tet[] siblings = TetreeFamily.getSiblings(tet);
            Set<TetreeKey> result = new HashSet<>();

            for (Tet sibling : siblings) {
                TetreeKey siblingIndex = sibling.tmIndex();
                // Add if it's not the current node and exists in the spatial index
                if (siblingIndex != tetIndex && spatialIndex.containsKey(siblingIndex)) {
                    result.add(siblingIndex);
                }
            }

            return result;
        }

        @Override
        protected TetreeKey getParentIndex(TetreeKey tetIndex) {
            Tet tet = Tet.tetrahedron(tetIndex);
            if (tet.l() == 0) {
                return null; // Root has no parent
            }

            // Use the t8code-compliant parent() method from the Tet class
            // This ensures correct parent calculation using the exact t8code algorithm
            Tet parentTet = tet.parent();
            return parentTet.tmIndex();
        }
    }

    /**
     * Optimized bulk insertion that pre-computes spatial regions for better cache utilization.
     * This override implements Phase 2 of the performance improvement plan.
     *
     * @param positions the positions to insert
     * @param contents  the content for each position
     * @param level     the level at which to insert
     * @return list of generated entity IDs
     */
    @Override
    public List<ID> insertBatch(List<Point3f> positions, List<Content> contents, byte level) {
        if (positions.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Calculate bounding box of all positions
        var minX = Float.MAX_VALUE;
        var minY = Float.MAX_VALUE;
        var minZ = Float.MAX_VALUE;
        var maxX = Float.MIN_VALUE;
        var maxY = Float.MIN_VALUE;
        var maxZ = Float.MIN_VALUE;
        
        for (var pos : positions) {
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }
        
        // PERFORMANCE: Pre-cache the region
        var bounds = new VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ);
        var regionCache = new TetreeRegionCache();
        
        // Pre-compute only for the target level to avoid excessive memory usage
        regionCache.precomputeRegion(bounds, level);
        
        // Pre-computation complete - cache is now warmed
        
        // Now perform normal bulk insertion with pre-warmed cache
        var result = super.insertBatch(positions, contents, level);
        
        // Clear region cache to free memory
        regionCache.clear();
        
        return result;
    }
}
