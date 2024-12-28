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
import java.util.*;

import static com.hellblazer.luciferase.geometry.Geometry.centerSphere;
import static com.hellblazer.luciferase.lucien.grid.V.*;

/**
 * An oriented, delaunay tetrahedral cell. The vertices of the tetrahedron are A, B, C and D. The vertices {A, B, C} are
 * positively oriented with respect to the fourth vertex D.
 * <p>
 *
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 */
public class Tetrahedron implements Iterable<OrientedFace> {
    /**
     * Matrix used to determine the next neighbor in a voronoi face traversal
     */
    private static final V[][][]     VORONOI_FACE_NEXT   = {
    { null, { null, null, D, C }, { null, D, null, B }, { null, C, B, null } },
    { { null, null, D, C }, null, { D, null, null, A }, { C, null, A, null } },
    { { null, D, null, B }, { D, null, null, A }, null, { B, A, null, null } },
    { { null, C, B, null }, { C, null, A, null }, { B, A, null, null }, null } };
    /**
     * Matrix used to determine the origin neighbor in a vororoni face traversal
     */
    private static final V[][]       VORONOI_FACE_ORIGIN = { { null, C, D, B }, { C, null, D, A }, { D, A, null, B },
                                                             { B, C, A, null } };
    private final        FaceCBD     faceCBD;
    private final        FaceDAC     faceDAC;
    private final        FaceADB     faceADB;
    private final        FaceBCA     faceBCA;
    /**
     * Vertex A
     */
    private              Vertex      a;
    /**
     * Vertx B
     */
    private              Vertex      b;
    /**
     * Vertex C
     */
    private              Vertex      c;
    /**
     * Vertex D
     */
    private              Vertex      d;
    /**
     * The neighboring tetrahedron opposite of vertex A
     */
    private              Tetrahedron nA;
    /**
     * The neighboring tetrahedron opposite of vertex B
     */
    private              Tetrahedron nB;
    /**
     * The neighboring tetrahedron opposite of vertex C
     */
    private              Tetrahedron nC;
    /**
     * The neighboring tetrahedron opposite of vertex D
     */
    private              Tetrahedron nD;

    /**
     * Construct a tetrahedron from the four vertices
     *
     * @param x
     * @param y
     * @param z
     * @param w
     */
    public Tetrahedron(Vertex x, Vertex y, Vertex z, Vertex w) {
        assert x != null & y != null & z != null & w != null;

        a = x;
        b = y;
        c = z;
        d = w;

        a.setAdjacent(this);
        b.setAdjacent(this);
        c.setAdjacent(this);
        d.setAdjacent(this);
        faceCBD = new FaceCBD();
        faceDAC = new FaceDAC();
        faceADB = new FaceADB();
        faceBCA = new FaceBCA();
    }

    /**
     * Construct a tetrahedron from the array of four vertices
     *
     * @param vertices
     */
    public Tetrahedron(Vertex[] vertices) {
        this(vertices[0], vertices[1], vertices[2], vertices[3]);
        assert vertices.length == 4;
    }

    /**
     * Answer +1 if the orientation of the query is positive with respect to the plane defined by {a, b, c}, -1 if
     * negative, or 0 if the test point is coplanar
     * <p>
     *
     * @param query - the point to query
     * @param a     , b, c - the points defining the plane
     * @return +1 if the orientation of the query point is positive with respect to the plane, -1 if negative and 0 if
     * the test point is coplanar
     */
    public static double orientation(Tuple3d query, Tuple3d a, Tuple3d b, Tuple3d c) {
        double result = Geometry.leftOfPlaneFast(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, query.x, query.y,
                                                 query.z);
        return Math.signum(result);
    }

    /**
     * Add the four faces defined by the tetrahedron to the list of faces
     *
     * @param faces
     */
    public void addFaces(List<Vertex[]> faces) {
        faces.add(new Vertex[] { a, d, b });
        faces.add(new Vertex[] { b, c, a });
        faces.add(new Vertex[] { c, b, d });
        faces.add(new Vertex[] { d, a, c });
    }

    public Point3d center() {
        double[] center = new double[3];
        centerSphere(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, center);
        return new Point3d(center[0], center[1], center[2]);
    }

    /**
     * Perform the 1 -> 4 bistellar flip. This produces 4 new tetrahedron from the original tetrahdron, by inserting the
     * new point in the interior of the receiver tetrahedron. The star set of the newly inserted point is pushed onto
     * the supplied stack.
     * <p>
     *
     * @param n    - the inserted point
     * @param ears - the stack of oriented faces that make up the ears of the inserted point
     * @return one of the four new tetrahedra
     */
    public Tetrahedron flip1to4(Vertex n, List<OrientedFace> ears) {
        Tetrahedron t0 = new Tetrahedron(a, b, c, n);
        Tetrahedron t1 = new Tetrahedron(a, d, b, n);
        Tetrahedron t2 = new Tetrahedron(a, c, d, n);
        Tetrahedron t3 = new Tetrahedron(b, d, c, n);

        t0.setNeighborA(t3);
        t0.setNeighborB(t2);
        t0.setNeighborC(t1);

        t1.setNeighborA(t3);
        t1.setNeighborB(t0);
        t1.setNeighborC(t2);

        t2.setNeighborA(t3);
        t2.setNeighborB(t1);
        t2.setNeighborC(t0);

        t3.setNeighborA(t2);
        t3.setNeighborB(t0);
        t3.setNeighborC(t1);

        patch(D, t0, D);
        patch(C, t1, D);
        patch(B, t2, D);
        patch(A, t3, D);

        delete();

        OrientedFace newFace = t0.getFace(D);
        if (newFace.hasAdjacent()) {
            ears.add(newFace);
        }
        newFace = t1.getFace(D);
        if (newFace.hasAdjacent()) {
            ears.add(newFace);
        }
        newFace = t2.getFace(D);
        if (newFace.hasAdjacent()) {
            ears.add(newFace);
        }
        newFace = t3.getFace(D);
        if (newFace.hasAdjacent()) {
            ears.add(newFace);
        }
        return t1;
    }

    /**
     * Answer the oriented face of the tetrahedron
     * <p>
     *
     * @param v - the vertex opposite the face
     * @return the OrientedFace
     */
    public OrientedFace getFace(V v) {
        if (v == A) {
            return faceCBD;
        }
        if (v == B) {
            return faceDAC;
        }
        if (v == C) {
            return faceADB;
        }
        if (v == D) {
            return faceBCA;
        }
        throw new IllegalArgumentException("Invalid vertex: " + v);
    }

    /**
     * Answer the oriented face opposite the vertex
     *
     * @param v
     * @return
     */
    public OrientedFace getFace(Vertex v) {
        return getFace(ordinalOf(v));
    }

    public List<Vertex[]> getFaces() {
        List<Vertex[]> faces = new ArrayList<>();
        faces.add(new Vertex[] { a, d, b });
        faces.add(new Vertex[] { b, c, a });
        faces.add(new Vertex[] { c, b, d });
        faces.add(new Vertex[] { d, a, c });
        return faces;
    }

    /**
     * Answer the neighbor that is adjacent to the face opposite of the vertex
     * <p>
     *
     * @param v - the opposing vertex defining the face
     * @return the neighboring tetrahedron, or null if none.
     */
    public Tetrahedron getNeighbor(V v) {
        if (v == A) {
            return nA;
        }
        if (v == B) {
            return nB;
        }
        if (v == C) {
            return nC;
        }
        return nD;
    }

    /**
     * Answer the neighbor that is adjacent to the face opposite of the vertex
     * <p>
     *
     * @param vertex
     * @return
     */
    public Tetrahedron getNeighbor(Vertex vertex) {
        return getNeighbor(ordinalOf(vertex));
    }

    /**
     * Answer the vertex of the tetrahedron
     *
     * @param v the vertex
     * @return the vertex
     */
    public Vertex getVertex(V v) {
        if (v == A) {
            return a;
        }
        if (v == B) {
            return b;
        }
        if (v == C) {
            return c;
        }
        if (v == D) {
            return d;
        }
        throw new IllegalStateException("No such point");
    }

    /**
     * Answer the four vertices that define the tetrahedron
     *
     * @return
     */
    public Vertex[] getVertices() {
        return new Vertex[] { a, b, c, d };
    }

    public boolean includes(Vertex query) {
        return a == query || b == query || c == query || d == query;
    }

    /**
     * Answer true if the query point is contained in the circumsphere of the tetrahedron
     *
     * @param query
     * @return
     */
    public boolean inSphere(Vertex query) {
        return query.inSphere(a, b, c, d) > 0;
    }

    /**
     * Answer the iterator over the faces of the tetrahedron
     * <p>
     *
     * @return the iterator of the faces, in the order of the index their opposite vertex
     */
    @Override
    public Iterator<OrientedFace> iterator() {
        return new Iterator<>() {
            OrientedFace[] faces = { getFace(A), getFace(B), getFace(C), getFace(D) };
            int            i     = 0;

            @Override
            public boolean hasNext() {
                return i < 4;
            }

            @Override
            public OrientedFace next() {
                return faces[i++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    public Tetrahedron locate(Tuple3d query, Random entropy) {
        assert query != null;

        V o = null;
        for (V face : Grid.VERTICES) {
            if (orientationWrt(face, query) < 0) {
                o = face;
                break;
            }
        }
        if (o == null) {
            // The query point is contained in the receiver
            return this;
        }

        Tetrahedron current = this;
        while (true) {
            // get the tetrahedron on the other side of the face
            Tetrahedron tetrahedron = current.getNeighbor(o);
            int i = 0;
            for (V v : Grid.ORDER[tetrahedron.ordinalOf(current).ordinal()][entropy.nextInt(6)]) {
                o = v;
                current = tetrahedron;
                if (tetrahedron.orientationWrt(v, query) < 0) {
                    // we have found a face which the query point is on the other side
                    break;
                }
                if (i++ == 2) {
                    return tetrahedron;
                }
            }
        }

    }

    /**
     * Answer the vertex indicator of the the point
     *
     * @param v - the vertex
     * @return the indicator of this vertex or null if not a vertex of this tetrahedron or the supplied vertex is null
     */
    public V ordinalOf(Vertex v) {
        if (v == null) {
            return null;
        }
        if (v == a) {
            return A;
        }
        if (v == b) {
            return B;
        }
        if (v == c) {
            return C;
        }
        return D;
    }

    /**
     * Answer > 0 if the query point is positively oriented with respect to the face opposite the vertex, < 0 if
     * negatively oriented, 0 if the query point is coplanar to the face
     *
     * @param face
     * @param query
     * @return
     */
    public double orientationWrt(V face, Tuple3d query) {
        if (face == A) {
            return orientationWrtCBD(query);
        }
        if (face == B) {
            return orientationWrtDAC(query);
        }
        if (face == C) {
            return orientationWrtADB(query);
        }
        if (face == D) {
            return orientationWrtBCA(query);
        }
        throw new IllegalArgumentException("Invalid face: " + face);
    }

    /**
     * Answer > 0 if the query point is positively oriented with respect to the face ADB, < 0 if negatively oriented, 0
     * if the query point is coplanar to the face
     *
     * @param query
     * @return
     */
    public double orientationWrtADB(Tuple3d query) {
        return orientation(query, a, d, b);
    }

    /**
     * Answer 1 if the query point is positively oriented with respect to the face BCA, -1 if negatively oriented, 0 if
     * the query point is coplanar to the face
     *
     * @param query
     * @return
     */
    public double orientationWrtBCA(Tuple3d query) {
        return orientation(query, b, c, a);
    }

    /**
     * Answer 1 if the query point is positively oriented with respect to the face CBD, -1 if negatively oriented, 0 if
     * the query point is coplanar to the face
     *
     * @param query
     * @return
     */
    public double orientationWrtCBD(Tuple3d query) {
        return orientation(query, c, b, d);
    }

    /**
     * Answer 1 if the query point is positively oriented with respect to the face DAC, -1 if negatively oriented, 0 if
     * the query point is coplanar to the face
     *
     * @param query
     * @return
     */
    public double orientationWrtDAC(Tuple3d query) {
        return orientation(query, d, a, c);
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append("Tetrahedron [");
        if (isDeleted()) {
            buf.append("DELETED]");
            return buf.toString();
        }
        for (Vertex v : getVertices()) {
            buf.append(v);
            buf.append(", ");
        }
        buf.append(']');
        return buf.toString();
    }

    void children(Stack<Tetrahedron> stack, Set<Tetrahedron> processed) {
        if (nA != null && !processed.contains(nA)) {
            stack.push(nA);
        }
        if (nB != null && !processed.contains(nB)) {
            stack.push(nB);
        }
        if (nC != null && !processed.contains(nC)) {
            stack.push(nC);
        }
        if (nD != null && !processed.contains(nD)) {
            stack.push(nD);
        }
    }

    /**
     * Clean up the pointers
     */
    void delete() {
        nA = nB = nC = nD = null;
        a = b = c = d = null;
    }

    Vertex getA() {
        return a;
    }

    Vertex getB() {
        return b;
    }

    Vertex getC() {
        return c;
    }

    Vertex getD() {
        return d;
    }

    boolean isDeleted() {
        return a == null;
    }

    /**
     * Answer the canonical ordinal of the opposite vertex of the neighboring tetrahedron
     *
     * @param neighbor
     * @return
     */
    V ordinalOf(Tetrahedron neighbor) {
        if (nA == neighbor) {
            return A;
        }
        if (nD == neighbor) {
            return D;
        }
        if (nB == neighbor) {
            return B;
        }
        if (nC == neighbor) {
            return C;
        }
        if (neighbor == null) {
            return null;
        }
        throw new IllegalArgumentException("Not a neighbor: " + neighbor);
    }

    private void clear() {
        faceADB.clear();
        faceBCA.clear();
        faceCBD.clear();
        faceDAC.clear();
    }

    /**
     * Patch the new tetrahedron created by a flip of the receiver by seting the neighbor to the value in the receiver
     * <p>
     *
     * @param vOld - the opposing vertex the neighboring tetrahedron in the receiver
     * @param n    - the new tetrahedron to patch
     * @param vNew - the opposing vertex of the neighbor to assign in the new tetrahedron
     */
    void patch(V vOld, Tetrahedron n, V vNew) {
        Tetrahedron neighbor = getNeighbor(vOld);
        if (neighbor != null) {
            neighbor.setNeighbor(neighbor.ordinalOf(this), n);
            n.setNeighbor(vNew, neighbor);
        }
    }

    /**
     * Patch the new tetrahedron created by a flip of the receiver by seting the neighbor to the value in the receiver
     * <p>
     *
     * @param old
     * @param n
     * @param vNew
     */
    void patch(Vertex old, Tetrahedron n, V vNew) {
        patch(ordinalOf(old), n, vNew);
    }

    void removeAnyDegenerateTetrahedronPair() {
        if (nA != null) {
            if (nA == nB) {
                removeDegenerateTetrahedronPair(A, B, C, D);
                return;
            }
            if (nA == nC) {
                removeDegenerateTetrahedronPair(A, C, B, D);
                return;
            }
            if (nA == nD) {
                removeDegenerateTetrahedronPair(A, D, B, C);
                return;
            }
        }

        if (nB != null) {
            if (nB == nC) {
                removeDegenerateTetrahedronPair(B, C, A, D);
                return;
            }
            if (nB == nD) {
                removeDegenerateTetrahedronPair(B, D, A, C);
                return;
            }
        }

        if (nC != null) {
            if (nC == nD) {
                removeDegenerateTetrahedronPair(C, D, A, B);
                return;
            }
        }
    }

    void setNeighbor(V v, Tetrahedron n) {
        clear();
        if (v == A) {
            nA = n;
            return;
        }
        if (v == B) {
            nB = n;
            return;
        }
        if (v == C) {
            nC = n;
            return;
        }
        nD = n;
    }

    void setNeighborA(Tetrahedron t) {
        clear();
        nA = t;
    }

    void setNeighborB(Tetrahedron t) {
        clear();
        nB = t;
    }

    void setNeighborC(Tetrahedron t) {
        clear();
        nC = t;
    }

    void setNeighborD(Tetrahedron t) {
        clear();
        nD = t;
    }

    /**
     * Traverse the points which define the voronoi face defined by the dual of the line segement defined by the center
     * point and the axis. Terminate the traversal if we have returned to the originating tetrahedron.
     * <p>
     *
     * @param origin
     * @param from
     * @param vC
     * @param axis
     * @param face
     */
    void traverseVoronoiFace(Tetrahedron origin, Tetrahedron from, Vertex vC, Vertex axis, List<Point3d> face) {
        if (origin == this) {
            return;
        }
        double[] center = new double[3];
        centerSphere(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, center);
        face.add(new Point3d(center[0], center[1], center[2]));
        V next = VORONOI_FACE_NEXT[ordinalOf(from).ordinal()][ordinalOf(vC).ordinal()][ordinalOf(axis).ordinal()];
        Tetrahedron t = getNeighbor(next);
        if (t != null) {
            t.traverseVoronoiFace(origin, this, vC, axis, face);
        }

    }

    /**
     * Traverse the points which define the voronoi face defined by the dual of the line segement defined by the center
     * point and the axis.
     * <p>
     *
     * @param vC
     * @param axis
     * @param faces
     */
    void traverseVoronoiFace(Vertex vC, Vertex axis, List<Tuple3d[]> faces) {
        ArrayList<Point3d> face = new ArrayList<>();
        double[] center = new double[3];
        centerSphere(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, center);
        face.add(new Point3d(center[0], center[1], center[2]));
        V v = VORONOI_FACE_ORIGIN[ordinalOf(vC).ordinal()][ordinalOf(axis).ordinal()];
        Tetrahedron next = getNeighbor(v);
        if (next != null) {
            next.traverseVoronoiFace(this, this, vC, axis, face);
        }
        faces.add(face.toArray(new Point3d[face.size()]));
    }

    /**
     * visit the receiver and push unvisited tetrahedrons around the supplied vertex
     *
     * @param vC      - the center vertex
     * @param visitor - the star visitor
     * @param stack   - the stack of visitations
     */
    void visit(Vertex vC, StarVisitor visitor, Stack<Tetrahedron> stack, Set<Tetrahedron> visited) {
        switch (ordinalOf(vC)) {
            case A:
                visitor.visit(A, this, c, b, d);
                if (nC != null) {
                    stack.push(nC);
                }
                if (nB != null) {
                    stack.push(nC);
                }
                if (nD != null) {
                    stack.push(nD);
                }
                break;
            case B:
                visitor.visit(B, this, d, a, c);
                if (nD != null) {
                    stack.push(nD);
                }
                if (nA != null) {
                    stack.push(nA);
                }
                if (nC != null) {
                    stack.push(nC);
                }
                break;
            case C:
                visitor.visit(C, this, a, d, b);
                if (nA != null) {
                    stack.push(nA);
                }
                if (nD != null) {
                    stack.push(nD);
                }
                if (nB != null) {
                    stack.push(nB);
                }
                break;
            case D:
                visitor.visit(D, this, b, c, a);
                if (nB != null) {
                    stack.push(nB);
                }
                if (nA != null) {
                    stack.push(nB);
                }
                if (nC != null) {
                    stack.push(nC);
                }
                break;
            default:
                throw new IllegalArgumentException("Invalid center vertex: " + vC);
        }
    }

    /**
     * Visit the star tetrahedra set of the of the center vertex
     *
     * @param vC      - the center vertex
     * @param visitor - the visitor to invoke for each tetrahedron in the star
     */
    void visitStar(Vertex vC, StarVisitor visitor) {
        Set<Tetrahedron> tetrahedrons = new IdentitySet<>(10);
        var stack = new Stack<Tetrahedron>();
        stack.push(this);
        while (!stack.isEmpty()) {
            var t = stack.pop();
            if (tetrahedrons.add(t)) {
                t.visit(vC, visitor, stack, tetrahedrons);
            }
        }
    }

    private void removeDegenerateTetrahedronPair(V ve1, V ve2, V vf1, V vf2) {
        Tetrahedron nE = getNeighbor(ve1);
        Tetrahedron nF1_that = nE.getNeighbor(getVertex(vf1));
        Tetrahedron nF2_that = nE.getNeighbor(getVertex(vf2));

        patch(vf1, nF1_that, nF1_that.ordinalOf(nE));
        patch(vf2, nF2_that, nF2_that.ordinalOf(nE));

        Vertex e1 = getVertex(ve1);
        Vertex e2 = getVertex(ve2);
        Vertex f1 = getVertex(vf1);
        Vertex f2 = getVertex(vf2);

        delete();
        nE.delete();

        e1.freshenAdjacent(nF1_that);
        f2.freshenAdjacent(nF1_that);
        e2.freshenAdjacent(nF2_that);
        f1.freshenAdjacent(nF2_that);
    }

    /**
     * Represents the oriented face opposite vertex C
     *
     * @author hhildebrand
     */
    private class FaceADB extends OrientedFace {

        @Override
        public Tetrahedron getAdjacent() {
            return nC;
        }

        @Override
        public Vertex[] getEdge(Vertex v) {
            var ordinal = ordinalOf(v);
            if (ordinal == A) {
                return new Vertex[] { d, b };
            }
            if (ordinal == D) {
                return new Vertex[] { b, a };
            }
            if (ordinal == B) {
                return new Vertex[] { a, d };
            }
            throw new IllegalArgumentException("Invalid vertex ordinal");
        }

        @Override
        public Tetrahedron getIncident() {
            return Tetrahedron.this;
        }

        @Override
        public Vertex getIncidentVertex() {
            return c;
        }

        @Override
        public Vertex getVertex(int v) {
            if (v == 0) {
                return a;
            }
            if (v == 1) {
                return d;
            }
            if (v == 2) {
                return b;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + v);
        }

        @Override
        public boolean includes(Vertex v) {
            if ((a == v) || (d == v) || (b == v)) {
                return true;
            }
            return false;
        }

        @Override
        public int indexOf(Vertex v) {
            if (v == a) {
                return 0;
            }
            if (v == d) {
                return 1;
            }
            if (v == b) {
                return 2;
            }
            throw new IllegalArgumentException("Vertex is not on face: " + v);
        }

        @Override
        public boolean isConvex(int vertex) {
            Vertex adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(c, d, b) < 0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(a, c, b) < 0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(a, d, c) < 0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public boolean isReflex(int vertex) {
            Vertex adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(c, d, b) > 0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(a, c, b) > 0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(a, d, c) > 0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public double orientationOf(Vertex query) {
            return orientationWrtADB(query);
        }

        @Override
        public String toString() {
            return "Face ADB";
        }

    }

    /**
     * Represents the oriented face opposite of vertex D
     *
     * @author hhildebrand
     */
    private class FaceBCA extends OrientedFace {

        @Override
        public Tetrahedron getAdjacent() {
            return nD;
        }

        @Override
        public Vertex[] getEdge(Vertex v) {
            var ordinal = ordinalOf(v);
            if (ordinal == B) {
                return new Vertex[] { c, a };
            }
            if (ordinal == C) {
                return new Vertex[] { a, b };
            }
            if (ordinal == A) {
                return new Vertex[] { b, c };
            }
            throw new IllegalArgumentException("Invalid vertex ordinal");
        }

        @Override
        public Tetrahedron getIncident() {
            return Tetrahedron.this;
        }

        @Override
        public Vertex getIncidentVertex() {
            return d;
        }

        @Override
        public Vertex getVertex(int v) {
            if (v == 0) {
                return b;
            }
            if (v == 1) {
                return c;
            }
            if (v == 2) {
                return a;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + v);
        }

        @Override
        public boolean includes(Vertex v) {
            if ((b == v) || (c == v) || (a == v)) {
                return true;
            }
            return false;
        }

        @Override
        public int indexOf(Vertex v) {
            if (v == b) {
                return 0;
            }
            if (v == c) {
                return 1;
            }
            if (v == a) {
                return 2;
            }
            throw new IllegalArgumentException("Vertex is not on face: " + v);
        }

        @Override
        public boolean isConvex(int vertex) {
            Vertex adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(d, c, a) < 0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(b, d, a) < 0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(b, c, d) < 0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public boolean isReflex(int vertex) {
            Vertex adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(d, c, a) > 0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(b, d, a) > 0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(b, c, d) > 0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public double orientationOf(Vertex query) {
            return orientationWrtBCA(query);
        }

        @Override
        public String toString() {
            return "Face BCA";
        }

    }

    /**
     * Represents the oriented face opposite of vertex A
     *
     * @author hhildebrand
     */
    private class FaceCBD extends OrientedFace {

        @Override
        public Tetrahedron getAdjacent() {
            return nA;
        }

        @Override
        public Vertex[] getEdge(Vertex v) {
            var ordinal = ordinalOf(v);
            if (ordinal == C) {
                return new Vertex[] { b, d };
            }
            if (ordinal == B) {
                return new Vertex[] { d, c };
            }
            if (ordinal == D) {
                return new Vertex[] { c, b };
            }
            throw new IllegalArgumentException("Invalid vertex ordinal");
        }

        @Override
        public Tetrahedron getIncident() {
            return Tetrahedron.this;
        }

        @Override
        public Vertex getIncidentVertex() {
            return a;
        }

        @Override
        public Vertex getVertex(int v) {
            if (v == 0) {
                return c;
            }
            if (v == 1) {
                return b;
            }
            if (v == 2) {
                return d;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + v);
        }

        @Override
        public boolean includes(Vertex v) {
            if ((c == v) || (b == v) || (d == v)) {
                return true;
            }
            return false;
        }

        @Override
        public int indexOf(Vertex v) {
            if (v == c) {
                return 0;
            }
            if (v == b) {
                return 1;
            }
            if (v == d) {
                return 2;
            }
            throw new IllegalArgumentException("Vertex is not on face: " + v);
        }

        @Override
        public boolean isConvex(int vertex) {
            Vertex adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(a, b, d) < 0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(c, a, d) < 0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(c, b, a) < 0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public boolean isReflex(int vertex) {
            Vertex adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(a, b, d) > 0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(c, a, d) > 0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(c, b, a) > 0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public double orientationOf(Vertex query) {
            return orientationWrtCBD(query);
        }

        @Override
        public String toString() {
            return "Face CBD";
        }

    }

    /**
     * Represents the oriented face opposite of vertex B
     *
     * @author hhildebrand
     */
    private class FaceDAC extends OrientedFace {

        @Override
        public Tetrahedron getAdjacent() {
            return nB;
        }

        @Override
        public Vertex[] getEdge(Vertex v) {
            var ordinal = ordinalOf(v);
            if (ordinal == D) {
                return new Vertex[] { a, c };
            }
            if (ordinal == A) {
                return new Vertex[] { c, d };
            }
            if (ordinal == C) {
                return new Vertex[] { d, a };
            }
            throw new IllegalArgumentException("Invalid vertex ordinal");
        }

        @Override
        public Tetrahedron getIncident() {
            return Tetrahedron.this;
        }

        @Override
        public Vertex getIncidentVertex() {
            return b;
        }

        @Override
        public Vertex getVertex(int v) {
            if (v == 0) {
                return d;
            }
            if (v == 1) {
                return a;
            }
            if (v == 2) {
                return c;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + v);
        }

        @Override
        public boolean includes(Vertex v) {
            if ((d == v) || (a == v) || (c == v)) {
                return true;
            }
            return false;
        }

        @Override
        public int indexOf(Vertex v) {
            if (v == d) {
                return 0;
            }
            if (v == a) {
                return 1;
            }
            if (v == c) {
                return 2;
            }
            throw new IllegalArgumentException("Vertex is not on face: " + v);
        }

        @Override
        public boolean isConvex(int vertex) {
            Vertex adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(b, a, c) < 0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(d, b, c) < 0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(d, a, b) < 0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public boolean isReflex(int vertex) {
            Vertex adjacentVertex = getAdjacentVertex();
            if (adjacentVertex == null) {
                return false;
            }
            if (vertex == 0) {
                return adjacentVertex.orientation(b, a, c) > 0;
            }
            if (vertex == 1) {
                return adjacentVertex.orientation(d, b, c) > 0;
            }
            if (vertex == 2) {
                return adjacentVertex.orientation(d, a, b) > 0;
            }
            throw new IllegalArgumentException("Invalid vertex index: " + vertex);
        }

        @Override
        public double orientationOf(Vertex query) {
            return orientationWrtDAC(query);
        }

        @Override
        public String toString() {
            return "Face DAC";
        }

    }
}
