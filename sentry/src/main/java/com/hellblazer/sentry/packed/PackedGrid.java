package com.hellblazer.sentry.packed;

import com.hellblazer.luciferase.common.FloatArrayList;
import com.hellblazer.luciferase.common.IntArrayList;
import com.hellblazer.luciferase.geometry.Geometry;
import com.hellblazer.sentry.Vertex;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.*;

/**
 * @author hal.hildebrand
 **/
public class PackedGrid {

    /**
     * For max corners we can represent with float
     */
    public static final Vertex[] FOUR_CORNERS = new Vertex[4];

    /**
     * Canonical enumeration of the vertex ordinals
     */
    public static final    int[]     VERTICES            = { 0, 1, 2, 3 };
    /**
     * A pre-built table of all the permutations of remaining faces to check in location.
     */
    protected static final int[][][] ORDER               = new int[][][] {
    { { 1, 2, 3 }, { 2, 1, 3 }, { 2, 3, 1 }, { 1, 3, 2 }, { 3, 1, 2 }, { 3, 2, 1 } },

    { { 0, 2, 3 }, { 2, 0, 3 }, { 2, 3, 0 }, { 0, 3, 2 }, { 3, 0, 2 }, { 3, 2, 0 } },

    { { 1, 0, 3 }, { 0, 1, 3 }, { 0, 3, 1 }, { 1, 3, 0 }, { 3, 1, 0 }, { 3, 0, 1 } },

    { { 1, 2, 0 }, { 2, 1, 0 }, { 2, 0, 1 }, { 1, 0, 2 }, { 0, 1, 2 }, { 0, 2, 1 } } };
    /**
     * Scale of the universe
     */
    private static final   float     SCALE               = (float) Math.pow(2, 24);
    /**
     * Matrix used to determine the next neighbor in a voronoi face traversal
     */
    private static final   int[][][] VORONOI_FACE_NEXT   = {
    { null, { -1, -1, 3, 2 }, { -1, 3, -1, 1 }, { -1, 2, 1, -1 } },
    { { -1, -1, 3, 2 }, null, { 3, -1, -1, 0 }, { 2, -1, 0, -1 } },
    { { -1, 3, -1, 1 }, { 3, -1, -1, 0 }, null, { 1, 0, -1, -1 } },
    { { -1, 2, 1, -1 }, { 2, -1, 0, -1 }, { 1, 0, -1, -1 }, null } };
    /**
     * Matrix used to determine the origin neighbor in a voronoi face traversal
     */
    private static final   short[][] VORONOI_FACE_ORIGIN = { { -1, 2, 3, 1 }, { 2, -1, 3, 0 }, { 3, 0, -1, 1 },
                                                             { 1, 2, 0, -1 } };

    static {
        FOUR_CORNERS[0] = new Vertex(-1, 1, -1, SCALE);
        FOUR_CORNERS[1] = new Vertex(1, 1, 1, SCALE);
        FOUR_CORNERS[2] = new Vertex(1, -1, -1, SCALE);
        FOUR_CORNERS[3] = new Vertex(-1, -1, 1, SCALE);
    }

    private final Deque<PackedTetrahedron> stack       = new ArrayDeque<>();
    private final Deque<Integer>           freed       = new ArrayDeque<>();
    private final IntArrayList             adjacent    = new IntArrayList();
    private final IntArrayList             tetrahedra  = new IntArrayList();
    private final FloatArrayList           vertices    = new FloatArrayList();
    private final Tuple3f[]                fourCorners = new Tuple3f[4];
    // for location
    private       PackedTetrahedron        last;

    public PackedGrid() {
        this(FOUR_CORNERS[0], FOUR_CORNERS[1], FOUR_CORNERS[2], FOUR_CORNERS[3]);
    }

    public PackedGrid(Tuple3f a, Tuple3f b, Tuple3f c, Tuple3f d) {
        fourCorners[0] = a;
        fourCorners[1] = b;
        fourCorners[2] = c;
        fourCorners[3] = d;

        vertices.add(a.x);
        vertices.add(a.y);
        vertices.add(a.z);

        vertices.add(b.x);
        vertices.add(b.y);
        vertices.add(b.z);

        vertices.add(c.x);
        vertices.add(c.y);
        vertices.add(c.z);

        vertices.add(d.x);
        vertices.add(d.y);
        vertices.add(d.z);

        for (int i = 0; i < 4; i++) {
            tetrahedra.add(i);
        }
        for (int i = 0; i < 4; i++) {
            adjacent.add(-1);
        }
        last = new PackedTetrahedron(0);
    }

    public boolean contains(Tuple3f point) {
        return Geometry.insideTetrahedron(point, fourCorners[0], fourCorners[1], fourCorners[2], fourCorners[3]);
    }

    public PackedTetrahedron getTetrahedron(int index) {
        if (index < 0) {
            return null;
        }
        var freed = stack.pollFirst();
        if (freed == null) {
            return new PackedTetrahedron(index);
        }
        freed.index = index;
        return freed;
    }

    public PackedTetrahedron locate(Tuple3f p, Random entropy) {
        return locate(p, last, entropy);
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
     * @return the Tetrahedron containing the query, or null if the query is outside the tetrahedralization.
     */
    public PackedTetrahedron locate(Tuple3f query, PackedTetrahedron start, Random random) {
        assert query != null;
        return start.locate(query, random);
    }

    public PackedTetrahedron newTetrahedron(int a, int b, int c, int d) {
        var inst = stack.pollFirst();
        var tetrahedron = inst == null ? new PackedTetrahedron(tetrahedra.size()) : inst;
        var idx = freed.pollLast();
        if (idx == null) {
            tetrahedron.index = tetrahedra.size();
            tetrahedra.add(a);
            tetrahedra.add(b);
            tetrahedra.add(c);
            tetrahedra.add(d);
            return tetrahedron;
        }
        tetrahedron.index = idx;
        tetrahedra.setInt(tetrahedron.index * 4, a);
        tetrahedra.setInt(tetrahedron.index * 4 + 1, b);
        tetrahedra.setInt(tetrahedron.index * 4 + 2, c);
        tetrahedra.setInt(tetrahedron.index * 4 + 3, d);
        return tetrahedron;
    }

    public Collection<PackedTetrahedron> tetrahedrons() {
        var size = tetrahedra.size() / 4;
        var result = new ArrayList<PackedTetrahedron>(size);
        for (var i = 0; i < size; i++) {
            if (tetrahedra.getInt(i * 4) != -1) {
                result.add(getTetrahedron(i));
            }
        }
        return result;
    }

    /**
     * Track the point into the tetrahedralization. See "Computing the 3D Voronoi Diagram Robustly: An Easy
     * Explanation", by Hugo Ledoux
     * <p>
     *
     * @param p - the point to be inserted
     * @return the vertex in the tetrahedralization
     */
    public int track(Point3f p, Random entropy) {
        assert p != null;
        if (!contains(p)) {
            return -1;
        }
        final var v = vertices.size() / 3;
        vertices.add(p.x);
        vertices.add(p.x);
        vertices.add(p.z);
        PackedTetrahedron locate = locate(p, entropy);
        add(v, locate);
        return v;
    }

    private void add(int v, final PackedTetrahedron target) {
        insert(v, target);
    }

    private void insert(int v, final PackedTetrahedron target) {
        List<OrientedFace> ears = new ArrayList<>(20);
        last = target.flip1to4(v, ears);
        while (!ears.isEmpty()) {
            var l = ears.removeLast().flip(v, ears);
            if (l != null) {
                last = l;
            }
        }
    }

    private record Freed(int index, PackedTetrahedron tetrahedron) {
    }

    /**
     * @author hal.hildebrand
     **/
    public class PackedTetrahedron {
        private final OrientedFace faceCBD;
        private final OrientedFace faceDAC;
        private final OrientedFace faceADB;
        private       int          index;

        {
            faceADB = new FaceADB();
            faceDAC = new FaceDAC();
            faceCBD = new FaceCBD();
        }

        public PackedTetrahedron(int index) {
            this.index = index;
        }

        public void delete() {
            adjacent.setInt(index * 4, -1);
            adjacent.setInt(index * 4 + 1, -1);
            adjacent.setInt(index * 4 + 2, -1);
            adjacent.setInt(index * 4 + 3, -1);

            tetrahedra.setInt(index * 4, -1);
            tetrahedra.setInt(index * 4 + 1, -1);
            tetrahedra.setInt(index * 4 + 2, -1);
            tetrahedra.setInt(index * 4 + 3, -1);
            freed.addLast(index);
            index = -1;
            stack.addLast(this);
        }

        /**
         * Perform the 1 -> 4 bistellar flip. This produces 4 new tetrahedron from the original tetrahdron, by inserting
         * the new point in the interior of the receiver tetrahedron. The star set of the newly inserted point is pushed
         * onto the supplied stack.
         * <p>
         *
         * @param n    - the inserted point
         * @param ears - the stack of oriented faces that make up the ears of the inserted point
         * @return one of the four new tetrahedra
         */
        public PackedTetrahedron flip1to4(int n, List<OrientedFace> ears) {
            var t0 = newTetrahedron(a(), b(), c(), n);
            var t1 = newTetrahedron(a(), d(), b(), n);
            var t2 = newTetrahedron(a(), c(), d(), n);
            var t3 = newTetrahedron(b(), d(), c(), n);

            adjacent.setInt(t0.index * 4, t3.index);
            adjacent.setInt(t0.index * 4 + 1, t2.index);
            adjacent.setInt(t0.index * 4 + 2, t1.index);

            adjacent.setInt(t1.index * 4, t3.index);
            adjacent.setInt(t1.index * 4 + 1, t0.index);
            adjacent.setInt(t1.index * 4 + 2, t2.index);

            adjacent.setInt(t2.index * 4, t3.index);
            adjacent.setInt(t2.index * 4 + 1, t1.index);
            adjacent.setInt(t2.index * 4 + 2, t0.index);

            adjacent.setInt(t3.index * 4, t2.index);
            adjacent.setInt(t3.index * 4 + 1, t0.index);
            adjacent.setInt(t3.index * 4 + 2, t1.index);

            patch(3, t0, 3);
            patch(2, t1, 3);
            patch(2, t2, 3);
            patch(0, t3, 3);

            delete();

            var newFace = t0.getFace(3);
            if (newFace.hasAdjacent()) {
                ears.add(newFace);
            }
            newFace = t1.getFace(3);
            if (newFace.hasAdjacent()) {
                ears.add(newFace);
            }
            newFace = t2.getFace(3);
            if (newFace.hasAdjacent()) {
                ears.add(newFace);
            }
            newFace = t3.getFace(3);
            if (newFace.hasAdjacent()) {
                ears.add(newFace);
            }
            return t1;
        }

        public int getD() {
            return tetrahedra.getInt(index * 4 + 3);
        }

        public OrientedFace getFace(int vertex) {
            final var tets = tetrahedra;
            final var i = index;
            return switch (vertex) {
                //faceCBD
                case 0 -> {
                    yield faceCBD;
                }
                // faceDAC
                case 1 -> {
                    yield faceDAC;
                }
                // face ADB
                case 2 -> {
                    yield faceADB;
                }
                // faceBCA
                case 3 -> null;
                default -> throw new IllegalArgumentException("Invalid vertex: " + vertex);
            };
        }

        /**
         * Answer the neighbor that is adjacent to the face opposite of the vertex
         * <p>
         *
         * @param v - the opposing vertex defining the face
         * @return the neighboring tetrahedron, or null if none.
         */
        public PackedTetrahedron getNeighbor(int v) {
            return getTetrahedron(adjacent.getInt(index * 4 + v));
        }

        public int getVertex(int current) {
            return tetrahedra.getInt(index * 4 + current);
        }

        /**
         * Answer true if the query point is contained in the circumsphere of the tetrahedron
         */
        public boolean inSphere(int query) {
            var x = vertices.get(query * 3);
            var y = vertices.get(query * 3 + 1);
            var z = vertices.get(query * 3 + 1);

            var a = tetrahedra.getInt(index * 4);
            var ax = vertices.get(a * 3);
            var ay = vertices.get(a * 3 + 1);
            var az = vertices.get(a * 3 + 2);

            var b = tetrahedra.getInt(index * 4 + 1);
            var bx = vertices.get(b * 3);
            var by = vertices.get(b * 3 + 1);
            var bz = vertices.get(b * 3 + 2);

            var c = tetrahedra.getInt(index * 4 + 2);
            var cx = vertices.get(c * 3);
            var cy = vertices.get(c * 3 + 1);
            var cz = vertices.get(c * 3 + 2);

            var d = tetrahedra.getInt(index * 4 + 2);
            var dx = vertices.get(d * 3);
            var dy = vertices.get(d * 3 + 1);
            var dz = vertices.get(d * 3 + 2);

            var result = Geometry.inSphereFast(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, x, y, z);
            return Math.signum(result) > 0.0d;
        }

        public boolean includes(int query) {
            return tetrahedra.getInt(index * 4) == query || tetrahedra.getInt(index * 4 + 1) == query
            || tetrahedra.getInt(index * 4 + 2) == query || tetrahedra.getInt(index * 4 + 3) == query;
        }

        public boolean isDeleted() {
            return index == -1;
        }

        public PackedTetrahedron locate(Tuple3f query, Random entropy) {
            var o = -1;
            for (var face : PackedGrid.VERTICES) {
                if (orientationWrt(face, query) < 0.0d) {
                    o = face;
                    break;
                }
            }
            if (o == -1) {
                // The query point is contained in the receiver
                return this;
            }

            var current = this;
            while (true) {
                // get the tetrahedron on the other side of the face
                var tetrahedron = current.getNeighbor(o);
                if (tetrahedron == null) {
                    return null; // not contained in this tetrahedron
                }
                int i = 0;
                for (var v : PackedGrid.ORDER[tetrahedron.ordinalOfNeighbor(current.index)][entropy.nextInt(6)]) {
                    o = v;
                    current = tetrahedron;
                    if (tetrahedron.orientationWrt(v, query) < 0.0d) {
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
         * @return the indicator of this vertex or null if not a vertex of this tetrahedron or the supplied vertex is
         * null
         */
        public int ordinalOf(int v) {
            if (v == -1) {
                return -1;
            }
            if (v == tetrahedra.getInt(index * 4)) {
                return 0;
            }
            if (v == tetrahedra.getInt(index * 4 + 1)) {
                return 1;
            }
            if (v == tetrahedra.getInt(index * 4 + 2)) {
                return 2;
            }
            return 3;
        }

        /**
         * Answer +1 if the orientation of the query is positive with respect to the plane defined by {a, b, c}, -1 if
         * negative, or 0 if the test point is coplanar
         * <p>
         *
         * @param query - the point to query
         * @param a     , b, c - the points defining the plane
         * @return +1 if the orientation of the query point is positive with respect to the plane, -1 if negative and 0
         * if the test point is coplanar
         */
        public double orientation(Tuple3f query, int a, int b, int c) {
            var ax = vertices.getFloat(a * 3);
            var ay = vertices.getFloat(a * 3) + 1;
            var az = vertices.getFloat(a * 3 + 2);

            var bx = vertices.getFloat(b * 3);
            var by = vertices.getFloat(b * 3) + 1;
            var bz = vertices.getFloat(b * 3 + 2);

            var cx = vertices.getFloat(c * 3);
            var cy = vertices.getFloat(c * 3) + 1;
            var cz = vertices.getFloat(c * 3 + 2);

            var result = Geometry.leftOfPlaneFast(ax, ay, az, bx, by, bz, cx, cy, cz, query.x, query.y, query.z);
            return Math.signum(result);
        }

        /**
         * Answer > 0 if the query point is positively oriented with respect to the face opposite the vertex, < 0 if
         * negatively oriented, 0 if the query point is coplanar to the face
         */
        public double orientationWrt(int face, Tuple3f query) {
            if (face == 0) {
                return orientationWrtCBD(query);
            }
            if (face == 1) {
                return orientationWrtDAC(query);
            }
            if (face == 2) {
                return orientationWrtADB(query);
            }
            if (face == 3) {
                return orientationWrtBCA(query);
            }
            throw new IllegalArgumentException("Invalid face: " + face);
        }

        /**
         * Answer > 0 if the query point is positively oriented with respect to the face ADB, < 0 if negatively
         * oriented, 0 if the query point is coplanar to the face
         */
        public double orientationWrtADB(Tuple3f query) {
            return orientation(query, tetrahedra.getInt(index * 4), tetrahedra.getInt(index * 4 + 3),
                               tetrahedra.getInt(index * 4 + 1));
        }

        /**
         * Answer 1 if the query point is positively oriented with respect to the face BCA, -1 if negatively oriented, 0
         * if the query point is coplanar to the face
         */
        public double orientationWrtBCA(Tuple3f query) {
            return orientation(query, tetrahedra.getInt(index * 4 + 1), tetrahedra.getInt(index * 4 + 2),
                               tetrahedra.getInt(index * 4));
        }

        /**
         * Answer 1 if the query point is positively oriented with respect to the face CBD, -1 if negatively oriented, 0
         * if the query point is coplanar to the face
         */
        public double orientationWrtCBD(Tuple3f query) {
            return orientation(query, tetrahedra.getInt(index * 4 + 2), tetrahedra.getInt(index * 4 + 1),
                               tetrahedra.getInt(index * 4 + 3));
        }

        /**
         * Answer 1 if the query point is positively oriented with respect to the face DAC, -1 if negatively oriented, 0
         * if the query point is coplanar to the face
         */
        public double orientationWrtDAC(Tuple3f query) {
            return orientation(query, tetrahedra.getInt(index * 4 + 3), tetrahedra.getInt(index * 4),
                               tetrahedra.getInt(index * 4 + 2));
        }

        public void setNeighborA(PackedTetrahedron t) {
            adjacent.setInt(index * 4, t.index);
        }

        public void setNeighborB(PackedTetrahedron t) {
            adjacent.setInt(index * 4 + 1, t.index);
        }

        public void setNeighborC(PackedTetrahedron t) {
            adjacent.setInt(index * 4 + 2, t.index);
        }

        public void setNeighborD(PackedTetrahedron t) {
            adjacent.setInt(index * 4 + 3, t.index);
        }

        /**
         * Answer the canonical ordinal of the opposite vertex of the neighboring tetrahedron
         */
        int ordinalOf(PackedTetrahedron neighbor) {
            if (neighbor == null) {
                return -1;
            }
            if (adjacent.getInt(index * 4) == neighbor.index) {
                return 0;
            }
            if (adjacent.getInt(index * 4 + 1) == neighbor.index) {
                return 1;
            }
            if (adjacent.getInt(index * 4 + 2) == neighbor.index) {
                return 2;
            }
            if (adjacent.getInt(index * 4 + 3) == neighbor.index) {
                return 3;
            }
            throw new IllegalArgumentException("Not a neighbor: " + neighbor);
        }

        /**
         * Answer the canonical ordinal of the opposite vertex of the neighboring tetrahedron
         */
        int ordinalOfNeighbor(int neighbor) {
            if (adjacent.get(index * 4) == neighbor) {
                return 0;
            }
            if (adjacent.get(index * 4 + 1) == neighbor) {
                return 1;
            }
            if (adjacent.get(index * 4 + 2) == neighbor) {
                return 2;
            }
            if (adjacent.get(index * 4 + 3) == neighbor) {
                return 3;
            }
            throw new IllegalArgumentException("Not a neighbor: " + neighbor);
        }

        /**
         * Patch the new tetrahedron created by a flip of the receiver by setting the neighbor to the value in the
         * receiver
         * <p>
         */
        void patch(int old, PackedTetrahedron n, int vNew) {
            patch(ordinalOf(old), n, vNew);
        }

        void removeAnyDegenerateTetrahedronPair() {
            if (adjacent.getInt(index * 4) != -1) {
                if (adjacent.getInt(index * 4) == adjacent.getInt(index * 4 + 1)) {
                    removeDegenerateTetrahedronPair(0, 2, 3);
                    return;
                }
                if (adjacent.getInt(index * 4) == adjacent.getInt(index * 4 + 2)) {
                    removeDegenerateTetrahedronPair(0, 1, 3);
                    return;
                }
                if (adjacent.getInt(index * 4) == adjacent.getInt(index * 4 + 3)) {
                    removeDegenerateTetrahedronPair(0, 1, 2);
                    return;
                }
            }

            if (adjacent.getInt(index * 4 + 1) != -1) {
                if (adjacent.getInt(index * 4 + 1) == adjacent.getInt(index * 4 + 2)) {
                    removeDegenerateTetrahedronPair(1, 0, 3);
                    return;
                }
                if (adjacent.getInt(index * 4 + 1) == adjacent.getInt(index * 4 + 3)) {
                    removeDegenerateTetrahedronPair(1, 0, 2);
                    return;
                }
            }

            if (adjacent.getInt(index * 4 + 2) != -1) {
                if (adjacent.getInt(index * 4 + 2) == adjacent.getInt(index * 4 + 3)) {
                    removeDegenerateTetrahedronPair(2, 0, 1);
                }
            }
        }

        private int a() {
            return tetrahedra.getInt(index * 4);
        }

        private int b() {
            return tetrahedra.getInt(index * 4 + 1);
        }

        private int c() {
            return tetrahedra.getInt(index * 4 + 2);
        }

        private int d() {
            return tetrahedra.getInt(index * 4 + 3);
        }

        private void removeDegenerateTetrahedronPair(int ve1, int vf1, int vf2) {
            var nE = getNeighbor(ve1);
            var nF1_that = nE.getNeighbor(getVertex(vf1));
            var nF2_that = nE.getNeighbor(getVertex(vf2));

            patch(vf1, nF1_that, nF1_that.ordinalOf(nE));
            patch(vf2, nF2_that, nF2_that.ordinalOf(nE));

            delete();
            nE.delete();
        }

        private class FaceDAC extends OrientedFace {

            @Override
            public PackedTetrahedron getAdjacent() {
                var tet = adjacent.getInt(index * 4 + 1);
                return tet == -1 ? null : getTetrahedron(tet);
            }

            @Override
            public PackedTetrahedron getIncident() {
                return PackedTetrahedron.this;
            }

            @Override
            public int getIncidentVertex() {
                return b();
            }

            @Override
            public int getVertex(int v) {
                if (v == 0) {
                    return d();
                }
                if (v == 1) {
                    return a();
                }
                if (v == 2) {
                    return c();
                }
                throw new IllegalArgumentException("Invalid vertex index: " + v);
            }

            @Override
            public boolean includes(int v) {
                return (d() == v) || (a() == v) || (c() == v);
            }

            @Override
            public int indexOf(int v) {
                if (v == d()) {
                    return 0;
                }
                if (v == a()) {
                    return 1;
                }
                if (v == c()) {
                    return 2;
                }
                throw new IllegalArgumentException("Vertex is not on face: " + v);
            }

            @Override
            public boolean isConvex(int vertex) {
                var adjacentVertex = getAdjacentVertex();
                if (adjacentVertex == -1) {
                    return false;
                }
                if (vertex == 0) {
                    return orientation(adjacentVertex, b(), a(), c()) < 0.0;
                }
                if (vertex == 1) {
                    return orientation(adjacentVertex, d(), b(), c()) < 0.0;
                }
                if (vertex == 2) {
                    return orientation(adjacentVertex, d(), a(), b()) < 0.0;
                }
                throw new IllegalArgumentException("Invalid vertex index: " + vertex);
            }

            @Override
            public boolean isReflex(int vertex) {
                var adjacent = getAdjacent();
                var current = adjacent == null ? null : adjacent.ordinalOf(getIncident());
                if (current == null) {
                    return false;
                }

                var adjacentVertex = adjacent.getVertex(current);
                if (adjacentVertex == -1) {
                    return false;
                }
                if (vertex == 0) {
                    return orientation(adjacentVertex, b(), a(), c()) > 0.0;
                }
                if (vertex == 1) {
                    return orientation(adjacentVertex, d(), b(), c()) > 0.0;
                }
                if (vertex == 2) {
                    return orientation(adjacentVertex, d(), a(), b()) > 0.0;
                }
                throw new IllegalArgumentException("Invalid vertex index: " + vertex);
            }

            @Override
            public double orientationOf(int query) {
                return orientationWrtDAC(new Point3f(vertices.getFloat(query * 3), vertices.getFloat(query * 3 + 1),
                                                     vertices.getFloat(query * 3 + 2)));
            }

            @Override
            public String toString() {
                return "Face DAC";
            }

            @Override
            double inSphere(int query, int a, int b, int c, int d) {
                var x = vertices.get(query * 3);
                var y = vertices.get(query * 3 + 1);
                var z = vertices.get(query * 3 + 1);

                var ax = vertices.get(a * 3);
                var ay = vertices.get(a * 3 + 1);
                var az = vertices.get(a * 3 + 2);

                var bx = vertices.get(b * 3);
                var by = vertices.get(b * 3 + 1);
                var bz = vertices.get(b * 3 + 2);

                var cx = vertices.get(c * 3);
                var cy = vertices.get(c * 3 + 1);
                var cz = vertices.get(c * 3 + 2);

                var dx = vertices.get(d * 3);
                var dy = vertices.get(d * 3 + 1);
                var dz = vertices.get(d * 3 + 2);

                return Geometry.inSphereFast(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, x, y, z);
            }

            @Override
            PackedTetrahedron newTetrahedron(int vertex1, int incidentVertex, int vertex2, int opposingVertex) {
                return null;
            }

            @Override
            double orientation(int d, int a, int b, int c) {
                var orientation = PackedTetrahedron.this.orientation(
                new Point3f(vertices.getFloat(d * 4), vertices.getFloat(d * 4 + 1), vertices.getFloat(d * 4 + 2)), a, b,
                c);
                return Math.signum(orientation);
            }

            private int a() {
                return tetrahedra.getInt(index * 4);
            }

            private int b() {
                return tetrahedra.getInt(index * 4 + 1);
            }

            private int c() {
                return tetrahedra.getInt(index * 4 + 2);
            }

            private int d() {
                return tetrahedra.getInt(index * 4 + 3);
            }
        }

        private class FaceADB extends OrientedFace {

            @Override
            public PackedTetrahedron getAdjacent() {
                var tet = adjacent.getInt(index * 4 + 1);
                return tet == -1 ? null : getTetrahedron(tet);
            }

            @Override
            public PackedTetrahedron getIncident() {
                return PackedTetrahedron.this;
            }

            @Override
            public int getIncidentVertex() {
                return b();
            }

            @Override
            public int getVertex(int v) {
                if (v == 0) {
                    return a();
                }
                if (v == 1) {
                    return d();
                }
                if (v == 2) {
                    return b();
                }
                throw new IllegalArgumentException("Invalid vertex index: " + v);
            }

            @Override
            public boolean includes(int v) {
                return (a() == v) || (d() == v) || (b() == v);
            }

            @Override
            public int indexOf(int v) {
                if (v == a()) {
                    return 0;
                }
                if (v == d()) {
                    return 1;
                }
                if (v == b()) {
                    return 2;
                }
                throw new IllegalArgumentException("Vertex is not on face: " + v);
            }

            @Override
            public boolean isConvex(int vertex) {
                var adjacentVertex = getAdjacentVertex();
                if (adjacentVertex == -1) {
                    return false;
                }
                if (vertex == 0) {
                    return orientation(adjacentVertex, c(), d(), b()) < 0.0;
                }
                if (vertex == 1) {
                    return orientation(adjacentVertex, a(), c(), b()) < 0.0;
                }
                if (vertex == 2) {
                    return orientation(adjacentVertex, a(), d(), c()) < 0.0;
                }
                throw new IllegalArgumentException("Invalid vertex index: " + vertex);
            }

            @Override
            public boolean isReflex(int vertex) {
                var adjacent = getAdjacent();
                var current = adjacent == null ? null : adjacent.ordinalOf(getIncident());
                if (current == null) {
                    return false;
                }

                var adjacentVertex = adjacent.getVertex(current);
                if (adjacentVertex == -1) {
                    return false;
                }
                if (vertex == 0) {
                    return orientation(adjacentVertex, c(), d(), b()) > 0.0;
                }
                if (vertex == 1) {
                    return orientation(adjacentVertex, a(), c(), b()) > 0.0;
                }
                if (vertex == 2) {
                    return orientation(adjacentVertex, a(), d(), c()) > 0.0;
                }
                throw new IllegalArgumentException("Invalid vertex index: " + vertex);
            }

            @Override
            public double orientationOf(int query) {
                return orientationWrtADB(new Point3f(vertices.getFloat(query * 3), vertices.getFloat(query * 3 + 1),
                                                     vertices.getFloat(query * 3 + 2)));
            }

            @Override
            public String toString() {
                return "Face DAC";
            }

            @Override
            double inSphere(int query, int a, int b, int c, int d) {
                var x = vertices.get(query * 3);
                var y = vertices.get(query * 3 + 1);
                var z = vertices.get(query * 3 + 1);

                var ax = vertices.get(a * 3);
                var ay = vertices.get(a * 3 + 1);
                var az = vertices.get(a * 3 + 2);

                var bx = vertices.get(b * 3);
                var by = vertices.get(b * 3 + 1);
                var bz = vertices.get(b * 3 + 2);

                var cx = vertices.get(c * 3);
                var cy = vertices.get(c * 3 + 1);
                var cz = vertices.get(c * 3 + 2);

                var dx = vertices.get(d * 3);
                var dy = vertices.get(d * 3 + 1);
                var dz = vertices.get(d * 3 + 2);

                return Geometry.inSphereFast(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, x, y, z);
            }

            @Override
            PackedTetrahedron newTetrahedron(int vertex1, int incidentVertex, int vertex2, int opposingVertex) {
                return null;
            }

            @Override
            double orientation(int d, int a, int b, int c) {
                var orientation = PackedTetrahedron.this.orientation(
                new Point3f(vertices.getFloat(d * 4), vertices.getFloat(d * 4 + 1), vertices.getFloat(d * 4 + 2)), a, b,
                c);
                return Math.signum(orientation);
            }

            private int a() {
                return tetrahedra.getInt(index * 4);
            }

            private int b() {
                return tetrahedra.getInt(index * 4 + 1);
            }

            private int c() {
                return tetrahedra.getInt(index * 4 + 2);
            }

            private int d() {
                return tetrahedra.getInt(index * 4 + 3);
            }
        }

        private class FaceBCA extends OrientedFace {

            @Override
            public PackedTetrahedron getAdjacent() {
                var tet = adjacent.getInt(index * 4 + 2);
                return tet == -1 ? null : getTetrahedron(tet);
            }

            @Override
            public PackedTetrahedron getIncident() {
                return PackedTetrahedron.this;
            }

            @Override
            public int getIncidentVertex() {
                return b();
            }

            @Override
            public int getVertex(int v) {
                if (v == 0) {
                    return b();
                }
                if (v == 1) {
                    return c();
                }
                if (v == 2) {
                    return a();
                }
                throw new IllegalArgumentException("Invalid vertex index: " + v);
            }

            @Override
            public boolean includes(int v) {
                return (b() == v) || (c() == v) || (a() == v);
            }

            @Override
            public int indexOf(int v) {
                if (v == b()) {
                    return 0;
                }
                if (v == c()) {
                    return 1;
                }
                if (v == a()) {
                    return 2;
                }
                throw new IllegalArgumentException("Vertex is not on face: " + v);
            }

            @Override
            public boolean isConvex(int vertex) {
                var adjacentVertex = getAdjacentVertex();
                if (adjacentVertex == -1) {
                    return false;
                }
                if (vertex == 0) {
                    return orientation(adjacentVertex, d(), c(), a()) < 0.0;
                }
                if (vertex == 1) {
                    return orientation(adjacentVertex, b(), d(), a()) < 0.0;
                }
                if (vertex == 2) {
                    return orientation(adjacentVertex, b(), c(), d()) < 0.0;
                }
                throw new IllegalArgumentException("Invalid vertex index: " + vertex);
            }

            @Override
            public boolean isReflex(int vertex) {
                var adjacent = getAdjacent();
                var current = adjacent == null ? null : adjacent.ordinalOf(getIncident());
                if (current == null) {
                    return false;
                }

                var adjacentVertex = adjacent.getVertex(current);
                if (adjacentVertex == -1) {
                    return false;
                }
                if (vertex == 0) {
                    return orientation(adjacentVertex, d(), c(), a()) > 0.0;
                }
                if (vertex == 1) {
                    return orientation(adjacentVertex, b(), d(), a()) > 0.0;
                }
                if (vertex == 2) {
                    return orientation(adjacentVertex, b(), c(), d()) > 0.0;
                }
                throw new IllegalArgumentException("Invalid vertex index: " + vertex);
            }

            @Override
            public double orientationOf(int query) {
                return orientationWrtBCA(new Point3f(vertices.getFloat(query * 3), vertices.getFloat(query * 3 + 1),
                                                     vertices.getFloat(query * 3 + 2)));
            }

            @Override
            public String toString() {
                return "Face BCA";
            }

            @Override
            double inSphere(int query, int a, int b, int c, int d) {
                var x = vertices.get(query * 3);
                var y = vertices.get(query * 3 + 1);
                var z = vertices.get(query * 3 + 1);

                var ax = vertices.get(a * 3);
                var ay = vertices.get(a * 3 + 1);
                var az = vertices.get(a * 3 + 2);

                var bx = vertices.get(b * 3);
                var by = vertices.get(b * 3 + 1);
                var bz = vertices.get(b * 3 + 2);

                var cx = vertices.get(c * 3);
                var cy = vertices.get(c * 3 + 1);
                var cz = vertices.get(c * 3 + 2);

                var dx = vertices.get(d * 3);
                var dy = vertices.get(d * 3 + 1);
                var dz = vertices.get(d * 3 + 2);

                return Geometry.inSphereFast(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, x, y, z);
            }

            @Override
            PackedTetrahedron newTetrahedron(int vertex1, int incidentVertex, int vertex2, int opposingVertex) {
                return null;
            }

            @Override
            double orientation(int d, int a, int b, int c) {
                var orientation = PackedTetrahedron.this.orientation(
                new Point3f(vertices.getFloat(d * 4), vertices.getFloat(d * 4 + 1), vertices.getFloat(d * 4 + 2)), a, b,
                c);
                return Math.signum(orientation);
            }

            private int a() {
                return tetrahedra.getInt(index * 4);
            }

            private int b() {
                return tetrahedra.getInt(index * 4 + 1);
            }

            private int c() {
                return tetrahedra.getInt(index * 4 + 2);
            }

            private int d() {
                return tetrahedra.getInt(index * 4 + 3);
            }
        }

        private class FaceCBD extends OrientedFace {

            @Override
            public PackedTetrahedron getAdjacent() {
                var tet = adjacent.getInt(index * 4);
                return tet == -1 ? null : getTetrahedron(tet);
            }

            @Override
            public PackedTetrahedron getIncident() {
                return PackedTetrahedron.this;
            }

            @Override
            public int getIncidentVertex() {
                return a();
            }

            @Override
            public int getVertex(int v) {
                if (v == 0) {
                    return c();
                }
                if (v == 1) {
                    return b();
                }
                if (v == 2) {
                    return d();
                }
                throw new IllegalArgumentException("Invalid vertex index: " + v);
            }

            @Override
            public boolean includes(int v) {
                return (c() == v) || (b() == v) || (d() == v);
            }

            @Override
            public int indexOf(int v) {
                if (v == c()) {
                    return 0;
                }
                if (v == b()) {
                    return 1;
                }
                if (v == d()) {
                    return 2;
                }
                throw new IllegalArgumentException("Vertex is not on face: " + v);
            }

            @Override
            public boolean isConvex(int vertex) {
                var adjacentVertex = getAdjacentVertex();
                if (adjacentVertex == -1) {
                    return false;
                }
                if (vertex == 0) {
                    return orientation(adjacentVertex, a(), b(), d()) < 0.0;
                }
                if (vertex == 1) {
                    return orientation(adjacentVertex, c(), a(), d()) < 0.0;
                }
                if (vertex == 2) {
                    return orientation(adjacentVertex, c(), b(), a()) < 0.0;
                }
                throw new IllegalArgumentException("Invalid vertex index: " + vertex);
            }

            @Override
            public boolean isReflex(int vertex) {
                var adjacent = getAdjacent();
                var current = adjacent == null ? null : adjacent.ordinalOf(getIncident());
                if (current == null) {
                    return false;
                }

                var adjacentVertex = adjacent.getVertex(current);
                if (adjacentVertex == -1) {
                    return false;
                }
                if (vertex == 0) {
                    return orientation(adjacentVertex, a(), b(), d()) > 0.0;
                }
                if (vertex == 1) {
                    return orientation(adjacentVertex, c(), a(), d()) > 0.0;
                }
                if (vertex == 2) {
                    return orientation(adjacentVertex, c(), b(), a()) > 0.0;
                }
                throw new IllegalArgumentException("Invalid vertex index: " + vertex);
            }

            @Override
            public double orientationOf(int query) {
                return orientationWrtCBD(new Point3f(vertices.getFloat(query * 3), vertices.getFloat(query * 3 + 1),
                                                     vertices.getFloat(query * 3 + 2)));
            }

            @Override
            public String toString() {
                return "Face CBD";
            }

            @Override
            double inSphere(int query, int a, int b, int c, int d) {
                var x = vertices.get(query * 3);
                var y = vertices.get(query * 3 + 1);
                var z = vertices.get(query * 3 + 1);

                var ax = vertices.get(a * 3);
                var ay = vertices.get(a * 3 + 1);
                var az = vertices.get(a * 3 + 2);

                var bx = vertices.get(b * 3);
                var by = vertices.get(b * 3 + 1);
                var bz = vertices.get(b * 3 + 2);

                var cx = vertices.get(c * 3);
                var cy = vertices.get(c * 3 + 1);
                var cz = vertices.get(c * 3 + 2);

                var dx = vertices.get(d * 3);
                var dy = vertices.get(d * 3 + 1);
                var dz = vertices.get(d * 3 + 2);

                return Geometry.inSphereFast(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, x, y, z);
            }

            @Override
            PackedTetrahedron newTetrahedron(int vertex1, int incidentVertex, int vertex2, int opposingVertex) {
                return null;
            }

            @Override
            double orientation(int d, int a, int b, int c) {
                return 0;
            }

            private int a() {
                return tetrahedra.getInt(index * 4);
            }

            private int b() {
                return tetrahedra.getInt(index * 4 + 1);
            }

            private int c() {
                return tetrahedra.getInt(index * 4 + 2);
            }

            private int d() {
                return tetrahedra.getInt(index * 4 + 3);
            }
        }
    }
}
