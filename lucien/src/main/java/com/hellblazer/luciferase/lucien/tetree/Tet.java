package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.Geometry;
import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.HybridFaceNeighbor;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.pyramid.Pyramid;
import com.hellblazer.luciferase.lucien.VolumeBounds;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.hellblazer.luciferase.lucien.Constants.*;

/**
 * A tetrahedron in the tetrahedral space-filling curve (Tet SFC) implementation.
 *
 * This class represents a single tetrahedron in the hierarchical tetrahedral subdivision of 3D space. The
 * implementation is based on t8code and the paper "A tetrahedral space-filling curve for non-conforming adaptive
 * meshes" (https://arxiv.org/abs/1509.04627).
 *
 * <p><b>Key Concepts:</b></p>
 * <ul>
 *   <li><b>Anchor Point</b>: The (x,y,z) coordinates represent the tetrahedron's anchor vertex</li>
 *   <li><b>Level</b>: Refinement level (0 = root, max = 21)</li>
 *   <li><b>Type</b>: One of 6 tetrahedral types (0-5) that tile a cubic cell</li>
 *   <li><b>SFC Index</b>: Space-filling curve index encoding the path from root</li>
 * </ul>
 *
 * <p><b>Critical Constraints:</b></p>
 * <ul>
 *   <li>All coordinates MUST be positive (tetrahedral SFC requirement)</li>
 *   <li>The root tetrahedron is the S0 simplex covering the positive octant</li>
 *   <li>Each cubic grid cell contains exactly 6 tetrahedra</li>
 *   <li>Children are generated using Bey's vertex midpoint refinement</li>
 * </ul>
 *
 * @author hal.hildebrand
 **/
public class Tet implements Spatial.aabt, HybridElement {
    public static final  TetreeKey<?> ROOT_TET      = TetreeKey.getRoot();
    /**
     * Default root tetrahedron type (follows t8code standard)
     */
    private static final byte DEFAULT_ROOT_TET_TYPE = 0;
    
    /**
     * Get the root tetrahedron type. Can be configured via system property
     * or defaults to type 0 (S0 tetrahedron in standard t8code).
     */
    private static byte getRootTetrahedronType() {
        String rootTypeStr = System.getProperty("tetree.root.type");
        if (rootTypeStr != null) {
            try {
                byte rootType = Byte.parseByte(rootTypeStr);
                if (rootType >= 0 && rootType <= 5) {
                    return rootType;
                }
            } catch (NumberFormatException e) {
                // Fall through to default
            }
        }
        return DEFAULT_ROOT_TET_TYPE;
    }    // Table 2: Local indices - Iloc(parent_type, bey_child_index)
    // Note: Different from TetreeConnectivity.INDEX_TO_BEY_NUMBER due to different indexing scheme
    private static final byte[][]     LOCAL_INDICES = { { 0, 1, 4, 7, 2, 3, 6, 5 }, // Parent type 0
                                                        { 0, 1, 5, 7, 2, 3, 6, 4 }, // Parent type 1
                                                        { 0, 3, 4, 7, 1, 2, 6, 5 }, // Parent type 2
                                                        { 0, 1, 6, 7, 2, 3, 4, 5 }, // Parent type 3
                                                        { 0, 3, 5, 7, 1, 2, 4, 6 }, // Parent type 4
                                                        { 0, 3, 6, 7, 2, 1, 4, 5 }  // Parent type 5
    };
    /**
     * Sentinel for {@link #minTetLevel}: this tetrahedron has no pyramidal ancestor (pure-Tetree, or
     * a tet whose whole ancestor chain is tetrahedral). RDR-010 pi1.2 / Knapp 2026 Algorithm 4.1.
     */
    public static final  byte         NO_TET_ANCESTOR = -1;
    public final         int          x;
    public final         int          y;
    public final         int          z;
    public final         byte         l;
    public final         byte         type;
    /**
     * Smallest level at which an ancestor is a tetrahedron; {@link #NO_TET_ANCESTOR} (-1) for
     * pure-Tetree elements. Contextual tree metadata for the hybrid pyramid/tet SFC (RDR-010);
     * excluded from geometric identity ({@link #equals}/{@link #hashCode}).
     */
    public final         byte         minTetLevel;

    public Tet(int x, int y, int z, byte l, byte type) {
        this(x, y, z, l, type, NO_TET_ANCESTOR);
    }

    /**
     * Create a Tet carrying an explicit {@code minTetLevel} (RDR-010 pi1.2, Knapp 2026
     * Algorithm 4.1). {@code minTetLevel} is the smallest level at which an ancestor is a
     * tetrahedron; it is {@link #NO_TET_ANCESTOR} (-1) for tetrahedra in a pure-Tetree tree and for
     * any tet whose entire ancestor chain is tetrahedral. It is non-negative only for tetrahedra
     * descending from a pyramidal root, where it marks the tet/pyramid boundary level. This is
     * contextual tree metadata: it is intentionally excluded from {@link #equals}/{@link #hashCode},
     * which identify a tetrahedron by its geometry {@code (x,y,z,l,type)} alone.
     *
     * @param minTetLevel {@link #NO_TET_ANCESTOR} (-1), or a level in {@code [0, l]}
     */
    public Tet(int x, int y, int z, byte l, byte type, byte minTetLevel) {
        // Validate level range first
        assert l >= 0 && l <= MortonCurve.MAX_REFINEMENT_LEVEL : "Level " + l + " must be between 0 and "
        + MortonCurve.MAX_REFINEMENT_LEVEL;

        assert type >= 0 && type <= 5 : "Type " + type + " must be between 0 and 5";
        // Validate coordinates
        assert x >= 0 && y >= 0 && z >= 0 : "Coordinates must be non-negative: (" + x + ", " + y + ", " + z + ")";
        // Validate that coordinates are correct anchor coordinates for the level and type
        assert validateAnchorCoordinates(x, y, z, l, type);
        assert minTetLevel == NO_TET_ANCESTOR || (minTetLevel >= 0 && minTetLevel <= l)
        : "minTetLevel must be -1 (NO_TET_ANCESTOR) or in [0, " + l + "], got: " + minTetLevel;
        this.x = x;
        this.y = y;
        this.z = z;
        this.l = l;
        this.type = type;
        this.minTetLevel = minTetLevel;
    }

    /**
     * Create a validated Tet instance. This factory method ensures that only valid tetrahedra can be created.
     *
     * @param x     X coordinate (must be non-negative)
     * @param y     Y coordinate (must be non-negative)
     * @param z     Z coordinate (must be non-negative)
     * @param level Refinement level (0-21)
     * @param type  Tetrahedron type (0-5)
     * @return a validated Tet instance
     * @throws IllegalArgumentException if the parameters don't form a valid tetrahedron
     */
    public static Tet createValidated(int x, int y, int z, byte level, byte type) {
        // First validate coordinates are non-negative
        if (x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException("Coordinates must be non-negative: (" + x + ", " + y + ", " + z + ")");
        }

        // Create the Tet
        var tet = new Tet(x, y, z, level, type);

        // Validate it
        if (!isValidTetrahedronStatic(x, y, z, level, type)) {
            throw new IllegalArgumentException(
            "Invalid tetrahedron: coordinates (" + x + ", " + y + ", " + z + ") with type " + type + " at level "
            + level + " does not form a valid tetrahedron");
        }

        return tet;
    }

    /**
     * Static validation method to check if the given parameters form a valid tetrahedron. This avoids recursion issues
     * during construction.
     */
    private static boolean isValidTetrahedronStatic(int x, int y, int z, byte level, byte type) {
        // Special case: root tetrahedron
        if (level == 0) {
            // Root must be at origin with type 0
            return x == 0 && y == 0 && z == 0 && type == 0;
        }

        // Check coordinates are aligned to the grid at this level
        int cellSize = Constants.lengthAtLevel(level);
        if (x % cellSize != 0 || y % cellSize != 0 || z % cellSize != 0) {
            return false;
        }

        // For now, we'll accept any valid type (0-5) at valid grid positions
        // A more thorough validation would require:
        // 1. Building the path from root to this tetrahedron
        // 2. Verifying each parent-child relationship is valid
        // 3. Checking that the final type matches what's expected
        // This is complex and would require essentially reconstructing the tetrahedron
        // from its SFC index, which is what the tmIndex() method does

        return type >= 0 && type <= 5;
    }

    /**
     * Test if a line segment intersects an AABB using the slab method.
     */
    private static boolean lineSegmentIntersectsAABB(Point3f p0, Point3f p1, VolumeBounds bounds) {
        // Direction vector from p0 to p1
        float dx = p1.x - p0.x;
        float dy = p1.y - p0.y;
        float dz = p1.z - p0.z;

        // Parameter t ranges from 0 to 1 along the line segment
        float tMin = 0.0f;
        float tMax = 1.0f;

        // Check X axis
        if (Math.abs(dx) < 1e-6f) {
            // Ray is parallel to X slab
            if (p0.x < bounds.minX() || p0.x > bounds.maxX()) {
                return false;
            }
        } else {
            // Compute intersection t values
            var t1 = (bounds.minX() - p0.x) / dx;
            var t2 = (bounds.maxX() - p0.x) / dx;

            if (t1 > t2) {
                // Swap
                var temp = t1;
                t1 = t2;
                t2 = temp;
            }

            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);

            if (tMin > tMax) {
                return false;
            }
        }

        // Check Y axis
        if (Math.abs(dy) < 1e-6f) {
            // Ray is parallel to Y slab
            if (p0.y < bounds.minY() || p0.y > bounds.maxY()) {
                return false;
            }
        } else {
            // Compute intersection t values
            var t1 = (bounds.minY() - p0.y) / dy;
            var t2 = (bounds.maxY() - p0.y) / dy;

            if (t1 > t2) {
                // Swap
                var temp = t1;
                t1 = t2;
                t2 = temp;
            }

            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);

            if (tMin > tMax) {
                return false;
            }
        }

        // Check Z axis
        if (Math.abs(dz) < 1e-6f) {
            // Ray is parallel to Z slab
            return !(p0.z < bounds.minZ()) && !(p0.z > bounds.maxZ());
        } else {
            // Compute intersection t values
            var t1 = (bounds.minZ() - p0.z) / dz;
            var t2 = (bounds.maxZ() - p0.z) / dz;

            if (t1 > t2) {
                // Swap
                var temp = t1;
                t1 = t2;
                t2 = temp;
            }

            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);

            return !(tMin > tMax);
        }
    }

    /**
     * Static method to locate a tetrahedron containing a point using Bey refinement traversal from a containing
     * ancestor.
     *
     * This method first uses quantization to find an initial tetrahedron at a coarse level, then uses Bey refinement
     * traversal to descend to the target level.
     *
     * This combines the benefits of: - Quantization: to quickly find an initial containing tetrahedron - Bey traversal:
     * to navigate through the actual tree structure
     *
     * @param px          x-coordinate of the point (must be non-negative)
     * @param py          y-coordinate of the point (must be non-negative)
     * @param pz          z-coordinate of the point (must be non-negative)
     * @param targetLevel the target level (0-20)
     * @return the tetrahedron at targetLevel containing the point, or null if not found
     */
    public static Tet locatePointBeyRefinementFromRoot(float px, float py, float pz, byte targetLevel) {
        // Validate inputs
        if (px < 0 || py < 0 || pz < 0) {
            throw new IllegalArgumentException("Coordinates must be non-negative");
        }
        if (targetLevel < 0 || targetLevel > Constants.getMaxRefinementLevel()) {
            throw new IllegalArgumentException(
            "Target level must be between 0 and " + Constants.getMaxRefinementLevel());
        }

        // Special case for level 0 - only type 0 exists
        if (targetLevel == 0) {
            Tet root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
            return root.contains12DOP(px, py, pz) ? root : null;
        }

        // Start at a coarse level where we can reliably find a containing tetrahedron
        // Level 5 gives us a reasonable granularity for initial search
        byte startLevel = (byte) Math.min(5, targetLevel);

        // Check if point is within valid domain
        int maxCoord = Constants.lengthAtLevel((byte) 0);
        if (px >= maxCoord || py >= maxCoord || pz >= maxCoord) {
            return null; // Point outside valid domain
        }

        // Use quantization to find initial containing tetrahedron
        int cellSize = Constants.lengthAtLevel(startLevel);
        int anchorX = (int) (Math.floor(px / cellSize) * cellSize);
        int anchorY = (int) (Math.floor(py / cellSize) * cellSize);
        int anchorZ = (int) (Math.floor(pz / cellSize) * cellSize);

        // Determine which of the 6 characteristic tetrahedra contains the point
        float relX = px - anchorX;
        float relY = py - anchorY;
        float relZ = pz - anchorZ;

        // Scale to unit cube
        float ux = relX / cellSize;
        float uy = relY / cellSize;
        float uz = relZ / cellSize;

        // Determine tetrahedron type based on coordinate ordering — t8code dtet convention, shared with
        // contains12DOP()/coordinates() (RDR-010 Luciferase-4pd: Tet type k IS t8code dtet type k).
        byte type = typeForOrdering(ux, uy, uz);

        // Create starting tetrahedron
        Tet current = new Tet(anchorX, anchorY, anchorZ, startLevel, type);

        // If target level is same as start level, we're done
        if (targetLevel == startLevel) {
            return current;
        }

        // Use Bey refinement traversal to reach target level
        return current.locatePointBeyRefinement(px, py, pz, targetLevel);
    }

    /**
     * Locate the t8code dtet at {@code targetLevel} whose Kuhn-simplex region contains the point.
     *
     * <p><b>Algorithm (RDR-010 Luciferase-4pd):</b> the type at any level is a pure function of the
     * point's coordinate <em>ordering</em> within that level's grid cell (the t8code/Kuhn partition),
     * so the type is classified <em>directly at the target level</em> — there is no per-level parent→child
     * walk. Snap the point to the target-level grid anchor, then map the local-coordinate ordering to a
     * type via {@link #typeForOrdering(double, double, double)} (t0 x&ge;z&ge;y, t1 x&ge;y&ge;z,
     * t2 y&ge;x&ge;z, t3 y&ge;z&ge;x, t4 z&ge;y&ge;x, t5 z&ge;x&ge;y). This is provably consistent
     * with {@link #coordinates()} and {@link #contains12DOP}, and agrees with the SFC locate methods
     * (proven by {@code T8codeDtetOracleTest.locateMethodsAgreeAndContainPoint}).</p>
     *
     * <p><b>Superseded:</b> the earlier implementation traced down from the root via
     * {@code TYPE_CID_TO_BEYID} → {@code PARENT_TYPE_TO_CHILD_TYPE} and assumed the Bey-tree types did
     * not match the S0-S5 geometry. That downward root-trace mis-indexed the Bey table by parent type,
     * was not t8code-consistent (wrong types at depth &ge; 2), and has been <em>deleted</em>. The current
     * t8code type numbering <em>does</em> match the geometry; do not reintroduce a downward Bey trace.</p>
     *
     * <p>Cost: O(1) — direct classification, independent of {@code targetLevel}.</p>
     *
     * @param px          x-coordinate (must be non-negative)
     * @param py          y-coordinate (must be non-negative)
     * @param pz          z-coordinate (must be non-negative)
     * @param targetLevel the target level (0-21)
     * @return the tetrahedron at targetLevel containing the point
     */
    public static Tet locatePointS0Tree(float px, float py, float pz, byte targetLevel) {
        // Validate inputs
        if (px < 0 || py < 0 || pz < 0) {
            throw new IllegalArgumentException("Coordinates must be non-negative");
        }
        if (targetLevel < 0 || targetLevel > Constants.getMaxRefinementLevel()) {
            throw new IllegalArgumentException(
                "Target level must be between 0 and " + Constants.getMaxRefinementLevel());
        }

        // Check bounds
        int maxCoord = Constants.lengthAtLevel((byte) 0);
        if (px >= maxCoord || py >= maxCoord || pz >= maxCoord) {
            throw new IllegalArgumentException("Coordinates must be less than " + maxCoord);
        }

        // Special case: level 0 is always the root tetrahedron of type 0
        if (targetLevel == 0) {
            return new Tet(0, 0, 0, (byte) 0, (byte) 0);
        }

        // RDR-010 Luciferase-4pd: the type at any level is a function of the point's coordinate
        // ordering within that level's grid cell (t8code/Kuhn partition), so classify directly at the
        // target level — provably consistent with coordinates()/contains12DOP. (The old downward
        // root-trace mis-indexed the bey table by parent type and was not t8code-consistent.)
        int targetH = Constants.lengthAtLevel(targetLevel);
        int anchorX = ((int) (px / targetH)) * targetH;
        int anchorY = ((int) (py / targetH)) * targetH;
        int anchorZ = ((int) (pz / targetH)) * targetH;
        byte currentType = typeForOrdering(px - anchorX, py - anchorY, pz - anchorZ);

        return new Tet(anchorX, anchorY, anchorZ, targetLevel, currentType);
    }

    /**
     * The t8code dtet type whose Kuhn-simplex region contains a point with the given LOCAL coordinates
     * within a grid cell (RDR-010 Luciferase-4pd). Matches {@link #contains12DOP} and
     * {@link #coordinates()}: t0 x&ge;z&ge;y, t1 x&ge;y&ge;z, t2 y&ge;x&ge;z, t3 y&ge;z&ge;x,
     * t4 z&ge;y&ge;x, t5 z&ge;x&ge;y.
     */
    private static byte typeForOrdering(double ux, double uy, double uz) {
        if (ux >= uy) {
            if (uy >= uz) {
                return 1; // x >= y >= z
            } else if (ux >= uz) {
                return 0; // x >= z >= y
            } else {
                return 5; // z >= x >= y
            }
        } else {
            if (ux >= uz) {
                return 2; // y >= x >= z
            } else if (uy >= uz) {
                return 3; // y >= z >= x
            } else {
                return 4; // z >= y >= x
            }
        }
    }

    public static double orientation(Tuple3f query, Tuple3i a, Tuple3i b, Tuple3i c) {
        var result = Geometry.leftOfPlane(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, query.x, query.y, query.z);
        return Math.signum(result);
    }

    public static double orientation(Tuple3i query, Tuple3i a, Tuple3i b, Tuple3i c) {
        var result = Geometry.leftOfPlane(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, query.x, query.y, query.z);
        return Math.signum(result);
    }

    /**
     * Calculate the tetrahedral refinement level from a space-filling curve index.
     *
     * <p><b>CRITICAL UNDERSTANDING:</b></p>
     * The Tet SFC index directly encodes the level through the number of bits used:
     * <ul>
     *   <li>Level 0: index = 0 (no bits)</li>
     *   <li>Level 1: indices 1-7 (3 bits)</li>
     *   <li>Level 2: indices 8-63 (6 bits)</li>
     *   <li>Level 3: indices 64-511 (9 bits)</li>
     *   <li>Level n: indices use 3n bits</li>
     * </ul>
     *
     * <p><b>IMPORTANT:</b> This is NOT like Morton codes with level offsets!</p>
     * The level is not implicit in the bit pattern itself.
     *
     * @param index the tetrahedral SFC index (must be non-negative)
     * @return the refinement level (0 to maxRefinementLevel)
     * @throws IllegalArgumentException if index is negative
     */
    public static byte tetLevelFromIndex(long index) {
        // Use O(1) cached lookup instead of O(log n) numberOfLeadingZeros
        return TetreeLevelCache.getLevelFromIndex(index);
    }

    /**
     * Decode a tetrahedron from its space-filling curve index.
     *
     * <p><b>Algorithm (from t8code):</b></p>
     * <ol>
     *   <li>Start at root (type 0)</li>
     *   <li>For each level, extract 3 bits encoding the local child index</li>
     *   <li>Use connectivity tables to determine cube position and new type</li>
     *   <li>Build coordinates by accumulating cube positions</li>
     * </ol>
     *
     * <p><b>CRITICAL:</b> Level must be provided explicitly as the same SFC index
     * can exist at multiple levels representing different tetrahedra. For example:
     * <ul>
     *   <li>Index 0 at level 0: Root tetrahedron covering entire positive octant</li>
     *   <li>Index 0 at level 10: Small tetrahedron at grid coordinates (0,0,0)</li>
     *   <li>Index 0 at level 21: Unit tetrahedron at origin</li>
     * </ul>
     * No level offset adjustment is needed (unlike Morton codes).</p>
     *
     * <p><b>Migration Note:</b> The single-parameter tetrahedron(long index) method
     * has been removed as it was fundamentally flawed. Always provide the level.</p>
     *
     * @param index the consecutive SFC index of the tetrahedron
     * @param level the refinement level of the target tetrahedron (0-21)
     * @return the Tet corresponding to the given index and level
     * @throws IllegalArgumentException if level is out of valid range
     */
    public static Tet tetrahedron(long index, byte level) {
        if (level == 0) {
            return new Tet(0, 0, 0, (byte) 0, (byte) 0); // Root tetrahedron
        }

        byte type = 0;
        int childrenM1 = 7;  // Mask for 3 bits (8 children - 1)
        var coordinates = new int[3];

        // Traverse from root to target level
        for (int i = 1; i <= level; i++) {
            var offsetIndex = level - i;
            int cellSize = Constants.lengthAtLevel((byte) i); // Size of cell at this level

            // Extract 3 bits for the local index at this level
            var localIndex = (int) ((index >> (3 * offsetIndex)) & childrenM1);

            // Look up cube position and child type from connectivity tables
            var cid = PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID[type][localIndex];
            type = PARENT_TYPE_LOCAL_INDEX_TO_TYPE[type][localIndex];

            // Accumulate actual coordinates based on cube position
            // cellSize is the size of cells at level i, so we add cellSize when the bit is set
            if ((cid & 1) > 0) {
                coordinates[0] += cellSize;
            }
            if ((cid & 2) > 0) {
                coordinates[1] += cellSize;
            }
            if ((cid & 4) > 0) {
                coordinates[2] += cellSize;
            }
        }
        return new Tet(coordinates[0], coordinates[1], coordinates[2], level, type);
    }

    public static Tet tetrahedron(TetreeKey<? extends TetreeKey<?>> key) {
        return tetrahedron(key.getLowBits(), key.getHighBits(), key.getLevel());
    }

    /**
     * Convert TM-index back to tetrahedron. This is the inverse of the tmIndex() method and properly decodes the
     * interleaved coordinate and type information.
     */
    public static Tet tetrahedron(long lowBits, long highBits, byte level) {
        if (level == 0) {
            return new Tet(0, 0, 0, (byte) 0, (byte) 0); // Root tetrahedron
        }

        // We only need to process 'level' number of 6-bit chunks
        int maxBits = level;

        // Extract interleaved bits from TM-index
        int[] coordXBits = new int[maxBits];
        int[] coordYBits = new int[maxBits];
        int[] coordZBits = new int[maxBits];
        int[] types = new int[maxBits];

        // We support up to level 21 with 128-bit representation
        if (level > MortonCurve.MAX_REFINEMENT_LEVEL) {
            throw new IllegalArgumentException("Level " + level + " exceeds maximum supported level 21");
        }

        // Extract 6-bit chunks. Coarsest-at-MSB layout (Luciferase-tkvb): step i (i=0 shallowest)
        // sits at bit offset 6*(level-1-i) from the LSB across the 128-bit (highBits, lowBits) value,
        // and may straddle the 64-bit boundary.
        for (int i = 0; i < maxBits; i++) {
            int bit = (maxBits - 1 - i) * 6; // offset from LSB
            int sixBits;
            if (bit >= 64) {
                sixBits = (int) ((highBits >>> (bit - 64)) & 0x3F);
            } else if (bit + 6 <= 64) {
                sixBits = (int) ((lowBits >>> bit) & 0x3F);
            } else {
                // Straddles the low/high boundary.
                long lowPart = lowBits >>> bit;
                long highPart = highBits << (64 - bit);
                sixBits = (int) ((lowPart | highPart) & 0x3F);
            }

            // Lower 3 bits are type
            types[i] = sixBits & 7;

            // Upper 3 bits are coordinate bits
            int coordBits = sixBits >> 3;
            coordXBits[i] = coordBits & 1;
            coordYBits[i] = (coordBits >> 1) & 1;
            coordZBits[i] = (coordBits >> 2) & 1;
        }

        // Reconstruct coordinates from bits
        // Place bits at the correct positions: [MAX_LEVEL-1, MAX_LEVEL-2, ..., MAX_LEVEL-L]
        int x = 0, y = 0, z = 0;

        // Build coordinates by placing bits at the correct positions
        // The bits were extracted LSB to MSB (i=0 is MSB in grid coordinates)
        // So we need to place them from MSB to LSB in the result
        for (int i = 0; i < maxBits; i++) {
            int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
            x |= (coordXBits[i] << bitPos);
            y |= (coordYBits[i] << bitPos);
            z |= (coordZBits[i] << bitPos);
        }

        // Current type is at the last position
        byte type = (byte) types[maxBits - 1];

        // Ensure coordinates are properly aligned to the grid for this level
        // At level L, coordinates must be multiples of cellSize = 1 << (21 - L)
        // This means the lower (21 - L) bits must be zero
        int cellSize = Constants.lengthAtLevel(level);

        // Convert from bit-level coordinates to actual grid coordinates
        // The extracted bits represent the path from root, so we need to scale them appropriately
        x = (x >> (Constants.getMaxRefinementLevel() - level)) * cellSize;
        y = (y >> (Constants.getMaxRefinementLevel() - level)) * cellSize;
        z = (z >> (Constants.getMaxRefinementLevel() - level)) * cellSize;

        return new Tet(x, y, z, level, type);
    }

    // Check if a tetrahedron is completely contained within a volume
    public static boolean tetrahedronContainedInVolume(Tet tet, Spatial volume) {
        var vertices = tet.coordinates();
        var bounds = VolumeBounds.from(volume);
        if (bounds == null) {
            return false;
        }

        // Simple AABB containment test - all vertices must be within bounds
        for (var vertex : vertices) {
            if (vertex.x < bounds.minX() || vertex.x > bounds.maxX() || vertex.y < bounds.minY()
            || vertex.y > bounds.maxY() || vertex.z < bounds.minZ() || vertex.z > bounds.maxZ()) {
                return false;
            }
        }
        return true;
    }

    // Check if a tetrahedron is completely contained within volume bounds
    public static boolean tetrahedronContainedInVolumeBounds(Tet tet, VolumeBounds bounds) {
        var vertices = tet.coordinates();

        // All vertices must be within bounds for complete containment
        for (var vertex : vertices) {
            if (vertex.x < bounds.minX() || vertex.x > bounds.maxX() || vertex.y < bounds.minY()
            || vertex.y > bounds.maxY() || vertex.z < bounds.minZ() || vertex.z > bounds.maxZ()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates that the given coordinates represent correct anchor coordinates for the specified level and type.
     *
     * @param x     X coordinate
     * @param y     Y coordinate
     * @param z     Z coordinate
     * @param level refinement level
     * @param type  tetrahedron type
     * @throws IllegalArgumentException if coordinates are not valid anchor coordinates
     */
    private static boolean validateAnchorCoordinates(int x, int y, int z, byte level, byte type) {
        // Special case: root tetrahedron must be at origin with type 0
        if (level == 0) {
            if (x != 0 || y != 0 || z != 0) {
                throw new IllegalArgumentException(
                "Root tetrahedron (level 0) must be at origin (0,0,0), got: (" + x + ", " + y + ", " + z + ")");
            }
            if (type != 0) {
                throw new IllegalArgumentException("Root tetrahedron (level 0) must have type 0, got: " + type);
            }
            return true;
        }

        // Check coordinates are aligned to the grid at this level
        int cellSize = Constants.lengthAtLevel(level);
        if (x % cellSize != 0) {
            throw new IllegalArgumentException(
            "X coordinate " + x + " is not aligned to grid at level " + level + " (cell size " + cellSize + ")");
        }
        if (y % cellSize != 0) {
            throw new IllegalArgumentException(
            "Y coordinate " + y + " is not aligned to grid at level " + level + " (cell size " + cellSize + ")");
        }
        if (z % cellSize != 0) {
            throw new IllegalArgumentException(
            "Z coordinate " + z + " is not aligned to grid at level " + level + " (cell size " + cellSize + ")");
        }

        // Validate coordinates are within bounds for this level
        int maxCoord = Constants.lengthAtLevel((byte) 0); // Maximum extent of the root tetrahedron
        if (x >= maxCoord) {
            throw new IllegalArgumentException(
            "X coordinate " + x + " exceeds maximum extent " + maxCoord + " for level " + level);
        }
        if (y >= maxCoord) {
            throw new IllegalArgumentException(
            "Y coordinate " + y + " exceeds maximum extent " + maxCoord + " for level " + level);
        }
        if (z >= maxCoord) {
            throw new IllegalArgumentException(
            "Z coordinate " + z + " exceeds maximum extent " + maxCoord + " for level " + level);
        }

        // For deeper validation, we could verify that the type is consistent with the coordinate path
        // from the root, but this would be computationally expensive and is better left to
        // the createValidated() factory method when full validation is needed.
        return true;
    }

    public Point3i anchor() {
        return new Point3i(x, y, z);
    }

    // -------------------------------------------------------------------------
    // Spatial.aabt implementation
    // -------------------------------------------------------------------------

    /**
     * Answer true if the given point is contained within this tetrahedron.
     * Delegates to the ultra-fast containment check.
     */
    @Override
    public boolean contains(float px, float py, float pz) {
        return contains12DOP(px, py, pz);
    }

    /**
     * Answer true if the given bounding volume is completely contained within this tetrahedron.
     * Checks all vertices of {@code other} using the tetrahedral containment test.
     */
    @Override
    public boolean containsBound(Spatial.aabt other) {
        for (var v : other.vertices()) {
            if (!contains12DOP(v[0], v[1], v[2])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Answer true if this tetrahedron intersects the given bounding volume.
     * <ul>
     *   <li>If {@code other} is a {@link Tet}, uses the 12-DOP tet-vs-tet test via
     *       {@link #intersectsTet12DOP}.</li>
     *   <li>Otherwise uses the 12-DOP AABB-vs-tet test via {@link #intersects12DOP}.</li>
     * </ul>
     */
    @Override
    public boolean intersectsBound(Spatial.aabt other) {
        if (other instanceof Tet otherTet) {
            return intersectsTet12DOP(otherTet);
        }
        var b = other.toVolumeBounds();
        return intersects12DOP(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    /**
     * Return the four vertices of this tetrahedron as a float[][] array.
     * Converts the Point3i coordinates to float arrays.
     */
    @Override
    public float[][] vertices() {
        var pts = coordinates();
        return new float[][] { { pts[0].x, pts[0].y, pts[0].z }, { pts[1].x, pts[1].y, pts[1].z },
                               { pts[2].x, pts[2].y, pts[2].z }, { pts[3].x, pts[3].y, pts[3].z } };
    }

    /**
     * Return the axis-aligned bounding box of this tetrahedron.
     */
    @Override
    public VolumeBounds toVolumeBounds() {
        var pts = coordinates();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (var p : pts) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        return new VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Answer true if this tetrahedron is completely contained within the given aabt bounds.
     * Delegates to {@code bounds.containsBound(this)}.
     */
    @Override
    public boolean containedBy(Spatial.aabt bounds) {
        return bounds.containsBound(this);
    }

    /**
     * Answer true if this tetrahedron intersects the given AABB defined by origin and extent corners.
     */
    @Override
    public boolean intersects(float oX, float oY, float oZ, float eX, float eY, float eZ) {
        return intersects12DOP(oX, oY, oZ, eX, eY, eZ);
    }

    /**
     * @param volume - the enclosing volume
     * @return the Stream of TetreeKeys locating the Tets bounded by the volume
     */
    public Stream<TetreeKey<?>> boundedBy(Spatial volume) {
        var bounds = VolumeBounds.from(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        return spatialRangeQueryKeys(bounds, false).filter(key -> {
            var tet = Tet.tetrahedron(key);
            return tetrahedronContainedInVolume(tet, volume);
        });
    }

    /**
     * @param volume the volume to contain
     * @return the Stream of TetreeKeys locating the Tets that minimally bound the volume
     */
    public Stream<TetreeKey<?>> bounding(Spatial volume) {
        var bounds = VolumeBounds.from(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        return spatialRangeQueryKeys(bounds, true).filter(key -> {
            var tet = Tet.tetrahedron(key);
            return tet.intersects12DOP(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(),
                                       bounds.maxZ());
        });
    }

    /**
     * AABT-based range query: walks the grid at this tet's level and returns keys for all tetrahedra that
     * pass SAT-based intersection with the given bounding volume. Compared to the AABB path (which emits all
     * 6 tet types per qualifying grid cell), this method tests each tet individually using
     * {@link Spatial.aabt#intersectsBound(Spatial.aabt)} and only emits those that pass.
     *
     * <p>This is the AABT spike implementation — it adds a parallel path alongside the existing AABB traversal.
     * It does NOT replace the AABB path.</p>
     *
     * @param queryBound the query volume as an aabt (may be a Box or a Tet)
     * @return stream of TetreeKeys for tets that intersect the query bound via SAT
     */
    public Stream<TetreeKey<?>> intersectingBound(Spatial.aabt queryBound) {
        return aabtSpatialRangeQueryKeys(queryBound);
    }

    /**
     * Hierarchical AABT traversal: like the AABB path but filters individual tet types per cell
     * using {@link Tet#intersects12DOPStatic} (SAT) instead of emitting all 6 per cell.
     * <p>
     * Allocation profile: 0 Tet allocations for rejected cells; 1 Tet allocation per passing type
     * (required for tmIndex). The 12-DOP rejection is done allocation-free via the static helper.
     */
    Stream<TetreeKey<?>> aabtSpatialRangeQueryKeys(Spatial.aabt queryBound) {
        var bounds = queryBound.toVolumeBounds();
        final int length = Constants.lengthAtLevel(this.l);
        final float qMinX = bounds.minX(), qMinY = bounds.minY(), qMinZ = bounds.minZ();
        final float qMaxX = bounds.maxX(), qMaxY = bounds.maxY(), qMaxZ = bounds.maxZ();

        // Compute grid cell range covering the query AABB
        int gridMinX = (int) Math.floor(qMinX / length);
        int gridMaxX = (int) Math.ceil(qMaxX / length);
        int gridMinY = (int) Math.floor(qMinY / length);
        int gridMaxY = (int) Math.ceil(qMaxY / length);
        int gridMinZ = (int) Math.floor(qMinZ / length);
        int gridMaxZ = (int) Math.ceil(qMaxZ / length);

        List<TetreeKey<?>> keys = new ArrayList<>();
        for (int xi = gridMinX; xi <= gridMaxX; xi++) {
            int cx = xi * length;
            // Quick AABB cell reject on X
            if (cx + length < qMinX || cx > qMaxX)
                continue;
            for (int yi = gridMinY; yi <= gridMaxY; yi++) {
                int cy = yi * length;
                // Quick AABB cell reject on Y
                if (cy + length < qMinY || cy > qMaxY)
                    continue;
                for (int zi = gridMinZ; zi <= gridMaxZ; zi++) {
                    int cz = zi * length;
                    // Quick AABB cell reject on Z
                    if (cz + length < qMinZ || cz > qMaxZ)
                        continue;
                    // SAT per-tet filter — allocation-free
                    for (int t = 0; t < 6; t++) {
                        if (intersects12DOPStatic(cx, cy, cz, this.l, t, qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ)) {
                            keys.add(new Tet(cx, cy, cz, this.l, (byte) t).tmIndex());
                        }
                    }
                }
            }
        }
        return keys.stream();
    }

    /**
     * Generate the i-th child of this tetrahedron using Bey's refinement scheme.
     *
     * <p><b>CRITICAL ALGORITHM (from t8code):</b></p>
     * This uses Bey's tetrahedral refinement which creates 8 children:
     * <ul>
     *   <li>Child 0: Interior tetrahedron at parent's anchor</li>
     *   <li>Children 1-7: Corner tetrahedra at vertex midpoints</li>
     * </ul>
     *
     * <p><b>Key Steps:</b></p>
     * <ol>
     *   <li>Convert Morton index (0-7) to Bey child ID</li>
     *   <li>Look up child type from connectivity table</li>
     *   <li>For child 0: use parent anchor directly</li>
     *   <li>For children 1-7: anchor = midpoint(parent_anchor, parent_vertex)</li>
     * </ol>
     *
     * <p><b>WARNING:</b> This is NOT cube-based subdivision!</p>
     * The child positions are determined by vertex midpoints, not cube offsets.
     *
     * <p><b>Performance Note (July 2025):</b> This method now uses
     * {@link BeySubdivision#getMortonChild(Tet, int)} internally, which is ~3x faster
     * than the previous implementation. It only computes the midpoints needed for the
     * requested child, avoiding unnecessary calculations.</p>
     *
     * @param childIndex Morton ordering index (0-7)
     * @return the child tetrahedron
     * @throws IllegalArgumentException if childIndex not in [0,7]
     * @throws IllegalStateException    if already at max refinement level
     */
    public Tet child(int childIndex) {
        if (childIndex < 0 || childIndex >= TetreeConnectivity.CHILDREN_PER_TET) {
            throw new IllegalArgumentException("Child index must be 0-7: " + childIndex);
        }
        if (l >= getMaxRefinementLevel()) {
            throw new IllegalStateException("Cannot create children at max refinement level");
        }

        // Use the efficient BeySubdivision method which produces identical results
        // This is ~3x faster than the previous implementation when computing single children
        var child = BeySubdivision.getMortonChild(this, childIndex);
        // RDR-010 (Knapp Algorithm 4.2b): a tetrahedral child of a tetrahedron inherits its
        // minTetLevel. Pure-Tetree (NO_TET_ANCESTOR) returns the child unchanged.
        return minTetLevel == NO_TET_ANCESTOR ? child : child.withMinTetLevel(minTetLevel);
    }

    /**
     * The smallest level at which an ancestor is a tetrahedron, or {@link #NO_TET_ANCESTOR} (-1) for
     * pure-Tetree elements (RDR-010, Knapp Algorithm 4.1).
     */
    public byte minTetLevel() {
        return minTetLevel;
    }

    /**
     * This tetrahedron with a different {@code minTetLevel} (same geometry). RDR-010 metadata carrier.
     */
    public Tet withMinTetLevel(byte newMinTetLevel) {
        return newMinTetLevel == minTetLevel ? this : new Tet(x, y, z, l, type, newMinTetLevel);
    }

    /**
     * Compare two tetrahedra for SFC ordering
     *
     * @param other the tetrahedron to compare to
     * @return negative if this < other, 0 if equal, positive if this > other
     */
    public int compareElements(Tet other) {
        // Compare by SFC index
        var thisKey = this.tmIndex();
        var otherKey = other.tmIndex();
        // Since we're comparing two TetreeKey instances, we need to handle the wildcard
        @SuppressWarnings({ "unchecked", "rawtypes" })
        int result = thisKey.compareTo(otherKey);
        return result;
    }

    /**
     * Compute the type of this tetrahedron's ancestor at a given level.
     *
     * <p>This walks UP from this tet's type to the target level using t8code's
     * {@code CID_TYPE_TO_PARENTTYPE} table (RDR-010 Luciferase-4pd), consistent with
     * {@link #parent()}, {@link #consecutiveIndex()} and {@link #tmIndex()}.</p>
     *
     * @param level the target level (0 to this.l)
     * @return the type at that level
     */
    public byte computeType(byte level) {
        assert (0 <= level && level <= l);

        // RDR-010: for a tetrahedron descending from a pyramidal root the "trace from root type 0"
        // assumption below is wrong above the tet/pyramid boundary (ancestor types are pyramid 6/7,
        // determined via Knapp 2026 §4.1). Computing those requires the pyramid ancestor-type
        // machinery deferred to the element-type unification (Luciferase-q3p). Fail loud rather than
        // return a wrong Bey-path type. Pure-Tetree (NO_TET_ANCESTOR) is unaffected.
        if (minTetLevel != NO_TET_ANCESTOR) {
            throw new IllegalStateException(
            "computeType on a pyramid-rooted tetrahedron (minTetLevel != -1) requires pyramid "
            + "ancestor-type resolution; deferred to Luciferase-q3p");
        }

        if (level == l) {
            return type;
        }
        if (level == 0) {
            return 0; // Root is always type 0
        }

        // RDR-010 Luciferase-4pd: walk UP from this tet's type using t8code's canonical
        // cid_type_to_parenttype table (the downward root-trace mis-indexed the bey table by parent
        // type and is not t8code-consistent). Ancestor type at `level` = repeated parent type.
        byte currentType = type;
        for (byte lvl = l; lvl > level; lvl--) {
            int h = 1 << (getMaxRefinementLevel() - lvl);
            int cubeId = ((x & h) != 0 ? 1 : 0) | ((y & h) != 0 ? 2 : 0) | ((z & h) != 0 ? 4 : 0);
            currentType = TetreeConnectivity.CID_TYPE_TO_PARENTTYPE[cubeId][currentType];
        }

        return currentType;
    }

    /**
     * Compute the consecutive index of this tetrahedron at this level.
     *
     * <p><b>Algorithm Overview:</b></p>
     * Encodes the path from root to this tetrahedron by computing types at each level via the t8code
     * upward {@code CID_TYPE_TO_PARENTTYPE} walk (RDR-010 Luciferase-4pd), then converting cubeId to
     * local index.
     *
     * <p><b>CRITICAL:</b> The consecutive index encodes the complete path with NO level offset.</p>
     * Each level contributes exactly 3 bits to the final index.
     *
     * <p><b>CRITICAL:</b> The consecutive index is not unique across levels. thus this index
     * does <b>not</b> implement a space filling curve.  This index is not a replacement
     * for the <code>tmIndex()</code> method.</p>
     *
     * @return the consecutive index (0 for root, 1-7 for level 1, etc.)
     */
    public long consecutiveIndex() {
        // Try cache first for O(1) lookup
        long cachedIndex = TetreeLevelCache.getCachedIndex(x, y, z, l, type);
        if (cachedIndex != -1) {
            return cachedIndex;
        }

        assert (0 <= l && l <= getMaxRefinementLevel());

        // Special case: root
        if (l == 0) {
            return 0;
        }

        // RDR-010 Luciferase-4pd: pre-compute types at each level by walking UP from this tet's type
        // via t8code's cid_type_to_parenttype (canonical t8code typing; the old downward root-trace
        // mis-indexed the bey table and is not t8code-consistent).
        byte[] typesAtLevel = new byte[l + 1];
        typesAtLevel[l] = type;
        for (byte lvl = l; lvl > 0; lvl--) {
            int h = 1 << (getMaxRefinementLevel() - lvl);
            int cubeId = ((x & h) != 0 ? 1 : 0) | ((y & h) != 0 ? 2 : 0) | ((z & h) != 0 ? 4 : 0);
            typesAtLevel[lvl - 1] = TetreeConnectivity.CID_TYPE_TO_PARENTTYPE[cubeId][typesAtLevel[lvl]];
        }

        // Now compute index by traversing from this level back to root
        long id = 0;
        int exponent = 0;

        for (int i = l; i > 0; i--) {
            byte cid = cubeId((byte) i);
            byte typeAtLevel = typesAtLevel[i];

            // Convert to local index using connectivity table
            id |= ((long) TYPE_CUBE_ID_TO_LOCAL_INDEX[typeAtLevel][cid]) << exponent;
            exponent += 3;
        }

        // Cache the result for future lookups
        TetreeLevelCache.cacheIndex(x, y, z, l, type, id);

        return id;
    }

    public boolean contains(Tuple3f point) {
        return contains12DOP(point.x, point.y, point.z);
    }

    /**
     * 12-DOP exact containment test using the permutohedron ordering structure. AABB check (6 comparisons) + local
     * coordinate subtraction (3 ops) + type-specific ordering test (2 comparisons) = 11 ops total.
     * <p>
     * The ordering for each type is derived from the Kuhn simplex vertex paths in {@link #coordinates()}. Uses
     * closed-simplex convention ({@code >=}) — points on shared faces may be contained by adjacent types.
     *
     * @param px X coordinate of the point to test
     * @param py Y coordinate of the point to test
     * @param pz Z coordinate of the point to test
     * @return true if the point is inside this tetrahedron
     */
    public boolean contains12DOP(float px, float py, float pz) {
        final int h = 1 << (Constants.getMaxRefinementLevel() - l);
        // AABB early-out (6 comparisons)
        if (px < x || px > x + h || py < y || py > y + h || pz < z || pz > z + h)
            return false;
        // Local coordinates (3 subtractions)
        float u = px - x, v = py - y, w = pz - z;
        // Ordering test (2 comparisons) — t8code dtet vertex geometry (Kuhn simplex edge paths).
        // RDR-010 Luciferase-4pd: type k IS t8code dtet type k. These orderings are the canonical
        // per-type interior orderings of the 6 Kuhn simplices (6 axes, same op count).
        return switch (type) {
            case 0 -> u >= w && w >= v;  // x ≥ z ≥ y
            case 1 -> u >= v && v >= w;  // x ≥ y ≥ z
            case 2 -> v >= u && u >= w;  // y ≥ x ≥ z
            case 3 -> v >= w && w >= u;  // y ≥ z ≥ x
            case 4 -> w >= v && v >= u;  // z ≥ y ≥ x
            case 5 -> w >= u && u >= v;  // z ≥ x ≥ y
            default -> throw new IllegalStateException("Invalid type: " + type);
        };
    }

    /**
     * 12-DOP tet-vs-tet intersection test. Two tetrahedra intersect iff their projections overlap
     * on all 6 axes: 3 AABB axes and 3 difference axes (d_xy, d_xz, d_yz).
     * <p>
     * Each t8code dtet type's difference-axis slab is either {@code [0,h]} (+, sign 0) or
     * {@code [-h,0]} (-, sign 1). RDR-010 Luciferase-4pd type numbering — this table mirrors
     * {@link #slabBounds12DOP} exactly (the old pre-migration S0-S5 sign table was wrong here):
     * <pre>
     *   t0: d_xy=+, d_xz=+, d_yz=-
     *   t1: d_xy=+, d_xz=+, d_yz=+
     *   t2: d_xy=-, d_xz=+, d_yz=+
     *   t3: d_xy=-, d_xz=-, d_yz=+
     *   t4: d_xy=-, d_xz=-, d_yz=-
     *   t5: d_xy=+, d_xz=-, d_yz=-
     * </pre>
     * Global slab for axis diff = anchor_diff, sign s, size h:
     * {@code lo = diff - s*h,  hi = diff + (1-s)*h}.
     * Overlap (closed convention ≥): {@code lo1 <= hi2 && lo2 <= hi1}.
     * <p>
     * Uses closed-simplex convention matching {@link #contains12DOP} — face-touching tets count
     * as intersecting.
     *
     * @param other the other tetrahedron
     * @return true if the two tetrahedra intersect (including face/edge/vertex contact)
     */
    public boolean intersectsTet12DOP(Tet other) {
        final int h1 = 1 << (Constants.getMaxRefinementLevel() - l);
        final int h2 = 1 << (Constants.getMaxRefinementLevel() - other.l);
        // AABB overlap (3 axes, 6 comparisons)
        if (x + h1 < other.x || other.x + h2 < x) return false;
        if (y + h1 < other.y || other.y + h2 < y) return false;
        if (z + h1 < other.z || other.z + h2 < z) return false;
        // Difference-axis slab overlap
        int[] s1 = slabBounds12DOP(x, y, z, l, type);
        int[] s2 = slabBounds12DOP(other.x, other.y, other.z, other.l, other.type);
        // d_xy: s1=[lo0,hi0], s2=[lo1,hi1]
        if (s1[0] > s2[1] || s2[0] > s1[1]) return false;
        // d_xz
        if (s1[2] > s2[3] || s2[2] > s1[3]) return false;
        // d_yz
        if (s1[4] > s2[5] || s2[4] > s1[5]) return false;
        return true;
    }

    /**
     * Returns the global 12-DOP slab bounds for a tetrahedron as an int[6]:
     * {@code [lo_xy, hi_xy, lo_xz, hi_xz, lo_yz, hi_yz]}.
     * <p>
     * For sign s (0 = [0,h], 1 = [-h,0]):
     * {@code lo = anchor_diff - s*h},  {@code hi = anchor_diff + (1-s)*h}.
     *
     * @param ax    anchor x
     * @param ay    anchor y
     * @param az    anchor z
     * @param level refinement level
     * @param type  t8code dtet type (0-5)
     * @return int[6] of global slab bounds
     */
    private static int[] slabBounds12DOP(int ax, int ay, int az, byte level, byte type) {
        final int h = 1 << (Constants.getMaxRefinementLevel() - level);
        final int dxy = ax - ay;
        final int dxz = ax - az;
        final int dyz = ay - az;
        // sign encoding: 0 means slab [0,h] (lo=diff, hi=diff+h)
        //                1 means slab [-h,0] (lo=diff-h, hi=diff)
        // RDR-010 Luciferase-4pd: t8code dtet typing — per-type signs are the canonical t8code dtet
        // type-k slab signs (see the table on intersectsTet12DOP).
        return switch (type) {
            case 0 -> // d_xy=+, d_xz=+, d_yz=-
                new int[]{ dxy,     dxy + h, dxz,     dxz + h, dyz - h, dyz     };
            case 1 -> // d_xy=+, d_xz=+, d_yz=+
                new int[]{ dxy,     dxy + h, dxz,     dxz + h, dyz,     dyz + h };
            case 2 -> // d_xy=-, d_xz=+, d_yz=+
                new int[]{ dxy - h, dxy,     dxz,     dxz + h, dyz,     dyz + h };
            case 3 -> // d_xy=-, d_xz=-, d_yz=+
                new int[]{ dxy - h, dxy,     dxz - h, dxz,     dyz,     dyz + h };
            case 4 -> // d_xy=-, d_xz=-, d_yz=-
                new int[]{ dxy - h, dxy,     dxz - h, dxz,     dyz - h, dyz     };
            case 5 -> // d_xy=+, d_xz=-, d_yz=-
                new int[]{ dxy,     dxy + h, dxz - h, dxz,     dyz - h, dyz     };
            default -> throw new IllegalStateException("Invalid type: " + type);
        };
    }

    /**
     * 12-DOP intersection test between this tetrahedron and an axis-aligned bounding box. Tests overlap on all 6
     * DOP axes: 3 AABB axes + 3 difference axes {x-y, x-z, y-z}.
     * <p>
     * Cost: ~21 ops (6 AABB comparisons + 6 entity projections + 3 anchor differences + 6 slab comparisons).
     *
     * @param exMin minimum X of the entity AABB
     * @param eyMin minimum Y of the entity AABB
     * @param ezMin minimum Z of the entity AABB
     * @param exMax maximum X of the entity AABB
     * @param eyMax maximum Y of the entity AABB
     * @param ezMax maximum Z of the entity AABB
     * @return true if the AABB intersects this tetrahedron's 12-DOP
     */
    public boolean intersects12DOP(float exMin, float eyMin, float ezMin, float exMax, float eyMax, float ezMax) {
        final int h = 1 << (Constants.getMaxRefinementLevel() - l);
        // Step 1: AABB overlap (6 comparisons)
        if (exMax < x || exMin > x + h || eyMax < y || eyMin > y + h || ezMax < z || ezMin > z + h)
            return false;
        // Step 2: Project entity AABB onto difference axes (6 subtractions)
        float dxyMin = exMin - eyMax, dxyMax = exMax - eyMin;
        float dxzMin = exMin - ezMax, dxzMax = exMax - ezMin;
        float dyzMin = eyMin - ezMax, dyzMax = eyMax - ezMin;
        // Step 3: Compute tet's global slab ranges and check overlap (6 comparisons)
        // Local slab [0,h] or [-h,0] is shifted by anchor differences (axy = x-y, etc.)
        int axy = x - y, axz = x - z, ayz = y - z;
        // RDR-010 Luciferase-4pd: t8code dtet typing — per-type slabs are the canonical t8code dtet
        // type-k slabs (see the table on intersectsTet12DOP).
        return switch (type) {
            case 0 ->
                dxyMax >= axy && dxyMin <= axy + h && dxzMax >= axz && dxzMin <= axz + h && dyzMax >= ayz - h
                && dyzMin <= ayz;
            case 1 ->
                dxyMax >= axy && dxyMin <= axy + h && dxzMax >= axz && dxzMin <= axz + h && dyzMax >= ayz
                && dyzMin <= ayz + h;
            case 2 ->
                dxyMax >= axy - h && dxyMin <= axy && dxzMax >= axz && dxzMin <= axz + h && dyzMax >= ayz
                && dyzMin <= ayz + h;
            case 3 ->
                dxyMax >= axy - h && dxyMin <= axy && dxzMax >= axz - h && dxzMin <= axz && dyzMax >= ayz
                && dyzMin <= ayz + h;
            case 4 ->
                dxyMax >= axy - h && dxyMin <= axy && dxzMax >= axz - h && dxzMin <= axz && dyzMax >= ayz - h
                && dyzMin <= ayz;
            case 5 ->
                dxyMax >= axy && dxyMin <= axy + h && dxzMax >= axz - h && dxzMin <= axz && dyzMax >= ayz - h
                && dyzMin <= ayz;
            default -> throw new IllegalStateException("Invalid type: " + type);
        };
    }

    /**
     * Static 12-DOP intersection test. Equivalent to {@link #intersects12DOP} but requires no Tet allocation.
     * Tests overlap on all 6 DOP axes: 3 AABB axes + 3 difference axes {x-y, x-z, y-z}.
     * <p>
     * Cost: ~21 ops (6 AABB comparisons + 6 entity projections + 3 anchor differences + 6 slab comparisons).
     *
     * @param x     anchor X of the tet cell (world coordinates)
     * @param y     anchor Y of the tet cell (world coordinates)
     * @param z     anchor Z of the tet cell (world coordinates)
     * @param l     refinement level
     * @param type  tet type in [0,5]
     * @param exMin minimum X of the query AABB
     * @param eyMin minimum Y of the query AABB
     * @param ezMin minimum Z of the query AABB
     * @param exMax maximum X of the query AABB
     * @param eyMax maximum Y of the query AABB
     * @param ezMax maximum Z of the query AABB
     * @return true if the query AABB intersects the tet's 12-DOP
     */
    static boolean intersects12DOPStatic(int x, int y, int z, byte l, int type,
                                         float exMin, float eyMin, float ezMin,
                                         float exMax, float eyMax, float ezMax) {
        final int h = 1 << (Constants.getMaxRefinementLevel() - l);
        // Step 1: AABB overlap (6 comparisons)
        if (exMax < x || exMin > x + h || eyMax < y || eyMin > y + h || ezMax < z || ezMin > z + h)
            return false;
        // Step 2: Project entity AABB onto difference axes (6 subtractions)
        float dxyMin = exMin - eyMax, dxyMax = exMax - eyMin;
        float dxzMin = exMin - ezMax, dxzMax = exMax - ezMin;
        float dyzMin = eyMin - ezMax, dyzMax = eyMax - ezMin;
        // Step 3: Compute tet's global slab ranges and check overlap (6 comparisons)
        int axy = x - y, axz = x - z, ayz = y - z;
        // RDR-010 Luciferase-4pd: t8code dtet typing — per-type slabs are the canonical t8code dtet
        // type-k slabs (see the table on intersectsTet12DOP).
        return switch (type) {
            case 0 ->
                dxyMax >= axy && dxyMin <= axy + h && dxzMax >= axz && dxzMin <= axz + h && dyzMax >= ayz - h
                && dyzMin <= ayz;
            case 1 ->
                dxyMax >= axy && dxyMin <= axy + h && dxzMax >= axz && dxzMin <= axz + h && dyzMax >= ayz
                && dyzMin <= ayz + h;
            case 2 ->
                dxyMax >= axy - h && dxyMin <= axy && dxzMax >= axz && dxzMin <= axz + h && dyzMax >= ayz
                && dyzMin <= ayz + h;
            case 3 ->
                dxyMax >= axy - h && dxyMin <= axy && dxzMax >= axz - h && dxzMin <= axz && dyzMax >= ayz
                && dyzMin <= ayz + h;
            case 4 ->
                dxyMax >= axy - h && dxyMin <= axy && dxzMax >= axz - h && dxzMin <= axz && dyzMax >= ayz - h
                && dyzMin <= ayz;
            case 5 ->
                dxyMax >= axy && dxyMin <= axy + h && dxzMax >= axz - h && dxzMin <= axz && dyzMax >= ayz - h
                && dyzMin <= ayz;
            default -> throw new IllegalStateException("Invalid type: " + type);
        };
    }




    /**
     * Answer the 3D coordinates of the tetrahedron represented by the receiver Using t8code's canonical vertex
     * coordinate algorithm
     *
     * @return the 3D coordinates of the tetrahedron described by the receiver
     */
    public Point3i[] coordinates() {
        var coords = new Point3i[4];
        var h = length();

        // t8code dtet canonical Kuhn-simplex vertices (RDR-010 Luciferase-4pd alignment): type k IS
        // t8code dtet type k. v0 = anchor, v3 = opposite cube corner (shared cube diagonal); v1, v2
        // walk dimensions ei, ej. See t8_dtet_compute_coords (origin/main).
        int ei = type / 2;
        int ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3;

        coords[0] = new Point3i(x, y, z);                  // v0: anchor
        coords[1] = new Point3i(x, y, z);                  // v1: anchor + h@ei
        addToDimension(coords[1], ei, h);
        coords[2] = new Point3i(coords[1].x, coords[1].y, coords[1].z); // v2: v1 + h@ej
        addToDimension(coords[2], ej, h);
        coords[3] = new Point3i(x + h, y + h, z + h);      // v3: opposite cube corner

        return coords;
    }

    /**
     * Legacy coordinate calculation using t8code algorithm. This method is preserved for reference and testing
     * compatibility.
     *
     * @deprecated Use {@link #coordinates()} which implements the t8code dtet vertex formula
     *             (RDR-010 Luciferase-4pd). Note: this legacy method's v3 differs from t8code's
     *             (v3 = opposite cube corner) and is retained only for reference.
     */
    @Deprecated
    public Point3i[] coordinatesLegacy() {
        var coords = new Point3i[4];
        var h = length();

        // t8code algorithm: ei = type / 2, ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3
        int ei = type / 2;
        int ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3;

        // vertex 0: anchor coordinates (x, y, z)
        coords[0] = new Point3i(x, y, z);

        // vertex 1: anchor + h in dimension ei
        coords[1] = new Point3i(x, y, z);
        addToDimension(coords[1], ei, h);

        // vertex 2: anchor + h in dimension ei + h in dimension ej
        coords[2] = new Point3i(x, y, z);
        addToDimension(coords[2], ei, h);
        addToDimension(coords[2], ej, h);

        // vertex 3: anchor + h in dimensions (ei+1)%3 and (ei+2)%3
        coords[3] = new Point3i(x, y, z);
        addToDimension(coords[3], (ei + 1) % 3, h);
        addToDimension(coords[3], (ei + 2) % 3, h);

        return coords;
    }

    public byte cubeId() {
        return cubeId(l);
    }

    /**
     * @return the cube id of t's ancestor of level "level"
     */
    public byte cubeId(byte level) {
        if (level < 0 || level > getMaxRefinementLevel()) {
            throw new IllegalArgumentException("Illegal level: " + level);
        }
        if (level > l) {
            return 0;
        }
        int h = 1 << (getMaxRefinementLevel() - level);
        byte id = 0;
        id |= ((x & h) != 0 ? (byte) 1 : 0);
        id |= ((y & h) != 0 ? (byte) 2 : 0);
        id |= ((z & h) != 0 ? (byte) 4 : 0);
        return id;
    }

    /**
     * @param volume - the volume to enclose
     * @return - index in the SFC of the minimum Tet enclosing the volume
     */
    public TetreeKey<? extends TetreeKey<?>> enclosing(Spatial volume) {
        // Extract bounding box of the volume
        var bounds = VolumeBounds.from(volume);
        if (bounds == null) {
            return TetreeKey.getRoot();
        }

        // Find the minimum level that can contain the volume
        byte level = findMinimumContainingLevel(bounds);

        // Find a tetrahedron at that level that contains the volume
        var centerPoint = new Point3f((bounds.minX() + bounds.maxX()) / 2, (bounds.minY() + bounds.maxY()) / 2,
                                      (bounds.minZ() + bounds.maxZ()) / 2);

        var tet = locatePointBeyRefinementFromRoot(centerPoint.x, centerPoint.y, centerPoint.z, level);
        return tet.tmIndex();
    }

    /**
     * @param point - the point to enclose
     * @param level - refinement level for enclosure
     * @return the simplex at the provided
     */
    public TetreeKey<? extends TetreeKey<?>> enclosing(Tuple3f point, byte level) {
        var tet = locatePointBeyRefinementFromRoot(point.x, point.y, point.z, level);
        return tet.tmIndex();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Tet t)) {
            return false;
        }
        return x == t.x && y == t.y && z == t.z && l == t.l && type == t.type;
    }

    public FaceNeighbor faceNeighbor(int face) {
        // Implement t8code's face neighbor algorithm from dtri_bits.c
        // This is the 3D version (T8_DTRI_TO_DTET branch)

        assert (0 <= face && face < 4);

        int typeOld = this.type;
        int typeNew = typeOld;
        int[] coords = { this.x, this.y, this.z };
        int h = length();
        int ret = -1;

        // 3D algorithm from t8code - exact implementation
        typeNew += 6; // We want to compute modulo six and don't want negative numbers

        if (face == 1 || face == 2) {
            int sign = (typeNew % 2 == 0 ? 1 : -1);
            sign *= (face % 2 == 0 ? 1 : -1);
            typeNew += sign;
            typeNew %= 6;
            ret = face;
        } else {
            if (face == 0) {
                /* type: 0,1 --> x+1
                 *       2,3 --> y+1
                 *       4,5 --> z+1 */
                coords[typeOld / 2] += h;
                typeNew += (typeNew % 2 == 0 ? 4 : 2);
            } else { // face == 3
                /* type: 1,2 --> z-1
                 *       3,4 --> x-1
                 *       5,0 --> y-1 */
                coords[((typeNew + 3) % 6) / 2] -= h;
                typeNew += (typeNew % 2 == 0 ? 2 : 4);
            }
            typeNew %= 6;
            ret = 3 - face;
        }

        // Check if the neighbor would have negative coordinates or exceed MAX_COORD
        if (coords[0] < 0 || coords[1] < 0 || coords[2] < 0 || coords[0] > Constants.MAX_COORD
        || coords[1] > Constants.MAX_COORD || coords[2] > Constants.MAX_COORD) {
            // Return null to indicate no neighbor exists (boundary of domain)
            return null;
        }

        // At level 0 a single-tree Tetree is a single type-0 root tet (t8code: the root tet's type is hardcoded
        // to 0). t8code's t8_dtri_is_inside_root classifies a level-0 simplex as inside the root tree only when
        // type==0 (and the anchor is the origin); a type-changing root face (faces 1/2 keep the coordinates but
        // change type) names a level-0 non-type-0 tet, which is OUTSIDE the root tree. t8code expects callers to
        // run is_inside_root after t8_dtri_face_neighbour; this guard inlines that caller-side check, returning
        // null (no same-tree neighbor). (Luciferase-t6su confirmed the guard is correct — NOT spurious. Note: it
        // is load-bearing under the default assertions-disabled build, where the Tet constructor's
        // validateAnchorCoordinates assert does not fire; without this guard faceNeighbor would return a
        // FaceNeighbor wrapping a semantically-invalid level-0 non-type-0 tet. Faces 0/3 are already rejected by
        // the out-of-bounds check above.)
        if (l == 0 && typeNew != 0) {
            return null;
        }

        return new FaceNeighbor((byte) ret, new Tet(coords[0], coords[1], coords[2], l, (byte) typeNew));
    }

    /**
     * Get the first descendant at the given level
     *
     * @param level the target level (must be >= this.l)
     * @return SFC index of first descendant
     */
    public TetreeKey<? extends TetreeKey<?>> firstDescendant(byte level) {
        if (level < this.l) {
            throw new IllegalArgumentException("Target level must be >= current level");
        }
        if (level == this.l) {
            return this.tmIndex();
        }

        // The first descendant is found by repeatedly taking child 0
        // This follows the SFC ordering where child 0 has the smallest index
        var current = this;
        while (current.l < level) {
            current = current.child(0); // Always take the first child
        }
        return current.tmIndex();
    }

    /**
     * Performs geometric subdivision of this tetrahedron using Bey's subdivision scheme. All 8 children are guaranteed
     * to be contained within this tetrahedron's volume.
     *
     * This is different from the child() method which is for grid-based navigation. This method performs true geometric
     * subdivision where all children are geometrically inside the parent by construction.
     *
     * @return Array of 8 child Tet objects in TM order
     * @throws IllegalStateException if at max refinement level
     */
    public Tet[] geometricSubdivide() {
        if (l >= MortonCurve.MAX_REFINEMENT_LEVEL) {
            throw new IllegalStateException("Cannot subdivide at max refinement level");
        }

        // Use BeySubdivision which internally uses subdivisionCoordinates()
        // This provides subdivision-compatible vertices without changing the global coordinate system
        var children = BeySubdivision.subdivide(this);
        // RDR-010 (Knapp Algorithm 4.2b): tetrahedral children inherit the parent's minTetLevel.
        // BeySubdivision uses the 5-arg constructor (NO_TET_ANCESTOR), so re-apply for hybrid tets.
        // Pure-Tetree (NO_TET_ANCESTOR) returns BeySubdivision's children unchanged.
        if (minTetLevel != NO_TET_ANCESTOR) {
            for (int i = 0; i < children.length; i++) {
                children[i] = children[i].withMinTetLevel(minTetLevel);
            }
        }
        return children;
    }

    @Override
    public int hashCode() {
        int result = x;
        result = 31 * result + y;
        result = 31 * result + z;
        result = 31 * result + l;
        result = 31 * result + type;
        return result;
    }

    /**
     * Find the first tetrahedron that intersects with the given volume.
     *
     * @param volume the spatial volume to test for intersection
     * @return the TetreeKey of the first intersecting tetrahedron, or null if none found
     */
    public TetreeKey<?> intersecting(Spatial volume) {
        // Simple implementation: find first intersecting tetrahedron
        var bounds = VolumeBounds.from(volume);
        if (bounds == null) {
            return null;
        }

        return spatialRangeQueryKeys(bounds, true).filter(key -> {
            var tet = Tet.tetrahedron(key);
            return tet.intersects12DOP(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(),
                                       bounds.maxZ());
        }).findFirst().orElse(null);
    }

    /**
     * Check if this tetrahedron is valid according to t8code constraints
     *
     * @return true if the tetrahedron structure is valid
     */
    public boolean isValid() {
        // Check level bounds
        if (l < 0 || l > getMaxRefinementLevel()) {
            return false;
        }

        // Check type bounds
        if (type < 0 || type >= TetreeConnectivity.TET_TYPES) {
            return false;
        }

        // Check coordinate bounds (must be non-negative and within grid)
        if (x < 0 || y < 0 || z < 0) {
            return false;
        }

        // Check that coordinates are aligned to grid at this level
        int cellSize = Constants.lengthAtLevel(l);
        if (x % cellSize != 0 || y % cellSize != 0 || z % cellSize != 0) {
            return false;
        }

        // Check that coordinates don't exceed maximum grid size
        int maxCoord = Constants.lengthAtLevel((byte) 0);
        return x < maxCoord && y < maxCoord && z < maxCoord;
    }

    public byte l() {
        return l;
    }

    /**
     * Get the last descendant at the given level
     *
     * @param level the target level (must be >= this.l)
     * @return SFC index of last descendant
     */
    public TetreeKey<? extends TetreeKey<?>> lastDescendant(byte level) {
        if (level < this.l) {
            throw new IllegalArgumentException("Target level must be >= current level");
        }
        if (level == this.l) {
            return this.tmIndex();
        }

        // The last descendant is found by repeatedly taking child 7
        // This follows the SFC ordering where child 7 has the largest index
        var current = this;
        while (current.l < level) {
            current = current.child(7); // Always take the last child
        }
        return current.tmIndex();
    }

    /**
     * @return the length of an edge at the given level, in integer coordinates
     */
    public int length() {
        return 1 << (getMaxRefinementLevel() - l);
    }

    /**
     * Locate a tetrahedron containing a point at a specific level using Bey refinement traversal.
     *
     * This method starts from the current tetrahedron and descends through Bey-refined children until reaching the
     * target level. It finds the child tetrahedron that contains the point at each step.
     *
     * NOTE: This is different from Tetree.locate() which uses direct quantization. This method performs actual tree
     * traversal through Bey refinement.
     *
     * @param px          x-coordinate of the point
     * @param py          y-coordinate of the point
     * @param pz          z-coordinate of the point
     * @param targetLevel the target level to reach (must be >= this.l)
     * @return the tetrahedron at targetLevel containing the point, or null if not found
     */
    public Tet locatePointBeyRefinement(float px, float py, float pz, byte targetLevel) {
        if (targetLevel < this.l) {
            throw new IllegalArgumentException("Target level must be >= current level");
        }

        // Start with current tetrahedron
        Tet current = this;

        // Check if current contains the point
        if (!current.contains12DOP(px, py, pz)) {
            return null; // Point not in this tetrahedron's subtree
        }

        // Descend through Bey-refined children
        while (current.l < targetLevel) {
            boolean found = false;

            // Check all 8 Bey children
            for (int i = 0; i < 8; i++) {
                Tet child = current.child(i);
                if (child.contains12DOP(px, py, pz)) {
                    current = child;
                    found = true;
                    break;
                }
            }

            if (!found) {
                // Point not contained in any child - this can happen with Bey refinement
                // due to gaps between children. Return the deepest containing tetrahedron.
                return current;
            }
        }

        return current;
    }

    /**
     * @return the parent Tet
     */
    public Tet parent() {
        if (l == 0) {
            throw new IllegalStateException("Root tetrahedron has no parent");
        }

        // RDR-010 (Knapp Algorithm 4.1): a tetrahedron descending from a pyramidal root.
        if (minTetLevel != NO_TET_ANCESTOR) {
            if (minTetLevel == l) {
                // This is the shallowest tetrahedron; its parent is the pyramid that birthed it.
                // Cross-type return (Tet -> Pyramid) is deferred to the element-type unification
                // (Luciferase-q3p); pure-Tetree pi1.2 cannot construct or traverse such an element.
                throw new IllegalStateException(
                "Tet at the tet/pyramid boundary (minTetLevel == level): its parent is a pyramid; "
                + "cross-type parent return is deferred to Luciferase-q3p");
            }
            // Parent is a tetrahedron; compute it directly (the TetreeLevelCache is keyed without
            // minTetLevel, so it is bypassed here) and propagate minTetLevel unchanged.
            int h = length(); // this tet's cell size; clearing its bit yields the parent anchor
            int parentX = x & ~h;
            int parentY = y & ~h;
            int parentZ = z & ~h;
            byte parentLevel = (byte) (l - 1);
            byte parentType = computeParentType(parentX, parentY, parentZ, parentLevel);
            return new Tet(parentX, parentY, parentZ, parentLevel, parentType, minTetLevel);
        }

        // Check if we have cached the parent of this Tet
        // Cache maps: child coordinates -> parent Tet
        Tet cached = TetreeLevelCache.getCachedParent(x, y, z, l, type);
        if (cached != null) {
            return cached;
        }

        // Use t8code's parent coordinate calculation: parent->x = t->x & ~h;
        int h = length(); // Cell size at current level
        int parentX = x & ~h;
        int parentY = y & ~h;
        int parentZ = z & ~h;

        byte parentLevel = (byte) (l - 1);

        // Try cached parent type to avoid table lookups
        byte parentType = TetreeLevelCache.getCachedParentType(x, y, z, l, type);
        if (parentType == -1) {
            // Cache miss - compute parent type
            parentType = computeParentType(parentX, parentY, parentZ, parentLevel);
            TetreeLevelCache.cacheParentType(x, y, z, l, type, parentType);
        }

        Tet parent = new Tet(parentX, parentY, parentZ, parentLevel, parentType);

        // Cache the complete parent for future lookups
        TetreeLevelCache.cacheParent(x, y, z, l, type, parent);

        return parent;
    }

    /**
     * The parent of this tetrahedron as a {@link HybridElement}, handling the tet/pyramid boundary
     * that {@link #parent()} cannot represent (RDR-010 q3p, Knapp 2026 Algorithm 4.1). Three cases:
     * <ul>
     *   <li><b>{@code minTetLevel == NO_TET_ANCESTOR}</b> (pure-Tetree): delegates to {@link #parent()},
     *       returning a {@code Tet}.</li>
     *   <li><b>{@code minTetLevel} in {@code [0, l)}</b>: the parent is a shallower tetrahedron (nearer
     *       the root) that still descends from a pyramid; returns a {@code Tet} with {@code minTetLevel}
     *       propagated unchanged (same result as {@link #parent()}).</li>
     *   <li><b>{@code minTetLevel == l}</b> (the shallowest tet of a pyramidal branch): the parent is
     *       the {@link Pyramid} that birthed this tet. Its type is recovered from the z-bit at this
     *       level (t8code {@code t8_dpyramid_tetparent_type}: {@code (z &amp; length) == 0} &rarr; type 6,
     *       else type 7), and it is a pure pyramid ({@link Pyramid#NO_TET_ANCESTOR}).</li>
     * </ul>
     * {@link #parent()} retains its boundary throw because its return type cannot hold a pyramid;
     * hybrid consumers (pi1.3 PyramidIndex) call this method instead.
     *
     * @return the parent element (tetrahedron or pyramid)
     * @throws IllegalStateException if invoked on the root (level 0)
     */
    public HybridElement parentElement() {
        if (l == 0) {
            throw new IllegalStateException("Root tetrahedron has no parent");
        }
        if (minTetLevel == l) {
            // Boundary: the shallowest tet's parent is the pyramid that birthed it.
            int h = length();
            byte pyramidType = (z & h) == 0 ? Pyramid.TYPE_6 : Pyramid.TYPE_7;
            return new Pyramid(x & ~h, y & ~h, z & ~h, (byte) (l - 1), pyramidType);
        }
        // Pure-Tetree or interior pyramid-rooted tet: parent is a tetrahedron.
        return parent();
    }

    /**
     * The face-neighbor of this tetrahedron across {@code face} as a {@link HybridFaceNeighbor},
     * handling the cross-shape case that {@link #faceNeighbor(int)} cannot represent (RDR-010 q3p,
     * Knapp 2026 §4.4; t8code {@code t8_dpyramid_face_neighbour}). For a pure-Tetree tetrahedron
     * ({@code minTetLevel == NO_TET_ANCESTOR}) this is exactly {@link #faceNeighbor(int)} wrapped. For a
     * pyramid-rooted tetrahedron of type 0 or 3, a face that touches the pyramid envelope yields a
     * {@link Pyramid} neighbor; all other faces (and all faces of types 1,2,4,5) yield a tetrahedral
     * neighbor with {@code minTetLevel} propagated.
     *
     * <p><b>Depth.</b> Both the shallowest pyramid-rooted tet ({@code l == minTetLevel}) and deep ones
     * ({@code l > minTetLevel}) are resolved via {@link #tetBoundary(int)} (RDR-010 Luciferase-cjwr).
     *
     * <p><b>Infrastructure-only for the deep path (RDR-012 D2).</b> The deep ({@code l > minTetLevel})
     * branch is validated topology but is <em>not consumed in production</em>: {@code PyramidIndex} locate
     * stops at the shallowest tet leaf, so deep tet keys are never inserted by normal index operation
     * (pinned by {@code PyramidBoundaryPinningTest}). It is reachable only via direct {@link #child(int)}
     * refinement and tests. RDR-012 (accepted 2026-05-31) kept it infrastructure-only; productionization
     * (deep insert/query) is D1, reopen-only when a concrete deep-insertion workload appears. The shallow
     * ({@code l == minTetLevel}) cross-shape path IS the production-live hex↔tet boundary.
     *
     * @param face face index, 0..3
     * @return the face neighbor (tetrahedron or pyramid) with its reciprocal face, or {@code null} if
     *         the neighbor lies outside the domain
     */
    public HybridFaceNeighbor faceNeighborElement(int face) {
        assert 0 <= face && face < 4;
        // RDR-010 Luciferase-4pd: Tet type k IS t8code dtet type k, so the t8code pyramid-boundary
        // logic applies directly with no type translation. Only types 0 and 3 ever touch a pyramid.
        byte t8 = type;
        boolean pyramidCapable = minTetLevel != NO_TET_ANCESTOR && (t8 == 0 || t8 == 3);
        // Pure-Tetree or a type that never touches a pyramid, or a face that does not: tet neighbor.
        // tetBoundary handles both the shallowest tet (l == minTetLevel) and deep pyramid-rooted tets
        // (l > minTetLevel) via the t8code corner-walk (RDR-010 Luciferase-cjwr).
        if (!pyramidCapable || !tetBoundary(face)) {
            var fn = faceNeighbor(face);
            if (fn == null) {
                return null;
            }
            // Hybrid context: the same-level tet neighbor stays in the same pyramidal subtree.
            var nbr = minTetLevel == NO_TET_ANCESTOR ? fn.tet() : fn.tet().withMinTetLevel(minTetLevel);
            return new HybridFaceNeighbor(fn.face(), nbr);
        }
        // Cross-type: the neighbor across this face is the pyramid that bounds the tet (t8code switch,
        // keyed on the t8code tet type).
        int len = length();
        int nx = x, ny = y, nz = z;
        byte pyramidType;
        byte reciprocal;
        if (t8 == 0) {
            switch (face) {
                case 0 -> { nx += len; pyramidType = Pyramid.TYPE_7; reciprocal = 3; }
                case 1 -> { pyramidType = Pyramid.TYPE_7; reciprocal = 2; }
                case 2 -> { pyramidType = Pyramid.TYPE_6; reciprocal = 2; }
                default -> { ny -= len; pyramidType = Pyramid.TYPE_6; reciprocal = 3; } // face 3
            }
        } else { // t8 == 3
            switch (face) {
                case 0 -> { ny += len; pyramidType = Pyramid.TYPE_7; reciprocal = 1; }
                case 1 -> { pyramidType = Pyramid.TYPE_7; reciprocal = 0; }
                case 2 -> { pyramidType = Pyramid.TYPE_6; reciprocal = 0; }
                default -> { nx -= len; pyramidType = Pyramid.TYPE_6; reciprocal = 1; } // face 3
            }
        }
        if (nx < 0 || ny < 0 || nz < 0 || nx > Constants.MAX_COORD || ny > Constants.MAX_COORD
        || nz > Constants.MAX_COORD) {
            return null;
        }
        return new HybridFaceNeighbor(reciprocal, new Pyramid(nx, ny, nz, l, pyramidType));
    }

    /** The cube-id (0..7) of this tetrahedron's anchor at the given refinement level. */
    private int cubeIdAt(byte level) {
        int h = Constants.lengthAtLevel(level);
        int cid = (x & h) != 0 ? 1 : 0;
        cid |= (y & h) != 0 ? 2 : 0;
        cid |= (z & h) != 0 ? 4 : 0;
        return cid;
    }

    /**
     * Whether a type-0/3 tet's {@code face} connects to a pyramid rather than a tet, given the tet's
     * cube-id at its own level (t8code {@code t8_dpyramid_tet_pyra_face_connection}).
     */
    private static boolean tetPyraFaceConnection(byte tetType, int cubeId, int face) {
        if ((cubeId == 2 && face != 1) || (cubeId == 6 && face != 2)) {
            return tetType == 0;
        } else if ((cubeId == 1 && face != 1) || (cubeId == 5 && face != 2)) {
            return tetType == 3;
        } else if (cubeId == 3) {
            return face != 0;
        } else if (cubeId == 4) {
            return face != 3;
        }
        return false;
    }

    /**
     * Whether this pyramid-rooted tet's {@code face} touches the bounding pyramid envelope — the deep
     * boundary test, a direct port of t8code {@code t8_dpyramid_tet_boundary} (RDR-010 Luciferase-cjwr).
     * Handles both the shallowest tet ({@code l == minTetLevel}, where it reduces to
     * {@code t8_dpyramid_tet_pyra_face_connection}) and deep pyramid-rooted tets ({@code l > minTetLevel}).
     *
     * <p>For a deep tet the connection is valid only when (a) the shallowest tet ancestor's same face
     * connects to a pyramid AND (b) the tet hugs that face's corner at every refinement level down to
     * the ancestor — otherwise the neighbor across the face is another tet. The receiver's {@code type}
     * is already a t8code dtet type (Luciferase-4pd alignment), so no translation is needed.
     *
     * @param face triangular face index 0..3 (type-0/3 tets only; the caller guards type)
     */
    private boolean tetBoundary(int face) {
        // Shallowest tet: its parent is the pyramid, so the connection is the direct face test.
        if (l == minTetLevel) {
            return tetPyraFaceConnection(type, cubeIdAt(l), face);
        }
        // Deep tet: walk the type up to the shallowest ancestor (at minTetLevel), then test that
        // ancestor's face against the pyramid (t8_dpyramid_tet_boundary: anc = ancestor at switch level).
        byte ancType = type;
        for (int i = l; i > minTetLevel; i--) {
            ancType = TetreeConnectivity.CID_TYPE_TO_PARENTTYPE[cubeIdAt((byte) i)][ancType];
        }
        boolean validTouch = tetPyraFaceConnection(ancType, cubeIdAt((byte) minTetLevel), face);
        if (!validTouch) {
            return false;
        }
        // Corner-walk: the tet must lie in the pyramid-face corner at every level i in (minTetLevel, l].
        // If at any level the Bey child is not "inside" that face, the neighbor is a tet, not the pyramid.
        byte typeTemp = type;
        for (int i = l; i > minTetLevel; i--) {
            int cubeId = cubeIdAt((byte) i);
            int beyId = TetreeConnectivity.TYPE_CID_TO_BEYID[typeTemp][cubeId];
            if (TetreeConnectivity.FACE_CHILDID_TO_IS_INSIDE[face][beyId] == -1) {
                return false;
            }
            typeTemp = TetreeConnectivity.CID_TYPE_TO_PARENTTYPE[cubeId][typeTemp];
        }
        return true;
    }

    /**
     * Get sibling tetrahedron by index
     *
     * @param siblingIndex - index of sibling (0-7)
     * @return the sibling tetrahedron
     */
    public Tet sibling(int siblingIndex) {
        if (siblingIndex < 0 || siblingIndex >= TetreeConnectivity.CHILDREN_PER_TET) {
            throw new IllegalArgumentException("Sibling index must be 0-7: " + siblingIndex);
        }
        if (l == 0) {
            throw new IllegalStateException("Root tetrahedron has no siblings");
        }

        // Get parent and then get the requested child
        var parentTet = parent();
        return parentTet.child(siblingIndex);
    }

    /**
     * Get the coordinates using the subdivision-compatible vertex system where V3 = anchor + (h,h,h). This method is
     * specifically for geometric subdivision operations to ensure compatibility with the Bey refinement algorithm.
     *
     * @return array of 4 Point3i vertices in canonical order [v0, v1, v2, v3]
     */
    public Point3i[] subdivisionCoordinates() {
        var coords = new Point3i[4];
        var h = length();

        // Same ei/ej computation as standard coordinates
        int ei = type / 2;
        int ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3;

        // vertex 0: anchor coordinates (x, y, z)
        coords[0] = new Point3i(x, y, z);

        // vertex 1: anchor + h in dimension ei
        coords[1] = new Point3i(x, y, z);
        addToDimension(coords[1], ei, h);

        // vertex 2: anchor + h in dimension ei + h in dimension ej
        coords[2] = new Point3i(x, y, z);
        addToDimension(coords[2], ei, h);
        addToDimension(coords[2], ej, h);

        // vertex 3: anchor + (h,h,h) for subdivision compatibility
        coords[3] = new Point3i(x + h, y + h, z + h);

        return coords;
    }

    /**
     * Compute the TM-index (Tetrahedral Morton index) which is globally unique across all levels. Based on the
     * algorithm from TMIndexSimple.tetToTMIndex().
     *
     * The TM-index interleaves coordinate bits with tetrahedral type information, creating a space-filling curve index
     * that includes both spatial position and the complete ancestor type hierarchy for global uniqueness.
     */
    public TetreeKey<? extends TetreeKey<?>> tmIndex() {
        // PERFORMANCE: Check cache first
        var cached = TetreeLevelCache.getCachedTetreeKey(x, y, z, l, type);
        if (cached != null) {
            return cached;
        }

        if (l == 0) {
            return ROOT_TET;
        }

        // Tet coordinates are always absolute world coordinates (multiples of cellSize at the given level)
        // as validated by validateAnchorCoordinates(). Use them directly for encoding.
        int shiftedX = x;
        int shiftedY = y;
        int shiftedZ = z;

        // Compute ancestor types directly from coordinates — zero Tet allocations. RDR-010
        // Luciferase-4pd: walk UP from this leaf type via t8code's CID_TYPE_TO_PARENTTYPE, identical
        // to computeType()/parent()/consecutiveIndex(). (The old downward root-trace mis-indexed the
        // bey table by parent type and is not t8code-consistent.) types[i] = type at level i+1;
        // types[l-1] = this.type (leaf).
        byte[] types = new byte[l];
        types[l - 1] = type; // Leaf type is always this.type
        byte ancestorType = type;
        for (int lvl = l; lvl > 1; lvl--) {
            int h = 1 << (getMaxRefinementLevel() - lvl);
            int cubeId = ((x & h) != 0 ? 1 : 0) | ((y & h) != 0 ? 2 : 0) | ((z & h) != 0 ? 4 : 0);
            ancestorType = TetreeConnectivity.CID_TYPE_TO_PARENTTYPE[cubeId][ancestorType];
            types[lvl - 2] = ancestorType; // type at level lvl-1
        }

        // Now build bits with types in correct order
        // We support up to level 21 with 128-bit representation
        if (l > MortonCurve.MAX_REFINEMENT_LEVEL) {
            throw new IllegalStateException("Level " + l + " exceeds maximum supported level 21 for 128-bit TM-index");
        }

        // Use 128-bit representation, coarsest-at-MSB consecutive layout (matches PyramidKey,
        // Luciferase-tkvb). Each refinement step's 6-bit group is appended at the least-significant
        // end: the shallowest step (i=0) migrates to the most significant bits, the leaf (i=l-1)
        // lands at bits 0-5. compareTo on (highBits, lowBits) unsigned then reproduces the
        // coarse-dominant SFC order. No level-21 split: 21 * 6 = 126 bits fit two longs.
        long lowBits = 0L;
        long highBits = 0L;

        for (int i = 0; i < l; i++) {
            int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
            int xBit = (shiftedX >> bitPos) & 1;
            int yBit = (shiftedY >> bitPos) & 1;
            int zBit = (shiftedZ >> bitPos) & 1;

            int coordBits = (zBit << 2) | (yBit << 1) | xBit;
            int sixBits = (coordBits << 3) | types[i];

            // Shift the running 128-bit value left by one group and OR the new group into the LSB.
            highBits = (highBits << 6) | (lowBits >>> 58);
            lowBits = (lowBits << 6) | sixBits;
        }

        // Use compact key for levels <= 10 for better performance
        TetreeKey<? extends TetreeKey<?>> result;
        if (l <= 10) {
            result = new CompactTetreeKey(l, lowBits);
        } else {
            result = new ExtendedTetreeKey(l, lowBits, highBits);
        }

        // PERFORMANCE: Cache result before returning
        TetreeLevelCache.cacheTetreeKey(x, y, z, l, type, result);

        return result;
    }

    public byte type() {
        return type;
    }

    /** Refinement level (0 = root); {@link HybridElement} accessor for the {@code l} field. */
    public byte level() {
        return l;
    }

    public Point3i[] vertexPoints() {
        var origin = new Point3i(x, y, z);
        var pts = new Point3i[4];
        int i = 0;
        for (var vertex : Constants.SIMPLEX_STANDARD[type]) {
            pts[i] = new Point3i(vertex.x, vertex.y, vertex.z);
            pts[i].scaleAdd(length(), origin);
            i++;
        }
        return pts;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    private void addToDimension(Point3i point, int dimension, int h) {
        switch (dimension) {
            case 0 -> point.x += h;
            case 1 -> point.y += h;
            case 2 -> point.z += h;
            default -> throw new IllegalArgumentException("Invalid dimension: " + dimension);
        }
    }

    // Calculate touched dimensions for optimized spatial range queries
    private TouchedDimensions calculateTouchedDimensions(VolumeBounds bounds, byte level) {
        int length = Constants.lengthAtLevel(level);

        // Calculate grid bounds at this level
        int minX = (int) Math.floor(bounds.minX() / length);
        int maxX = (int) Math.ceil(bounds.maxX() / length);
        int minY = (int) Math.floor(bounds.minY() / length);
        int maxY = (int) Math.ceil(bounds.maxY() / length);
        int minZ = (int) Math.floor(bounds.minZ() / length);
        int maxZ = (int) Math.ceil(bounds.maxZ() / length);

        // Determine which dimensions are actually split by the volume
        byte mask = 0;
        if (minX != maxX) {
            mask |= 0x01; // X dimension touched
        }
        if (minY != maxY) {
            mask |= 0x02; // Y dimension touched
        }
        if (minZ != maxZ) {
            mask |= 0x04; // Z dimension touched
        }

        // Calculate lower segment ID for SFC traversal optimization
        byte lowerSegment = (byte) ((minX & 1) | ((minY & 1) << 1) | ((minZ & 1) << 2));

        return new TouchedDimensions(mask, lowerSegment, level);
    }

    // Basic SFC range computation without optimization
    private Stream<SFCRange> computeBasicSFCRanges(VolumeBounds bounds, boolean includeIntersecting) {
        // Find appropriate refinement levels for the query volume
        byte minLevel = (byte) Math.max(0, findMinimumContainingLevel(bounds) - 2);
        byte maxLevel = (byte) Math.min(Constants.getMaxRefinementLevel(), findMinimumContainingLevel(bounds) + 3);

        return IntStream.rangeClosed(minLevel, maxLevel).boxed().flatMap(
        level -> computeOptimizedSFCRangesAtLevel(bounds, (byte) level.intValue(), includeIntersecting));
    }

    // Compute SFC ranges for all tetrahedra in a grid cell - streaming version
    private Stream<SFCRange> computeCellSFCRanges(Point3f cellOrigin, byte level) {
        // For a grid cell, there can be multiple tetrahedra (6 types)
        // Find the SFC indices for all tetrahedron types at this location
        return IntStream.range(0, 6).mapToObj(type -> {
            var tet = new Tet((int) cellOrigin.x, (int) cellOrigin.y, (int) cellOrigin.z, level, (byte) type);
            var index = tet.tmIndex();
            return new SFCRange(index, index);
        });
    }

    // Depth-aware spatial range computation with adaptive level selection
    private Stream<SFCRange> computeDepthAwareSFCRanges(VolumeBounds bounds, boolean includeIntersecting) {
        // Calculate optimal level range based on volume characteristics
        byte optimalLevel = findOptimalLevel(bounds);
        byte minLevel = (byte) Math.max(0, optimalLevel - 1);
        byte maxLevel = (byte) Math.min(Constants.getMaxRefinementLevel(), optimalLevel + 2);

        // Use depth-dependent importance weighting
        return IntStream.rangeClosed(minLevel, maxLevel).boxed().flatMap(level -> {
            byte levelByte = level.byteValue();
            var touchedDims = calculateTouchedDimensions(bounds, levelByte);

            // Skip levels that don't contribute meaningfully
            if (shouldSkipLevel(bounds, levelByte, touchedDims)) {
                return Stream.empty();
            }

            return computeOptimizedSFCRangesAtLevel(bounds, levelByte, includeIntersecting);
        });
    }

    // Hierarchical range splitting optimization for large volumes
    private Stream<SFCRange> computeHierarchicalSFCRanges(VolumeBounds bounds, boolean includeIntersecting) {
        float volumeSize = (bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ()
                                                                                                - bounds.minZ());

        // For large volumes, split hierarchically to reduce computation
        if (volumeSize > 10000.0f) {
            return splitVolumeHierarchically(bounds, includeIntersecting, 0);
        } else {
            return computeDepthAwareSFCRanges(bounds, includeIntersecting);
        }
    }

    // Optimized linear SFC range computation (1 dimension varies)
    private Stream<SFCRange> computeLinearSFCRanges(VolumeBounds bounds, byte level, int minX, int maxX, int minY,
                                                    int maxY, int minZ, int maxZ, TouchedDimensions touchedDims,
                                                    boolean includeIntersecting) {
        int length = Constants.lengthAtLevel(level);

        if (touchedDims.isDimensionTouched(0)) {
            // X dimension varies
            return IntStream.rangeClosed(minX, maxX).filter(x -> {
                Point3f cellPoint = new Point3f(x * length, minY * length, minZ * length);
                return hybridCellIntersectsBounds(cellPoint, length, level, bounds, includeIntersecting);
            }).mapToObj(x -> {
                Point3f cellPoint = new Point3f(x * length, minY * length, minZ * length);
                return computeCellSFCRanges(cellPoint, level);
            }).flatMap(stream -> stream);
        } else if (touchedDims.isDimensionTouched(1)) {
            // Y dimension varies
            return IntStream.rangeClosed(minY, maxY).filter(y -> {
                Point3f cellPoint = new Point3f(minX * length, y * length, minZ * length);
                return hybridCellIntersectsBounds(cellPoint, length, level, bounds, includeIntersecting);
            }).mapToObj(y -> {
                Point3f cellPoint = new Point3f(minX * length, y * length, minZ * length);
                return computeCellSFCRanges(cellPoint, level);
            }).flatMap(stream -> stream);
        } else {
            // Z dimension varies
            return IntStream.rangeClosed(minZ, maxZ).filter(z -> {
                Point3f cellPoint = new Point3f(minX * length, minY * length, z * length);
                return hybridCellIntersectsBounds(cellPoint, length, level, bounds, includeIntersecting);
            }).mapToObj(z -> {
                Point3f cellPoint = new Point3f(minX * length, minY * length, z * length);
                return computeCellSFCRanges(cellPoint, level);
            }).flatMap(stream -> stream);
        }
    }

    // Optimized SFC range computation using touched dimensions analysis
    private Stream<SFCRange> computeOptimizedSFCRangesAtLevel(VolumeBounds bounds, byte level,
                                                              boolean includeIntersecting) {
        var touchedDims = calculateTouchedDimensions(bounds, level);
        int length = Constants.lengthAtLevel(level);

        // Early termination if volume is too small for this level
        if (touchedDims.getTouchedDimensionCount() == 0) {
            // Volume fits in single grid cell
            int centerX = (int) ((bounds.minX() + bounds.maxX()) / 2 / length) * length;
            int centerY = (int) ((bounds.minY() + bounds.maxY()) / 2 / length) * length;
            int centerZ = (int) ((bounds.minZ() + bounds.maxZ()) / 2 / length) * length;
            return computeCellSFCRanges(new Point3f(centerX, centerY, centerZ), level);
        }

        // Use touched dimensions to optimize traversal
        int minX = (int) Math.floor(bounds.minX() / length);
        int maxX = (int) Math.ceil(bounds.maxX() / length);
        int minY = (int) Math.floor(bounds.minY() / length);
        int maxY = (int) Math.ceil(bounds.maxY() / length);
        int minZ = (int) Math.floor(bounds.minZ() / length);
        int maxZ = (int) Math.ceil(bounds.maxZ() / length);

        // Optimize iteration based on touched dimensions
        if (touchedDims.getTouchedDimensionCount() == 1) {
            // Only one dimension varies - linear traversal
            return computeLinearSFCRanges(bounds, level, minX, maxX, minY, maxY, minZ, maxZ, touchedDims,
                                          includeIntersecting);
        } else if (touchedDims.getTouchedDimensionCount() == 2) {
            // Two dimensions vary - planar traversal
            return computePlanarSFCRanges(bounds, level, minX, maxX, minY, maxY, minZ, maxZ, touchedDims,
                                          includeIntersecting);
        } else {
            // All dimensions vary - full 3D traversal (fallback to original method)
            return IntStream.rangeClosed(minX, maxX).boxed().flatMap(x -> IntStream.rangeClosed(minY, maxY)
                                                                                   .boxed()
                                                                                   .flatMap(y -> IntStream.rangeClosed(
                                                                                   minZ, maxZ).filter(z -> {
                                                                                       Point3f cellPoint = new Point3f(
                                                                                       x * length, y * length,
                                                                                       z * length);
                                                                                       return hybridCellIntersectsBounds(
                                                                                       cellPoint, length, level, bounds,
                                                                                       includeIntersecting);
                                                                                   }).mapToObj(z -> {
                                                                                       Point3f cellPoint = new Point3f(
                                                                                       x * length, y * length,
                                                                                       z * length);
                                                                                       return computeCellSFCRanges(
                                                                                       cellPoint, level);
                                                                                   }).flatMap(stream -> stream)));
        }
    }

    /**
     * Compute the parent's type via t8code's canonical O(1) parent-type lookup (RDR-010 Luciferase-4pd,
     * {@code t8_dtet_parent}).
     *
     * <p>The parent is exactly one level above this tet, so its type is
     * {@code CID_TYPE_TO_PARENTTYPE[childCubeId][childType]} keyed by THIS tet's cube-id and type —
     * a single table lookup, no per-level walk. This is the same upward step used by {@link #parent()},
     * {@link #computeType(byte)}, and {@link #consecutiveIndex()}.</p>
     *
     * <p><b>Superseded:</b> the deleted implementation traced down from the root through the Bey tree
     * (O(parentLevel)) on the false premise that the reverse lookup was not a unique inverse; the t8code
     * {@code CID_TYPE_TO_PARENTTYPE} table keyed by (cubeId, childType) is the correct O(1) inverse.</p>
     */
    private byte computeParentType(int parentX, int parentY, int parentZ, byte parentLevel) {
        if (parentLevel == 0) {
            return 0; // Root is always type 0
        }
        // RDR-010 Luciferase-4pd: t8code's canonical O(1) parent-type lookup. The parent is exactly one
        // level above this tet, so its type is cid_type_to_parenttype[childCubeId][childType] keyed by
        // THIS tet's cube-id and type (t8_dtet_parent).
        int h = length();
        int cubeId = ((x & h) != 0 ? 1 : 0) | ((y & h) != 0 ? 2 : 0) | ((z & h) != 0 ? 4 : 0);
        return TetreeConnectivity.CID_TYPE_TO_PARENTTYPE[cubeId][type];
    }

    // Optimized planar SFC range computation (2 dimensions vary)
    private Stream<SFCRange> computePlanarSFCRanges(VolumeBounds bounds, byte level, int minX, int maxX, int minY,
                                                    int maxY, int minZ, int maxZ, TouchedDimensions touchedDims,
                                                    boolean includeIntersecting) {
        int length = Constants.lengthAtLevel(level);

        if (!touchedDims.isDimensionTouched(0)) {
            // X fixed, Y and Z vary
            return IntStream.rangeClosed(minY, maxY).boxed().flatMap(y -> IntStream.rangeClosed(minZ, maxZ).filter(
            z -> {
                Point3f cellPoint = new Point3f(minX * length, y * length, z * length);
                return hybridCellIntersectsBounds(cellPoint, length, level, bounds, includeIntersecting);
            }).mapToObj(z -> {
                Point3f cellPoint = new Point3f(minX * length, y * length, z * length);
                return computeCellSFCRanges(cellPoint, level);
            }).flatMap(stream -> stream));
        } else if (!touchedDims.isDimensionTouched(1)) {
            // Y fixed, X and Z vary
            return IntStream.rangeClosed(minX, maxX).boxed().flatMap(x -> IntStream.rangeClosed(minZ, maxZ).filter(
            z -> {
                Point3f cellPoint = new Point3f(x * length, minY * length, z * length);
                return hybridCellIntersectsBounds(cellPoint, length, level, bounds, includeIntersecting);
            }).mapToObj(z -> {
                Point3f cellPoint = new Point3f(x * length, minY * length, z * length);
                return computeCellSFCRanges(cellPoint, level);
            }).flatMap(stream -> stream));
        } else {
            // Z fixed, X and Y vary
            return IntStream.rangeClosed(minX, maxX).boxed().flatMap(x -> IntStream.rangeClosed(minY, maxY).filter(
            y -> {
                Point3f cellPoint = new Point3f(x * length, y * length, minZ * length);
                return hybridCellIntersectsBounds(cellPoint, length, level, bounds, includeIntersecting);
            }).mapToObj(y -> {
                Point3f cellPoint = new Point3f(x * length, y * length, minZ * length);
                return computeCellSFCRanges(cellPoint, level);
            }).flatMap(stream -> stream));
        }
    }

    // Compute SFC ranges that could contain tetrahedra intersecting the volume - optimized version
    private Stream<SFCRange> computeSFCRanges(VolumeBounds bounds, boolean includeIntersecting) {
        // Use the optimal strategy selector instead of direct computation
        return selectOptimalRangeStrategy(bounds, includeIntersecting);
    }

    /**
     * Compute the absolute coordinates of a specific vertex of this tetrahedron.
     *
     * <p><b>Algorithm (from t8code's t8_dtri_compute_coords):</b></p>
     * This is the exact t8code algorithm for computing vertex coordinates:
     * <ol>
     *   <li>Start with anchor coordinates (x, y, z)</li>
     *   <li>If vertex == 0, return anchor coordinates</li>
     *   <li>For vertex != 0, add h to the ei dimension</li>
     *   <li>For vertex == 2, also add h to the ej dimension</li>
     *   <li>For vertex == 3, add h to both (ei+1)%3 and (ei+2)%3 dimensions</li>
     * </ol>
     *
     * <p>Where ei = type / 2 and ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3</p>
     *
     * @param vertex vertex number (0-3)
     * @return absolute coordinates of the vertex
     * @throws IllegalArgumentException if vertex is not in range [0,3]
     */
    private Point3i computeVertexCoordinates(int vertex) {
        if (vertex < 0 || vertex > 3) {
            throw new IllegalArgumentException("Vertex must be 0-3: " + vertex);
        }

        // t8code algorithm: ei = type / 2, ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3
        int ei = type / 2;
        int ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3;
        int h = length(); // Cell size at this level

        // Start with anchor coordinates
        int[] coords = { x, y, z };

        if (vertex == 0) {
            return new Point3i(coords[0], coords[1], coords[2]);
        }

        // Add h to the ei dimension for all non-zero vertices
        coords[ei] += h;

        if (vertex == 2) {
            // Also add h to the ej dimension
            coords[ej] += h;
        } else if (vertex == 3) {
            // Add h to both (ei+1)%3 and (ei+2)%3 dimensions
            coords[(ei + 1) % 3] += h;
            coords[(ei + 2) % 3] += h;
        }

        return new Point3i(coords[0], coords[1], coords[2]);
    }

    // Find minimum level that can contain the volume
    private byte findMinimumContainingLevel(VolumeBounds bounds) {
        float maxExtent = Math.max(Math.max(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY()),
                                   bounds.maxZ() - bounds.minZ());

        // Find the level where tetrahedron length >= maxExtent
        for (byte level = 0; level <= Constants.getMaxRefinementLevel(); level++) {
            if (Constants.lengthAtLevel(level) >= maxExtent) {
                return level;
            }
        }
        return Constants.getMaxRefinementLevel();
    }

    // Find optimal level based on volume size and spatial characteristics
    private byte findOptimalLevel(VolumeBounds bounds) {
        float volumeSize = (bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ()
                                                                                                - bounds.minZ());
        float maxExtent = Math.max(Math.max(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY()),
                                   bounds.maxZ() - bounds.minZ());

        // Find level where tetrahedron size is roughly 1/4 to 1/2 of max extent
        for (byte level = 0; level <= Constants.getMaxRefinementLevel(); level++) {
            int tetLength = Constants.lengthAtLevel(level);
            if (tetLength <= maxExtent * 2 && tetLength >= maxExtent / 4) {
                return level;
            }
        }

        return findMinimumContainingLevel(bounds);
    }

    // Hybrid cube/tetrahedral intersection test - preserves SFC cube navigation with tetrahedral geometry
    private boolean hybridCellIntersectsBounds(Point3f cellOrigin, int cellSize, byte level, VolumeBounds bounds,
                                               boolean includeIntersecting) {
        // First: Fast cube-based intersection test for early rejection (preserves SFC navigation)
        float cellMaxX = cellOrigin.x + cellSize;
        float cellMaxY = cellOrigin.y + cellSize;
        float cellMaxZ = cellOrigin.z + cellSize;

        // Quick cube-based bounding box test - if cube doesn't intersect, no tetrahedra will
        if (cellMaxX < bounds.minX() || cellOrigin.x > bounds.maxX() || cellMaxY < bounds.minY()
        || cellOrigin.y > bounds.maxY() || cellMaxZ < bounds.minZ() || cellOrigin.z > bounds.maxZ()) {
            return false;
        }

        // Second: Test individual tetrahedra within the cube for precise tetrahedral geometry
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet((int) cellOrigin.x, (int) cellOrigin.y, (int) cellOrigin.z, level, type);

            if (includeIntersecting) {
                // Check if tetrahedron intersects the volume bounds
                if (tet.intersects12DOP(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(),
                                        bounds.maxZ())) {
                    return true;
                }
            } else {
                // Check if tetrahedron is completely contained within bounds
                if (tetrahedronContainedInVolumeBounds(tet, bounds)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Validates that this tetrahedron is properly formed based on its coordinates, level, and type.
     *
     * A valid tetrahedron must: 1. Have coordinates aligned to the grid at its level 2. Have a valid type for its
     * position within the cubic cell 3. Be within the bounds of the positive octant
     *
     * @return true if this is a valid tetrahedron, false otherwise
     */
    private boolean isValidTetrahedron() {
        // Special case: root tetrahedron
        if (l == 0) {
            // Root must be at origin with type 0
            return x == 0 && y == 0 && z == 0 && type == 0;
        }

        // Check coordinates are aligned to the grid at this level
        int cellSize = Constants.lengthAtLevel(l);
        if (x % cellSize != 0 || y % cellSize != 0 || z % cellSize != 0) {
            return false;
        }

        // For non-root tetrahedra, we need to validate the type matches standard refinement
        // Compute what type this tetrahedron should have based on its coordinates
        int currentType = 0; // Start at root type 0

        // Walk through each level computing type transformations
        for (int i = 0; i < l; i++) {
            // Extract coordinate bits at this level
            int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
            int xBit = (x >> bitPos) & 1;
            int yBit = (y >> bitPos) & 1;
            int zBit = (z >> bitPos) & 1;

            // Child index from coordinate bits
            int childIdx = (zBit << 2) | (yBit << 1) | xBit;

            // Transform type based on child position
            currentType = Constants.TYPE_TO_TYPE_OF_CHILD[currentType][childIdx];
        }

        // Check if the actual type matches the expected type
        if (type != currentType) {
            return false;
        }

        // Validate we can reach the parent (basic parent-child relationship check)
        if (l > 1) {
            try {
                // Ensure we can compute a valid parent
                var parent = parent();
                if (parent == null) {
                    return false;
                }

                // Verify this tetrahedron could be a child of its parent
                for (int i = 0; i < 8; i++) {
                    var possibleChild = parent.child(i);
                    if (possibleChild.x == x && possibleChild.y == y && possibleChild.z == z
                    && possibleChild.type == type) {
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                // If parent computation fails, the tetrahedron is invalid
                return false;
            }
        }

        return true;
    }

    // Merge overlapping SFC ranges for efficiency
    private List<SFCRange> mergeRanges(List<SFCRange> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }

        // Sort ranges by start key
        ranges.sort((a, b) -> a.start.compareTo(b.start));
        List<SFCRange> merged = new ArrayList<>();
        var current = ranges.get(0);

        for (int i = 1; i < ranges.size(); i++) {
            var next = ranges.get(i);
            if (current.canMergeWith(next)) {
                // Merge overlapping or adjacent ranges
                current = current.mergeWith(next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    // Enhanced range merging with hierarchical consideration
    private List<SFCRange> mergeRangesOptimized(List<SFCRange> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }

        // Sort ranges by start key
        ranges.sort((a, b) -> a.start.compareTo(b.start));

        // Use more aggressive merging for better performance
        List<SFCRange> merged = new ArrayList<>();
        var current = ranges.get(0);

        for (int i = 1; i < ranges.size(); i++) {
            var next = ranges.get(i);

            // For optimized merging, we can be more aggressive and merge ranges
            // that are close but not necessarily adjacent. However, without
            // arithmetic operations on TetreeKey, we can only merge truly
            // adjacent or overlapping ranges.
            if (current.canMergeWith(next)) {
                current = current.mergeWith(next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    // Select optimal range computation strategy based on volume characteristics
    private Stream<SFCRange> selectOptimalRangeStrategy(VolumeBounds bounds, boolean includeIntersecting) {
        float volumeSize = (bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ()
                                                                                                - bounds.minZ());
        float maxExtent = Math.max(Math.max(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY()),
                                   bounds.maxZ() - bounds.minZ());

        // Strategy selection based on volume characteristics
        if (volumeSize > 10000.0f) {
            // Large volumes: use hierarchical splitting
            return computeHierarchicalSFCRanges(bounds, includeIntersecting);
        } else if (shouldUseDepthAwareOptimization(bounds)) {
            // Medium volumes: use depth-aware optimization
            return computeDepthAwareSFCRanges(bounds, includeIntersecting);
        } else {
            // Small volumes: use basic computation
            return computeBasicSFCRanges(bounds, includeIntersecting);
        }
    }

    // Determine if a level should be skipped based on spatial characteristics
    private boolean shouldSkipLevel(VolumeBounds bounds, byte level, TouchedDimensions touchedDims) {
        int tetLength = Constants.lengthAtLevel(level);
        float maxExtent = Math.max(Math.max(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY()),
                                   bounds.maxZ() - bounds.minZ());

        // Skip if tetrahedra are much larger than the volume
        if (tetLength > maxExtent * 8) {
            return true;
        }

        // Skip if tetrahedra are much smaller and no dimensions are touched
        return tetLength < maxExtent / 16 && touchedDims.getTouchedDimensionCount() == 0;
    }

    // Determine whether to use depth-aware optimization based on volume characteristics
    private boolean shouldUseDepthAwareOptimization(VolumeBounds bounds) {
        float volumeSize = (bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ()
                                                                                                - bounds.minZ());
        float maxExtent = Math.max(Math.max(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY()),
                                   bounds.maxZ() - bounds.minZ());

        // Use depth-aware optimization for medium to large volumes
        // Small volumes benefit from simpler computation
        return volumeSize > 1000.0f && maxExtent > 10.0f;
    }

    // Determine if we should use lazy enumeration based on bounds size
    private boolean shouldUseLazyEnumeration(VolumeBounds bounds) {
        float volumeSize = (bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ()
                                                                                                - bounds.minZ());

        // Use lazy enumeration for large volumes
        // Also consider the level - deeper levels benefit more from lazy evaluation
        return volumeSize > 5000.0f || this.l > 15;
    }

    // Efficient spatial range query using tetrahedral space-filling curve properties - optimized version
    private Stream<TetreeKey<?>> spatialRangeQueryKeys(VolumeBounds bounds, boolean includeIntersecting) {
        // Use RangeHandle for lazy evaluation
        var handle = new RangeHandle(this, bounds, includeIntersecting, this.l);

        // Option 1: For large volumes, use lazy streaming
        if (shouldUseLazyEnumeration(bounds)) {
            return handle.stream();
        }

        // Option 2: For smaller ranges, use the existing range-based approach
        // but with lazy enumeration via LazySFCRangeStream
        return computeSFCRanges(bounds, includeIntersecting).flatMap(range -> {
            if (range.start().equals(range.end())) {
                // Single key range
                return Stream.of(range.start());
            } else {
                // Use lazy stream for multi-key ranges
                return LazySFCRangeStream.stream(range);
            }
        });
    }

    // Recursively split large volumes into smaller manageable pieces
    private Stream<SFCRange> splitVolumeHierarchically(VolumeBounds bounds, boolean includeIntersecting, int depth) {
        final int MAX_SPLIT_DEPTH = 3;
        float volumeSize = (bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ()
                                                                                                - bounds.minZ());

        // Base case: volume is small enough or max depth reached
        if (volumeSize <= 5000.0f || depth >= MAX_SPLIT_DEPTH) {
            return computeDepthAwareSFCRanges(bounds, includeIntersecting);
        }

        // Find the largest dimension to split
        float xExtent = bounds.maxX() - bounds.minX();
        float yExtent = bounds.maxY() - bounds.minY();
        float zExtent = bounds.maxZ() - bounds.minZ();

        if (xExtent >= yExtent && xExtent >= zExtent) {
            // Split along X dimension
            float midX = (bounds.minX() + bounds.maxX()) / 2;
            return Stream.of(
            new VolumeBounds(bounds.minX(), bounds.minY(), bounds.minZ(), midX, bounds.maxY(), bounds.maxZ()),
            new VolumeBounds(midX, bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())).flatMap(
            subBounds -> splitVolumeHierarchically(subBounds, includeIntersecting, depth + 1));
        } else if (yExtent >= zExtent) {
            // Split along Y dimension
            float midY = (bounds.minY() + bounds.maxY()) / 2;
            return Stream.of(
            new VolumeBounds(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), midY, bounds.maxZ()),
            new VolumeBounds(bounds.minX(), midY, bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())).flatMap(
            subBounds -> splitVolumeHierarchically(subBounds, includeIntersecting, depth + 1));
        } else {
            // Split along Z dimension
            float midZ = (bounds.minZ() + bounds.maxZ()) / 2;
            return Stream.of(
            new VolumeBounds(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), midZ),
            new VolumeBounds(bounds.minX(), bounds.minY(), midZ, bounds.maxX(), bounds.maxY(), bounds.maxZ())).flatMap(
            subBounds -> splitVolumeHierarchically(subBounds, includeIntersecting, depth + 1));
        }
    }

    public record FaceNeighbor(byte face, Tet tet) {
    }

    // Record to represent touched dimensions for optimized range queries
    private record TouchedDimensions(byte mask, byte lowerSegment, byte depthID) {
        int getTouchedDimensionCount() {
            return Integer.bitCount(mask & 0xFF);
        }

        boolean isAllDimensionsTouched() {
            return (mask & 0x07) == 0x07; // All 3 dimensions touched
        }

        boolean isDimensionTouched(int dimension) {
            return (mask & (1 << dimension)) != 0;
        }
    }

    // Record to represent SFC index ranges
    record SFCRange(TetreeKey<?> start, TetreeKey<?> end) {
        /**
         * Checks if this range can be merged with another range. Ranges can be merged if the end of this range is
         * adjacent to or overlaps with the start of the other range.
         *
         * @param other the other range to check
         * @return true if ranges can be merged, false otherwise
         */
        boolean canMergeWith(SFCRange other) {
            if (other == null) {
                return false;
            }

            // Ranges must be at the same level to be merged
            if (this.end.getLevel() != other.start.getLevel()) {
                return false;
            }

            // Check if ranges are adjacent or overlapping
            return this.end.canMergeWith(other.start) || this.end.compareTo(other.start) >= 0;
        }

        /**
         * Estimates the size of this range without iterating.
         *
         * @return An estimate of the number of keys in the range
         */
        long estimateSize() {
            if (start.equals(end)) {
                return 1;
            }

            var startTet = Tet.tetrahedron(start);
            var endTet = Tet.tetrahedron(end);
            var iter = new LazyRangeIterator(startTet, endTet);
            return iter.estimateSize();
        }

        /**
         * Checks if this is a single-key range.
         *
         * @return true if start equals end
         */
        boolean isSingle() {
            return start.equals(end);
        }

        /**
         * Creates a lazy iterator for this range.
         *
         * @return An iterator that generates keys on demand
         */
        Iterator<TetreeKey<? extends TetreeKey<?>>> iterator() {
            var startTet = Tet.tetrahedron(start);
            var endTet = Tet.tetrahedron(end);
            return new LazyRangeIterator(startTet, endTet);
        }

        /**
         * Merges this range with another range.
         *
         * @param other the range to merge with
         * @return a new SFCRange representing the merged range
         * @throws IllegalArgumentException if ranges cannot be merged
         */
        SFCRange mergeWith(SFCRange other) {
            if (!canMergeWith(other)) {
                throw new IllegalArgumentException("Cannot merge non-adjacent ranges");
            }

            // The merged range spans from the minimum start to the maximum end
            TetreeKey<?> newStart = this.start.compareTo(other.start) <= 0 ? this.start : other.start;
            TetreeKey<?> newEnd = this.end.max(other.end);

            return new SFCRange(newStart, newEnd);
        }

        /**
         * Splits this range into multiple sub-ranges for parallel processing. Note: This is an approximation since we
         * can't do precise arithmetic on TetreeKeys.
         *
         * @param parts The number of parts to split into
         * @return An array of sub-ranges
         */
        SFCRange[] split(int parts) {
            if (parts <= 1 || isSingle()) {
                return new SFCRange[] { this };
            }

            // For now, return the original range
            // A full implementation would require tree traversal
            return new SFCRange[] { this };
        }

        /**
         * Creates a lazy stream for this range.
         *
         * @return A stream that generates keys on demand
         */
        Stream<TetreeKey<? extends TetreeKey<?>>> stream() {
            return LazySFCRangeStream.stream(this);
        }
    }
}
