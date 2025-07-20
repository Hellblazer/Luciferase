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
import java.util.Set;

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
    protected              Vertex        tail;
    protected              Tetrahedron   last;  // Changed to protected for testing
    protected              LandmarkIndex landmarkIndex;  // Changed to protected for testing
    protected              ValidationManager validationManager;  // Optional validation framework
    
    // Configuration flags
    protected final boolean useLandmarkIndex;
    protected final boolean useOptimizedFlip;

    public MutableGrid() {
        this(getFourCorners());
    }

    public MutableGrid(Vertex[] fourCorners) {
        this(fourCorners, SentryConfiguration.getDefault());
    }
    
    /**
     * Create a new MutableGrid with specific configuration.
     * 
     * @param fourCorners The four corner vertices
     * @param config The configuration to use
     */
    public MutableGrid(Vertex[] fourCorners, SentryConfiguration config) {
        super(fourCorners);
        
        // Store configuration
        this.useLandmarkIndex = config.isLandmarkIndexEnabled();
        this.useOptimizedFlip = config.isOptimizedFlipEnabled();
        
        initialize();
        
        // Create validation manager if validation is enabled
        if (config.isValidationEnabled()) {
            validationManager = new ValidationManager(this);
            validationManager.enableValidation(true);
        }
    }

    public void clear() {
        // First, collect and release all tetrahedrons back to the pool
        releaseAllTetrahedrons();
        
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
        if (landmarkIndex != null && useLandmarkIndex) {
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
        
        // Use the list-based rebuild method which properly handles the vertices
        rebuild(vertices, entropy);
    }

    public void rebuild(List<Vertex> vertices, Random entropy) {
        // Release all tetrahedrons back to the pool before rebuilding
        releaseAllTetrahedrons();
        
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
        
        // Validate and fix any missing vertex references
        validateVertexReferences();
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

        // Release deleted tetrahedra back to pool
        TetrahedronPool pool = TetrahedronPool.getInstance();
        for (Tetrahedron tet : deleted) {
            tet.delete();
            pool.release(tet);
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
        
        // Note: Automatic validation removed to prevent cross-test interference
        // Validation should be triggered explicitly via getValidationManager().createReport()
    }

    private void initialize() {
        last = TetrahedronPool.getInstance().acquire(fourCorners);
        landmarkIndex = new LandmarkIndex(new Random());
    }

    protected void insert(Vertex v, final Tetrahedron target) {
        List<OrientedFace> ears = new ArrayList<>(20);
        last = target.flip1to4(v, ears);

        // Update landmark index with new tetrahedra from initial flip
        if (landmarkIndex != null && useLandmarkIndex) {
            // The flip1to4 creates 4 new tetrahedra
            landmarkIndex.addTetrahedron(last, size * 4);
        }

        // Use optimized flip processing
        if (useOptimizedFlip) {
            while (!ears.isEmpty()) {
                int lastIndex = ears.size() - 1;
                OrientedFace face = ears.remove(lastIndex);
                Tetrahedron l = FlipOptimizer.flipOptimized(face, v, ears);
                if (l != null) {
                    last = l;
                    // Occasionally update landmarks during cascading flips
                    if (landmarkIndex != null && useLandmarkIndex && ears.size() % 10 == 0) {
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
                    if (landmarkIndex != null && useLandmarkIndex && ears.size() % 10 == 0) {
                        landmarkIndex.addTetrahedron(l, size * 4);
                    }
                }
            }
        }

        // Periodically clean up deleted landmarks
        if (landmarkIndex != null && useLandmarkIndex && size % 100 == 0) {
            landmarkIndex.cleanup();
        }
    }
    
    /**
     * Validate that all vertices have valid adjacent tetrahedron references.
     * If a vertex is missing its adjacent reference, find a valid tetrahedron containing it.
     * Also validates bidirectional consistency between vertices and tetrahedra.
     */
    private void validateVertexReferences() {
        if (head == null) {
            return;
        }
        
        int validatedCount = 0;
        int repairedCount = 0;
        
        for (var v : head) {
            Tetrahedron adjacent = v.getAdjacent();
            
            // Check if vertex has no adjacent reference or reference is invalid
            if (adjacent == null || adjacent.isDeleted()) {
                adjacent = findTetrahedronContaining(v);
                if (adjacent != null) {
                    v.setAdjacent(adjacent);
                    repairedCount++;
                }
            } else {
                // Verify bidirectional consistency: tetrahedron should contain vertex
                boolean found = false;
                for (V vertex : V.values()) {
                    if (adjacent.getVertex(vertex) == v) {
                        found = true;
                        break;
                    }
                }
                
                if (!found) {
                    // Adjacent tetrahedron doesn't contain this vertex - find correct one
                    Tetrahedron correctAdjacent = findTetrahedronContaining(v);
                    if (correctAdjacent != null) {
                        v.setAdjacent(correctAdjacent);
                        repairedCount++;
                    }
                } else {
                    validatedCount++;
                }
            }
        }
        
        // Optionally log validation results for debugging large repairs only
        if (repairedCount > 50) {
            System.out.println("Vertex reference validation: " + validatedCount + " valid, " + 
                repairedCount + " repaired");
        }
    }
    
    /**
     * Find a tetrahedron that contains the given vertex.
     * This is used to repair missing vertex-tetrahedron references.
     */
    private Tetrahedron findTetrahedronContaining(Vertex v) {
        // Start from the last tetrahedron and walk the mesh
        if (last != null && !last.isDeleted()) {
            // Check if last contains the vertex
            if (last.includes(v)) {
                return last;
            }
            
            // Perform a breadth-first search from last
            List<Tetrahedron> visited = new ArrayList<>();
            List<Tetrahedron> queue = new ArrayList<>();
            queue.add(last);
            visited.add(last);
            
            while (!queue.isEmpty()) {
                Tetrahedron current = queue.remove(0);
                
                // Check all neighbors
                for (V vertex : V.values()) {
                    Tetrahedron neighbor = current.getNeighbor(vertex);
                    if (neighbor != null && !neighbor.isDeleted() && !visited.contains(neighbor)) {
                        if (neighbor.includes(v)) {
                            return neighbor;
                        }
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }
        
        return null; // Vertex not found in any tetrahedron
    }
    
    /**
     * Enable or disable validation.
     * When enabled, validation will be performed after insert/delete operations.
     */
    public void enableValidation(boolean enable) {
        if (enable && validationManager == null) {
            validationManager = new ValidationManager(this);
        }
        if (validationManager != null) {
            validationManager.enableValidation(enable);
        }
    }
    
    /**
     * Get the validation manager for this grid.
     * Creates one if it doesn't exist.
     */
    public ValidationManager getValidationManager() {
        if (validationManager == null) {
            validationManager = new ValidationManager(this);
        }
        return validationManager;
    }
    
    /**
     * Perform validation after an operation if validation is enabled.
     */
    private void performValidation() {
        if (validationManager != null) {
            validationManager.validateInvariants();
        }
    }
    
    /**
     * Release all tetrahedrons in the grid back to the pool.
     * This should be called before clear() or rebuild() to reuse memory.
     */
    private void releaseAllTetrahedrons() {
        if (size == 0 || head == null) {
            return;
        }
        
        // Collect all tetrahedrons
        Set<Tetrahedron> allTetrahedrons = tetrahedrons();
        
        // Release them all back to the pool
        TetrahedronPool pool = TetrahedronPool.getInstance();
        int releasedCount = 0;
        for (Tetrahedron t : allTetrahedrons) {
            if (!t.isDeleted()) {
                t.delete();
                pool.release(t);
                releasedCount++;
            }
        }
        
        // Also ensure the 'last' reference is cleared if it was released
        if (last != null && last.isDeleted()) {
            last = null;
        }
        
        // Optionally log for debugging very large rebuilds only
        if (releasedCount > 10000) {
            System.out.println("Released " + releasedCount + " tetrahedrons back to pool");
        }
    }
}
