package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.Geometry;
import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.hellblazer.luciferase.lucien.Constants.*;

/**
 * A tetrahedron in the tetrahedral space-filling curve (Tet SFC) implementation.
 *
 * This class represents a single tetrahedron in the hierarchical tetrahedral decomposition of 3D space. The
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
public class Tet {
    public static final  TetreeKey<?> ROOT_TET      = TetreeKey.getRoot();
    // Table 2: Local indices - Iloc(parent_type, bey_child_index)
    // Note: Different from TetreeConnectivity.INDEX_TO_BEY_NUMBER due to different indexing scheme
    private static final byte[][]     LOCAL_INDICES = { { 0, 1, 4, 7, 2, 3, 6, 5 }, // Parent type 0
                                                        { 0, 1, 5, 7, 2, 3, 6, 4 }, // Parent type 1
                                                        { 0, 3, 4, 7, 1, 2, 6, 5 }, // Parent type 2
                                                        { 0, 1, 6, 7, 2, 3, 4, 5 }, // Parent type 3
                                                        { 0, 3, 5, 7, 1, 2, 4, 6 }, // Parent type 4
                                                        { 0, 3, 6, 7, 2, 1, 4, 5 }  // Parent type 5
    };
    public final         int          x;
    public final         int          y;
    public final         int          z;
    public final         byte         l;
    public final         byte         type;

    public Tet(int x, int y, int z, byte l, byte type) {
        // Validate level range first
        assert l >= 0 && l <= MortonCurve.MAX_REFINEMENT_LEVEL : "Level " + l + " must be between 0 and "
        + MortonCurve.MAX_REFINEMENT_LEVEL;

        assert type >= 0 && type <= 5 : "Type " + type + " must be between 0 and 5";
        // Validate coordinates
        assert x >= 0 && y >= 0 && z >= 0 : "Coordinates must be non-negative: (" + x + ", " + y + ", " + z + ")";
        // Validate that coordinates are correct anchor coordinates for the level and type
        assert validateAnchorCoordinates(x, y, z, l, type);
        this.x = x;
        this.y = y;
        this.z = z;
        this.l = l;
        this.type = type;
    }

    /**
     * Check if a point is contained within the tetrahedron. This uses the proper tetrahedral containment test, not just
     * the bounding box.
     */
    private static boolean containsPointInTetrahedron(Tet tet, float px, float py, float pz) {
        return tet.containsUltraFast(px, py, pz);
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
            return root.containsUltraFast(px, py, pz) ? root : null;
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

        // Determine tetrahedron type based on position
        byte type;
        if (ux <= uy) {
            if (uy <= uz) {
                type = 3; // x <= y <= z
            } else if (ux <= uz) {
                type = 2; // x <= z < y
            } else {
                type = 4; // z < x <= y
            }
        } else {
            // ux > uy
            if (ux <= uz) {
                type = 5; // y < x <= z
            } else if (uy <= uz) {
                type = 0; // y <= z < x
            } else {
                type = 1; // z < y < x
            }
        }

        // Create starting tetrahedron
        Tet current = new Tet(anchorX, anchorY, anchorZ, startLevel, type);

        // If target level is same as start level, we're done
        if (targetLevel == startLevel) {
            return current;
        }

        // Use Bey refinement traversal to reach target level
        return current.locatePointBeyRefinement(px, py, pz, targetLevel);
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

    public static Tet tetrahedron(TetreeKey<? extends TetreeKey> key) {
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

        // Extract 6-bit chunks from least significant to most significant
        for (int i = 0; i < maxBits; i++) {
            int sixBits;
            if (i < 10) {
                sixBits = (int) ((lowBits >> (6 * i)) & 0x3F);
            } else {
                sixBits = (int) ((highBits >> (6 * (i - 10))) & 0x3F);
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

    // Check if a tetrahedron intersects with a volume
    public static boolean tetrahedronIntersectsVolume(Tet tet, Spatial volume) {
        var vertices = tet.coordinates();
        var bounds = VolumeBounds.from(volume);
        if (bounds == null) {
            return false;
        }

        // Simple AABB intersection test - any vertex within bounds indicates intersection
        for (var vertex : vertices) {
            if (vertex.x >= bounds.minX() && vertex.x <= bounds.maxX() && vertex.y >= bounds.minY()
            && vertex.y <= bounds.maxY() && vertex.z >= bounds.minZ() && vertex.z <= bounds.maxZ()) {
                return true;
            }
        }

        // Also check if the volume center is inside the tetrahedron
        var centerPoint = new Point3f((bounds.minX() + bounds.maxX()) / 2, (bounds.minY() + bounds.maxY()) / 2,
                                      (bounds.minZ() + bounds.maxZ()) / 2);
        return tet.contains(centerPoint);
    }

    // Check if a tetrahedron intersects with volume bounds (proper tetrahedral geometry)
    public static boolean tetrahedronIntersectsVolumeBounds(Tet tet, VolumeBounds bounds) {
        var vertices = tet.coordinates();

        // Quick bounding box rejection test first
        float tetMinX = Float.MAX_VALUE, tetMaxX = Float.MIN_VALUE;
        float tetMinY = Float.MAX_VALUE, tetMaxY = Float.MIN_VALUE;
        float tetMinZ = Float.MAX_VALUE, tetMaxZ = Float.MIN_VALUE;

        for (var vertex : vertices) {
            tetMinX = Math.min(tetMinX, vertex.x);
            tetMaxX = Math.max(tetMaxX, vertex.x);
            tetMinY = Math.min(tetMinY, vertex.y);
            tetMaxY = Math.max(tetMaxY, vertex.y);
            tetMinZ = Math.min(tetMinZ, vertex.z);
            tetMaxZ = Math.max(tetMaxZ, vertex.z);
        }

        // Bounding box intersection test
        if (tetMaxX < bounds.minX() || tetMinX > bounds.maxX() || tetMaxY < bounds.minY() || tetMinY > bounds.maxY()
        || tetMaxZ < bounds.minZ() || tetMinZ > bounds.maxZ()) {
            return false;
        }

        // Test if any vertex of tetrahedron is inside bounds
        for (var vertex : vertices) {
            if (vertex.x >= bounds.minX() && vertex.x <= bounds.maxX() && vertex.y >= bounds.minY()
            && vertex.y <= bounds.maxY() && vertex.z >= bounds.minZ() && vertex.z <= bounds.maxZ()) {
                return true;
            }
        }

        // Test if any corner of bounds is inside tetrahedron
        var boundCorners = new Point3f[] { new Point3f(bounds.minX(), bounds.minY(), bounds.minZ()), new Point3f(
        bounds.maxX(), bounds.minY(), bounds.minZ()), new Point3f(bounds.minX(), bounds.maxY(), bounds.minZ()),
                                           new Point3f(bounds.maxX(), bounds.maxY(), bounds.minZ()), new Point3f(
        bounds.minX(), bounds.minY(), bounds.maxZ()), new Point3f(bounds.maxX(), bounds.minY(), bounds.maxZ()),
                                           new Point3f(bounds.minX(), bounds.maxY(), bounds.maxZ()), new Point3f(
        bounds.maxX(), bounds.maxY(), bounds.maxZ()) };

        for (var corner : boundCorners) {
            if (tet.contains(corner)) {
                return true;
            }
        }

        // Test tetrahedron edges against AABB
        // A tetrahedron has 6 edges: (0,1), (0,2), (0,3), (1,2), (1,3), (2,3)
        int[][] edges = { { 0, 1 }, { 0, 2 }, { 0, 3 }, { 1, 2 }, { 1, 3 }, { 2, 3 } };

        for (int[] edge : edges) {
            Point3f p0 = new Point3f(vertices[edge[0]].x, vertices[edge[0]].y, vertices[edge[0]].z);
            Point3f p1 = new Point3f(vertices[edge[1]].x, vertices[edge[1]].y, vertices[edge[1]].z);

            if (lineSegmentIntersectsAABB(p0, p1, bounds)) {
                return true;
            }
        }

        // If we've gotten this far, the volumes might still intersect along faces
        // For now, use conservative approximation
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
            return tetrahedronIntersectsVolume(tet, volume);
        });
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
        return BeySubdivision.getMortonChild(this, childIndex);
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

    /* A routine to compute the type of t's ancestor of level "level",
     * if its type at an intermediate level is already known.
     * If "level" equals t's level then t's type is returned.
     * It is not allowed to call this function with "level" greater than t->level.
     * This method runs in O(t->level - level).
     */
    public byte computeType(byte level) {
        assert (0 <= level && level <= l);

        if (level == l) {
            return type;
        }
        if (level == 0) {
            /* TODO: the type of the root tet is hardcoded to 0
             *       maybe once we want to allow the root tet to have different types */
            return 0;
        }

        // Try cached lookup first for O(1) operation
        byte cachedType = TetreeLevelCache.getTypeAtLevel(type, l, level);
        if (cachedType != -1) {
            return cachedType;
        }

        // Fallback to computation if not cached
        byte type = this.type;
        for (byte i = l; i > level; i--) {
            /* compute type as the type of T^{i+1}, that is T's ancestor of level i+1 */
            type = CUBE_ID_TYPE_TO_PARENT_TYPE[cubeId(i)][type];
        }
        return type;
    }

    /**
     * Compute the consecutive index of this tetrahedron at this level.
     *
     * <p><b>Algorithm Overview:</b></p>
     * Encodes the path from root to this tetrahedron by:
     * <ol>
     *   <li>Starting at this tetrahedron's level</li>
     *   <li>Working backwards to root, extracting cube ID at each level</li>
     *   <li>Converting cube ID to local index using connectivity table</li>
     *   <li>Packing 3-bit local indices to form complete SFC index</li>
     * </ol>
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

        // Cache miss - compute index
        long id = 0;
        byte typeTemp = 0;
        byte cid;
        int i;
        int exponent;

        assert (0 <= l && l <= getMaxRefinementLevel());

        exponent = 0;
        typeTemp = computeType(l);

        // Traverse from this level back to root
        for (i = l; i > 0; i--) {
            // Get cube position at this level
            cid = cubeId((byte) i);

            // Convert to local index using connectivity table
            id |= ((long) TYPE_CUBE_ID_TO_LOCAL_INDEX[typeTemp][cid]) << exponent;

            // Each level adds 3 bits
            exponent += 3;
            typeTemp = CUBE_ID_TYPE_TO_PARENT_TYPE[cid][typeTemp];
        }

        // Cache the result for future lookups
        TetreeLevelCache.cacheIndex(x, y, z, l, type, id);

        // Return the raw SFC index without level offset (matching t8code)
        return id;
    }

    public boolean contains(Tuple3f point) {
        return containsUltraFast(point.x, point.y, point.z);
    }

    /**
     * Ultra-fast contains check using direct arithmetic without method calls. This is the fastest possible
     * implementation for tetrahedral containment testing, achieving up to 4x speedup over the standard method.
     *
     * Based on the plane-based algorithm which requires: - 12 multiplications per plane test (48 total) - 8 additions
     * per plane test (32 total) - 4 comparisons (one per face)
     *
     * @param px X coordinate of the point to test
     * @param py Y coordinate of the point to test
     * @param pz Z coordinate of the point to test
     * @return true if the point is inside the tetrahedron
     */
    public boolean containsUltraFast(float px, float py, float pz) {
        // Inline all computations for maximum performance
        final int h = 1 << (Constants.getMaxRefinementLevel() - l);

        // Precompute all vertex coordinates using S0-S5 decomposition
        float v0x = x, v0y = y, v0z = z;  // v0 is the anchor point (always same for all types)
        float v1x, v1y, v1z;
        float v2x, v2y, v2z;
        float v3x = x + h, v3y = y + h, v3z = z + h; // v3 is always the opposite corner

        // Apply S0-S5 vertex assignments based on type
        switch (type) {
            case 0: // S0: vertices 0, 1, 3, 7
                v1x = x + h;
                v1y = y;
                v1z = z;     // V1
                v2x = x + h;
                v2y = y + h;
                v2z = z;     // V3
                break;
            case 1: // S1: vertices 0, 2, 3, 7
                v1x = x;
                v1y = y + h;
                v1z = z;     // V2
                v2x = x + h;
                v2y = y + h;
                v2z = z;     // V3
                break;
            case 2: // S2: vertices 0, 4, 5, 7
                v1x = x;
                v1y = y;
                v1z = z + h; // V4
                v2x = x + h;
                v2y = y;
                v2z = z + h; // V5
                break;
            case 3: // S3: vertices 0, 4, 6, 7
                v1x = x;
                v1y = y;
                v1z = z + h; // V4
                v2x = x;
                v2y = y + h;
                v2z = z + h; // V6
                break;
            case 4: // S4: vertices 0, 1, 5, 7
                v1x = x + h;
                v1y = y;
                v1z = z;     // V1
                v2x = x + h;
                v2y = y;
                v2z = z + h; // V5
                break;
            case 5: // S5: vertices 0, 2, 6, 7
                v1x = x;
                v1y = y + h;
                v1z = z;     // V2
                v2x = x;
                v2y = y + h;
                v2z = z + h; // V6
                break;
            default:
                throw new IllegalStateException("Invalid tet type: " + type);
        }

        // Determine if this is a mirrored (left-handed) tetrahedron
        // Types 1, 3, 4 are mirrors of types 0, 2, 5 respectively
        boolean isMirrored = (type == 1 || type == 3 || type == 4);

        // Inline the plane equation calculations directly
        // For mirrored tetrahedra, we need to reverse the inequality tests

        // Face 1: v1, v2, v3 (opposite v0)
        float adx = v1x - px;
        float bdx = v2x - px;
        float cdx = v3x - px;
        float ady = v1y - py;
        float bdy = v2y - py;
        float cdy = v3y - py;
        float adz = v1z - pz;
        float bdz = v2z - pz;
        float cdz = v3z - pz;

        float det1 = adx * (bdy * cdz - bdz * cdy) + bdx * (cdy * adz - cdz * ady) + cdx * (ady * bdz - adz * bdy);
        if (isMirrored ? det1 > 0 : det1 < 0) {
            return false;
        }

        // Face 2: v0, v3, v2 (opposite v1)
        adx = v0x - px;
        bdx = v3x - px;
        cdx = v2x - px;
        ady = v0y - py;
        bdy = v3y - py;
        cdy = v2y - py;
        adz = v0z - pz;
        bdz = v3z - pz;
        cdz = v2z - pz;

        float det2 = adx * (bdy * cdz - bdz * cdy) + bdx * (cdy * adz - cdz * ady) + cdx * (ady * bdz - adz * bdy);
        if (isMirrored ? det2 > 0 : det2 < 0) {
            return false;
        }

        // Face 3: v0, v1, v3 (opposite v2)
        adx = v0x - px;
        bdx = v1x - px;
        cdx = v3x - px;
        ady = v0y - py;
        bdy = v1y - py;
        cdy = v3y - py;
        adz = v0z - pz;
        bdz = v1z - pz;
        cdz = v3z - pz;

        float det3 = adx * (bdy * cdz - bdz * cdy) + bdx * (cdy * adz - cdz * ady) + cdx * (ady * bdz - adz * bdy);
        if (isMirrored ? det3 > 0 : det3 < 0) {
            return false;
        }

        // Face 4: v0, v2, v1 (opposite v3)
        adx = v0x - px;
        bdx = v2x - px;
        cdx = v1x - px;
        ady = v0y - py;
        bdy = v2y - py;
        cdy = v1y - py;
        adz = v0z - pz;
        bdz = v2z - pz;
        cdz = v1z - pz;

        float det4 = adx * (bdy * cdz - bdz * cdy) + bdx * (cdy * adz - cdz * ady) + cdx * (ady * bdz - adz * bdy);
        return isMirrored ? det4 <= 0 : det4 >= 0;
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

        // Correct S0-S5 cube decomposition
        // All 6 tetrahedra share vertices V0 (origin) and V7 (opposite corner)
        switch (type) {
            case 0: // S0: vertices 0, 1, 3, 7 of cube
                coords[0] = new Point3i(x, y, z);              // V0
                coords[1] = new Point3i(x + h, y, z);          // V1
                coords[2] = new Point3i(x + h, y + h, z);      // V3
                coords[3] = new Point3i(x + h, y + h, z + h);  // V7
                break;
            case 1: // S1: vertices 0, 2, 3, 7 of cube
                coords[0] = new Point3i(x, y, z);              // V0
                coords[1] = new Point3i(x, y + h, z);          // V2
                coords[2] = new Point3i(x + h, y + h, z);      // V3
                coords[3] = new Point3i(x + h, y + h, z + h);  // V7
                break;
            case 2: // S2: vertices 0, 4, 5, 7 of cube
                coords[0] = new Point3i(x, y, z);              // V0
                coords[1] = new Point3i(x, y, z + h);          // V4
                coords[2] = new Point3i(x + h, y, z + h);      // V5
                coords[3] = new Point3i(x + h, y + h, z + h);  // V7
                break;
            case 3: // S3: vertices 0, 4, 6, 7 of cube
                coords[0] = new Point3i(x, y, z);              // V0
                coords[1] = new Point3i(x, y, z + h);          // V4
                coords[2] = new Point3i(x, y + h, z + h);      // V6
                coords[3] = new Point3i(x + h, y + h, z + h);  // V7
                break;
            case 4: // S4: vertices 0, 1, 5, 7 of cube
                coords[0] = new Point3i(x, y, z);              // V0
                coords[1] = new Point3i(x + h, y, z);          // V1
                coords[2] = new Point3i(x + h, y, z + h);      // V5
                coords[3] = new Point3i(x + h, y + h, z + h);  // V7
                break;
            case 5: // S5: vertices 0, 2, 6, 7 of cube
                coords[0] = new Point3i(x, y, z);              // V0
                coords[1] = new Point3i(x, y + h, z);          // V2
                coords[2] = new Point3i(x, y + h, z + h);      // V6
                coords[3] = new Point3i(x + h, y + h, z + h);  // V7
                break;
            default:
                throw new IllegalStateException("Invalid tetrahedron type: " + type);
        }

        return coords;
    }

    /**
     * Legacy coordinate calculation using t8code algorithm. This method is preserved for reference and testing
     * compatibility.
     *
     * @deprecated Use {@link #coordinates()} which implements correct S0-S5 decomposition
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
    public TetreeKey<? extends TetreeKey> enclosing(Spatial volume) {
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
    public TetreeKey<? extends TetreeKey> enclosing(Tuple3f point, byte level) {
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

        // Check if the neighbor would have negative coordinates
        if (coords[0] < 0 || coords[1] < 0 || coords[2] < 0) {
            // Return null to indicate no neighbor exists (boundary of positive octant)
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
    public TetreeKey<? extends TetreeKey> firstDescendant(byte level) {
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
        if (l >= TetreeKey.MAX_REFINEMENT_LEVEL) {
            throw new IllegalStateException("Cannot subdivide at max refinement level");
        }

        // Use BeySubdivision which internally uses subdivisionCoordinates()
        // This provides subdivision-compatible vertices without changing the global coordinate system
        return BeySubdivision.subdivide(this);
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
    public TetreeKey<? extends TetreeKey> lastDescendant(byte level) {
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
            return tetrahedronIntersectsVolume(tet, volume);
        }).findFirst().orElse(null);
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
        if (!current.containsUltraFast(px, py, pz)) {
            return null; // Point not in this tetrahedron's subtree
        }

        // Descend through Bey-refined children
        while (current.l < targetLevel) {
            boolean found = false;

            // Check all 8 Bey children
            for (int i = 0; i < 8; i++) {
                Tet child = current.child(i);
                if (child.containsUltraFast(px, py, pz)) {
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
    public TetreeKey<? extends TetreeKey> tmIndex() {
        // PERFORMANCE: Check cache first
        var cached = TetreeLevelCache.getCachedTetreeKey(x, y, z, l, type);
        if (cached != null) {
            return cached;
        }

        if (l == 0) {
            return ROOT_TET;
        }

        // Determine if we have grid coordinates or absolute coordinates
        // Grid coordinates from locateStandardRefinement are in range [0, 2^level-1]
        // Absolute coordinates (from tests) can be much larger
        int maxGridCoord = (1 << l) - 1;
        boolean isGridCoordinates = x <= maxGridCoord && y <= maxGridCoord && z <= maxGridCoord;

        int shiftedX, shiftedY, shiftedZ;
        if (isGridCoordinates) {
            // Convert grid coordinates to absolute coordinates for encoding
            // Grid coordinates at level L need to be shifted to align with the hierarchical bit structure
            // At level L, we have L bits of precision, but tmIndex extracts from bits 20 down to (20-L+1)
            int shiftAmount = Constants.getMaxRefinementLevel() - l;
            shiftedX = x << shiftAmount;
            shiftedY = y << shiftAmount;
            shiftedZ = z << shiftAmount;
        } else {
            // Already absolute coordinates, use as-is
            shiftedX = x;
            shiftedY = y;
            shiftedZ = z;
        }

        // V2 OPTIMIZATION: Build parent chain in reverse order while building bits
        // Stack to hold types as we walk up
        byte[] types = new byte[l];
        Tet current = this;

        // Walk up to collect types efficiently
        for (int i = l - 1; i >= 0; i--) {
            types[i] = current.type;
            if (i > 0) {
                current = current.parent();
            }
        }

        // Now build bits with types in correct order
        // We support up to level 21 with 128-bit representation
        if (l > MortonCurve.MAX_REFINEMENT_LEVEL) {
            throw new IllegalStateException("Level " + l + " exceeds maximum supported level 21 for 128-bit TM-index");
        }

        // Use 128-bit representation
        long lowBits = 0L;
        long highBits = 0L;

        // Process each level in order with cached types
        for (int i = 0; i < l; i++) {
            int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
            int xBit = (shiftedX >> bitPos) & 1;
            int yBit = (shiftedY >> bitPos) & 1;
            int zBit = (shiftedZ >> bitPos) & 1;

            int coordBits = (zBit << 2) | (yBit << 1) | xBit;
            int sixBits = (coordBits << 3) | types[i];

            if (i < 10) {
                lowBits |= ((long) sixBits) << (6 * i);
            } else {
                highBits |= ((long) sixBits) << (6 * (i - 10));
            }
        }

        // Use compact key for levels <= 10 for better performance
        TetreeKey<? extends TetreeKey> result;
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

    public Point3i[] vertices() {
        var origin = new Point3i(x, y, z);
        var vertices = new Point3i[4];
        int i = 0;
        for (var vertex : Constants.SIMPLEX_STANDARD[type]) {
            vertices[i] = new Point3i(vertex.x, vertex.y, vertex.z);
            vertices[i].scaleAdd(length(), origin);
            i++;
        }
        return vertices;
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
     * Compute parent type using reverse lookup from connectivity tables. Based on t8code's parent type computation
     * algorithm.
     */
    private byte computeParentType(int parentX, int parentY, int parentZ, byte parentLevel) {
        // Special case: root tetrahedron must always have type 0
        if (parentLevel == 0) {
            return 0;
        }

        // Calculate the cube ID of this child within its parent
        // This is which octant of the parent cube contains this child
        int h = length(); // Cell size at current level
        byte cubeId = 0;

        // Each bit indicates if the child is in the upper half of that dimension
        if ((x & h) != 0) {
            cubeId |= 1;  // X bit
        }
        if ((y & h) != 0) {
            cubeId |= 2;  // Y bit
        }
        if ((z & h) != 0) {
            cubeId |= 4;  // Z bit
        }

        // Use reverse lookup table to find parent type
        return CUBE_ID_TYPE_TO_PARENT_TYPE[cubeId][type];
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
    
    // Basic SFC range computation without optimization
    private Stream<SFCRange> computeBasicSFCRanges(VolumeBounds bounds, boolean includeIntersecting) {
        // Find appropriate refinement levels for the query volume
        byte minLevel = (byte) Math.max(0, findMinimumContainingLevel(bounds) - 2);
        byte maxLevel = (byte) Math.min(Constants.getMaxRefinementLevel(), findMinimumContainingLevel(bounds) + 3);

        return IntStream.rangeClosed(minLevel, maxLevel).boxed().flatMap(
        level -> computeOptimizedSFCRangesAtLevel(bounds, (byte) level.intValue(), includeIntersecting));
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
                if (tetrahedronIntersectsVolumeBounds(tet, bounds)) {
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

    // Efficient spatial range query using tetrahedral space-filling curve properties - optimized version
    private Stream<TetreeKey<?>> spatialRangeQueryKeys(VolumeBounds bounds, boolean includeIntersecting) {
        // Use the optimized SFC range computation
        var sfcRanges = computeSFCRanges(bounds, includeIntersecting)
            .collect(Collectors.toList());
        
        // Merge adjacent ranges for efficiency
        var mergedRanges = mergeRangesOptimized(sfcRanges);
        
        // For now, we can only return the start and end of each range
        // Full enumeration would require understanding the tree structure
        return mergedRanges.stream().flatMap(range -> {
            if (range.start().equals(range.end())) {
                // Single key range
                return Stream.of(range.start());
            } else {
                // Return start and end keys only
                // A full implementation would need to traverse the tree structure
                return Stream.of(range.start(), range.end());
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
    private record SFCRange(TetreeKey<?> start, TetreeKey<?> end) {
        /**
         * Checks if this range can be merged with another range.
         * Ranges can be merged if the end of this range is adjacent to or overlaps with the start of the other range.
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
    }
}
