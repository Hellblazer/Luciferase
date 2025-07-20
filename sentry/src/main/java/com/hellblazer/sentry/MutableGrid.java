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
 * MutableGrid is <b>NOT thread-safe</b>. This class is designed for single-threaded use only.
 * All methods that modify the grid structure (insert, delete) must be called from a
 * single thread or with external synchronization.
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
    private final          List<Vertex>  vertices = new ArrayList<>();
    private final          TetrahedronPool pool = new TetrahedronPool();
    protected              Tetrahedron   last;  // Changed to protected for testing
    protected              LandmarkIndex landmarkIndex;  // Changed to protected for testing

    public MutableGrid() {
        this(getFourCorners());
    }

    /**
     * Create a new MutableGrid.
     *
     * @param fourCorners The four corner vertices
     */
    public MutableGrid(Vertex[] fourCorners) {
        super(fourCorners);
        initialize();
        // Note: Validation is now handled externally via GridValidator and ValidationManager
    }

    public void clear() {
        // First, collect and release all tetrahedrons back to the pool
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
        // Release all tetrahedrons back to the pool before rebuilding
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

        last = pool.acquire(fourCorners);
        initialize();

        // Re-insert all vertices
        for (var v : verticesList) {
            var containedIn = locate(v, last, entropy);
            if (containedIn != null) {
                add(v, containedIn);
            }
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
     * Perform the 4->1 bistellar flip. This flip is the inverse of the 1->4 flip.
     *
     * @param n - the vertex who's star defines the 4 tetrahedron
     * @return the tetrahedron created from the flip
     */
    protected Tetrahedron flip4to1(Vertex n) {
        return TetrahedronPoolContext.withPool(pool, () -> {
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
        Tetrahedron t = pool.acquire(a, b, c, d);
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

            // Release deleted tetrahedra back to pool
            // Use instance pool
            for (Tetrahedron tet : deleted) {
                tet.delete();
                pool.release(tet);
            }
            return t;
        });
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
        last = pool.acquire(fourCorners);
        landmarkIndex = new LandmarkIndex(new Random());
    }

    protected void insert(Vertex v, final Tetrahedron target) {
        // Set pool context for this operation
        TetrahedronPoolContext.withPool(pool, () -> {
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
        });
    }

    /**
     * Get the vertices list for package-private access.
     * Used by GridValidator.
     */
    List<Vertex> getVertices() {
        return vertices;
    }
    
    /**
     * Get the last tetrahedron for package-private access.
     * Used by GridValidator.
     */
    Tetrahedron getLastTetrahedron() {
        return last;
    }

    /**
     * Get an unmodifiable view of the vertices in this grid.
     * @return an unmodifiable list of vertices
     */
    public List<Vertex> vertices() {
        return Collections.unmodifiableList(vertices);
    }

    @Override
    public Iterator<Vertex> iterator() {
        return vertices.iterator();
    }


    /**
     * Get the TetrahedronPool for this grid.
     * Package-private for testing.
     */
    TetrahedronPool getPool() {
        return pool;
    }

    /**
     * Release all tetrahedrons in the grid back to the pool.
     * This should be called before clear() or rebuild() to reuse memory.
     */
    private void releaseAllTetrahedrons() {
        if (size == 0 || vertices.isEmpty()) {
            return;
        }

        // Release them all back to the pool
        // Use instance pool
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
                pool.release(next);
                releasedCount++;
            }
        }

        // Also ensure the 'last' reference is cleared if it was released
        if (last != null && last.isDeleted()) {
            last = null;
        }

        // Optionally log for debugging very large rebuilds only
        if (releasedCount > 10000) {
            // Silent - no logging in production code without SLF4J dependency
        }
    }
}
