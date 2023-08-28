/**
 * Copyright (C) 2009 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the 3D Incremental Voronoi system
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.grid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import javax.vecmath.Vector3f;

import com.hellblazer.luciferase.common.Geometry;
import com.hellblazer.luciferase.common.IdentitySet;

/**
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */
public class Vertex extends Vector3f implements Iterable<Vertex> {
    /**
     * Minimal zero
     */
    static final double       EPSILON          = Math.pow(10F, -20F);
    static final Point3f      ORIGIN           = new Point3f(0, 0, 0);
    private static final long serialVersionUID = 1L;

    /**
     * Create some random points in a sphere
     *
     * @param random
     * @param numberOfPoints
     * @param radius
     * @param inSphere
     * @return
     */
    public static Point3f[] getRandomPoints(Random random, int numberOfPoints, float radius, boolean inSphere) {
        double radiusSquared = radius * radius;
        Point3f ourPoints[] = new Point3f[numberOfPoints];
        for (int i = 0; i < ourPoints.length; i++) {
            if (inSphere) {
                do {
                    ourPoints[i] = randomPoint(random, -radius, radius);
                } while (ourPoints[i].distanceSquared(ORIGIN) >= radiusSquared);
            } else {
                ourPoints[i] = randomPoint(random, -radius, radius);
            }
        }

        return ourPoints;
    }

    /**
     * Generate a bounded random double
     *
     * @param random
     * @param min
     * @param max
     * @return
     */
    public static float random(Random random, float min, float max) {
        float result = random.nextFloat();
        if (min > max) {
            result *= min - max;
            result += max;
        } else {
            result *= max - min;
            result += min;
        }
        return result;
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

    /**
     * One of the tetrahedra adjacent to the vertex
     */
    private Tetrahedron adjacent;

    private Vertex next; // linked list o' vertices

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

    public final double distanceSquared(Tuple3f p1) {
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

    public final List<OrientedFace> getEars() {
        assert adjacent != null;
        EarSet aggregator = new EarSet();
        adjacent.visitStar(this, aggregator);
        return aggregator.getEars();
    }

    /**
     * Answer the collection of neighboring vertices around the receiver.
     *
     * @param v - the vertex determining the neighborhood
     * @return the collection of neighboring vertices
     */
    public final Collection<Vertex> getNeighbors() {
        assert adjacent != null;

        final Set<Vertex> neighbors = new IdentitySet<>();
        adjacent.visitStar(this, (vertex, t, x, y, z) -> {
            neighbors.add(x);
            neighbors.add(y);
            neighbors.add(z);
        });
        return neighbors;
    }

    public final Deque<OrientedFace> getStar() {
        assert adjacent != null;

        final Deque<OrientedFace> star = new ArrayDeque<>();
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

        final List<Tuple3f[]> faces = new ArrayList<>();
        Set<Vertex> neighbors = new IdentitySet<>(10);
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
     * Return +1 if the receiver lies inside the sphere passing through a, b, c, and
     * d; -1 if it lies outside; and 0 if the five points are cospherical. The
     * vertices a, b, c, and d must be ordered so that they have a positive
     * orientation (as defined by {@link #orientation(Vertex, Vertex, Vertex)}), or
     * the sign of the result will be reversed.
     * <p>
     *
     * @param a , b, c, d - the points defining the sphere, in oriented order
     * @return +1 if the receiver lies inside the sphere passing through a, b, c,
     *         and d; -1 if it lies outside; and 0 if the five points are
     *         cospherical
     */
    public final int inSphere(Tuple3f a, Tuple3f b, Tuple3f c, Tuple3f d) {
        double result = Geometry.inSphereFast(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, x, y, z);
        if (result > 0.0) {
            return 1;
        } else if (result < 0.0) {
            return -1;
        }
        return 0;
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
     * @return the Tetrahedron that encompasses the query point
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

    /**
     * Answer +1 if the orientation of the receiver is positive with respect to the
     * plane defined by {a, b, c}, -1 if negative, or 0 if the test point is
     * coplanar
     * <p>
     *
     * @param a , b, c - the points defining the plane
     * @return +1 if the orientation of the query point is positive with respect to
     *         the plane, -1 if negative and 0 if the test point is coplanar
     */
    public final int orientation(Tuple3f a, Tuple3f b, Tuple3f c) {
        double result = Geometry.leftOfPlaneFast(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, x, y, z);
        if (result > 0.0) {
            return 1;
        } else if (result < 0.0) {
            return -1;
        }
        return 0;
    }

    @Override
    public String toString() {
        return "{" + x + ", " + y + ", " + z + "}";
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
        Vertex n = next;
        while (n != null) {
            n.adjacent = null;
            n = n.next;
        }
    }

    void detach(Vertex v) {
        if (next == null) {
            throw new NoSuchElementException();
        }
        if (v == next) {
            next = v.next;
        }
    }

    void freshenAdjacent(Tetrahedron tetrahedron) {
        if (adjacent == null || adjacent.isDeleted())
            adjacent = tetrahedron;
    }

    void reset() {
        adjacent = null;
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

}
