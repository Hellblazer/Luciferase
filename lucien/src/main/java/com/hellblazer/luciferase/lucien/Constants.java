package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.tetree.Tet;

import javax.vecmath.Point3f;
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

    public static final int MAX_EXTENT = 1 << getMaxRefinementLevel();

    /** Tetrahedron type and Cube ID to local index **/
    public static final byte[][] TYPE_CUBE_ID_TO_LOCAL_INDEX = new byte[][] { { 0, 1, 1, 4, 1, 4, 4, 7 },
                                                                              { 0, 1, 2, 5, 2, 5, 4, 7 },
                                                                              { 0, 2, 3, 4, 1, 6, 5, 7 },
                                                                              { 0, 3, 1, 5, 2, 4, 6, 7 },
                                                                              { 0, 2, 2, 6, 3, 5, 5, 7 },
                                                                              { 0, 3, 3, 6, 3, 6, 6, 7 } };

    /** Parent type and local index to cube id - from t8code t8_dtet_parenttype_Iloc_to_cid **/
    public static final byte[][] PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID = new byte[][] { { 0, 1, 1, 1, 5, 5, 5, 7 },
                                                                                     // Parent type 0
                                                                                     { 0, 1, 1, 1, 3, 3, 3, 7 },
                                                                                     // Parent type 1
                                                                                     { 0, 2, 2, 2, 3, 3, 3, 7 },
                                                                                     // Parent type 2
                                                                                     { 0, 2, 2, 2, 6, 6, 6, 7 },
                                                                                     // Parent type 3
                                                                                     { 0, 4, 4, 4, 6, 6, 6, 7 },
                                                                                     // Parent type 4
                                                                                     { 0, 4, 4, 4, 5, 5, 5,
                                                                                       7 } }; // Parent type 5

    /** Parent type and local index to type **/
    public static final byte[][] PARENT_TYPE_LOCAL_INDEX_TO_TYPE = new byte[][] { { 0, 0, 4, 5, 0, 1, 2, 0 },
                                                                                  { 1, 1, 2, 3, 0, 1, 5, 1 },
                                                                                  { 2, 0, 1, 2, 2, 3, 4, 2 },
                                                                                  { 3, 3, 4, 5, 1, 2, 3, 3 },
                                                                                  { 4, 2, 3, 4, 0, 4, 5, 4 },
                                                                                  { 5, 0, 1, 5, 3, 4, 5, 5 } };

    /** Tet ID of the Root Simplex **/
    public static final Tet ROOT_SIMPLEX = new Tet(0, 0, 0, (byte) 0, (byte) 0);

    /** Tet ID of the unit simplex - the representative simplex of unit length, type 0, corner coordinates {0,0,0} **/
    public static final Tet UNIT_SIMPLEX = new Tet(0, 0, 0, (byte) 20, (byte) 0);
    public static final int MAX_COORD    = (1 << MortonCurve.MAX_REFINEMENT_LEVEL) - 1;

    /**
     * Calculate the Morton index for a given point and level
     *
     * @param point the 3D point
     * @param level the refinement level
     * @return the Morton index
     *
     * Note: Morton encoding uses 21-bit coordinates. The maximum coordinate value that can be encoded is 2^21 - 1 =
     * 2,097,151. Coordinates beyond this range will wrap around due to bit masking in the MortonCurve.encode method. At
     * level 0, the cell length is 2^21 = 2,097,152, which exceeds the maximum encodable coordinate.
     */
    public static long calculateMortonIndex(Point3f point, byte level) {
        var length = lengthAtLevel(level);
        int quantizedX = (int) (Math.floor(point.x / length) * length);
        int quantizedY = (int) (Math.floor(point.y / length) * length);
        int quantizedZ = (int) (Math.floor(point.z / length) * length);

        // Warn if coordinates will overflow (only in debug/development)
        // Note: Negative coordinates will be wrapped by the cast to int and then by Morton encoding
        assert (quantizedX & MAX_COORD) == quantizedX || quantizedX < 0 : String.format(
        "X coordinate %d exceeds 21-bit range, will wrap to %d", quantizedX, quantizedX & MAX_COORD);
        assert (quantizedY & MAX_COORD) == quantizedY || quantizedY < 0 : String.format(
        "Y coordinate %d exceeds 21-bit range, will wrap to %d", quantizedY, quantizedY & MAX_COORD);
        assert (quantizedZ & MAX_COORD) == quantizedZ || quantizedZ < 0 : String.format(
        "Z coordinate %d exceeds 21-bit range, will wrap to %d", quantizedZ, quantizedZ & MAX_COORD);

        return MortonCurve.encode(quantizedX, quantizedY, quantizedZ);
    }

    /** maximum level we can accommodate without overflow **/
    public static byte getMaxRefinementLevel() {
        return MortonCurve.MAX_REFINEMENT_LEVEL;
    }

    /**
     * @return the length of an edge at the given level, in integer coordinates
     */
    public static int lengthAtLevel(byte level) {
        assert level <= getMaxRefinementLevel();
        return 1 << (getMaxRefinementLevel() - level);
    }

    public static byte toLevel(long mortonCode) {
        if (mortonCode == 0) {
            return 0; // origin at coarsest level (root)
        }

        // Decode the Morton code to get coordinates
        int[] coords = MortonCurve.decode(mortonCode);
        int x = coords[0];
        int y = coords[1];
        int z = coords[2];

        // Find the maximum coordinate to determine appropriate level
        int maxCoord = Math.max(Math.max(x, y), z);

        if (maxCoord == 0) {
            return getMaxRefinementLevel();
        }

        // Find the level that provides an appropriate cube size for this coordinate range
        // We want a level where the grid size is large enough to reasonably contain the coordinates
        // but not unnecessarily coarse

        // Find the minimum number of bits needed to represent the max coordinate
        int bitsNeeded = 32 - Integer.numberOfLeadingZeros(maxCoord);

        // Choose a level that provides reasonable granularity
        // Level 0 has grid size = 2^21, Level 21 has grid size = 1
        // We want the coarsest level that still provides reasonable precision
        byte level = (byte) Math.max(0, Math.min(getMaxRefinementLevel(), getMaxRefinementLevel() - bitsNeeded + 3));

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
