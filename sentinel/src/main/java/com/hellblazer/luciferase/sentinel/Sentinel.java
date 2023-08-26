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

package com.hellblazer.luciferase.sentinel;

import static com.hellblazer.luciferase.sentinel.V.A;
import static com.hellblazer.luciferase.sentinel.V.B;
import static com.hellblazer.luciferase.sentinel.V.C;
import static com.hellblazer.luciferase.sentinel.V.D;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;

/**
 * A Delaunay tetrahedralization. This implementation is optimized for
 * Luciferase, not for any other particular use. As such, it is based on floats,
 * not doubles - although predicates are evaluated with doubles to ensure
 * nothing horrible blows up. The extent of the "Big Tetrahedron" that defines
 * the maximum extent of the universe for this tetrahedralization is thus {-32k,
 * +32k}. This implementation also uses the "fast" version of the inSphere
 * predicate, rather than the exact. The exact version is most expensive so made
 * the call here. The result is that the generated mesh isn't precisely exact.
 * As long as it doesn't blow up, for the purposes of kinetic point tracking,
 * we're happy.
 * <p>
 * We are largely concerned with the topology of the tracked points, and their
 * relative location, not the precise form of the mesh that encodes the
 * topology. Because we throw the tetrahedra away on every rebuild, there's
 * really little need for an index and so random walk is used. It is assumed
 * that the vast majority, if not damn near entirety of operations concerning
 * the Sentinel and its tracked components and topology will be operating with a
 * vertex after the tetrahedralization has occurred. Consequently operations on
 * Vertex and Tetrahedron are the defacto operation origination rather at the
 * Sentinel level.
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 *
 */

public class Sentinel implements Iterable<Vertex> {
    /**
     * Cannonical enumeration of the vertex ordinals
     */
    public final static V[] VERTICES = { A, B, C, D };

    /**
     * A pre-built table of all the permutations of remaining faces to check in
     * location.
     */
    static final V[][][] ORDER = new V[][][] { { { B, C, D }, { C, B, D }, { C, D, B }, { B, D, C }, { D, B, C },
                                                 { D, C, B } },

                                               { { A, C, D }, { C, A, D }, { C, D, A }, { A, D, C }, { D, A, C },
                                                 { D, C, A } },

                                               { { B, A, D }, { A, B, D }, { A, D, B }, { B, D, A }, { D, B, A },
                                                 { D, A, B } },

                                               { { B, C, A }, { C, B, A }, { C, A, B }, { B, A, C }, { A, B, C },
                                                 { A, C, B } } };

    /**
     * Scale of the universe
     */
    private static float SCALE = (float) Math.pow(2, 16);

    public static Vertex[] getFourCorners() {
        Vertex[] fourCorners = new Vertex[4];
        fourCorners[0] = new Vertex(-1, 1, -1, SCALE);
        fourCorners[1] = new Vertex(1, 1, 1, SCALE);
        fourCorners[2] = new Vertex(1, -1, -1, SCALE);
        fourCorners[3] = new Vertex(-1, -1, 1, SCALE);
        return fourCorners;
    }

    /**
     * Construct a Tetrahedron which is set up to encompass the numerical span
     *
     * @return
     */
    public static Tetrahedron myOwnPrivateIdaho(Sentinel s) {
        Vertex[] U = new Vertex[4];
        int i = 0;
        for (Vertex v : s.extent()) {
            U[i++] = new Vertex(v);
        }
        return new Tetrahedron(U);
    }

    /**
     * The four corners of the maximally bounding tetrahedron
     */
    private final Vertex[] fourCorners;

    /**
     * the Head of the vertices list
     */
    private Vertex head;

    /**
     * The last valid tetrahedron noted
     */
    private Tetrahedron last;

    /**
     * A random number generator
     */
    private final Random random;

    /**
     * The number of points in this Sentinel
     */
    private int size = 0;

    /**
     * Tail of the vertices list
     */
    private Vertex tail;

    /**
     * Construct a new Sentinel with the default random number generator
     */
    public Sentinel() {
        this(new Random());
    }

    /**
     * Construct a Sentinel using the supplied random number generator
     *
     * @param random
     */
    public Sentinel(Random random) {
        assert random != null;
        fourCorners = getFourCorners();
        this.random = random;
        last = new Tetrahedron(fourCorners);
    }

    public void clear() {
        if (head != null) {
            head.clear();
        }
        for (var v : fourCorners) {
            v.reset();
        }
    }

    /**
     * Answer the four corners of the universe
     *
     * @return
     */
    public Vertex[] extent() {
        return fourCorners;
    }

    @Override
    public Iterator<Vertex> iterator() {
        return head != null ? head.iterator() : new Iterator<Vertex>() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public Vertex next() {
                throw new NoSuchElementException();
            }
        };
    }

    /**
     * Locate the tetrahedron which contains the query point via a stochastic walk
     * through the delaunay triangulation. This location algorithm is a slight
     * variation of the 3D jump and walk algorithm found in: "Fast randomized point
     * location without preprocessing in two- and three-dimensional Delaunay
     * triangulations", Computational Geometry 12 (1999) 63-83.
     *
     * @param query - the query point
     * @return the Tetrahedron containing the query
     */
    public Tetrahedron locate(Tuple3f query) {
        return locate(query, last);
    }

    /**
     * Locate the tetrahedron which contains the query point via a stochastic walk
     * through the delaunay triangulation. This location algorithm is a slight
     * variation of the 3D jump and walk algorithm found in: "Fast randomized point
     * location without preprocessing in two- and three-dimensional Delaunay
     * triangulations", Computational Geometry 12 (1999) 63-83.
     *
     * @param query - the query point
     * @param start - the starting tetrahedron
     * @return the Tetrahedron containing the query
     */
    public Tetrahedron locate(Tuple3f query, Tetrahedron start) {
        assert query != null;

        return start.locate(query, random);
    }

    public void rebuild() {
        clear();
        last = new Tetrahedron(fourCorners);
        for (var v : head) {
            insert(v, locate(v, last));
        }
    }

    /**
     * Answer the stream of all vertices in this tetrahedralization
     *
     * @return
     */
    public Stream<Vertex> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    /**
     * Answer the set of all tetrahedrons in this tetrahedralization
     *
     * @return
     */
    public Set<Tetrahedron> tetrahedrons() {
        Set<Tetrahedron> all = new IdentitySet<>(size);
        var stack = new Stack<Tetrahedron>();
        stack.push(last);
        while (!stack.isEmpty()) {
            var next = stack.pop();
            if (all.add(next)) {
                next.children(stack, all);
            }
        }
        return all;
    }

    /**
     * Track the point into the tetrahedralization. See "Computing the 3D Voronoi
     * Diagram Robustly: An Easy Explanation", by Hugo Ledoux
     * <p>
     *
     * @param p - the point to be inserted
     * @return the Vertex in the tetrahedralization
     */
    public Vertex track(Point3f p) {
        assert p != null;
        final var v = new Vertex(p);
        add(v, locate(p, last));
        return v;
    }

    /**
     * Track the point into the tetrahedralization. See "Computing the 3D Voronoi
     * Diagram Robustly: An Easy Explanation", by Hugo Ledoux
     * <p>
     *
     * @param p    - the point to be inserted
     * @param near - the nearby vertex
     * @return the new Vertex in the tetrahedralization
     */
    public Vertex track(Point3f p, Vertex near) {
        assert p != null;
        final var v = new Vertex(p);
        add(v, near.locate(p, random));
        return v;
    }

    public void untrack(Vertex v) {
        if (head != null) {
            head.detach(v);
            v.clear();
        }
    }

    /**
     * Perform the 4->1 bistellar flip. This flip is the inverse of the 1->4 flip.
     *
     * @param n - the vertex who's star defines the 4 tetrahedron
     *
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
        Tetrahedron t = new Tetrahedron(a, b, c, d);
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

    private void insert(Vertex v, final Tetrahedron target) {
        List<OrientedFace> ears = new ArrayList<>();
        last = target.flip1to4(v, ears);
        while (!ears.isEmpty()) {
            Tetrahedron l = ears.remove(ears.size() - 1).flip(v, ears);
            if (l != null) {
                last = l;
            }
        }
    }
}
