/**
 * Copyright (C) 2009-2023 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.grid;

import com.hellblazer.luciferase.common.IdentitySet;

import javax.vecmath.Tuple3d;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.hellblazer.luciferase.lucien.grid.V.*;

/**
 * A Delaunay tetrahedralization. This implementation is optimized for Luciferase, not for any other particular use. As
 * such, it is based on floats, not doubles - although predicates are evaluated with doubles to ensure nothing horrible
 * blows up. The extent of the "Big Tetrahedron" that defines the maximum extent of the universe for this
 * tetrahedralization is thus {-32k, +32k}. This implementation also uses the "fast" version of the inSphere predicate,
 * rather than the exact. The exact version is most expensive so made the call here. The result is that the generated
 * mesh isn't precisely exact. As long as it doesn't blow up, for the purposes of kinetic point tracking, we're happy.
 * <p>
 * We are largely concerned with the topology of the tracked points, and their relative location, not the precise form
 * of the mesh that encodes the topology. Because we throw the tetrahedra away on every rebuild, there's really little
 * need for an index and so random walk is used. It is assumed that the vast majority, if not damn near entirety of
 * operations concerning the Grid and its tracked components and topology will be operating with a vertex after the
 * tetrahedralization has occurred. Consequently, operations on Vertex and Tetrahedron are the de facto operation
 * origination rather at the Grid level.
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class Grid implements Iterable<Vertex> {

    /**
     * Cannonical enumeration of the vertex ordinals
     */
    public static final    V[]      VERTICES = { A, B, C, D };
    /**
     * A pre-built table of all the permutations of remaining faces to check in location.
     */
    protected static final V[][][]  ORDER    = new V[][][] {
    { { B, C, D }, { C, B, D }, { C, D, B }, { B, D, C }, { D, B, C }, { D, C, B } },

    { { A, C, D }, { C, A, D }, { C, D, A }, { A, D, C }, { D, A, C }, { D, C, A } },

    { { B, A, D }, { A, B, D }, { A, D, B }, { B, D, A }, { D, B, A }, { D, A, B } },

    { { B, C, A }, { C, B, A }, { C, A, B }, { B, A, C }, { A, B, C }, { A, C, B } } };
    /**
     * Scale of the universe
     */
    private static         float    SCALE    = (float) Math.pow(2, 16);
    /**
     * The four corners of the maximally bounding tetrahedron
     */
    protected final        Vertex[] fourCorners;
    /**
     * the Head of the vertices list
     */
    protected              Vertex   head;
    /**
     * The number of points in this Grid
     */
    protected              int      size     = 0;

    Grid(Vertex[] fourCorners) {
        this.fourCorners = fourCorners;
    }

    Grid(Vertex[] fourCorners, Vertex head) {
        this(fourCorners);
        this.head = head;
    }

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
    public static Tetrahedron myOwnPrivateIdaho(Grid s) {
        Vertex[] U = new Vertex[4];
        int i = 0;
        for (Vertex v : s.extent()) {
            U[i++] = new Vertex(v);
        }
        return new Tetrahedron(U);
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
     * Locate the tetrahedron which contains the query point via a stochastic walk through the delaunay triangulation.
     * This location algorithm is a slight variation of the 3D jump and walk algorithm found in: "Fast randomized point
     * location without preprocessing in two- and three-dimensional Delaunay triangulations", Computational Geometry 12
     * (1999) 63-83.
     *
     * @param query  - the query point
     * @param start  - the starting tetrahedron
     * @param random - the source of entropy for the randomized algo
     * @return the Tetrahedron containing the query
     */
    public Tetrahedron locate(Tuple3d query, Tetrahedron start, Random random) {
        assert query != null;
        return start.locate(query, random);
    }

    public int size() {
        return size;
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
        if (size == 0) {
            return Collections.emptySet();
        }
        Set<Tetrahedron> all = new IdentitySet<>(size);
        var stack = new Stack<Tetrahedron>();
        stack.push(head.getAdjacent());
        while (!stack.isEmpty()) {
            var next = stack.pop();
            if (all.add(next)) {
                next.children(stack, all);
            }
        }
        return all;
    }

    Vertex getHead() {
        return head;
    }

    void setHead(Vertex head) {
        this.head = head;
    }

    void setSize(int size) {
        this.size = size;
    }
}
