package com.hellblazer.luciferase.lucien.voxel;

import javax.vecmath.Point3i;
import javax.vecmath.Tuple3i;

/**
 * A tetrahedron in the mesh
 *
 * @author hal.hildebrand
 **/
public record Tet(int l, int x, int y, int z, byte type) {

    private static final byte[][] PARENT_2D = new byte[][] { { 0, 1 }, { 0, 0 }, { 1, 1 }, { 0, 1 } };
    private static final byte[][] PARENT_3D = new byte[][] { { 0, 1, 2, 3, 4, 5 }, { 0, 1, 1, 1, 0, 0 },
                                                             { 2, 2, 2, 3, 3, 3 }, { 1, 1, 2, 2, 2, 1 },
                                                             { 5, 5, 4, 4, 4, 5 }, { 0, 0, 0, 5, 5, 5 },
                                                             { 4, 3, 3, 3, 4, 4 }, { 0, 1, 2, 3, 4, 5 } };

    private static final byte[][] CHILD_2D = new byte[][] { { 0, 1, 3, 2 }, { 0, 2, 3, 1 } };
    private static final byte[][] CHILD_3D = new byte[][] { { 0, 1, 4, 7, 2, 3, 6, 5 }, { 0, 1, 5, 7, 2, 3, 6, 4 },
                                                            { 0, 3, 4, 7, 1, 2, 6, 5 }, { 0, 1, 6, 7, 2, 3, 4, 5 },
                                                            { 0, 3, 5, 7, 1, 2, 4, 6 }, { 0, 3, 6, 7, 2, 1, 4, 5 } };

    private static final byte[][] CHILD_TYPE_2D = new byte[][] { { 0, 0, 0, 1 }, { 1, 1, 1, 0 } };
    private static final byte[][] CHILD_TYPE_3D = new byte[][] { { 0, 0, 0, 0, 4, 5, 2, 1 }, { 1, 1, 1, 1, 3, 2, 5, 0 },
                                                                 { 2, 2, 2, 2, 0, 1, 4, 3 }, { 3, 3, 3, 3, 5, 4, 1, 2 },
                                                                 { 4, 4, 4, 4, 2, 3, 0, 5 },
                                                                 { 5, 5, 5, 5, 1, 0, 3, 4 } };

    private static final byte[][] TRI_INDEX_TO_BEYS    = { { 0, 1, 3, 2 }, { 0, 3, 1, 2 } };
    private static final byte[]   TRI_BEY_ID_TO_VERTEX = { 0, 1, 2, 1 };

    private static final byte[][] TRI_TYPE_OF_CHILD        = { { 0, 0, 0, 1 }, { 1, 1, 1, 0 } };
    private static final byte[][] TRI_TYPE_OF_CHILD_MORTON = { { 0, 0, 1, 0 }, { 1, 0, 1, 1 } };

    private static final Tuple3i[][] BASIC_TYPE_2D = new Tuple3i[][] {
    { CORNER.c0.base(), CORNER.c1.base(), CORNER.c3.base() },
    { CORNER.c0.base(), CORNER.c2.base(), CORNER.c3.base() } };
    private static final Tuple3i[][] BASIC_TYPE_3D = new Tuple3i[][] {
    { CORNER.c0.base(), CORNER.c1.base(), CORNER.c5.base(), CORNER.c7.base() },
    { CORNER.c0.base(), CORNER.c1.base(), CORNER.c3.base(), CORNER.c7.base() },
    { CORNER.c0.base(), CORNER.c2.base(), CORNER.c3.base(), CORNER.c7.base() },
    { CORNER.c0.base(), CORNER.c2.base(), CORNER.c6.base(), CORNER.c7.base() },
    { CORNER.c0.base(), CORNER.c4.base(), CORNER.c6.base(), CORNER.c7.base() },
    { CORNER.c0.base(), CORNER.c4.base(), CORNER.c5.base(), CORNER.c7.base() } };

    /**
     * Answer the 3D coordinates of the vertex
     *
     * @param L      - max refinement level
     * @param vertex - the vertex #
     * @return the 3D coordinates of the vertex
     */
    public Tuple3i coordinates(int L, int vertex) {
        int h = (int) Math.pow(2, L - l);
        int ei = type / 2;

        var coords = new int[] { x, y, z };
        if (vertex == 0) {
            return new Point3i(coords[0], coords[1], coords[2]);
        }
        coords[ei] += h;
        if (vertex == 2) {
            int ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3;
            coords[1 - ej] += h;
            return new Point3i(coords[0], coords[1], coords[2]);
        }
        if (vertex == 3) {
            coords[(ei + 1) % 3] += h;
            coords[(ei + 2) % 3] += h;
        }
        return new Point3i(coords[0], coords[1], coords[2]);
    }

    /**
     * @param L - max refinement level
     * @return the cube id of the receiver
     */
    public int cubeId(int L) {
        int i = 0;
        int h = (int) Math.pow(2, L - l);
        i |= (x & h) > 0 ? 1 : 0;
        i |= (y & h) > 0 ? 2 : 0;
        i |= (z & h) > 0 ? 4 : 0;
        return i;
    }

    /**
     * @param L - max refinement level
     * @return the parent Tet
     */
    public Tet parent(int L) {
        int h = (int) Math.pow(2, L - l);
        return new Tet(l - 1, x & ~h, y & ~h, z & ~h, PARENT_3D[cubeId(L)][type]);
    }

    /**
     * @param i - the Morton child id
     * @return the i-th child (in Bey's order) of the receiver
     */
    public Tet child(int i, int L) {
        var coords = new int[] { x, y, z };
        int Bey_cid;
        Bey_cid = TRI_INDEX_TO_BEYS[type][i];
        if (Bey_cid == 0) {
            coords[0] = x;
            coords[1] = y;
            coords[2] = z;
        } else {
            /* i-th anchor coordinate of child is (X_(0,i)+X_(vertex,i))/2
             * where X_(i,j) is the j-th coordinate of t's ith node */
            var coordinates = coordinates(L, TRI_BEY_ID_TO_VERTEX[Bey_cid]);
            coords[0] = x + coordinates.x >> 1;
            coords[1] = y + coordinates.y >> 1;
            coords[2] = x + coordinates.z >> 1;
        }
        return new Tet(l - 1, coords[0], coords[1], coords[2], TRI_TYPE_OF_CHILD[type][Bey_cid]);
    }

    /**
     * @param i - the Tet Morton child id
     * @return the i-th child (in Tet Morton order) of the receiver
     */
    public Tet childTM(int i, int L) {
        var coords = new int[] { x, y, z };
        int Bey_cid;
        Bey_cid = TRI_INDEX_TO_BEYS[type][i];
        if (Bey_cid == 0) {
            coords[0] = x;
            coords[1] = y;
            coords[2] = z;
        } else {
            /* i-th anchor coordinate of child is (X_(0,i)+X_(vertex,i))/2
             * where X_(i,j) is the j-th coordinate of t's ith node */
            var coordinates = coordinates(L, TRI_BEY_ID_TO_VERTEX[Bey_cid]);
            coords[0] = x + coordinates.x >> 1;
            coords[1] = y + coordinates.y >> 1;
            coords[2] = x + coordinates.z >> 1;
        }
        return new Tet(l - 1, coords[0], coords[1], coords[2], TRI_TYPE_OF_CHILD[type][Bey_cid]);
    }

    // The corners of a cube
    private enum CORNER {
        c0 {
            @Override
            public Tuple3i base() {
                return new Point3i(0, 0, 0);
            }
        }, c1 {
            @Override
            public Tuple3i base() {
                return new Point3i(1, 0, 0);
            }
        }, c2 {
            @Override
            public Tuple3i base() {
                return new Point3i(0, 1, 0);
            }
        }, c3 {
            @Override
            public Tuple3i base() {
                return new Point3i(1, 1, 0);
            }
        }, c4 {
            @Override
            public Tuple3i base() {
                return new Point3i(0, 0, 1);
            }
        }, c5 {
            @Override
            public Tuple3i base() {
                return new Point3i(1, 0, 1);
            }
        }, c6 {
            @Override
            public Tuple3i base() {
                return new Point3i(0, 1, 1);
            }
        }, c7 {
            @Override
            public Tuple3i base() {
                return new Point3i(1, 1, 1);
            }
        };

        abstract public Tuple3i base();
    }
}
