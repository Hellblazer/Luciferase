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
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;

import static com.hellblazer.sentry.V.*;

/**
 * The dynamic, mutable version of the Grid.
 * 
 * <h2>Thread Safety Model</h2>
 * <p>
 * MutableGrid is <b>NOT thread-safe</b>. This class is designed for single-threaded use only.
 * All methods that modify the grid structure (insert, delete, untrack) must be called from a
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
 *     
 *     public void untrack(Vertex vertex) {
 *         lock.writeLock().lock();
 *         try {
 *             grid.untrack(vertex);
 *         } finally {
 *             lock.writeLock().unlock();
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
    // Flag to enable/disable landmark index
    protected static final boolean       USE_LANDMARK_INDEX = Boolean.parseBoolean(
    System.getProperty("sentry.useLandmarkIndex", "false"));
    // Flag to enable/disable optimized flip for benchmarking
    protected static final boolean       USE_OPTIMIZED_FLIP = Boolean.parseBoolean(
    System.getProperty("sentry.useOptimizedFlip", "true"));
    protected              Vertex        tail;
    protected              Tetrahedron   last;  // Changed to protected for testing
    protected              LandmarkIndex landmarkIndex;  // Changed to protected for testing

    public MutableGrid() {
        this(getFourCorners());
    }

    public MutableGrid(Vertex[] fourCorners) {
        super(fourCorners);
        initialize();
    }

    public void clear() {
        if (head != null) {
            head.clear();
        }
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
        if (landmarkIndex != null && USE_LANDMARK_INDEX) {
            Tetrahedron result = landmarkIndex.locate(p, last, entropy);
            if (result != null) {
                last = result;  // Update last for next query
            }
            return result;
        }
        return locate(p, last, entropy);
    }

    public void rebuild(Random entropy) {
        // Collect all vertices before clearing
        List<Vertex> vertices = new ArrayList<>();
        if (head != null) {
            for (var v : head) {
                vertices.add(v);
            }
        }
        
        // Clear the grid but not the vertex linked list
        if (head != null) {
            // Clear adjacent references but preserve linked list structure
            for (var v : head) {
                v.setAdjacent(null);
            }
        }
        
        // Reset tetrahedra
        for (var v : fourCorners) {
            v.reset();
        }
        if (landmarkIndex != null) {
            landmarkIndex.clear();
        }
        
        // Reinitialize 
        last = TetrahedronPool.getInstance().acquire(fourCorners);
        head = tail = null;
        size = 0;
        initialize();
        
        // Clear the next pointers before re-insertion
        for (var v : vertices) {
            v.clearNext();
        }
        
        // Re-insert all vertices
        for (var v : vertices) {
            var containedIn = locate(v, last, entropy);
            if (containedIn != null) {
                add(v, containedIn);
            }
        }
    }

    public void rebuild(List<Vertex> vertices, Random entropy) {
        // Clear adjacent references and next pointers for all vertices
        for (var v : vertices) {
            v.setAdjacent(null);
            v.clearNext();
        }
        
        // Reset tetrahedra and clear the grid
        for (var v : fourCorners) {
            v.reset();
        }
        if (landmarkIndex != null) {
            landmarkIndex.clear();
        }
        
        // Reinitialize
        last = TetrahedronPool.getInstance().acquire(fourCorners);
        head = tail = null;
        size = 0;
        initialize();

        // Re-insert all vertices
        for (var v : vertices) {
            var containedIn = locate(v, last, entropy);
            if (containedIn != null) {
                add(v, containedIn);
            }
        }
    }

    /**
     * Track the point into the tetrahedralization. See "Computing the 3D Voronoi Diagram Robustly: An Easy
     * Explanation", by Hugo Ledoux
     * <p>
     *
     * @param p - the point to be inserted
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
        add(v, locate(p, entropy));
        return v;
    }

    public void untrack(Vertex v) {
        if (head == null || v == null) {
            return;
        }
        
        // Special case: removing the head
        if (head == v) {
            // Find the next vertex after head
            Iterator<Vertex> it = head.iterator();
            it.next(); // Skip head itself
            if (it.hasNext()) {
                head = it.next();
            } else {
                head = null;
                tail = null;
            }
            v.clear();
            size--;
            return;
        }
        
        // General case: find and remove from linked list
        try {
            head.detach(v);
            // If we removed the tail, find the new tail
            if (tail == v) {
                Vertex newTail = null;
                for (Vertex curr : head) {
                    newTail = curr;
                }
                tail = newTail;
            }
            v.clear();
            size--;
        } catch (NoSuchElementException e) {
            // Vertex not found in list - ignore
        }
    }

    /**
     * Perform the 4->1 bistellar flip. This flip is the inverse of the 1->4 flip.
     *
     * @param n - the vertex who's star defines the 4 tetrahedron
     * @return the tetrahedron created from the flip
     */
    protected Tetrahedron flip4to1(Vertex n) {
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
        Tetrahedron t = TetrahedronPool.getInstance().acquire(a, b, c, d);
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

        for (Tetrahedron tet : deleted) {
            tet.delete();
        }
        return t;
    }

    private void add(Vertex v, final Tetrahedron target) {
        insert(v, target);
        if (head == null) {
            head = v;
        } else if (tail != null) {
            tail.append(v);
            tail = v;
        } else {
            head.append(v);
            tail = v;
        }
        size++;
    }

    private void initialize() {
        last = TetrahedronPool.getInstance().acquire(fourCorners);
        landmarkIndex = new LandmarkIndex(new Random());
    }

    protected void insert(Vertex v, final Tetrahedron target) {
        List<OrientedFace> ears = new ArrayList<>(20);
        last = target.flip1to4(v, ears);

        // Update landmark index with new tetrahedra from initial flip
        if (landmarkIndex != null && USE_LANDMARK_INDEX) {
            // The flip1to4 creates 4 new tetrahedra
            landmarkIndex.addTetrahedron(last, size * 4);
        }

        // Use optimized flip processing
        if (USE_OPTIMIZED_FLIP) {
            while (!ears.isEmpty()) {
                int lastIndex = ears.size() - 1;
                OrientedFace face = ears.remove(lastIndex);
                Tetrahedron l = FlipOptimizer.flipOptimized(face, v, ears);
                if (l != null) {
                    last = l;
                    // Occasionally update landmarks during cascading flips
                    if (landmarkIndex != null && USE_LANDMARK_INDEX && ears.size() % 10 == 0) {
                        landmarkIndex.addTetrahedron(l, size * 4);
                    }
                }
            }
        } else {
            // Original implementation
            while (!ears.isEmpty()) {
                Tetrahedron l = ears.remove(ears.size() - 1).flip(v, ears);
                if (l != null) {
                    last = l;
                    // Occasionally update landmarks during cascading flips
                    if (landmarkIndex != null && USE_LANDMARK_INDEX && ears.size() % 10 == 0) {
                        landmarkIndex.addTetrahedron(l, size * 4);
                    }
                }
            }
        }

        // Periodically clean up deleted landmarks
        if (landmarkIndex != null && USE_LANDMARK_INDEX && size % 100 == 0) {
            landmarkIndex.cleanup();
        }
    }
}
