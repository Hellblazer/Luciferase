package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.Geometry;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Tuple3f;
import javax.vecmath.Tuple3i;
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

    // Compact constructor for validation
    public Tet {
        if (l < 0 || l > getMaxRefinementLevel()) {
            throw new IllegalArgumentException("Level must be in range [0, " + getMaxRefinementLevel() + "]: " + l);
        }
        if (type < 0 || type > 5) {
            throw new IllegalArgumentException("Type must be in range [0, 5]: " + type);
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

    public static boolean contains(Point3i[] vertices, Tuple3f point) {
        // wrt face CDB
        if (orientation(point, vertices[2], vertices[3], vertices[1]) > 0.0d) {
            return false;
        }
        // wrt face DCA
        if (orientation(point, vertices[3], vertices[2], vertices[0]) > 0.0d) {
            return false;
        }
        // wrt face BDA
        if (orientation(point, vertices[1], vertices[3], vertices[0]) > 0.0d) {
            return false;
        }
        // wrt face BAC
        return orientation(point, vertices[1], vertices[0], vertices[2]) <= 0.0d;
    }

    /**
     * Check if a set of tetrahedra form a family (can be merged)
     *
     * @param tets array of tetrahedra to check
     * @return true if they form a valid family
     */
    public static boolean isFamily(Tet[] tets) {
        if (tets == null || tets.length != TetreeConnectivity.CHILDREN_PER_TET) {
            return false;
        }

        // All must be at same level
        byte level = tets[0].l;
        for (Tet tet : tets) {
            if (tet.l != level) {
                return false;
            }
        }

        // Check if they all have the same parent
        Tet parent0 = tets[0].parent();
        for (int i = 1; i < tets.length; i++) {
            Tet parent = tets[i].parent();
            if (!parent.equals(parent0)) {
                return false;
            }
        }

        // Verify they are all distinct children of the parent
        boolean[] childFound = new boolean[TetreeConnectivity.CHILDREN_PER_TET];
        for (Tet tet : tets) {
            // Determine which child this is
            for (int childIdx = 0; childIdx < TetreeConnectivity.CHILDREN_PER_TET; childIdx++) {
                Tet expectedChild = parent0.child(childIdx);
                if (tet.equals(expectedChild)) {
                    if (childFound[childIdx]) {
                        return false; // Duplicate child
                    }
                    childFound[childIdx] = true;
                    break;
                }
            }
        }

        // All children must be present
        for (boolean found : childFound) {
            if (!found) {
                return false;
            }
        }

        return true;
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
        if (index < 0) {
            throw new IllegalArgumentException("Index must be non-negative: " + index);
        }

        if (index == 0) {
            return 0; // Root tetrahedron
        }

        // Find the highest set bit position
        int highBit = 63 - Long.numberOfLeadingZeros(index);

        // Each level uses 3 bits, so divide by 3 and add 1
        // Add 1 because level 1 uses bits 0-2, level 2 uses bits 3-5, etc.
        byte level = (byte) ((highBit / 3) + 1);

        // Ensure we don't exceed max level
        if (level > getMaxRefinementLevel()) {
            return getMaxRefinementLevel();
        }

        return level;
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
     * <p><b>IMPORTANT:</b> The SFC index directly encodes the complete path from root.
     * No level offset adjustment is needed (unlike Morton codes).</p>
     *
     * @param index the consecutive SFC index of the tetrahedron
     * @param level the refinement level of the target tetrahedron
     * @return the Tet corresponding to the given index and level
     */
    public static Tet tetrahedron(long index, byte level) {
        byte type = 0;
        int childrenM1 = 7;  // Mask for 3 bits (8 children - 1)
        var coordinates = new int[3];

        // Traverse from root to target level
        for (int i = 1; i <= level; i++) {
            var offsetCoords = getMaxRefinementLevel() - i;
            var offsetIndex = level - i;

            // Extract 3 bits for the local index at this level
            var localIndex = (int) ((index >> (3 * offsetIndex)) & childrenM1);

            // Look up cube position and child type from connectivity tables
            var cid = PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID[type][localIndex];
            type = PARENT_TYPE_LOCAL_INDEX_TO_TYPE[type][localIndex];

            // Accumulate coordinate bits based on cube position
            coordinates[0] |= (cid & 1) > 0 ? 1 << offsetCoords : 0;
            coordinates[1] |= (cid & 2) > 0 ? 1 << offsetCoords : 0;
            coordinates[2] |= (cid & 4) > 0 ? 1 << offsetCoords : 0;
        }
        return new Tet(coordinates[0], coordinates[1], coordinates[2], level, type);
    }

    /**
     * @param index - the consecutive index of the tetrahedron
     * @return the Tet corresponding to the consecutive index
     */
    public static Tet tetrahedron(long index) {
        return tetrahedron(index, tetLevelFromIndex(index));
    }

    /**
     * @param volume - the enclosing volume
     * @return the Stream of indexes in the SFC locating the Tets bounded by the volume
     */
    public Stream<Long> boundedBy(Spatial volume) {
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return Stream.empty();
        }

        return spatialRangeQuery(bounds, false).filter(index -> {
            var tet = Tet.tetrahedron(index);
            return tetrahedronContainedInVolume(tet, volume);
        });
    }

    /**
     * @param childIndex - the child id (0-7 in Bey's order)
     * @return the i-th child of the receiver
     */

    /**
     * @param volume the volume to contain
     * @return the Stream of indexes in the SFC locating the Tets that minimally bound the volume
     */
    public Stream<Long> bounding(Spatial volume) {
        var bounds = getVolumeBounds(volume);
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

        // Get Bey child ID from Morton index using parent type
        byte beyChildId = TetreeConnectivity.getBeyChildId(type, childIndex);

        // Get child type from connectivity table  
        byte childType = Constants.TYPE_TO_TYPE_OF_CHILD[type][beyChildId];
        byte childLevel = (byte) (l + 1);

        // For Bey child 0, use parent anchor directly (interior child)
        if (beyChildId == 0) {
            return new Tet(x, y, z, childLevel, childType);
        }

        // For other children, compute position as midpoint between parent anchor and vertex
        // This is the t8code algorithm: child anchor = (parent anchor + parent vertex) / 2
        byte vertex = TetreeConnectivity.getBeyVertex(beyChildId);
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
        long thisIndex = this.index();
        long otherIndex = other.index();
        return Long.compare(thisIndex, otherIndex);
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

        byte type = this.type;
        for (byte i = l; i > level; i--) {
            /* compute type as the type of T^{i+1}, that is T's ancestor of level i+1 */
            type = CUBE_ID_TYPE_TO_PARENT_TYPE[cubeId(i)][type];
        }
        return type;
    }

    public boolean contains(Tuple3f point) {
        return contains(coordinates(), point);
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
        id |= ((x & h) > 0 ? (byte) 1 : 0);
        id |= ((y & h) > 0 ? (byte) 2 : 0);
        id |= ((z & h) > 0 ? (byte) 4 : 0);
        return id;
    }

    /**
     * @param volume - the volume to enclose
     * @return - index in the SFC of the minimum Tet enclosing the volume
     */
    public long enclosing(Spatial volume) {
        // Extract bounding box of the volume
        var bounds = getVolumeBounds(volume);
        if (bounds == null) {
            return 0L;
        }

        // Find the minimum level that can contain the volume
        byte level = findMinimumContainingLevel(bounds);

        // Find a tetrahedron at that level that contains the volume
        var centerPoint = new Point3f((bounds.minX() + bounds.maxX()) / 2, (bounds.minY() + bounds.maxY()) / 2,
                                      (bounds.minZ() + bounds.maxZ()) / 2);

        var tet = locate(centerPoint, level);
        return tet.index();
    }

    /**
     * @param point - the point to enclose
     * @param level - refinement level for enclosure
     * @return the simplex at the provided
     */
    public long enclosing(Tuple3f point, byte level) {
        var tet = locate(new Point3f(point.x, point.y, point.z), level);
        return tet.index();
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
            return this.index();
        }

        // The first descendant is found by repeatedly taking child 0
        // This follows the SFC ordering where child 0 has the smallest index
        Tet current = this;
        while (current.l < level) {
            current = current.child(0); // Always take the first child
        }
        return current.index();
    }

    /**
     * Compute the space-filling curve index of this tetrahedron.
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
     * <p><b>CRITICAL:</b> The index encodes the complete path with NO level offset.</p>
     * Each level contributes exactly 3 bits to the final index.
     *
     * @return the consecutive SFC index (0 for root, 1-7 for level 1, etc.)
     */
    public long index() {
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

        // Return the raw SFC index without level offset (matching t8code)
        return id;
    }

    public long intersecting(Spatial volume) {
        // Simple implementation: find first intersecting tetrahedron
        var bounds = getVolumeBounds(volume);
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
        if (x >= maxCoord || y >= maxCoord || z >= maxCoord) {
            return false;
        }

        return true;
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
            return this.index();
        }

        // The last descendant is found by repeatedly taking child 7
        // This follows the SFC ordering where child 7 has the largest index
        Tet current = this;
        while (current.l < level) {
            current = current.child(7); // Always take the last child
        }
        return current.index();
    }

    /**
     * @return the length of an edge at the given level, in integer coordinates
     */
    public int length() {
        return 1 << (getMaxRefinementLevel() - l);
    }

    // Helper method - locate tetrahedron containing a point using direct containment test
    public Tet locate(Point3f point, byte level) {
        var length = Constants.lengthAtLevel(level);
        var c0 = new Point3i((int) (Math.floor(point.x / length) * length),
                             (int) (Math.floor(point.y / length) * length),
                             (int) (Math.floor(point.z / length) * length));

        // Test all 6 tetrahedron types at this grid location to find which one contains the point
        for (byte type = 0; type < 6; type++) {
            var testTet = new Tet(c0.x, c0.y, c0.z, level, type);
            if (testTet.contains(point)) {
                return testTet;
            }
        }

        // Fallback: if no tetrahedron contains the point (shouldn't happen), return type 0
        // This could happen due to floating-point precision issues at boundaries
        return new Tet(c0.x, c0.y, c0.z, level, (byte) 0);
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
            long index = tet.index();
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
     * <p><b>Vertex Numbering Convention:</b></p>
     * The 4 vertices are numbered 0-3 according to the simplex type definition in Constants.SIMPLEX. Each tetrahedron
     * type has a specific vertex arrangement.
     *
     * <p><b>Algorithm (from t8code's t8_dtet_compute_coords):</b></p>
     * <ol>
     *   <li>Get relative vertex positions from simplex type definition</li>
     *   <li>Scale by cell size (edge length at this level)</li>
     *   <li>Add to anchor point to get absolute coordinates</li>
     * </ol>
     *
     * @param vertex vertex number (0-3)
     * @return absolute coordinates of the vertex
     * @throws IllegalArgumentException if vertex is not in range [0,3]
     */
    private Point3i computeVertexCoordinates(int vertex) {
        if (vertex < 0 || vertex > 3) {
            throw new IllegalArgumentException("Vertex must be 0-3: " + vertex);
        }

        int h = length(); // Cell size at this level

        // Get vertex coordinates from simplex definition
        Point3i[] simplexVertices = Constants.SIMPLEX[type];
        Point3i relativeVertex = simplexVertices[vertex];

        // Scale relative coordinates by cell size and add to anchor
        int vertexX = x + relativeVertex.x * h;
        int vertexY = y + relativeVertex.y * h;
        int vertexZ = z + relativeVertex.z * h;

        return new Point3i(vertexX, vertexY, vertexZ);
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
        long locationID = tet.index();

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

    // Check if a tetrahedron is completely contained within a volume
    private boolean tetrahedronContainedInVolume(Tet tet, Spatial volume) {
        var vertices = tet.coordinates();
        var bounds = getVolumeBounds(volume);
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
    private boolean tetrahedronContainedInVolumeBounds(Tet tet, VolumeBounds bounds) {
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
    private boolean tetrahedronIntersectsVolume(Tet tet, Spatial volume) {
        var vertices = tet.coordinates();
        var bounds = getVolumeBounds(volume);
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
    private boolean tetrahedronIntersectsVolumeBounds(Tet tet, VolumeBounds bounds) {
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

        return false; // More sophisticated intersection tests could be added here
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
