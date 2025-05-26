package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.geometry.MortonCurve;

import javax.vecmath.Point3i;

/**
 * Tables o' data for Tetree and Octree spatial indexing z
 *
 * @author hal.hildebrand
 **/
public class Constants {
    /** Cube ID and Type to Parent Type **/
    public static final byte[][] CUBE_ID_TYPE_TO_PARENT_TYPE  = new byte[][] { { 0, 1, 2, 3, 4, 5 },
                                                                               { 0, 1, 1, 1, 0, 0 },
                                                                               { 2, 2, 2, 3, 3, 3 },
                                                                               { 1, 1, 2, 2, 2, 1 },
                                                                               { 5, 5, 4, 4, 4, 5 },
                                                                               { 0, 0, 0, 5, 5, 5 },
                                                                               { 4, 3, 3, 3, 4, 4 },
                                                                               { 0, 1, 2, 3, 4, 5 } };
    /* in dependence of a type x give the type of
     * the child with Morton number y */
    public static final byte[][] TYPE_TO_TYPE_OF_CHILD_MORTON = new byte[][] { { 0, 0, 4, 5, 0, 1, 2, 0 },
                                                                               { 1, 1, 2, 3, 0, 1, 5, 1 },
                                                                               { 2, 0, 1, 2, 2, 3, 4, 2 },
                                                                               { 3, 3, 4, 5, 1, 2, 3, 3 },
                                                                               { 4, 2, 3, 4, 0, 4, 5, 4 },
                                                                               { 5, 0, 1, 5, 3, 4, 5, 5 } };

    /* In dependence of a type x give the type of
     * the child with Bey number y */
    public static final byte[][]    TYPE_TO_TYPE_OF_CHILD = new byte[][] { { 0, 0, 0, 0, 4, 5, 2, 1 },
                                                                           { 1, 1, 1, 1, 3, 2, 5, 0 },
                                                                           { 2, 2, 2, 2, 0, 1, 4, 3 },
                                                                           { 3, 3, 3, 3, 5, 4, 1, 2 },
                                                                           { 4, 4, 4, 4, 2, 3, 0, 5 },
                                                                           { 5, 5, 5, 5, 1, 0, 3, 4 } };
    /**
     * The Tetrahedrons in Bey's order, corners ordered z,y,x
     * <p>
     * <img src="reference-simplexes.png" alt="Bey's order" />
     * </p>
     */
    public static final Point3i[][] SIMPLEX               = new Point3i[][] {
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c5.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c3.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c3.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c6.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c4.coords(), CORNER.c6.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c4.coords(), CORNER.c5.coords(), CORNER.c7.coords() } };

    /**
     * The Tetrahedrons in Bey's order, corners ordered in standard position
     * <p>
     * <img src="reference-simplexes.png" alt="Bey's order" />
     * </p>
     */

    public static final Point3i[][] SIMPLEX_STANDARD = new Point3i[][] {
    { CORNER.c0.coords(), CORNER.c1.coords(), CORNER.c5.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c7.coords(), CORNER.c3.coords(), CORNER.c1.coords() },
    { CORNER.c0.coords(), CORNER.c2.coords(), CORNER.c3.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c7.coords(), CORNER.c6.coords(), CORNER.c2.coords() },
    { CORNER.c0.coords(), CORNER.c4.coords(), CORNER.c6.coords(), CORNER.c7.coords() },
    { CORNER.c0.coords(), CORNER.c7.coords(), CORNER.c5.coords(), CORNER.c4.coords() } };

    public static int MAX_EXTENT = 1 << getMaxRefinementLevel();

    /** Tetrahedron type and Cube ID to local index **/
    public static byte[][] TYPE_CUBE_ID_TO_LOCAL_INDEX = new byte[][] { { 0, 1, 1, 4, 1, 4, 4, 7 },
                                                                        { 0, 1, 2, 5, 2, 5, 4, 7 },
                                                                        { 0, 2, 3, 4, 1, 6, 5, 7 },
                                                                        { 0, 3, 1, 5, 2, 4, 6, 7 },
                                                                        { 0, 2, 2, 6, 3, 5, 5, 7 },
                                                                        { 0, 3, 3, 6, 3, 6, 6, 7 } };

    /** Parent type and local index to cube id **/
    public static byte[][] PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID = new byte[][] { { 0, 1, 1, 1, 5, 5, 5, 7 },
                                                                               { 0, 1, 1, 1, 3, 3, 3, 7 },
                                                                               { 0, 2, 2, 2, 3, 3, 3, 7 },
                                                                               { 0, 2, 2, 2, 6, 6, 6, 7 },
                                                                               { 0, 4, 4, 4, 6, 6, 6, 7 },
                                                                               { 0, 4, 4, 4, 5, 5, 5, 7 } };

    /** Parent type and local index to type **/
    public static byte[][] PARENT_TYPE_LOCAL_INDEX_TO_TYPE = new byte[][] { { 0, 0, 4, 5, 0, 1, 2, 0 },
                                                                            { 1, 1, 2, 3, 0, 1, 5, 1 },
                                                                            { 2, 0, 1, 2, 2, 3, 4, 2 },
                                                                            { 3, 3, 4, 5, 1, 2, 3, 3 },
                                                                            { 4, 2, 3, 4, 0, 4, 5, 4 },
                                                                            { 5, 0, 1, 5, 3, 4, 5, 5 } };

    /** Tet ID of the Root Simplex **/
    public static Tet ROOT_SIMPLEX = new Tet(0, 0, 0, (byte) 0, (byte) 0);

    /** Tet ID of the unit simplex - the representative simplex of unit length, type 0, corner coordinates {0,0,0} **/
    public static Tet UNIT_SIMPLEX = new Tet(0, 0, 0, getMaxRefinementLevel(), (byte) 0);

    /** maximum level we can accommodate without overflow **/
    public static byte getMaxRefinementLevel() {
        return MortonCurve.MAX_REFINEMENT_LEVEL;
    }

    /**
     * @return the length of an edge at the given level, in integer coordinates
     */
    public static int lengthAtLevel(byte level) {
        return 1 << (getMaxRefinementLevel() - level);
    }

    public static byte toLevel(long mortonCode) {
        if (mortonCode == 0) {
            return 0;
        }
        byte level = 0;
        while (mortonCode > 0) {
            mortonCode >>= 3; // pop the octet
            level++;
        }
        return level;
    }

    /**
     * The corners of a cube
     * <p>
     * <img src="reference-cube.png" alt="Reference cube" />
     */
    public enum CORNER {
        c0 {
            @Override
            public Point3i coords() {
                return new Point3i(0, 0, 0);
            }
        }, c1 {
            @Override
            public Point3i coords() {
                return new Point3i(1, 0, 0);
            }
        }, c2 {
            @Override
            public Point3i coords() {
                return new Point3i(0, 1, 0);
            }
        }, c3 {
            @Override
            public Point3i coords() {
                return new Point3i(1, 1, 0);
            }
        }, c4 {
            @Override
            public Point3i coords() {
                return new Point3i(0, 0, 1);
            }
        }, c5 {
            @Override
            public Point3i coords() {
                return new Point3i(1, 0, 1);
            }
        }, c6 {
            @Override
            public Point3i coords() {
                return new Point3i(0, 1, 1);
            }
        }, c7 {
            @Override
            public Point3i coords() {
                return new Point3i(1, 1, 1);
            }
        };

        abstract public Point3i coords();
    }
}
