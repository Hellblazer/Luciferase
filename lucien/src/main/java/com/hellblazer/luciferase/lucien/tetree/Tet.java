package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.Geometry;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.hellblazer.luciferase.lucien.Constants.*;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

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
 * <p><b>Implementation Notes:</b></p>
 * <ul>
 *   <li>SFC indices use NO level offset (unlike Morton codes)</li>
 *   <li>Level 0: index 0, Level 1: indices 1-7, Level 2: indices 8-63, etc.</li>
 *   <li>Many-to-one mappings in connectivity tables are EXPECTED</li>
 *   <li>Child positions use vertex midpoints, NOT cube-based offsets</li>
 * </ul>
 *
 * @author hal.hildebrand
 **/
public record Tet(int x, int y, int z, byte l, byte type) {

    public static final  TetreeKey ROOT_TET      = new TetreeKey((byte) 0, BigInteger.ZERO);
    // Table 2: Local indices - Iloc(parent_type, bey_child_index)
    // Note: Different from TetreeConnectivity.INDEX_TO_BEY_NUMBER due to different indexing scheme
    private static final byte[][]  LOCAL_INDICES = { { 0, 1, 4, 7, 2, 3, 6, 5 }, // Parent type 0
                                                     { 0, 1, 5, 7, 2, 3, 6, 4 }, // Parent type 1
                                                     { 0, 3, 4, 7, 1, 2, 6, 5 }, // Parent type 2
                                                     { 0, 1, 6, 7, 2, 3, 4, 5 }, // Parent type 3
                                                     { 0, 3, 5, 7, 1, 2, 4, 6 }, // Parent type 4
                                                     { 0, 3, 6, 7, 2, 1, 4, 5 }  // Parent type 5
    };

    // Compact constructor for validation
    public Tet {
        if (l < 0 || l > getMaxRefinementLevel()) {
            throw new IllegalArgumentException("Level must be in range [0, " + getMaxRefinementLevel() + "]: " + l);
        }
        if (type < 0 || type > 5) {
            throw new IllegalArgumentException("Type must be in range [0, 5]: " + type);
        }

        // Validate coordinates are non-negative (required for tetrahedral SFC)
        if (x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException("Coordinates must be non-negative: (" + x + ", " + y + ", " + z + ")");
        }
    }

    public Tet(int level, int type) {
        this((byte) level, (byte) type);
    }

    public Tet(byte level, byte type) {
        this(ROOT_SIMPLEX.x, ROOT_SIMPLEX.y, ROOT_SIMPLEX.z, level, type);
    }

    public Tet(Point3i cubeId, int level, int type) {
        this(cubeId, (byte) level, (byte) type);
    }

    public Tet(Point3i cubeId, byte level, byte type) {
        this(cubeId.x, cubeId.y, cubeId.z, level, type);
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
        Tet tet = new Tet(x, y, z, level, type);

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
            float t1 = (bounds.minX() - p0.x) / dx;
            float t2 = (bounds.maxX() - p0.x) / dx;

            if (t1 > t2) {
                // Swap
                float temp = t1;
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
            float t1 = (bounds.minY() - p0.y) / dy;
            float t2 = (bounds.maxY() - p0.y) / dy;

            if (t1 > t2) {
                // Swap
                float temp = t1;
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
            float t1 = (bounds.minZ() - p0.z) / dz;
            float t2 = (bounds.maxZ() - p0.z) / dz;

            if (t1 > t2) {
                // Swap
                float temp = t1;
                t1 = t2;
                t2 = temp;
            }

            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);

            return !(tMin > tMax);
        }
    }

    /**
     * Optimized location method using simplified plane tests based on the actual tetrahedral decomposition geometry.
     * This version reduces computation by checking only the necessary planes in a decision tree structure.
     *
     * The cube is decomposed into 6 tetrahedra using Bey's refinement scheme: - Type 0: vertices (0,0,0), (1,0,0),
     * (1,1,0), (1,1,1) - Type 1: vertices (0,0,0), (0,1,0), (1,1,0), (1,1,1) - Type 2: vertices (0,0,0), (0,0,1),
     * (1,0,1), (1,1,1) - Type 3: vertices (0,0,0), (0,1,0), (0,1,1), (1,1,1) - Type 4: vertices (0,0,0), (0,0,1),
     * (0,1,1), (1,1,1) - Type 5: vertices (0,0,0), (1,0,0), (1,0,1), (1,1,1)
     *
     * @param px    X coordinate of the point
     * @param py    Y coordinate of the point
     * @param pz    Z coordinate of the point
     * @param level The refinement level
     * @return The Tet containing the point
     */
    public static Tet locateFreudenthal(float px, float py, float pz, byte level) {
        int length = Constants.lengthAtLevel(level);

        // Grid cell origin
        int c0x = (int) (Math.floor(px / length) * length);
        int c0y = (int) (Math.floor(py / length) * length);
        int c0z = (int) (Math.floor(pz / length) * length);

        // Check plane (0,0,0)-(1,1,0)-(1,1,1): separates types {2,4} from {0,1,3,5}
        // Normal is (0, -1, 1) pointing towards types 2,4
        float d1 = (pz - c0z) - (py - c0y);

        if (d1 > 0) {
            // Types 2 or 4
            // Check plane (0,0,0)-(0,0,1)-(1,1,1): separates type 2 from type 4
            // Normal is (1, -1, 0) pointing towards type 2
            float d2 = (px - c0x) - (py - c0y);
            return new Tet(c0x, c0y, c0z, level, (byte) (d2 > 0 ? 2 : 4));
        } else {
            // Types 0, 1, 3, or 5
            // Check plane (0,0,0)-(1,0,0)-(1,1,1): separates types {1,3} from {0,5}
            // Normal is (0, 1, -1) pointing towards types 1,3
            float d3 = (py - c0y) - (pz - c0z);

            if (d3 > 0) {
                // Types 1 or 3
                // Check plane (0,0,0)-(0,1,0)-(1,1,1): separates type 1 from type 3
                // Normal is (-1, 0, 1) pointing towards type 3
                float d4 = (pz - c0z) - (px - c0x);
                return new Tet(c0x, c0y, c0z, level, (byte) (d4 > 0 ? 3 : 1));
            } else {
                // Types 0 or 5
                // Check plane (0,0,0)-(1,0,0)-(1,0,1)-(1,1,1): separates type 0 from type 5
                // This is actually checking if y > z in the normalized space
                float d5 = (py - c0y) - (pz - c0z);
                return new Tet(c0x, c0y, c0z, level, (byte) (d5 >= 0 ? 0 : 5));
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
     * The level is implicit in the bit pattern itself.
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

    public static Tet tetrahedron(TetreeKey key) {
        return tetrahedron(key.getTmIndex(), key.getLevel());
    }

    /**
     * @param index - the consecutive index of the tetrahedron
     * @return the Tet corresponding to the consecutive index
     * @deprecated This method is fundamentally flawed as it attempts to derive level from index. Use
     * {@link #tetrahedron(long, byte)} with explicit level instead.
     */
    @Deprecated(since = "0.0.1", forRemoval = true)
    public static Tet tetrahedron(long index) {
        return tetrahedron(index, tetLevelFromIndex(index));
    }

    /**
     * Convert TM-index back to tetrahedron. This is the inverse of the tmIndex() method and properly decodes the
     * interleaved coordinate and type information.
     */
    public static Tet tetrahedron(BigInteger tmIndex, byte level) {
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

        BigInteger index = tmIndex;
        BigInteger sixty_four = BigInteger.valueOf(64);

        // Extract from least significant to most significant
        for (int i = maxBits - 1; i >= 0; i--) {
            BigInteger[] divRem = index.divideAndRemainder(sixty_four);
            index = divRem[0];
            int sixBits = divRem[1].intValue();

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
        for (int i = 0; i < maxBits; i++) {
            int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
            x |= (coordXBits[i] << bitPos);
            y |= (coordYBits[i] << bitPos);
            z |= (coordZBits[i] << bitPos);
        }

        // Current type is at the last position
        byte type = (byte) types[maxBits - 1];

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
     * @param volume - the enclosing volume
     * @return the Stream of indexes in the SFC locating the Tets bounded by the volume
     */
    public Stream<Long> boundedBy(Spatial volume) {
        var bounds = VolumeBounds.from(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        return spatialRangeQuery(bounds, false).filter(index -> {
            var tet = Tet.tetrahedron(index, this.l);  // Use the level of this Tet
            return tetrahedronContainedInVolume(tet, volume);
        });
    }

    /**
     * @param volume the volume to contain
     * @return the Stream of indexes in the SFC locating the Tets that minimally bound the volume
     */
    public Stream<Long> bounding(Spatial volume) {
        var bounds = VolumeBounds.from(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        return spatialRangeQuery(bounds, true).filter(index -> {
            var tet = Tet.tetrahedron(index);
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

        // Get Bey child ID from Morton index using parent type (t8code: t8_dtet_index_to_bey_number)
        byte beyChildId = TetreeConnectivity.getBeyChildId(type, childIndex);

        // Get child type from connectivity table using Bey ID (t8code: t8_dtet_type_of_child)
        byte childType = TetreeConnectivity.getChildType(type, beyChildId);
        byte childLevel = (byte) (l + 1);

        // For all children, compute position as midpoint between parent anchor and vertex
        // This is the t8code algorithm: child anchor = (parent anchor + parent vertex) / 2
        byte vertex = TetreeConnectivity.getBeyVertex(beyChildId);

        // Use the exact t8code algorithm to compute vertex coordinates
        Point3i vertexCoords = computeVertexCoordinates(vertex);

        // Child anchor is midpoint between parent anchor and the defining vertex
        int childX = (x + vertexCoords.x) >> 1;  // Bit shift for division by 2
        int childY = (y + vertexCoords.y) >> 1;
        int childZ = (z + vertexCoords.z) >> 1;

        return new Tet(childX, childY, childZ, childLevel, childType);
    }

    /**
     * @param i - the Tet Morton child id
     * @return the i-th child (in Tet Morton order) of the receiver
     */
    public Tet childTM(byte i) {
        if (l == getMaxRefinementLevel()) {
            throw new IllegalArgumentException(
            "No children at maximum refinement level: %s".formatted(getMaxRefinementLevel()));
        }
        return child(TYPE_TO_TYPE_OF_CHILD_MORTON[type][i]);
    }

    /**
     * Compare two tetrahedra for SFC ordering
     *
     * @param other the tetrahedron to compare to
     * @return negative if this < other, 0 if equal, positive if this > other
     */
    public int compareElements(Tet other) {
        // Compare by SFC index
        TetreeKey thisKey = this.tmIndex();
        TetreeKey otherKey = other.tmIndex();
        return thisKey.compareTo(otherKey);
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
        final int ei = type >> 1;  // type / 2
        final int ej = (ei + ((type & 1) == 0 ? 2 : 1)) % 3;

        // Precompute all vertex coordinates
        float v1x = x, v1y = y, v1z = z;
        float v2x = x, v2y = y, v2z = z;
        float v3x = x, v3y = y, v3z = z;

        // Apply offsets based on ei
        if (ei == 0) {
            v1x += h;
            v2x = v1x;
        } else if (ei == 1) {
            v1y += h;
            v2y = v1y;
        } else {
            v1z += h;
            v2z = v1z;
        }

        // Apply offsets based on ej for v2
        if (ej == 0) {
            v2x += h;
        } else if (ej == 1) {
            v2y += h;
        } else {
            v2z += h;
        }

        // Apply offsets for v3 (always adds to the other two dimensions)
        if (ei == 0) {
            v3y += h;
            v3z += h;
        } else if (ei == 1) {
            v3x += h;
            v3z += h;
        } else {
            v3x += h;
            v3y += h;
        }

        // Inline the plane equation calculations directly
        // Face CDB (v2, v3, v1) vs point
        float adx = v2x - px;
        float bdx = v3x - px;
        float cdx = v1x - px;
        float ady = v2y - py;
        float bdy = v3y - py;
        float cdy = v1y - py;
        float adz = v2z - pz;
        float bdz = v3z - pz;
        float cdz = v1z - pz;

        if (adx * (bdy * cdz - bdz * cdy) + bdx * (cdy * adz - cdz * ady) + cdx * (ady * bdz - adz * bdy) > 0) {
            return false;
        }

        // Face DCA (v3, v2, v0) vs point
        adx = v3x - px;
        bdx = v2x - px;
        cdx = x - px;
        ady = v3y - py;
        bdy = v2y - py;
        cdy = y - py;
        adz = v3z - pz;
        bdz = v2z - pz;
        cdz = z - pz;

        if (adx * (bdy * cdz - bdz * cdy) + bdx * (cdy * adz - cdz * ady) + cdx * (ady * bdz - adz * bdy) > 0) {
            return false;
        }

        // Face BDA (v1, v3, v0) vs point
        adx = v1x - px;
        bdx = v3x - px;
        cdx = x - px;
        ady = v1y - py;
        bdy = v3y - py;
        cdy = y - py;
        adz = v1z - pz;
        bdz = v3z - pz;
        cdz = z - pz;

        if (adx * (bdy * cdz - bdz * cdy) + bdx * (cdy * adz - cdz * ady) + cdx * (ady * bdz - adz * bdy) > 0) {
            return false;
        }

        // Face BAC (v1, v0, v2) vs point
        adx = v1x - px;
        bdx = x - px;
        cdx = v2x - px;
        ady = v1y - py;
        bdy = y - py;
        cdy = v2y - py;
        adz = v1z - pz;
        bdz = z - pz;
        cdz = v2z - pz;

        return adx * (bdy * cdz - bdz * cdy) + bdx * (cdy * adz - cdz * ady) + cdx * (ady * bdz - adz * bdy) <= 0;
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
    public long enclosing(Spatial volume) {
        // Extract bounding box of the volume
        var bounds = VolumeBounds.from(volume);
        if (bounds == null) {
            return 0L;
        }

        // Find the minimum level that can contain the volume
        byte level = findMinimumContainingLevel(bounds);

        // Find a tetrahedron at that level that contains the volume
        var centerPoint = new Point3f((bounds.minX() + bounds.maxX()) / 2, (bounds.minY() + bounds.maxY()) / 2,
                                      (bounds.minZ() + bounds.maxZ()) / 2);

        var tet = locate(centerPoint, level);
        return tet.tmIndex().getTmIndex().longValue();
    }

    /**
     * @param point - the point to enclose
     * @param level - refinement level for enclosure
     * @return the simplex at the provided
     */
    public long enclosing(Tuple3f point, byte level) {
        var tet = locate(new Point3f(point.x, point.y, point.z), level);
        return tet.tmIndex().getTmIndex().longValue();
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
    public long firstDescendant(byte level) {
        if (level < this.l) {
            throw new IllegalArgumentException("Target level must be >= current level");
        }
        if (level == this.l) {
            return this.tmIndex().getTmIndex().longValue();
        }

        // The first descendant is found by repeatedly taking child 0
        // This follows the SFC ordering where child 0 has the smallest index
        Tet current = this;
        while (current.l < level) {
            current = current.child(0); // Always take the first child
        }
        return current.tmIndex().getTmIndex().longValue();
    }

    public long intersecting(Spatial volume) {
        // Simple implementation: find first intersecting tetrahedron
        var bounds = VolumeBounds.from(volume);
        if (bounds == null) {
            return 0L;
        }

        return spatialRangeQuery(bounds, true).filter(index -> {
            var tet = Tet.tetrahedron(index);
            return tetrahedronIntersectsVolume(tet, volume);
        }).findFirst().orElse(0L);
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

    /**
     * Get the last descendant at the given level
     *
     * @param level the target level (must be >= this.l)
     * @return SFC index of last descendant
     */
    public long lastDescendant(byte level) {
        if (level < this.l) {
            throw new IllegalArgumentException("Target level must be >= current level");
        }
        if (level == this.l) {
            return this.tmIndex().getTmIndex().longValue();
        }

        // The last descendant is found by repeatedly taking child 7
        // This follows the SFC ordering where child 7 has the largest index
        Tet current = this;
        while (current.l < level) {
            current = current.child(7); // Always take the last child
        }
        return current.tmIndex().getTmIndex().longValue();
    }

    /**
     * @return the length of an edge at the given level, in integer coordinates
     */
    public int length() {
        return 1 << (getMaxRefinementLevel() - l);
    }

    // Helper method - locate tetrahedron containing a point using direct containment test
    public Tet locate(Point3f point, byte level) {
        return locateFreudenthal(point.x, point.y, point.z, level);
    }

    /**
     * @return the parent Tet
     */
    public Tet parent() {
        if (l == 0) {
            throw new IllegalStateException("Root tetrahedron has no parent");
        }

        // Use t8code's parent coordinate calculation: parent->x = t->x & ~h;
        int h = length(); // Cell size at current level
        int parentX = x & ~h;
        int parentY = y & ~h;
        int parentZ = z & ~h;

        byte parentLevel = (byte) (l - 1);

        // Determine parent type using connectivity tables
        byte parentType = computeParentType(parentX, parentY, parentZ, parentLevel);

        return new Tet(parentX, parentY, parentZ, parentLevel, parentType);
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
        Tet parentTet = parent();
        return parentTet.child(siblingIndex);
    }

    /**
     * Compute the TM-index (Tetrahedral Morton index) which is globally unique across all levels. Based on the
     * algorithm from TMIndexSimple.tetToTMIndex().
     *
     * The TM-index interleaves coordinate bits with tetrahedral type information, creating a space-filling curve index
     * that includes both spatial position and the complete ancestor type hierarchy for global uniqueness.
     */
    public TetreeKey tmIndex() {
        // PERFORMANCE: Check cache first
        var cached = TetreeLevelCache.getCachedTetreeKey(x, y, z, l, type);
        if (cached != null) {
            return cached;
        }

        if (l == 0) {
            return ROOT_TET;
        }

        // For TM-index, we only process bits up to the current level
        // This matches the reference implementation behavior
        int maxBits = l;

        // PERFORMANCE: Check parent chain cache first
        var cachedChain = TetreeLevelCache.getCachedParentChain(this);
        List<Byte> ancestorTypes = new ArrayList<>();
        
        if (cachedChain != null) {
            // Use cached parent chain to build ancestor types
            for (int i = 1; i < cachedChain.length; i++) { // Skip self at index 0
                if (cachedChain[i] != null) {
                    ancestorTypes.addFirst(cachedChain[i].type());
                }
            }
        } else {
            // Build parent chain and cache it
            var chain = new ArrayList<Tet>();
            chain.add(this);
            
            // Get ancestor types by walking up the tree
            Tet current = this;

            // Collect types from parent up to root
            while (current.l() > 1) {
                current = current.parent();
                if (current != null) {
                    ancestorTypes.addFirst(current.type());
                    chain.add(current);
                }
            }
            
            // Cache the parent chain for future use
            TetreeLevelCache.cacheParentChain(this, chain.toArray(new Tet[0]));
        }

        // Build type array for the bits we'll process (ancestor types + current type)
        int[] typeArray = new int[maxBits];

        // Fill ancestor types from the most significant bits
        for (int i = 0; i < ancestorTypes.size() && i < maxBits; i++) {
            typeArray[i] = ancestorTypes.get(i);
        }

        // Set current type at the least significant position
        if (l > 0 && ancestorTypes.size() < maxBits) {
            typeArray[maxBits - 1] = type;
        }

        // Build TM-index by interleaving coordinate bits with type information
        BigInteger index = BigInteger.ZERO;
        BigInteger sixty_four = BigInteger.valueOf(64);

        // Process each bit position from most significant to least
        // For level L, we need to extract bits [MAX_LEVEL-1, MAX_LEVEL-2, ..., MAX_LEVEL-L]

        for (int i = 0; i < maxBits; i++) {
            // Extract bit at position: start from highest significant bit for this level
            int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
            int xBit = (x >> bitPos) & 1;
            int yBit = (y >> bitPos) & 1;
            int zBit = (z >> bitPos) & 1;

            // Combine coordinate bits (z is MSB, x is LSB in this encoding)
            int coordBits = (zBit << 2) | (yBit << 1) | xBit;

            // Combine with type bits: upper 3 bits are coords, lower 3 bits are type
            int sixBits = (coordBits << 3) | typeArray[i];

            // Add to result
            index = index.multiply(sixty_four).add(BigInteger.valueOf(sixBits));
        }

        var result = new TetreeKey(l, index);
        
        // PERFORMANCE: Cache result before returning
        TetreeLevelCache.cacheTetreeKey(x, y, z, l, type, result);
        
        return result;
    }

    public Point3i[] vertices() {
        var origin = new Point3i(x, y, z);
        var vertices = new Point3i[4];
        int i = 0;
        for (var vertex : Constants.SIMPLEX_STANDARD[type()]) {
            vertices[i] = new Point3i(vertex.x, vertex.y, vertex.z);
            vertices[i].scaleAdd(length(), origin);
            i++;
        }
        return vertices;
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
            long index = tet.tmIndex().getTmIndex().longValue();
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

    // Create a spatial volume from bounds for final filtering
    private Spatial createSpatialFromBounds(VolumeBounds bounds) {
        return new Spatial.aabb(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(),
                                bounds.maxZ());
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

    /**
     * Helper: Get cube-id from local index and parent type Using Table 6 from the paper
     */
    private int getCubeIdFromLocal(int parentType, int localIndex) {
        // Use the system's PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID table
        return Constants.PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID[parentType][localIndex];
    }

    // Get spatial range metadata for optimized queries
    private SpatialRangeMetaData getSpatialRangeMetaData(VolumeBounds bounds, byte level) {
        var touched = calculateTouchedDimensions(bounds, level);

        // Calculate representative location ID
        int length = Constants.lengthAtLevel(level);
        int centerX = (int) ((bounds.minX() + bounds.maxX()) / (2 * length)) * length;
        int centerY = (int) ((bounds.minY() + bounds.maxY()) / (2 * length)) * length;
        int centerZ = (int) ((bounds.minZ() + bounds.maxZ()) / (2 * length)) * length;

        // Create representative tetrahedron and get its SFC location
        var tet = new Tet(centerX, centerY, centerZ, level, (byte) 0);
        long locationID = tet.tmIndex().getTmIndex().longValue();

        return new SpatialRangeMetaData(level, locationID, touched);
    }

    // Get bounding box of a tetrahedron for quick filtering
    private VolumeBounds getTetrahedronBounds(Tet tet) {
        var vertices = tet.coordinates();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;

        for (var vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }

        return new VolumeBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Helper: Get type from local index and parent type Using Table 8 from the paper
     */
    private int getTypeFromLocal(int parentType, int localIndex) {
        // Use the system's PARENT_TYPE_LOCAL_INDEX_TO_TYPE table which is identical
        return Constants.PARENT_TYPE_LOCAL_INDEX_TO_TYPE[parentType][localIndex];
    }

    // Extract bounding box from various spatial volume types
    private VolumeBounds getVolumeBounds(Spatial volume) {
        return VolumeBounds.from(volume);
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

        // For non-root tetrahedra, we need to validate the type matches the expected type
        // for this position within its cubic cell

        // First, find which type this tetrahedron should have based on its position
        // within the cubic cell using the locateFreudenthal algorithm
        Tet expected = locateFreudenthal(x + cellSize / 2.0f, y + cellSize / 2.0f, z + cellSize / 2.0f, l);

        // The type must match what's expected for this position
        if (expected.type() != type) {
            return false;
        }

        // Validate we can reach the parent (basic parent-child relationship check)
        if (l > 1) {
            try {
                // Ensure we can compute a valid parent
                Tet parent = parent();
                if (parent == null) {
                    return false;
                }

                // Verify this tetrahedron could be a child of its parent
                for (int i = 0; i < 8; i++) {
                    Tet possibleChild = parent.child(i);
                    if (possibleChild.x() == x && possibleChild.y() == y && possibleChild.z() == z
                    && possibleChild.type() == type) {
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

        ranges.sort((a, b) -> Long.compare(a.start, b.start));
        List<SFCRange> merged = new ArrayList<>();
        SFCRange current = ranges.get(0);

        for (int i = 1; i < ranges.size(); i++) {
            SFCRange next = ranges.get(i);
            if (current.end + 1 >= next.start) {
                // Merge overlapping ranges
                current = new SFCRange(current.start, Math.max(current.end, next.end));
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

        // Sort ranges by start index
        ranges.sort((a, b) -> Long.compare(a.start, b.start));

        // Use more aggressive merging for better performance
        List<SFCRange> merged = new ArrayList<>();
        SFCRange current = ranges.get(0);

        for (int i = 1; i < ranges.size(); i++) {
            SFCRange next = ranges.get(i);

            // Merge if ranges overlap or are very close (within a small gap)
            long gap = next.start - current.end;
            if (gap <= 8) { // Allow small gaps to reduce fragmentation
                current = new SFCRange(current.start, Math.max(current.end, next.end));
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
            // Small volumes: use standard computation
            return computeSFCRanges(bounds, includeIntersecting);
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
    private Stream<Long> spatialRangeQuery(VolumeBounds bounds, boolean includeIntersecting) {
        // Choose optimization strategy based on volume characteristics
        var rangeStream = selectOptimalRangeStrategy(bounds, includeIntersecting);

        // Apply optimized range merging and streaming
        return rangeStream.collect(collectingAndThen(toList(), this::mergeRangesOptimized)).stream().flatMap(
        range -> LongStream.rangeClosed(range.start(), range.end()).boxed()).filter(index -> {
            // Final precise filtering for elements that passed SFC range test
            try {
                var tet = Tet.tetrahedron(index);
                if (includeIntersecting) {
                    return tetrahedronIntersectsVolume(tet, createSpatialFromBounds(bounds));
                } else {
                    return tetrahedronContainedInVolume(tet, createSpatialFromBounds(bounds));
                }
            } catch (Exception e) {
                // Skip invalid indices
                return false;
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

    // Record to represent spatial range metadata
    private record SpatialRangeMetaData(byte depthID, long locationID, TouchedDimensions touched) {
    }

    // Record to represent SFC index ranges
    private record SFCRange(long start, long end) {
    }
}
