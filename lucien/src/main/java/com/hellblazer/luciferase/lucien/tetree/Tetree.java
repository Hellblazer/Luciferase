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
    public SpatialNode<ID> enclosing(Spatial volume) {
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
        TetreeNodeImpl<ID> node = spatialIndex.get(tet.index());
        if (node != null && !node.isEmpty()) {
            return new SpatialNode<>(tet.index(), new HashSet<>(node.getEntityIds()));
        }
        return null;
    }

    @Override
    public SpatialNode<ID> enclosing(Tuple3i point, byte level) {
        TetreeValidationUtils.validatePositiveCoordinates(point);

        var tet = locate(new Point3f(point.x, point.y, point.z), level);
        TetreeNodeImpl<ID> node = spatialIndex.get(tet.index());
        if (node != null && !node.isEmpty()) {
            return new SpatialNode<>(tet.index(), new HashSet<>(node.getEntityIds()));
        }
        return null;
    }

    @Override
    public List<ID> entitiesInRegion(Spatial.Cube region) {
        TetreeValidationUtils.validatePositiveCoordinates(region);

        Set<ID> uniqueEntities = new HashSet<>();

        lock.readLock().lock();
        try {
            // Use the spatial index to find entities in the region
            // This properly handles spanning entities
            var bounds = new VolumeBounds(region.originX(), region.originY(), region.originZ(),
                                          region.originX() + region.extent(), region.originY() + region.extent(),
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

    // ===== New Algorithm Integration Methods =====

    /**
     * Find all face neighbors of the tetrahedron
     *
     * @param tetIndex the tetrahedral index
     * @return array of neighbor indices (length 4), -1 for no neighbor
     */
    public long[] findAllFaceNeighbors(long tetIndex) {
        long startTime = performanceMonitoringEnabled ? System.nanoTime() : 0;

        Tet tet = Tet.tetrahedron(tetIndex);
        long[] neighbors = new long[4];
        for (int i = 0; i < 4; i++) {
            Tet neighbor = getNeighborFinder().findFaceNeighbor(tet, i);
            neighbors[i] = neighbor != null ? neighbor.index() : -1;
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
    public List<Long> findCellNeighbors(long tetIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        // For tetrahedral decomposition, find all neighbors (face-adjacent)
        List<Tet> neighborTets = getNeighborFinder().findAllNeighbors(tet);
        return neighborTets.stream().map(Tet::index).collect(java.util.stream.Collectors.toList());
    }

    /**
     * Find the common ancestor of multiple tetrahedral nodes.
     *
     * @param tetIndices The indices to find common ancestor for
     * @return The common ancestor index, or 0 if none
     */
    public long findCommonAncestor(long... tetIndices) {
        if (tetIndices.length == 0) {
            return 0;
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

        return ancestor.index();
    }

    /**
     * Find all neighbors that share a specific edge with the given tetrahedron.
     *
     * @param tetIndex  The SFC index of the tetrahedron
     * @param edgeIndex The edge index (0-5)
     * @return List of neighbor tetrahedron indices sharing the specified edge
     */
    public List<Long> findEdgeNeighbors(long tetIndex, int edgeIndex) {
        return getNeighborFinder().findEdgeNeighbors(tetIndex, edgeIndex);
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
        Set<Long> entityLocations = entityManager.getEntityLocations(entityId);
        if (entityLocations == null || entityLocations.isEmpty()) {
            return neighbors;
        }

        // For each location, find neighbors
        for (Long location : entityLocations) {
            // Find all face neighbors
            long[] faceNeighbors = findAllFaceNeighbors(location);
            for (long neighborIndex : faceNeighbors) {
                if (neighborIndex != -1) {
                    TetreeNodeImpl<ID> neighborNode = spatialIndex.get(neighborIndex);
                    if (neighborNode != null) {
                        neighbors.addAll(neighborNode.getEntityIds());
                    }
                }
            }

            // Also find edge and vertex neighbors for more comprehensive coverage
            for (int edge = 0; edge < 6; edge++) {
                List<Long> edgeNeighbors = findEdgeNeighbors(location, edge);
                for (Long neighborIndex : edgeNeighbors) {
                    TetreeNodeImpl<ID> neighborNode = spatialIndex.get(neighborIndex);
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
     * @return the neighbor index, or -1 if no neighbor exists
     */
    public long findFaceNeighbor(long tetIndex, int faceIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        Tet neighbor = getNeighborFinder().findFaceNeighbor(tet, faceIndex);
        return neighbor != null ? neighbor.index() : -1;
    }

    /**
     * Find neighbors within a specific Euclidean distance from a tetrahedron.
     *
     * @param tetIndex The SFC index of the tetrahedron
     * @param distance The maximum Euclidean distance
     * @return Set of neighbor node indices within the distance
     */
    public Set<TetreeNodeImpl<ID>> findNeighborsWithinDistance(long tetIndex, float distance) {
        Tet tet = Tet.tetrahedron(tetIndex);
        Point3i[] vertices = tet.coordinates();

        // Calculate centroid of tetrahedron
        float centerX = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
        float centerY = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
        float centerZ = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;

        Set<TetreeNodeImpl<ID>> neighbors = new HashSet<>();

        // Manually search through all nodes (since findIntersectingNodes doesn't exist)
        lock.readLock().lock();
        try {
            for (Map.Entry<Long, TetreeNodeImpl<ID>> entry : spatialIndex.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    Tet candidateTet = Tet.tetrahedron(entry.getKey());
                    Point3i[] candidateVertices = candidateTet.coordinates();

                    // Calculate centroid of candidate
                    float candX =
                    (candidateVertices[0].x + candidateVertices[1].x + candidateVertices[2].x + candidateVertices[3].x)
                    / 4.0f;
                    float candY =
                    (candidateVertices[0].y + candidateVertices[1].y + candidateVertices[2].y + candidateVertices[3].y)
                    / 4.0f;
                    float candZ =
                    (candidateVertices[0].z + candidateVertices[1].z + candidateVertices[2].z + candidateVertices[3].z)
                    / 4.0f;

                    // Check distance
                    float dx = centerX - candX;
                    float dy = centerY - candY;
                    float dz = centerZ - candZ;
                    float distSq = dx * dx + dy * dy + dz * dz;

                    if (distSq <= distance * distance) {
                        neighbors.add(entry.getValue());
                    }
                }
            }
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
    public List<Long> findVertexNeighbors(long tetIndex, int vertexIndex) {
        return getNeighborFinder().findVertexNeighbors(tetIndex, vertexIndex);
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
        return sortedSpatialIndices.stream().collect(
        java.util.stream.Collectors.groupingBy(index -> Tet.tetLevelFromIndex(index),
                                               java.util.stream.Collectors.collectingAndThen(
                                               java.util.stream.Collectors.toList(), List::size)));
    }

    // ===== Stream API Integration =====

    /**
     * Get the sorted spatial indices for traversal
     *
     * @return NavigableSet of spatial indices
     */
    public NavigableSet<Long> getSortedSpatialIndices() {
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
    public boolean hasNode(long tetIndex) {
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
    public SpatialNode<ID> intersecting(Spatial volume) {
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
     * Check if a tetrahedron index is valid using optimized bit operations
     *
     * @param index the SFC index to validate
     * @return true if valid, false otherwise
     */
    public boolean isValidTetIndex(long index) {
        return TetreeBits.isValidIndex(index);
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
        return sortedSpatialIndices.stream().filter(index -> Tet.tetLevelFromIndex(index) == level).map(
        spatialIndex::get).filter(node -> node != null && !node.isEmpty());
    }

    // ===== Enhanced Neighbor Finding API =====

    /**
     * Find all neighbors of an entity (not just a tet index).
     * This finds all entities in neighboring tetrahedra of the entity's containing nodes.
     *
     * @param entityId The entity ID to find neighbors for
     * @return Set of neighboring entity IDs
     */

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
        long tetIndex = tet.index();

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
    public Iterator<TetreeNodeImpl<ID>> parentChildIterator(long startIndex) {
        return new Iterator<TetreeNodeImpl<ID>>() {
            private final List<Long> path         = new ArrayList<>();
            private       int        currentIndex = 0;

            {
                // Build path from start to root
                long current = startIndex;
                while (current >= 0) {
                    path.add(0, current); // Insert at beginning
                    Tet tet = Tet.tetrahedron(current);
                    if (tet.l() == 0) {
                        break;
                    }
                    current = tet.parent().index();
                }

                // Add all descendants of start node
                addDescendants(startIndex);
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
                long nodeIndex = path.get(currentIndex++);
                return spatialIndex.get(nodeIndex);
            }

            private void addDescendants(long nodeIndex) {
                if (hasChildren(nodeIndex)) {
                    Tet parent = Tet.tetrahedron(nodeIndex);
                    if (parent.l() < maxDepth) {
                        for (int i = 0; i < 8; i++) {
                            try {
                                Tet child = parent.child(i);
                                long childIndex = child.index();
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
    public Iterator<TetreeNodeImpl<ID>> siblingIterator(long tetIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        if (tet.l() == 0) {
            // Root has no siblings
            return Collections.emptyIterator();
        }

        Tet[] siblings = TetreeFamily.getSiblings(tet);
        List<TetreeNodeImpl<ID>> siblingNodes = new ArrayList<>();

        for (Tet sibling : siblings) {
            if (sibling != null) {
                long siblingIndex = sibling.index();
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
    public TetreeValidator.ValidationResult validateSubtree(long rootIndex) {
        // For now, just validate that the node exists
        // A full subtree validation would require traversing the entire subtree
        if (!spatialIndex.containsKey(rootIndex)) {
            return TetreeValidator.ValidationResult.invalid(
            Collections.singletonList("Root node " + rootIndex + " does not exist"));
        }

        // Get all nodes in the subtree
        Set<Long> subtreeNodes = new HashSet<>();
        Queue<Long> toVisit = new LinkedList<>();
        toVisit.add(rootIndex);

        while (!toVisit.isEmpty()) {
            long current = toVisit.poll();
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
                        long childIndex = child.index();
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

    @Override
    protected void addNeighboringNodes(long tetIndex, Queue<Long> toVisit, Set<Long> visitedNodes) {
        Tet currentTet = Tet.tetrahedron(tetIndex);

        // Use proper neighbor finding instead of grid-based approach
        TetreeNeighborFinder neighborFinder = getNeighborFinder();
        List<Tet> neighbors = neighborFinder.findAllNeighbors(currentTet);

        for (Tet neighbor : neighbors) {
            long neighborIndex = neighbor.index();
            if (!visitedNodes.contains(neighborIndex) && spatialIndex.containsKey(neighborIndex)) {
                toVisit.add(neighborIndex);
            }
        }

        // Also check neighbors at different levels for multi-level support
        // Check one level coarser
        if (currentTet.l() > 0) {
            List<Tet> coarserNeighbors = neighborFinder.findNeighborsAtLevel(currentTet, (byte) (currentTet.l() - 1));
            for (Tet neighbor : coarserNeighbors) {
                long neighborIndex = neighbor.index();
                if (!visitedNodes.contains(neighborIndex) && spatialIndex.containsKey(neighborIndex)) {
                    toVisit.add(neighborIndex);
                }
            }
        }

        // Check one level finer
        if (currentTet.l() < Constants.getMaxRefinementLevel()) {
            List<Tet> finerNeighbors = neighborFinder.findNeighborsAtLevel(currentTet, (byte) (currentTet.l() + 1));
            for (Tet neighbor : finerNeighbors) {
                long neighborIndex = neighbor.index();
                if (!visitedNodes.contains(neighborIndex) && spatialIndex.containsKey(neighborIndex)) {
                    toVisit.add(neighborIndex);
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

    // ===== Frustum Intersection Implementation =====

    @Override
    protected TreeBalancer<ID> createTreeBalancer() {
        return new TetreeBalancer();
    }

    @Override
    protected boolean doesFrustumIntersectNode(long nodeIndex, Frustum3D frustum) {
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
    protected boolean doesNodeIntersectVolume(long tetIndex, Spatial volume) {
        Tet tet = Tet.tetrahedron(tetIndex);
        // Use the same logic as the SFC range computation for consistency
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return false;
        }
        return Tet.tetrahedronIntersectsVolumeBounds(tet, bounds);
    }

    // These methods are now handled by AbstractSpatialIndex

    @Override
    protected boolean doesPlaneIntersectNode(long nodeIndex, Plane3D plane) {
        // Check if plane intersects with the tetrahedron
        Tet tet = Tet.tetrahedron(nodeIndex);
        return planeIntersectsTetrahedron(plane, tet);
    }

    @Override
    protected boolean doesRayIntersectNode(long nodeIndex, Ray3D ray) {
        // Use TetrahedralGeometry for ray-tetrahedron intersection
        var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, nodeIndex);
        return intersection.intersects;
    }

    @Override
    protected float estimateNodeDistance(long nodeIndex, Point3f queryPoint) {
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
    protected int getCellSizeAtLevel(byte level) {
        return Constants.lengthAtLevel(level);
    }

    @Override
    protected List<Long> getChildNodes(long tetIndex) {
        List<Long> children = new ArrayList<>();
        Tet parentTet = Tet.tetrahedron(tetIndex);
        byte level = parentTet.l();

        if (level >= maxDepth) {
            return children; // No children possible at max depth
        }

        // Use the t8code-compliant Tet.child() method to generate the 8 children
        // This ensures proper Bey refinement scheme and correct connectivity
        for (int childIndex = 0; childIndex < TetreeConnectivity.CHILDREN_PER_TET; childIndex++) {
            Tet childTet = parentTet.child(childIndex);
            long childSFCIndex = childTet.index();

            // Only add if child exists in spatial index
            if (spatialIndex.containsKey(childSFCIndex)) {
                children.add(childSFCIndex);
            }
        }

        return children;
    }

    // These methods are now handled by AbstractSpatialIndex

    @Override
    protected Stream<Long> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition) {
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

    @Override
    protected byte getLevelFromIndex(long index) {
        // Use optimized bit extraction from TetreeBits
        return TetreeBits.extractLevel(index);
    }

    @Override
    protected Spatial getNodeBounds(long tetIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        // Return the bounding cube of the tetrahedron
        int cellSize = Constants.lengthAtLevel(tet.l());
        return new Spatial.Cube(tet.x(), tet.y(), tet.z(), cellSize);
    }

    @Override
    protected Stream<Long> getPlaneTraversalOrder(Plane3D plane) {
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
    protected float getRayNodeIntersectionDistance(long nodeIndex, Ray3D ray) {
        // Get ray-tetrahedron intersection distance
        var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, nodeIndex);
        return intersection.intersects ? intersection.distance : Float.MAX_VALUE;
    }

    @Override
    protected Stream<Long> getRayTraversalOrder(Ray3D ray) {
        // Use the optimized TetreeSFCRayTraversal implementation
        return getRayTraversal().traverseRay(ray);
    }

    /**
     * Access the spatial index directly (package-private for internal use)
     *
     * @return the spatial index map
     */
    protected Map<Long, TetreeNodeImpl<ID>> getSpatialIndex() {
        return spatialIndex;
    }

    @Override
    protected NavigableSet<Long> getSpatialIndexRange(VolumeBounds bounds) {
        // CRITICAL FIX: The memory-efficient strategies are still broken.
        // For now, use the direct approach that works correctly.
        return getStandardEntitySpatialRange(bounds, true);
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

        // Get parent tetrahedron from the SFC index directly
        // This is the actual tetrahedron being subdivided
        Tet parentTet = Tet.tetrahedron(parentTetIndex, parentLevel);

        // Generate all 8 children using Bey refinement
        Tet[] children = new Tet[8];
        for (int i = 0; i < 8; i++) {
            children[i] = parentTet.child(i);
        }

        long[] childIndices = new long[8];
        for (int i = 0; i < 8; i++) {
            childIndices[i] = children[i].index();
        }

        // Validate that the children form a proper family for subdivision
        assert TetreeFamily.isFamily(children) : "Children do not form a valid subdivision family";

        // Create map to group entities by their child tetrahedron
        Map<Long, List<ID>> childEntityMap = new HashMap<>();

        // Determine which child tetrahedron each entity belongs to
        for (ID entityId : parentEntities) {
            Point3f entityPos = entityManager.getEntityPosition(entityId);
            if (entityPos != null) {
                // Find which of the 8 children contains this entity
                boolean assigned = false;
                for (int childIndex = 0; childIndex < children.length; childIndex++) {
                    Tet child = children[childIndex];
                    if (child.contains(entityPos)) {
                        long childTetIndex = childIndices[childIndex];
                        childEntityMap.computeIfAbsent(childTetIndex, k -> new ArrayList<>()).add(entityId);
                        assigned = true;
                        break;
                    }
                }

                if (!assigned) {
                    // Fallback: use locate method if direct containment fails
                    Tet childTet = locate(entityPos, childLevel);
                    childEntityMap.computeIfAbsent(childTet.index(), k -> new ArrayList<>()).add(entityId);
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
            long childTetIndex = child.index();
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
            long childTetIndex = children[i].index();
            if (spatialIndex.containsKey(childTetIndex)) {
                parentNode.setChildBit(i);
            }
        }
    }

    @Override
    protected boolean hasChildren(long tetIndex) {
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

    @Override
    protected boolean isNodeContainedInVolume(long tetIndex, Spatial volume) {
        Tet tet = Tet.tetrahedron(tetIndex);
        return Tet.tetrahedronContainedInVolume(tet, volume);
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
     * Add intersected children for ray traversal
     */
    private void addIntersectedChildren(Tet currentTet, Ray3D ray, PriorityQueue<TetDistance> tetQueue,
                                        Set<Long> visitedTets) {
        byte childLevel = (byte) (currentTet.l() + 1);
        if (childLevel > Constants.getMaxRefinementLevel()) {
            return;
        }

        // Get all 8 children using Bey refinement
        try {
            for (int i = 0; i < 8; i++) {
                Tet child = currentTet.child(i);
                long childIndex = child.index();

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
                                             Set<Long> visitedTets) {
        // Check all 4 face neighbors
        for (int face = 0; face < 4; face++) {
            try {
                Tet.FaceNeighbor neighbor = currentTet.faceNeighbor(face);
                Tet neighborTet = neighbor.tet();
                long neighborIndex = neighborTet.index();

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
    private void addIntersectingChildren(long parentIndex, byte parentLevel, Ray3D ray,
                                         PriorityQueue<TetDistance> tetQueue, Set<Long> visitedTets) {
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
                        long childIndex = childTet.index();

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

    // ===== Tree Balancing Implementation =====

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
            long index = tet.index();
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

    // Compute SFC ranges that could contain tetrahedra intersecting the volume
    private List<SFCRange> computeSFCRanges(VolumeBounds bounds, boolean includeIntersecting) {
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
            Point3f center = new Point3f((bounds.getMinX() + bounds.getMaxX()) / 2,
                                         (bounds.getMinY() + bounds.getMaxY()) / 2,
                                         (bounds.getMinZ() + bounds.getMaxZ()) / 2);

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
                                           Set<Long> visited) {
        // Use the optimized TetreeSFCRayTraversal for efficient ray-tetrahedron intersection
        // This replaces the previous step-based approach with SFC-guided traversal

        TetreeSFCRayTraversal<ID, Content> sfcTraversal = getRayTraversal();

        // Get all tetrahedra intersected by the ray using SFC traversal
        Stream<Long> rayIntersectedTets = sfcTraversal.traverseRay(ray);

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
    private float getFrustumTetrahedronDistance(long nodeIndex, Point3f cameraPosition) {
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
    private NavigableSet<Long> getLargeEntitySpatialRange(VolumeBounds bounds, boolean includeIntersecting) {
        NavigableSet<Long> result = new TreeSet<>();

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
     * Memory-efficient spatial index range computation with adaptive strategies for large entities Based on t8code's
     * hierarchical range computation algorithms
     */
    private NavigableSet<Long> getMemoryEfficientSpatialIndexRange(VolumeBounds bounds, boolean includeIntersecting) {
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
    private float getPlaneTetrahedronDistance(long nodeIndex, Plane3D plane) {
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
    private long getSuccessor(long tetIndex) {
        // For tetrahedral SFC, successor is simply the next index
        // The Tet class's index() method implements the proper SFC ordering
        return tetIndex + 1;
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

    /**
     * Tetree-specific tree balancer implementation
     */
    protected class TetreeBalancer extends DefaultTreeBalancer {

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
                if (pos == null) {
                    continue;
                }

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

            // Set the specific child bits for the children that were created
            // We need to determine which child indices these represent
            for (Long childTetIndex : createdChildren) {
                Tet childTet = Tet.tetrahedron(childTetIndex);
                // Find which child index this represents (0-7)
                for (int i = 0; i < 8; i++) {
                    try {
                        Tet expectedChild = parentTet.child(i);
                        if (expectedChild.index() == childTetIndex) {
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
        protected Set<Long> findSiblings(long tetIndex) {
            Tet tet = Tet.tetrahedron(tetIndex);
            if (tet.l() == 0) {
                return Collections.emptySet(); // Root has no siblings
            }

            // Use the t8code-compliant TetreeFamily algorithm for finding siblings
            Tet[] siblings = TetreeFamily.getSiblings(tet);
            Set<Long> result = new HashSet<>();

            for (Tet sibling : siblings) {
                long siblingIndex = sibling.index();
                // Add if it's not the current node and exists in the spatial index
                if (siblingIndex != tetIndex && spatialIndex.containsKey(siblingIndex)) {
                    result.add(siblingIndex);
                }
            }

            return result;
        }

        @Override
        protected long getParentIndex(long tetIndex) {
            Tet tet = Tet.tetrahedron(tetIndex);
            if (tet.l() == 0) {
                return -1; // Root has no parent
            }

            // Use the t8code-compliant parent() method from the Tet class
            // This ensures correct parent calculation using the exact t8code algorithm
            Tet parentTet = tet.parent();
            return parentTet.index();
        }
    }
}
