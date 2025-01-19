package com.hellblazer.luciferase.lucien.index;

import javax.vecmath.Point3i;
import javax.vecmath.Tuple3i;

/**
 * Tables o' data for Tet spatial indexing
 *
 * @author hal.hildebrand
 **/
public class TetConstants {
    public static int MAX_REFINEMENT_LEVEL = 21;
    public static final byte[][] PARENT_2D = new byte[][] { { 0, 1 }, { 0, 0 }, { 1, 1 }, { 0, 1 } };
    public static final byte[][] PARENT_3D = new byte[][] { { 0, 1, 2, 3, 4, 5 }, { 0, 1, 1, 1, 0, 0 },
                                                            { 2, 2, 2, 3, 3, 3 }, { 1, 1, 2, 2, 2, 1 },
                                                            { 5, 5, 4, 4, 4, 5 }, { 0, 0, 0, 5, 5, 5 },
                                                            { 4, 3, 3, 3, 4, 4 }, { 0, 1, 2, 3, 4, 5 } };

    public static final byte[][] CHILD_TYPE_TO_PARENT_TYPE = new byte[][] { { 0, 1 }, { 0, 0 }, { 1, 1 }, { 0, 1 } };

    public static final byte[][] CHILD_TYPE_2D_MORTON = new byte[][] { { 0, 1, 3, 2 }, { 0, 2, 3, 1 } };
    public static final byte[][] CHILD_TYPE_3D_MORTON = new byte[][] { { 0, 1, 4, 7, 2, 3, 6, 5 },
                                                                       { 0, 1, 5, 7, 2, 3, 6, 4 },
                                                                       { 0, 3, 4, 7, 1, 2, 6, 5 },
                                                                       { 0, 1, 6, 7, 2, 3, 4, 5 },
                                                                       { 0, 3, 5, 7, 1, 2, 4, 6 },
                                                                       { 0, 3, 6, 7, 2, 1, 4, 5 } };

    public static final byte[][] CHILD_TYPE_2D = new byte[][] { { 0, 0, 0, 1 }, { 1, 1, 1, 0 } };
    public static final byte[][] CHILD_TYPE_3D = new byte[][] { { 0, 0, 0, 0, 4, 5, 2, 1 }, { 1, 1, 1, 1, 3, 2, 5, 0 },
                                                                { 2, 2, 2, 2, 0, 1, 4, 3 }, { 3, 3, 3, 3, 5, 4, 1, 2 },
                                                                { 4, 4, 4, 4, 2, 3, 0, 5 },
                                                                { 5, 5, 5, 5, 1, 0, 3, 4 } };

    public static final Tuple3i[][] BASIC_TYPE_2D = new Tuple3i[][] {
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c3.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c3.coords() } };
    public static final Tuple3i[][] BASIC_TYPE_3D = new Tuple3i[][] {
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c5.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c3.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c3.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c6.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c4.coords(), CORNER.c6.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c4.coords(), CORNER.c5.coords(), CORNER.c7.coords() } };

    public static byte[][] TYPE_CUBE_ID_TO_LOCAL_INDEX = new byte[][] { { 1, 1, 4, 1, 4, 4, 7 },
                                                                        { 1, 2, 5, 2, 5, 4, 7 },
                                                                        { 2, 3, 4, 1, 6, 5, 7 },
                                                                        { 3, 1, 5, 2, 4, 6, 7 },
                                                                        { 2, 2, 6, 3, 5, 5, 7 },
                                                                        { 3, 3, 6, 3, 6, 6, 7 } };
    public static byte[][] PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID = new byte[][] {

    };
    public static byte[][] CUBE_ID_TO_LOCAL_INDEX      = new byte[][] { { 0, 0, 4, 5, 0, 1, 2, 0 },
                                                                      { 1, 1, 2, 3, 0, 1, 5, 1 },
                                                                      { 2, 0, 1, 2, 2, 3, 4, 2 },
                                                                      { 3, 3, 4, 5, 1, 2, 3, 3 },
                                                                      { 4, 2, 3, 4, 0, 4, 5, 4 },
                                                                      { 5, 0, 1, 5, 3, 4, 5, 5 } };

    // The corners of a cube
    public enum CORNER {
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
