/**
 * Copyright (C) 2009 Hal Hildebrand. All rights reserved.
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

import com.hellblazer.luciferase.common.IdentitySet;
import com.hellblazer.luciferase.geometry.Geometry;
import com.hellblazer.luciferase.geometry.MortonCurve;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import javax.vecmath.Vector3f;
import java.io.Serial;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class Vertex extends Vector3f implements Cursor, Iterable<Vertex>, Comparable<Vertex> {
    static final         Point3f     ORIGIN           = new Point3f(0, 0, 0);
    @Serial
    private static final long        serialVersionUID = 1L;
    /**
     * Geometric predicates implementation (scalar or SIMD)
     */
    private static final GeometricPredicates PREDICATES = GeometricPredicatesFactory.getInstance();
    /**
     * One of the tetrahedra adjacent to the vertex
     */
    private              Tetrahedron adjacent;
    private              Vertex      next; // linked list o' vertices

    public Vertex(float i, float j, float k) {
        x = i;
        y = j;
        z = k;
    }

    public Vertex(float i, float j, float k, float scale) {
        this(i * scale, j * scale, k * scale);
    }

    public Vertex(Tuple3f p) {
        this(p.x, p.y, p.z);
    }

    public static Point3f[] getRandomPoints(Random random, int numberOfPoints, float radius, boolean inSphere) {
        float radiusSquared = radius * radius;
        Point3f[] ourPoints = new Point3f[numberOfPoints];
        for (int i = 0; i < ourPoints.length; i++) {
            if (inSphere) {
                do {
                    ourPoints[i] = randomPoint(radius, random);
                } while (ourPoints[i].distanceSquared(ORIGIN) >= radiusSquared);
            } else {
                ourPoints[i] = randomPoint(radius, random);
            }
        }

        return ourPoints;
    }

    /**
     * Generate a bounded random float
     *
     * @param random
     * @param min
     * @param max
     * @return
     */
    public static float random(Random random, float min, float max) {
        var result = random.nextFloat();
        if (min > max) {
            result *= min - max;
            result += max;
        } else {
            result *= max - min;
            result += min;
        }
        return result;
    }

    public static Point3f randomPoint(float radius, Random random) {
        var x = random.nextFloat() * (random.nextBoolean() ? 1.0f : -1.0f);
        var y = random.nextFloat() * (random.nextBoolean() ? 1.0f : -1.0f);
        var z = random.nextFloat() * (random.nextBoolean() ? 1.0f : -1.0f);

        return new Point3f(x * radius, y * radius, z * radius);
    }

    /**
     * Generate a random point
     *
     * @param random
     * @param min
     * @param max
     * @return
     */
    public static Point3f randomPoint(Random random, float min, float max) {
        return new Point3f(random(random, min, max), random(random, min, max), random(random, min, max));
    }

    public static Vertex[] vertices(Tuple3i[] vertices) {
        Vertex[] result = new Vertex[vertices.length];
        for (int i = 0; i < vertices.length; i++) {
            var vertex = vertices[i];
            result[i] = new Vertex((float) vertex.x, (float) vertex.y, (float) vertex.z);
        }
        return result;
    }

    /**
     * Answer the component model of the receiver corresponding to the model class
     *
     * @param <T>   - type of model
     * @param model - model class
     * @return the typed instance of the model, or null if none
     */
    public <T> T as(Class<T> model) {
        return null;
    }

    @Override
    public int compareTo(Vertex o) {
        var a = MortonCurve.encode((int) x, (int) y, (int) z);
        var b = MortonCurve.encode((int) o.x, (int) o.y, (int) o.z);
        return Long.compare(a, b);
    }

    public final float distanceSquared(Tuple3f p1) {
        float dx, dy, dz;

        dx = x - p1.x;
        dy = y - p1.y;
        dz = z - p1.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Answer one of the adjacent tetrahedron
     *
     * @return
     */
    public final Tetrahedron getAdjacent() {
        return adjacent;
    }

    /**
     * Note one of the adjacent tetrahedron
     * <p>
     *
     * @param tetrahedron
     */
    final void setAdjacent(Tetrahedron tetrahedron) {
        adjacent = tetrahedron;
    }
    
    /**
     * Remove the adjacent tetrahedron reference if it matches
     * <p>
     *
     * @param tetrahedron
     */
    final void removeAdjacent(Tetrahedron tetrahedron) {
        if (adjacent == tetrahedron) {
            adjacent = null;
        }
    }

    public Point3f getLocation() {
        return new Point3f(x, y, z);
    }

    /**
     * Answer the collection of neighboring vertices around the receiver.
     *
     * @return the collection of neighboring vertices
     */
    public Collection<Vertex> getNeighbors() {
        assert adjacent != null;

        final var neighbors = new IdentitySet<Vertex>();
        adjacent.visitStar(this, (vertex, t, x, y, z) -> {
            neighbors.add(x);
            neighbors.add(y);
            neighbors.add(z);
        });
        return neighbors;
    }

    public final Deque<OrientedFace> getStar() {
        assert adjacent != null;

        final var star = new ArrayDeque<OrientedFace>();
        adjacent.visitStar(this, (vertex, t, x, y, z) -> {
            star.push(t.getFace(vertex));
        });
        return star;
    }

    /**
     * Answer the faces of the voronoi region around the receiver
     *
     * @return the list of faces defining the voronoi region defined by the receiver
     */
    public final List<Tuple3f[]> getVoronoiRegion() {
        assert adjacent != null;

        final var faces = new ArrayList<Tuple3f[]>();
        var neighbors = new IdentitySet<Tuple3f>(10);
        adjacent.visitStar(this, (vertex, t, x, y, z) -> {
            if (neighbors.add(x)) {
                t.traverseVoronoiFace(this, x, faces);
            }
            if (neighbors.add(y)) {
                t.traverseVoronoiFace(this, y, faces);
            }
            if (neighbors.add(z)) {
                t.traverseVoronoiFace(this, z, faces);
            }
        });
        return faces;
    }

    /**
     * Return +1 if the receiver lies inside the sphere passing through a, b, c, and d; -1 if it lies outside; and 0 if
     * the five points are cospherical. The vertices a, b, c, and d must be ordered so that they have a positive
     * orientation (as defined by {@link #orientation(Tuple3f, Tuple3f, Tuple3f)}), or the sign of the result will be
     * reversed.
     * <p>
     *
     * @param a , b, c, d - the points defining the sphere, in oriented order
     * @return +1 if the receiver lies inside the sphere passing through a, b, c, and d; -1 if it lies outside; and 0 if
     * the five points are cospherical
     */
    public final double inSphere(Tuple3f a, Tuple3f b, Tuple3f c, Tuple3f d) {
        var result = PREDICATES.inSphere(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, x, y, z);
        return Math.signum(result);
    }

    @Override
    public final Iterator<Vertex> iterator() {
        return new Iterator<Vertex>() {
            private Vertex next = Vertex.this;

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public Vertex next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                var current = next;
                next = next.next;
                return current;
            }
        };
    }

    /**
     * Locate the tetrahedron encompassing the query point
     *
     * @param query
     * @param entropy - entropy used for randomization of search
     * @return the Tetrahedron that encompasses the query point or null if point falls outside of the
     * tetrahedralization.
     */
    public final Tetrahedron locate(Tuple3f query, Random entropy) {
        assert adjacent != null;
        return adjacent.locate(query, entropy);
    }

    public void moveBy(Tuple3f delta) {
        x = x + delta.x;
        y = y + delta.y;
        z = z + delta.z;
    }

    @Override
    public void moveTo(Tuple3f position) {
        x = position.x;
        y = position.y;
        z = position.z;
    }

    @Override
    public Stream<Cursor> neighbors() {
        return getNeighbors().stream().map(e -> e);
    }

    /**
     * Answer +1 if the orientation of the receiver is positive with respect to the plane defined by {a, b, c}, -1 if
     * negative, or 0 if the test point is coplanar
     * <p>
     *
     * @param a , b, c - the points defining the plane
     * @return +1 if the orientation of the query point is positive with respect to the plane, -1 if negative and 0 if
     * the test point is coplanar
     */
    public final double orientation(Tuple3f a, Tuple3f b, Tuple3f c) {
        var result = PREDICATES.orientation(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, x, y, z);
        return Math.signum(result);
    }

    @Override
    public String toString() {
        return "{" + x + ", " + y + ", " + z + "}";
    }

    @Override
    public void visitNeighbors(Consumer<Cursor> consumer) {
        final var neighbors = new IdentitySet<Vertex>();
        visitNeighbors((vertex, t, x, y, z) -> {
            if (neighbors.add(x)) {
                consumer.accept(x);
            }
            if (neighbors.add(y)) {
                consumer.accept(y);
            }
            if (neighbors.add(z)) {
                consumer.accept(z);
            }
        });
    }

    public final void visitNeighbors(StarVisitor visitor) {
        assert adjacent != null;
        adjacent.visitStar(this, visitor);
    }

    void append(Vertex v) {
        assert next == null : "Next is not null";
        next = v;
    }

    void clear() {
        adjacent = null;
        var n = next;
        while (n != null) {
            n.adjacent = null;
            n = n.next;
        }
        next = null;
    }

    void detach(Vertex v) {
        var n = next;
        while (n != null) {
            if (v == next) {
                next = v.next;
                return;
            }
        }
        throw new NoSuchElementException();
    }

    void freshenAdjacent(Tetrahedron tetrahedron) {
        if (adjacent == null || adjacent.isDeleted()) {
            adjacent = tetrahedron;
        }
    }

    void reset() {
        adjacent = null;
    }
    
    /**
     * Calculate the star size (number of tetrahedra incident to this vertex).
     *
     * @return the number of incident tetrahedra
     */
    public int getStarSize() {
        if (adjacent == null) return 0;
        
        final AtomicInteger count = new AtomicInteger(0);
        adjacent.visitStar(this, (vertex, t, x, y, z) -> {
            count.incrementAndGet();
        });
        return count.get();
    }
    
    /**
     * Check if this vertex is on the convex hull of the tetrahedralization.
     *
     * @return true if on convex hull
     */
    public boolean isOnConvexHull() {
        if (adjacent == null) return false;
        
        // A vertex is on the convex hull if any of its incident faces
        // has no adjacent tetrahedron
        final AtomicBoolean onHull = new AtomicBoolean(false);
        adjacent.visitStar(this, (vertex, t, x, y, z) -> {
            OrientedFace face = t.getFace(vertex);
            if (!face.hasAdjacent()) {
                onHull.set(true);
            }
        });
        return onHull.get();
    }
    
    /**
     * Get the average edge length from this vertex to its neighbors.
     *
     * @return the average edge length
     */
    public float getAverageEdgeLength() {
        Collection<Vertex> neighbors = getNeighbors();
        if (neighbors.isEmpty()) return 0;
        
        float totalLength = 0;
        for (Vertex neighbor : neighbors) {
            totalLength += Math.sqrt(distanceSquared(neighbor));
        }
        return totalLength / neighbors.size();
    }
    
    /**
     * Get validation metrics for this vertex.
     *
     * @return a map of validation metrics
     */
    public Map<String, Object> getValidationMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("position", new Point3f(x, y, z));
        metrics.put("starSize", getStarSize());
        metrics.put("neighborCount", getNeighbors().size());
        metrics.put("isOnConvexHull", isOnConvexHull());
        metrics.put("averageEdgeLength", getAverageEdgeLength());
        metrics.put("hasAdjacent", adjacent != null);
        return metrics;
    }
    
    /**
     * Check if the star of this vertex is valid (all tetrahedra are properly connected).
     *
     * @return true if star is valid
     */
    public boolean hasValidStar() {
        if (adjacent == null) return false;
        
        final Set<Tetrahedron> starTets = new HashSet<>();
        final AtomicBoolean allValid = new AtomicBoolean(true);
        
        adjacent.visitStar(this, (vertex, t, x, y, z) -> {
            if (t.isDeleted()) {
                allValid.set(false);
                return;
            }
            
            // Check that this vertex is actually in the tetrahedron
            if (!t.includes(this)) {
                allValid.set(false);
                return;
            }
            
            starTets.add(t);
        });
        
        return allValid.get() && !starTets.isEmpty();
    }

}
