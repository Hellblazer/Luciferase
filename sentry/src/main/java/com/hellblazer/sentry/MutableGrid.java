/**
 * Copyright (C) 2009-2023 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the 3D Incremental Voronoi system
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.sentry;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.*;

import static com.hellblazer.sentry.V.*;

/**
 * The dynamic, mutable version of the Grid.
 *
 * <h2>Thread Safety Model</h2>
 * <p>
 * MutableGrid is <b>NOT thread-safe</b>. This class is designed for single-threaded use only. All methods that modify
 * the grid structure (insert, delete) must be called from a single thread or with external synchronization.
 * </p>
 *
 * <h3>Design Rationale</h3>
 * <p>
 * The single-threaded design was chosen for performance reasons:
 * <ul>
 *   <li>Avoids synchronization overhead in the common single-threaded use case</li>
 *   <li>Allows for more efficient internal data structures (linked lists, object pooling)</li>
 *   <li>Simplifies the implementation of complex geometric algorithms</li>
 * </ul>
 * </p>
 *
 * <h3>External Synchronization</h3>
 * <p>
 * If you need to use MutableGrid from multiple threads, you must provide external synchronization.
 * Here's a simple example using a ReentrantReadWriteLock:
 * </p>
 * <pre>{@code
 * public class ThreadSafeMutableGrid {
 *     private final MutableGrid grid = new MutableGrid();
 *     private final ReadWriteLock lock = new ReentrantReadWriteLock();
 *
 *     public void insert(Point3f point) {
 *         lock.writeLock().lock();
 *         try {
 *             grid.insert(point);
 *         } finally {
 *             lock.writeLock().unlock();
 *         }
 *     }
 *
 *     public Tetrahedron locate(Point3f point) {
 *         lock.readLock().lock();
 *         try {
 *             return grid.locate(point);
 *         } finally {
 *             lock.readLock().unlock();
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h3>Thread-Safe Alternatives</h3>
 * <p>
 * For concurrent scenarios, consider:
 * <ul>
 *   <li>Using multiple independent MutableGrid instances (one per thread)</li>
 *   <li>Batching operations and processing them sequentially</li>
 *   <li>Using a concurrent spatial data structure if available</li>
 * </ul>
 * </p>
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */

public class MutableGrid extends Grid {
    // Configuration options
    public enum AllocationStrategy {
        POOLED,    // Use TetrahedronPool (default)
        DIRECT     // Direct allocation without pooling
    }
    
    private static final String ALLOCATION_PROPERTY = "sentry.allocation.strategy";
    private final List<Vertex>    vertices = new ArrayList<>();
    private final TetrahedronAllocator allocator;
    private final AllocationStrategy strategy;
    protected     Tetrahedron     last;  // Changed to protected for testing
    protected     LandmarkIndex   landmarkIndex;  // Changed to protected for testing
    
    private static AllocationStrategy getDefaultStrategy() {
        String prop = System.getProperty(ALLOCATION_PROPERTY);
        if ("direct".equalsIgnoreCase(prop)) {
            return AllocationStrategy.DIRECT;
        }
        return AllocationStrategy.POOLED; // Default
    }

    public MutableGrid() {
        this(getFourCorners(), getDefaultStrategy());
    }

    /**
     * Create with specified allocation strategy.
     */
    public MutableGrid(AllocationStrategy strategy) {
        this(getFourCorners(), strategy);
    }

    /**
     * Create a new MutableGrid.
     *
     * @param fourCorners The four corner vertices
     */
    public MutableGrid(Vertex[] fourCorners) {
        this(fourCorners, getDefaultStrategy());
    }
    
    /**
     * Create with specified allocation strategy.
     */
    public MutableGrid(Vertex[] fourCorners, AllocationStrategy strategy) {
        super(fourCorners);
        this.strategy = strategy;
        this.allocator = createAllocator(strategy);
        initialize();
        // Note: Validation is now handled externally via GridValidator and ValidationManager
    }
    
    private TetrahedronAllocator createAllocator(AllocationStrategy strategy) {
        switch (strategy) {
            case POOLED:
                return new PooledAllocator(new TetrahedronPool());
            case DIRECT:
                return new DirectAllocator();
            default:
                throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }
    }
    
    /**
     * Get the allocation strategy in use.
     */
    public AllocationStrategy getAllocationStrategy() {
        return strategy;
    }
    
    /**
     * Get the allocator for performance statistics.
     */
    TetrahedronAllocator getAllocator() {
        return allocator;
    }

    public void clear() {
        // First, collect and release all tetrahedrons back to the allocator
        releaseAllTetrahedrons();

        // Clear all vertex references
        for (Vertex v : vertices) {
            v.reset();
        }
        vertices.clear();

        // Clear head reference
        head = null;

        // Reset size
        size = 0;

        for (var v : fourCorners) {
            v.reset();
        }
        if (landmarkIndex != null) {
            landmarkIndex.clear();
        }
    }

    /**
     * Get performance statistics from the landmark index.
     */
    public String getLandmarkStatistics() {
        return landmarkIndex != null ? landmarkIndex.getStatistics() : "No landmark index";
    }

    @Override
    public Iterator<Vertex> iterator() {
        return vertices.iterator();
    }

    public Tetrahedron locate(Tuple3f p, Random entropy) {
        // Use landmark index for better starting point
        if (landmarkIndex != null) {
            Tetrahedron result = landmarkIndex.locate(p, last, entropy);
            if (result != null) {
                last = result;  // Update last for next query
                return result;
            }
        }
        return locate(p, last, entropy);
    }

    public void rebuild(Random entropy) {
        // Create snapshot of current vertices
        List<Vertex> snapshot = new ArrayList<>(vertices);
        rebuild(snapshot, entropy);
    }

    public void rebuild(List<Vertex> verticesList, Random entropy) {
        // Use optimized rebuild for better performance
        rebuildOptimized(verticesList, entropy);
    }
    
    /**
     * Optimized rebuild that bypasses pooling context overhead for better performance.
     * For small rebuilds like 256 points, uses direct allocation to avoid pooling overhead.
     */
    private void rebuildOptimized(List<Vertex> verticesList, Random entropy) {
        // For small rebuilds like 256 points, use direct allocation to avoid pooling overhead
        boolean useDirectForRebuild = verticesList.size() <= 256 || "true".equals(System.getProperty("sentry.rebuild.direct"));
        TetrahedronAllocator rebuildAllocator = useDirectForRebuild ? new DirectAllocator() : allocator;
        
        // Release all tetrahedrons back to the allocator before rebuilding
        releaseAllTetrahedrons();

        // Clear vertex references
        for (var v : verticesList) {
            v.setAdjacent(null);
        }

        // Clear internal state
        vertices.clear();
        size = 0;
        head = null;

        // Reset and reinitialize
        for (var v : fourCorners) {
            v.reset();
        }
        if (landmarkIndex != null) {
            landmarkIndex.clear();
        }

        last = rebuildAllocator.acquire(fourCorners);
        allocator.warmUp(128); // Keep original allocator warm

        if (useDirectForRebuild) {
            // Skip context overhead entirely for direct allocation
            for (var v : verticesList) {
                var containedIn = locate(v, last, entropy);
                if (containedIn != null) {
                    insertDirectly(v, containedIn, rebuildAllocator);
                }
            }
        } else {
            // Set allocator context once for the entire rebuild operation
            // This avoids the overhead of multiple context switches
            TetrahedronPoolContext.withAllocator(rebuildAllocator, () -> {
                // Re-insert all vertices with optimized insertion
                for (var v : verticesList) {
                    var containedIn = locate(v, last, entropy);
                    if (containedIn != null) {
                        insertOptimized(v, containedIn);
                    }
                }
            });
        }

        // Note: Call GridValidator.validateAndRepairVertexReferences() if needed
    }

    /**
     * Track the point into the tetrahedralization. See "Computing the 3D Voronoi Diagram Robustly: An Easy
     * Explanation", by Hugo Ledoux
     * <p>
     *
     * @return the Vertex in the tetrahedralization
     */
    public Vertex track(Vertex v, Random entropy) {
        assert v != null;
        if (!contains(v)) {
            return null;
        }
        add(v, locate(v, entropy));
        return v;
    }

    /**
     * Track the point into the tetrahedralization. See "Computing the 3D Voronoi Diagram Robustly: An Easy
     * Explanation", by Hugo Ledoux
     * <p>
     *
     * @param p    - the point to be inserted
     * @param near - the nearby vertex
     * @return the new Vertex in the tetrahedralization or null if the point is contained in the tetrahedralization
     */
    public Vertex track(Point3f p, Vertex near, Random entropy) {
        assert p != null;
        if (!contains(p)) {
            return null;
        }
        final var v = new Vertex(p);
        var containedIn = near.locate(p, entropy);
        if (containedIn == null) {
            return null;
        }
        add(v, containedIn);
        return v;
    }

    /**
     * Track the point into the tetrahedralization. See "Computing the 3D Voronoi Diagram Robustly: An Easy
     * Explanation", by Hugo Ledoux
     * <p>
     *
     * @param p - the point to be inserted
     * @return the Vertex in the tetrahedralization
     */
    public Vertex track(Point3f p, Random entropy) {
        assert p != null;
        if (!contains(p)) {
            return null;
        }
        final var v = new Vertex(p);
        var located = locate(p, entropy);
        if (located == null) {
            if (contains(p)) {
                throw new IllegalStateException("This grid should contain: " + p);
            }
            throw new IllegalArgumentException("There is no located vertex for " + p);
        }
        add(v, located);
        return v;
    }

    /**
     * Get an unmodifiable view of the vertices in this grid.
     *
     * @return an unmodifiable list of vertices
     */
    public List<Vertex> vertices() {
        return Collections.unmodifiableList(vertices);
    }

    /**
     * Perform the 4->1 bistellar flip. This flip is the inverse of the 1->4 flip.
     *
     * @param n - the vertex who's star defines the 4 tetrahedron
     * @return the tetrahedron created from the flip
     */
    protected Tetrahedron flip4to1(Vertex n) {
        return TetrahedronPoolContext.withAllocator(allocator, () -> {
            Deque<OrientedFace> star = n.getStar();
            ArrayList<Tetrahedron> deleted = new ArrayList<>();
            for (OrientedFace f : star) {
                deleted.add(f.getIncident());
            }
            assert star.size() == 4;
            OrientedFace base = star.pop();
            Vertex a = base.getVertex(2);
            Vertex b = base.getVertex(0);
            Vertex c = base.getVertex(1);
            Vertex d = null;
            OrientedFace face = star.pop();
            for (Vertex v : face) {
                if (!base.includes(v)) {
                    d = v;
                    break;
                }
            }
            assert d != null;
            Tetrahedron t = allocator.acquire(a, b, c, d);
            base.getIncident().patch(base.getIncidentVertex(), t, D);
            if (face.includes(a)) {
                if (face.includes(b)) {
                    assert !face.includes(c);
                    face.getIncident().patch(face.getIncidentVertex(), t, C);
                    face = star.pop();
                    if (face.includes(a)) {
                        assert !face.includes(b);
                        face.getIncident().patch(face.getIncidentVertex(), t, B);
                        face = star.pop();
                        assert !face.includes(a);
                        face.getIncident().patch(face.getIncidentVertex(), t, A);
                    } else {
                        face.getIncident().patch(face.getIncidentVertex(), t, A);
                        face = star.pop();
                        assert !face.includes(b);
                        face.getIncident().patch(face.getIncidentVertex(), t, B);
                    }
                } else {
                    face.getIncident().patch(face.getIncidentVertex(), t, B);
                    face = star.pop();
                    if (face.includes(a)) {
                        assert !face.includes(c);
                        face.getIncident().patch(face.getIncidentVertex(), t, C);
                        face = star.pop();
                        assert !face.includes(a);
                        face.getIncident().patch(face.getIncidentVertex(), t, A);
                    } else {
                        face.getIncident().patch(face.getIncidentVertex(), t, A);
                        face = star.pop();
                        assert !face.includes(c);
                        face.getIncident().patch(face.getIncidentVertex(), t, C);
                    }
                }
            } else {
                face.getIncident().patch(face.getIncidentVertex(), t, A);
                face = star.pop();
                if (face.includes(b)) {
                    assert !face.includes(c);
                    face.getIncident().patch(face.getIncidentVertex(), t, C);
                    face = star.pop();
                    assert !face.includes(b);
                    face.getIncident().patch(face.getIncidentVertex(), t, B);
                } else {
                    face.getIncident().patch(face.getIncidentVertex(), t, B);
                    face = star.pop();
                    assert !face.includes(c);
                    face.getIncident().patch(face.getIncidentVertex(), t, C);
                }
            }

            // Release deleted tetrahedra back to allocator
            // Use instance allocator
            for (Tetrahedron tet : deleted) {
                tet.delete();
                allocator.release(tet);
            }
            return t;
        });
    }

    protected void insert(Vertex v, final Tetrahedron target) {
        // Set allocator context for this operation
        TetrahedronPoolContext.withAllocator(allocator, () -> {
            insertCore(v, target);
        });
    }
    
    /**
     * Optimized insertion for rebuild operations. Assumes allocator context is already set.
     */
    private void insertOptimized(Vertex v, final Tetrahedron target) {
        insertCore(v, target);
        vertices.add(v);  // Simple append
        size++;

        // Set head to any valid vertex (per user guidance)
        head = v;
    }
    
    /**
     * Direct insertion without any context overhead. Uses provided allocator directly.
     */
    private void insertDirectly(Vertex v, final Tetrahedron target, TetrahedronAllocator directAllocator) {
        insertCoreWithAllocator(v, target, directAllocator);
        vertices.add(v);  // Simple append
        size++;

        // Set head to any valid vertex (per user guidance)
        head = v;
    }
    
    /**
     * Core insertion logic shared by both regular and optimized insertion.
     */
    private void insertCore(Vertex v, final Tetrahedron target) {
        insertCoreWithAllocator(v, target, TetrahedronPoolContext.getAllocator());
    }
    
    /**
     * Core insertion logic with explicit allocator (no context lookup).
     */
    private void insertCoreWithAllocator(Vertex v, final Tetrahedron target, TetrahedronAllocator allocator) {
        // Set the allocator in context temporarily for flip operations
        TetrahedronPoolContext.setAllocator(allocator);
        try {
            List<OrientedFace> ears = new ArrayList<>(20);
            last = target.flip1to4(v, ears);

            // Update landmark index with new tetrahedra from initial flip
            if (landmarkIndex != null) {
                // The flip1to4 creates 4 new tetrahedra
                landmarkIndex.addTetrahedron(last, size * 4);
            }

            // Use optimized flip processing
            while (!ears.isEmpty()) {
                int lastIndex = ears.size() - 1;
                OrientedFace face = ears.remove(lastIndex);
                Tetrahedron l = FlipOptimizer.flipOptimized(face, v, ears);
                if (l != null) {
                    last = l;
                    // Occasionally update landmarks during cascading flips
                    if (landmarkIndex != null && ears.size() % 10 == 0) {
                        landmarkIndex.addTetrahedron(l, size * 4);
                    }
                }
            }

            // Periodically clean up deleted landmarks
            if (landmarkIndex != null && size % 100 == 0) {
                landmarkIndex.cleanup();
            }
        } finally {
            TetrahedronPoolContext.clearAllocator();
        }
    }

    /**
     * Get the last tetrahedron for package-private access. Used by GridValidator.
     */
    Tetrahedron getLastTetrahedron() {
        return last;
    }

    /**
     * Get the TetrahedronPool for this grid. Package-private for testing.
     * @deprecated Use getAllocator() instead
     */
    @Deprecated
    TetrahedronPool getPool() {
        if (allocator instanceof PooledAllocator) {
            return ((PooledAllocator) allocator).getPool();
        }
        return null;
    }

    /**
     * Get the vertices list for package-private access. Used by GridValidator.
     */
    List<Vertex> getVertices() {
        return vertices;
    }
    
    // Profiling methods for performance analysis
    
    void releaseAllTetrahedronsForProfiling() {
        releaseAllTetrahedrons();
    }
    
    void clearVerticesForProfiling() {
        for (Vertex v : vertices) {
            v.reset();
        }
        vertices.clear();
        head = null;
        size = 0;
    }
    
    void reinitializeForProfiling() {
        for (var v : fourCorners) {
            v.reset();
        }
        if (landmarkIndex != null) {
            landmarkIndex.clear();
        }
        last = allocator.acquire(fourCorners);
        initialize();
    }
    
    void addForProfiling(Vertex v, Tetrahedron target) {
        add(v, target);
    }
    
    Tetrahedron getLastTetrahedronForProfiling() {
        return last;
    }

    private void add(Vertex v, final Tetrahedron target) {
        insert(v, target);
        vertices.add(v);  // Simple append
        size++;

        // Set head to any valid vertex (per user guidance)
        head = v;

        // Note: Validation should be triggered explicitly via GridValidator or ValidationManager
    }

    private void initialize() {
        // Warm up allocator for initial operations
        allocator.warmUp(128);
        last = allocator.acquire(fourCorners);
        landmarkIndex = new LandmarkIndex(new Random());
    }

    /**
     * Release all tetrahedrons in the grid back to the allocator. This should be called before clear() or rebuild() to reuse
     * memory.
     */
    private void releaseAllTetrahedrons() {
        if (size == 0 || vertices.isEmpty()) {
            return;
        }

        // Release them all back to the allocator
        // Use instance allocator
        int releasedCount = 0;

        var stack = new ArrayDeque<Tetrahedron>();
        // Start from any vertex's adjacent tetrahedron
        if (!vertices.isEmpty() && vertices.get(0).getAdjacent() != null) {
            stack.push(vertices.get(0).getAdjacent());
        }
        while (!stack.isEmpty()) {
            var next = stack.pop();
            if (!next.isDeleted()) {
                next.children(stack);
                next.delete();
                allocator.release(next);
                releasedCount++;
            }
        }
        last = null;
    }
}
