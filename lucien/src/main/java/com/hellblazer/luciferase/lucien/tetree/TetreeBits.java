package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;

/**
 * Efficient bitwise operations for tetrahedral indices based on t8code algorithms.
 *
 * This class provides low-level bit manipulation utilities for working with tetrahedral space-filling curve indices and
 * coordinates. All methods are static for maximum performance.
 *
 * @author hal.hildebrand
 */
public final class TetreeBits {

    // Bit layout constants for packed indices
    private static final int  LEVEL_BITS  = 5;  // 5 bits for level (0-31, though we use 0-21)
    private static final int  TYPE_BITS   = 3;   // 3 bits for type (0-5)
    private static final int  COORD_BITS  = 21; // 21 bits per coordinate
    // Bit masks
    private static final long LEVEL_MASK  = (1L << LEVEL_BITS) - 1;  // 0x1F
    private static final long TYPE_MASK   = (1L << TYPE_BITS) - 1;    // 0x07
    private static final long COORD_MASK  = (1L << COORD_BITS) - 1;  // 0x1FFFFF
    // Bit shift positions for packed format
    private static final int  LEVEL_SHIFT = 0;
    private static final int  TYPE_SHIFT  = LEVEL_BITS;
    private static final int  X_SHIFT     = LEVEL_BITS + TYPE_BITS;
    private static final int  Y_SHIFT     = X_SHIFT + COORD_BITS;
    private static final int  Z_SHIFT     = Y_SHIFT + COORD_BITS;

    // Private constructor to prevent instantiation
    private TetreeBits() {
        throw new AssertionError("TetreeBits is a utility class");
    }

    /**
     * Fast comparison of two packed tetrahedra representations.
     *
     * @param packed1 packed representation of first tetrahedron
     * @param packed2 packed representation of second tetrahedron
     * @return comparison result
     */
    public static int comparePackedTets(long packed1, long packed2) {
        // Extract levels first (most significant for ordering)
        byte level1 = (byte) (packed1 & LEVEL_MASK);
        byte level2 = (byte) (packed2 & LEVEL_MASK);

        if (level1 != level2) {
            return Byte.compare(level1, level2);
        }

        // Same level, compare by spatial position
        // The packed format preserves spatial ordering within a level
        return Long.compare(packed1, packed2);
    }

    /**
     * Fast comparison of two tetrahedra using their SFC indices. Returns negative if tet1 < tet2, zero if equal,
     * positive if tet1 > tet2.
     *
     * @param tet1Index SFC index of first tetrahedron
     * @param tet2Index SFC index of second tetrahedron
     * @return comparison result
     */
    public static int compareTets(long tet1Index, long tet2Index) {
        // Direct comparison of SFC indices preserves spatial ordering
        return Long.compare(tet1Index, tet2Index);
    }

    /**
     * Compute the XOR of two tetrahedra's coordinates. Useful for finding the highest differing bit (depth of common
     * ancestor).
     *
     * @param tet1 first tetrahedron
     * @param tet2 second tetrahedron
     * @return XOR of coordinates packed into a long
     */
    public static long coordinateXor(Tet tet1, Tet tet2) {
        int xorX = tet1.x() ^ tet2.x();
        int xorY = tet1.y() ^ tet2.y();
        int xorZ = tet1.z() ^ tet2.z();

        // Pack XOR results
        long result = 0L;
        result |= (xorX & COORD_MASK);
        result |= (xorY & COORD_MASK) << COORD_BITS;
        result |= (xorZ & COORD_MASK) << (2 * COORD_BITS);

        return result;
    }

    /**
     * Fast division by 8 using bit shift.
     *
     * @param value the value to divide by 8
     * @return value / 8
     */
    public static int div8(int value) {
        return value >> 3;
    }

    /**
     * Extract the refinement level from a space-filling curve index. This is more efficient than Tet.tetLevelFromIndex
     * for repeated operations.
     *
     * @param sfcIndex the space-filling curve index
     * @return the refinement level (0-21)
     */
    public static byte extractLevel(long sfcIndex) {
        // Use O(1) cached lookup instead of O(log n) numberOfLeadingZeros
        return TetreeLevelCache.getLevelFromIndex(sfcIndex);
    }

    /**
     * Extract the type of a tetrahedron from its SFC index and level. This requires partial decoding of the SFC index.
     *
     * @param sfcIndex the space-filling curve index
     * @param level    the refinement level
     * @return the tetrahedron type (0-5)
     */
    public static byte extractType(long sfcIndex, byte level) {
        if (level == 0) {
            return 0; // Root is always type 0
        }

        // Match the t8code algorithm exactly - no level offset adjustment
        // The SFC index directly encodes the path from root
        byte type = 0;
        int childrenM1 = 7;

        for (int i = 1; i <= level; i++) {
            int offsetIndex = level - i;
            // Get the local index of T's ancestor on level i
            int localIndex = (int) ((sfcIndex >> (3 * offsetIndex)) & childrenM1);

            // Get parent cube ID from lookup table
            byte cubeId = Constants.PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID[type][localIndex];

            // Update type based on parent type and local index
            type = Constants.PARENT_TYPE_LOCAL_INDEX_TO_TYPE[type][localIndex];
        }

        return type;
    }

    /**
     * Check if a coordinate is aligned to the grid at a given level.
     *
     * @param coord the coordinate value
     * @param level the refinement level
     * @return true if aligned, false otherwise
     */
    public static boolean isAlignedToLevel(int coord, byte level) {
        int cellSize = Constants.lengthAtLevel(level);
        return (coord & (cellSize - 1)) == 0;
    }

    /**
     * Check if a tetrahedron index is valid for the maximum refinement level.
     *
     * @param sfcIndex the space-filling curve index
     * @return true if valid, false otherwise
     */
    public static boolean isValidIndex(long sfcIndex) {
        if (sfcIndex < 0) {
            return false;
        }

        // The maximum valid index is based on the SFC encoding
        // At each level L, indices range from 2^(3*(L-1)) to 2^(3*L) - 1
        // We need to check if the index could belong to any valid level

        // Special case for root
        if (sfcIndex == 0) {
            return true;
        }

        // Find which level this index would belong to
        byte level = extractLevel(sfcIndex);

        // Check if level is within valid range
        return level >= 0 && level <= Constants.getMaxRefinementLevel();
    }

    /**
     * Compute a hash code for a tetrahedron that preserves spatial locality. Tetrahedra that are close in space will
     * have similar hash codes.
     *
     * @param tet the tetrahedron
     * @return locality-sensitive hash code
     */
    public static int localityHash(Tet tet) {
        // Use Morton encoding of the coordinates at this level
        // This preserves spatial locality in the hash space
        int cellSize = Constants.lengthAtLevel(tet.l());
        int gridX = tet.x() / cellSize;
        int gridY = tet.y() / cellSize;
        int gridZ = tet.z() / cellSize;

        // Interleave bits for spatial locality
        int hash = 0;
        for (int i = 0; i < 10; i++) { // Use 10 bits from each dimension
            hash |= ((gridX >> i) & 1) << (3 * i);
            hash |= ((gridY >> i) & 1) << (3 * i + 1);
            hash |= ((gridZ >> i) & 1) << (3 * i + 2);
        }

        // Include level and type in high bits
        hash |= (tet.l() << 24);
        hash |= (tet.type() << 28);

        return hash;
    }

    /**
     * Find the level of the lowest common ancestor of two tetrahedra. Based on t8code's t8_dtri_nearest_common_ancestor
     * algorithm.
     *
     * @param tet1 first tetrahedron
     * @param tet2 second tetrahedron
     * @return level of lowest common ancestor
     */
    public static byte lowestCommonAncestorLevel(Tet tet1, Tet tet2) {
        // Find the level of the NCA using XOR to find differing bits
        int exclorx = tet1.x() ^ tet2.x();
        int exclory = tet1.y() ^ tet2.y();
        int exclorz = tet1.z() ^ tet2.z();

        // Combine all differences
        int maxclor = exclorx | exclory | exclorz;

        if (maxclor == 0) {
            // Same coordinates - they share an ancestor at their minimum level
            return (byte) Math.min(tet1.l(), tet2.l());
        }

        // Find the highest set bit position (SC_LOG2_32 in t8code)
        int maxlevel = 31 - Integer.numberOfLeadingZeros(maxclor) + 1;

        // The level of the NCA cube surrounding tet1 and tet2
        // This matches t8code: c_level = SC_MIN(T8_DTRI_MAXLEVEL - maxlevel, SC_MIN(t1->level, t2->level))
        byte c_level = (byte) Math.min(Constants.getMaxRefinementLevel() - maxlevel, Math.min(tet1.l(), tet2.l()));

        // TODO: In t8code, they also check if the types are different at this level
        // and potentially decrease the level further. For now, we return c_level.
        return c_level;
    }

    /**
     * Fast modulo 8 operation using bitwise AND.
     *
     * @param value the value to take modulo 8 of
     * @return value % 8
     */
    public static int mod8(int value) {
        return value & 7;
    }

    /**
     * Fast multiplication by 8 using bit shift.
     *
     * @param value the value to multiply by 8
     * @return value * 8
     */
    public static int mul8(int value) {
        return value << 3;
    }

    /**
     * Pack a Tet into a single long value for efficient storage and comparison.
     *
     * Format (64 bits total): - Bits 0-4:   Level (5 bits) - Bits 5-7:   Type (3 bits) - Bits 8-28:  X coordinate (21
     * bits) - Bits 29-49: Y coordinate (21 bits) - Bits 50-70: Z coordinate (21 bits) - Note: uses 71 bits total, need
     * adjustment
     *
     * @param tet the tetrahedron to pack
     * @return packed representation
     */
    public static long packTet(Tet tet) {
        // For coordinates that may not be aligned to level boundaries,
        // we need to use a different approach. Since we have 64 bits total,
        // we'll allocate bits more carefully to preserve coordinate information.

        // Layout:
        // - Bits 0-4:   Level (5 bits)
        // - Bits 5-7:   Type (3 bits)
        // - Bits 8-25:  X coordinate (18 bits) - allows up to 262,144
        // - Bits 26-43: Y coordinate (18 bits) - allows up to 262,144
        // - Bits 44-61: Z coordinate (18 bits) - allows up to 262,144
        // Total: 62 bits (fits in 64-bit long)

        long packed = 0L;
        packed |= (tet.l() & LEVEL_MASK);
        packed |= (tet.type() & TYPE_MASK) << TYPE_SHIFT;
        packed |= ((long) (tet.x() & 0x3FFFF)) << 8;   // 18 bits for X
        packed |= ((long) (tet.y() & 0x3FFFF)) << 26;  // 18 bits for Y
        packed |= ((long) (tet.z() & 0x3FFFF)) << 44;  // 18 bits for Z

        return packed;
    }

    /**
     * Calculate the parent coordinate from a child coordinate at a given level. Uses bit manipulation to clear the
     * appropriate low-order bit.
     *
     * @param childCoord the child's coordinate value
     * @param childLevel the child's refinement level
     * @return the parent's coordinate value
     */
    public static int parentCoordinate(int childCoord, byte childLevel) {
        if (childLevel == 0) {
            throw new IllegalArgumentException("Root tetrahedron has no parent");
        }

        // The cell size at this level
        int cellSize = Constants.lengthAtLevel(childLevel);

        // Clear the bit that distinguishes children within a parent cell
        // This is exactly what t8code does: parent->x = t->x & ~h;
        // This works because coordinates must be aligned to the cell grid
        return childCoord & ~cellSize;
    }

    /**
     * Unpack a Tet from a packed long value.
     *
     * @param packed the packed representation
     * @return the unpacked Tet
     */
    public static Tet unpackTet(long packed) {
        byte level = (byte) (packed & LEVEL_MASK);
        byte type = (byte) ((packed >> TYPE_SHIFT) & TYPE_MASK);

        // Extract coordinates directly (18 bits each)
        int x = (int) ((packed >> 8) & 0x3FFFF);   // 18 bits for X
        int y = (int) ((packed >> 26) & 0x3FFFF);  // 18 bits for Y
        int z = (int) ((packed >> 44) & 0x3FFFF);  // 18 bits for Z

        return new Tet(x, y, z, level, type);
    }

    /**
     * Compute the cube level that would contain the given tetrahedral coordinates.
     * This is based on t8code's cube level computation where the tetrahedron
     * is embedded within a cube at a specific refinement level.
     *
     * @param x the x coordinate
     * @param y the y coordinate  
     * @param z the z coordinate
     * @return the cube level (0 = root level)
     */
    public static byte computeCubeLevel(int x, int y, int z) {
        // Find the maximum coordinate to determine the required level
        int maxCoord = Math.max(Math.max(x, y), z);
        
        if (maxCoord == 0) {
            return 0; // Root level
        }
        
        // Find the highest bit position in the maximum coordinate
        // This tells us the minimum level needed to contain this coordinate
        int highBit = 31 - Integer.numberOfLeadingZeros(maxCoord);
        
        // The cube level is determined by how many times we need to subdivide
        // the root cube to get cells small enough to contain the coordinate
        // At level L, the cell size is Constants.ROOT_CELL_SIZE >> L
        // We need: cell_size > max_coordinate, so L >= log2(ROOT_CELL_SIZE / max_coordinate)
        
        // For our tetrahedral space, we use Constants.getMaxRefinementLevel() as reference
        // The level is essentially: max_refinement_level - log2(max_coordinate)
        byte level = (byte) Math.max(0, Constants.getMaxRefinementLevel() - highBit);
        
        // Clamp to valid range
        return (byte) Math.min(level, Constants.getMaxRefinementLevel());
    }
}
