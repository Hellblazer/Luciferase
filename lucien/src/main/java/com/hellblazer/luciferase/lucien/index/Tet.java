package com.hellblazer.luciferase.lucien.index;

import javax.vecmath.Point3i;
import javax.vecmath.Tuple3i;

/**
 * A tetrahedron in the mesh
 *
 * @author hal.hildebrand
 **/
public record Tet(int x1, int x2, int x3, byte l, byte type) {

    private static final byte[][] PARENT_2D = new byte[][] { { 0, 1 }, { 0, 0 }, { 1, 1 }, { 0, 1 } };
    private static final byte[][] PARENT_3D = new byte[][] { { 0, 1, 2, 3, 4, 5 }, { 0, 1, 1, 1, 0, 0 },
                                                             { 2, 2, 2, 3, 3, 3 }, { 1, 1, 2, 2, 2, 1 },
                                                             { 5, 5, 4, 4, 4, 5 }, { 0, 0, 0, 5, 5, 5 },
                                                             { 4, 3, 3, 3, 4, 4 }, { 0, 1, 2, 3, 4, 5 } };

    private static final byte[][] CHILD_TYPE_TO_PARENT_TYPE = new byte[][] { { 0, 1 }, { 0, 0 }, { 1, 1 }, { 0, 1 } };

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
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c3.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c3.coords() } };
    private static final Tuple3i[][] BASIC_TYPE_3D = new Tuple3i[][] {
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c5.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c3.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c3.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c6.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c4.coords(), CORNER.c6.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c4.coords(), CORNER.c5.coords(), CORNER.c7.coords() } };

    /**
     * @param L - maximum refinement level
     * @return the length of a triangle at the given level, in integer coordinates
     */
    public int lengthAtLevel(int L) {
        return 1 << (L - l);
    }

    /**
     * Answer the 3D coordinates of the vertex
     *
     * @param L      - max refinement level
     * @param vertex - the vertex #
     * @return the 3D coordinates of the vertex
     */
    public Tuple3i coordinates(int L, int vertex) {
        int h = lengthAtLevel(L);
        int ei = type / 2;

        var coords = new int[] { x1, x2, x3 };
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
        if (l == 0) {
            return 0;
        }
        int i = 0;
        int h = lengthAtLevel(L);
        i |= (x1 & h) > 0 ? 1 : 0;
        i |= (x2 & h) > 0 ? 2 : 0;
        i |= (x3 & h) > 0 ? 4 : 0;
        return i;
    }

    /**
     * @param L - max refinement level
     * @return the parent Tet
     */
    public Tet parent(int L) {
        int h = lengthAtLevel(L);
        return new Tet(x1 & ~h, x2 & ~h, x3 & ~h, (byte) (l - 1), PARENT_3D[cubeId(L)][type]);
    }

    /**
     * @param i - the Morton child id
     * @return the i-th child (in Bey's order) of the receiver
     */
    public Tet child(int i, int L) {
        var coords = new int[] { x1, x2, x3 };
        var bey = TRI_INDEX_TO_BEYS[type][i];
        if (bey == 0) {
            coords[0] = x1;
            coords[1] = x2;
            coords[2] = x3;
        } else {
            /* i-th anchor coordinate of child is (X_(0,i)+X_(vertex,i))/2
             * where X_(i,j) is the j-th coordinate of t's ith node */
            var coordinates = coordinates(L, TRI_BEY_ID_TO_VERTEX[bey]);
            coords[0] = x1 + coordinates.x >> 1;
            coords[1] = x2 + coordinates.y >> 1;
            coords[2] = x1 + coordinates.z >> 1;
        }
        return new Tet(coords[0], coords[1], coords[2], (byte) (l - 1), TRI_TYPE_OF_CHILD[type][bey]);
    }

    /**
     * @param i - the Tet Morton child id
     * @return the i-th child (in Tet Morton order) of the receiver
     */
    public Tet childTM(int i, int L) {
        return child(TRI_TYPE_OF_CHILD_MORTON[type][i], L);
    }

    // The corners of a cube
    private enum CORNER {
        c0 {
            @Override
            public Tuple3i coords() {
                return new Point3i(0, 0, 0);
            }
        }, c1 {
            @Override
            public Tuple3i coords() {
                return new Point3i(1, 0, 0);
            }
        }, c2 {
            @Override
            public Tuple3i coords() {
                return new Point3i(0, 1, 0);
            }
        }, c3 {
            @Override
            public Tuple3i coords() {
                return new Point3i(1, 1, 0);
            }
        }, c4 {
            @Override
            public Tuple3i coords() {
                return new Point3i(0, 0, 1);
            }
        }, c5 {
            @Override
            public Tuple3i coords() {
                return new Point3i(1, 0, 1);
            }
        }, c6 {
            @Override
            public Tuple3i coords() {
                return new Point3i(0, 1, 1);
            }
        }, c7 {
            @Override
            public Tuple3i coords() {
                return new Point3i(1, 1, 1);
            }
        };

        abstract public Tuple3i coords();
    }
}
