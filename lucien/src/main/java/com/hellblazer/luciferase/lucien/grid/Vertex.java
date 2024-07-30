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

package com.hellblazer.luciferase.lucien.grid;

import com.hellblazer.luciferase.common.IdentitySet;
import com.hellblazer.luciferase.geometry.Geometry;

import javax.vecmath.Point3d;
import javax.vecmath.Tuple3d;
import javax.vecmath.Vector3d;
import java.io.Serial;
import java.util.*;

/**
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class Vertex extends Vector3d implements Iterable<Vertex> {
    static final         Point3d     ORIGIN           = new Point3d(0, 0, 0);
    @Serial
    private static final long        serialVersionUID = 1L;
    /**
     * One of the tetrahedra adjacent to the vertex
     */
    private              Tetrahedron adjacent;
    private              Vertex      next; // linked list o' vertices

    public Vertex(double i, double j, double k) {
        x = i;
        y = j;
        z = k;
    }

    public Vertex(double i, double j, double k, double scale) {
        this(i * scale, j * scale, k * scale);
    }

    public Vertex(Tuple3d p) {
        this(p.x, p.y, p.z);
    }

    /**
     * Generate a bounded random double
     *
     * @param random
     * @param min
     * @param max
     * @return
     */
    public static double random(Random random, double min, double max) {
        double result = random.nextDouble();
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
    public static Point3d randomPoint(Random random, double min, double max) {
        return new Point3d(random(random, min, max), random(random, min, max), random(random, min, max));
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

    public final double distanceSquared(Tuple3d p1) {
        double dx, dy, dz;

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

    public final List<OrientedFace> getEars() {
        assert adjacent != null;
        EarSet aggregator = new EarSet();
        adjacent.visitStar(this, aggregator);
        return aggregator.getEars();
    }

    /**
     * Answer the collection of neighboring vertices around the receiver.
     *
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
    public final List<Tuple3d[]> getVoronoiRegion() {
        assert adjacent != null;

        final List<Tuple3d[]> faces = new ArrayList<>();
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
     * Return +1 if the receiver lies inside the sphere passing through a, b, c, and d; -1 if it lies outside; and 0 if
     * the five points are cospherical. The vertices a, b, c, and d must be ordered so that they have a positive
     * orientation (as defined by {@link #orientation(Tuple3d, Tuple3d, Tuple3d)}), or the sign of the result will be
     * reversed.
     * <p>
     *
     * @param a , b, c, d - the points defining the sphere, in oriented order
     * @return +1 if the receiver lies inside the sphere passing through a, b, c, and d; -1 if it lies outside; and 0 if
     * the five points are cospherical
     */
    public final int inSphere(Tuple3d a, Tuple3d b, Tuple3d c, Tuple3d d) {
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
    public final Tetrahedron locate(Tuple3d query, Random entropy) {
        assert adjacent != null;
        return adjacent.locate(query, entropy);
    }

    public void moveBy(Tuple3d delta) {
        x = x + delta.x;
        y = y + delta.y;
        z = z + delta.z;
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
    public final int orientation(Tuple3d a, Tuple3d b, Tuple3d c) {
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
        if (adjacent == null || adjacent.isDeleted()) {
            adjacent = tetrahedron;
        }
    }

    void reset() {
        adjacent = null;
    }

}
