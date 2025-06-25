package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import com.hellblazer.luciferase.lucien.Constants;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Tetrahedral Morton Index (TM-Index) implementation in Java Using the simple Tet structure from the paper (Section 4)
 * 
 * Note: This implementation uses a different child ordering scheme than the main Tetree implementation.
 * - CHILD_TYPES differs from TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE
 * - LOCAL_INDICES differs from TetreeConnectivity.INDEX_TO_BEY_NUMBER
 * - PARENT_TYPES matches Constants.CUBE_ID_TYPE_TO_PARENT_TYPE (so we use the system table)
 * 
 * The differences in child ordering suggest this implements the TM-index paper's specific
 * ordering rather than the t8code Bey refinement ordering used in the main Tetree.
 */
public class TMIndexSimple {

    // Table 1: Child types - Ct(parent_type, child_index)
    // Note: Different from TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE due to different child ordering
    private static final byte[][] CHILD_TYPES   = { { 0, 1, 4, 7, 2, 3, 6, 5 }, // Parent type 0
                                                    { 1, 1, 5, 7, 2, 3, 6, 4 }, // Parent type 1
                                                    { 2, 0, 4, 7, 1, 2, 6, 5 }, // Parent type 2
                                                    { 3, 1, 6, 7, 2, 3, 4, 5 }, // Parent type 3
                                                    { 4, 0, 5, 7, 1, 2, 4, 6 }, // Parent type 4
                                                    { 5, 0, 6, 7, 2, 1, 4, 5 }  // Parent type 5
    };
    // Table 2: Local indices - Iloc(parent_type, bey_child_index)
    // Note: Different from TetreeConnectivity.INDEX_TO_BEY_NUMBER due to different indexing scheme
    private static final byte[][] LOCAL_INDICES = { { 0, 1, 4, 7, 2, 3, 6, 5 }, // Parent type 0
                                                    { 0, 1, 5, 7, 2, 3, 6, 4 }, // Parent type 1
                                                    { 0, 3, 4, 7, 1, 2, 6, 5 }, // Parent type 2
                                                    { 0, 1, 6, 7, 2, 3, 4, 5 }, // Parent type 3
                                                    { 0, 3, 5, 7, 1, 2, 4, 6 }, // Parent type 4
                                                    { 0, 3, 6, 7, 2, 1, 4, 5 }  // Parent type 5
    };
    // Table 8/Figure 8: Parent types - Using Constants.CUBE_ID_TYPE_TO_PARENT_TYPE
    // This table is identical to PARENT_TYPES, so we'll use the system's existing table
    private final        int      maxLevel;

    public TMIndexSimple(int maxLevel) {
        this.maxLevel = maxLevel;
    }

    /**
     * Demo the implementation
     */
    public static void main(String[] args) {
        TMIndexSimple tmIndex = new TMIndexSimple(8);

        System.out.println("=== TM-Index Implementation with Simple Tet Structure ===\n");

        // Test 1: Create tetrahedra and compute parents
        System.out.println("Test 1: Parent computation");
        Tet tet1 = new Tet(2, 1, 0, (byte) 2, (byte) 0);
        System.out.println("Tet: " + tet1);
        Tet parent1 = tmIndex.parent(tet1);
        System.out.println("Parent: " + parent1);

        // Test 2: Compute children
        System.out.println("\nTest 2: Child computation");
        Tet root = new Tet(0, 0, 0, (byte) 1, (byte) 0);
        System.out.println("Parent: " + root);
        System.out.println("Children (Bey ordering):");
        for (int i = 0; i < 8; i++) {
            Tet child = tmIndex.child(root, i);
            System.out.println("  T" + i + ": " + child);
        }

        // Test 3: TM-ordering children
        System.out.println("\nChildren (TM ordering):");
        for (int i = 0; i < 8; i++) {
            Tet child = tmIndex.tmChild(root, i);
            System.out.println("  Local " + i + ": " + child);
        }

        // Test 4: Compute TM-index
        System.out.println("\nTest 4: TM-index computation");
        BigInteger idx1 = tmIndex.tetToTMIndex(tet1);
        System.out.println("Tet: " + tet1);
        System.out.println("TM-Index: " + idx1);

        // Test 5: Consecutive index
        System.out.println("\nTest 5: Consecutive index");
        BigInteger consIdx = tmIndex.computeConsecutiveIndex(tet1);
        System.out.println("Consecutive Index: " + consIdx);

        // Test 6: Verify parent-child relationship
        System.out.println("\nTest 6: Parent-child verification");
        Tet level3 = new Tet(5, 3, 2, (byte) 3, (byte) 3);
        System.out.println("Level 3 tet: " + level3);
        Tet p2 = tmIndex.parent(level3);
        System.out.println("Level 2 parent: " + p2);
        Tet p1 = tmIndex.parent(p2);
        System.out.println("Level 1 parent: " + p1);

        // Verify ancestor types
        int[] ancestors = tmIndex.getAncestorTypes(level3);
        System.out.print("Ancestor types: [");
        for (int i = 0; i < ancestors.length; i++) {
            System.out.print(ancestors[i]);
            if (i < ancestors.length - 1) {
                System.out.print(", ");
            }
        }
        System.out.println("]");

        // Test 7: Round-trip conversion TM-index <-> Tet
        System.out.println("\nTest 7: Round-trip conversion");
        Tet original = new Tet(3, 2, 1, (byte) 3, (byte) 2);
        System.out.println("Original: " + original);

        BigInteger tmIdx = tmIndex.tetToTMIndex(original);
        System.out.println("TM-Index: " + tmIdx);

        Tet recovered = tmIndex.tmIndexToTet(tmIdx, 3);
        System.out.println("Recovered: " + recovered);
        System.out.println("Round-trip success: " + original.equals(recovered));

        // Test 8: Consecutive index round-trip
        System.out.println("\nTest 8: Consecutive index round-trip");
        BigInteger consIdx2 = tmIndex.computeConsecutiveIndex(original);
        System.out.println("Consecutive Index: " + consIdx2);

        Tet fromConsecutive = tmIndex.consecutiveIndexToTet(consIdx2, 3);
        System.out.println("From Consecutive: " + fromConsecutive);
        System.out.println("Consecutive round-trip success: " + original.equals(fromConsecutive));

        // Test 9: Edge cases
        System.out.println("\nTest 9: Edge cases");

        // Root tetrahedron
        Tet rootTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        BigInteger rootTmIdx = tmIndex.tetToTMIndex(rootTet);
        Tet rootRecovered = tmIndex.tmIndexToTet(rootTmIdx, 0);
        System.out.println("Root: " + rootTet + " -> TM-Index: " + rootTmIdx + " -> Recovered: " + rootRecovered);

        // Level 1 tetrahedron
        Tet level1 = new Tet(0, 0, 0, (byte) 1, (byte) 0);
        BigInteger level1Idx = tmIndex.tetToTMIndex(level1);
        Tet level1Recovered = tmIndex.tmIndexToTet(level1Idx, 1);
        System.out.println("Level 1: " + level1 + " -> TM-Index: " + level1Idx + " -> Recovered: " + level1Recovered);
    }

    /**
     * Algorithm 4.4: Compute i-th child in Bey's ordering
     */
    public Tet child(Tet t, int i) {
        // First compute anchor coordinates
        int[][] coordinates = getCoordinates(t);

        int j;
        if (i == 0) {
            j = 0;
        } else if (i == 1 || i == 4 || i == 5) {
            j = 1;
        } else if (i == 2 || i == 6 || i == 7) {
            j = 2;
        } else { // i == 3
            j = 3;
        }

        int childX = (coordinates[0][0] + coordinates[j][0]) / 2;
        int childY = (coordinates[0][1] + coordinates[j][1]) / 2;
        int childZ = (coordinates[0][2] + coordinates[j][2]) / 2;
        byte childType = CHILD_TYPES[t.type()][i];
        Tet child = new Tet(childX, childY, childZ, (byte) (t.l() + 1), childType);

        return child;
    }

    /**
     * Algorithm 4.7: Compute consecutive index
     */
    public BigInteger computeConsecutiveIndex(Tet t) {
        BigInteger index = BigInteger.ZERO;
        BigInteger eight = BigInteger.valueOf(8);

        if (t.l() == 0) {
            return index;
        }

        // Build path from root to current tetrahedron
        List<Integer> localIndices = new ArrayList<>();
        Tet current = t;

        while (current.l() > 0) {
            Tet p = parent(current);
            if (p != null) {
                // Find which child of parent we are
                int localIdx = -1;

                // Find local index by checking all children
                for (int i = 0; i < 8; i++) {
                    Tet testChild = child(p, i);
                    if (testChild.x() == current.x() && testChild.y() == current.y() && testChild.z() == current.z()
                    && testChild.type() == current.type()) {
                        localIdx = LOCAL_INDICES[p.type()][i];
                        break;
                    }
                }

                localIndices.addFirst(localIdx);
                current = p;
            } else {
                break;
            }
        }

        // Build consecutive index from local indices
        for (int localIdx : localIndices) {
            index = index.multiply(eight).add(BigInteger.valueOf(localIdx));
        }

        return index;
    }

    /**
     * Alternative: Convert from consecutive index to Tet Algorithm 4.8 from the paper
     */
    public Tet consecutiveIndexToTet(BigInteger consecutiveIndex, int level) {
        if (level == 0) {
            return new Tet(0, 0, 0, (byte) 0, (byte) 0);
        }

        // Initialize result
        int x = 0, y = 0, z = 0;
        int currentType = 0; // Start at root type

        // Extract local indices from consecutive index
        int[] localIndices = new int[level];
        BigInteger idx = consecutiveIndex;
        BigInteger eight = BigInteger.valueOf(8);

        // Extract from right to left (least significant first)
        for (int i = level - 1; i >= 0; i--) {
            localIndices[i] = idx.mod(eight).intValue();
            idx = idx.divide(eight);
        }

        // Build tetrahedron by following path from root
        for (int i = 0; i < level; i++) {
            int localIdx = localIndices[i];

            // Get cube-id and type from tables
            int cubeId = getCubeIdFromLocal(currentType, localIdx);
            int childType = getTypeFromLocal(currentType, localIdx);

            // Update coordinates based on cube-id
            int h = 1 << (maxLevel - i - 1);
            if ((cubeId & 1) != 0) {
                x += h;
            }
            if ((cubeId & 2) != 0) {
                y += h;
            }
            if ((cubeId & 4) != 0) {
                z += h;
            }

            // Update current type for next iteration
            currentType = childType;
        }

        return new Tet(x, y, z, (byte) level, (byte) currentType);
    }

    /**
     * Algorithm 4.3: Compute parent of a tetrahedron
     */
    public Tet parent(Tet t) {
        if (t.l() == 0) {
            return null; // Root has no parent
        }

        int h = 1 << (maxLevel - t.l());
        int px = t.x() & ~h;
        int py = t.y() & ~h;
        int pz = t.z() & ~h;
        byte ptype = Constants.CUBE_ID_TYPE_TO_PARENT_TYPE[getCubeId(t, t.l())][t.type()];

        return new Tet(px, py, pz, (byte) (t.l() - 1), ptype);
    }

    /**
     * Compute TM-index from a tetrahedron
     */
    public BigInteger tetToTMIndex(Tet tet) {
        // Get ancestor types by walking up the tree
        int[] ancestorTypes = getAncestorTypes(tet);

        // Convert coordinates to binary arrays
        boolean[] X = toBinaryArray(tet.x(), maxLevel);
        boolean[] Y = toBinaryArray(tet.y(), maxLevel);
        boolean[] Z = toBinaryArray(tet.z(), maxLevel);

        // Build B array (ancestor types + current type + zeros)
        int[] B = new int[maxLevel];
        for (int i = 0; i < ancestorTypes.length && i < maxLevel; i++) {
            B[maxLevel - 1 - i] = ancestorTypes[i];
        }
        if (tet.l() > 0 && tet.l() <= maxLevel) {
            B[maxLevel - tet.l()] = tet.type();
        }

        // Compute TM-index by interleaving
        BigInteger index = BigInteger.ZERO;
        BigInteger multiplier = BigInteger.valueOf(64);

        for (int i = 0; i < maxLevel; i++) {
            int zBit = Z[i] ? 1 : 0;
            int yBit = Y[i] ? 1 : 0;
            int xBit = X[i] ? 1 : 0;
            int coordBits = (zBit << 2) | (yBit << 1) | xBit;
            int typeBits = B[i];
            int contribution = coordBits * 8 + typeBits;
            index = index.multiply(multiplier).add(BigInteger.valueOf(contribution));
        }

        return index;
    }

    /**
     * Algorithm 4.5: Compute i-th child in TM-ordering
     */
    public Tet tmChild(Tet t, int i) {
        // Convert from TM-ordering to Bey's ordering
        int beyIndex = getBeyChildFromLocal(t.type(), i);
        return child(t, beyIndex);
    }

    /**
     * Convert TM-index back to tetrahedron Inverse operation of tetToTMIndex
     */
    public Tet tmIndexToTet(BigInteger tmIndex, int level) {
        if (level == 0) {
            return new Tet(0, 0, 0, (byte) 0, (byte) 0); // Root tetrahedron
        }

        // Extract interleaved bits
        int[] coordX = new int[maxLevel];
        int[] coordY = new int[maxLevel];
        int[] coordZ = new int[maxLevel];
        int[] types = new int[maxLevel];

        BigInteger index = tmIndex;
        BigInteger sixtyFour = BigInteger.valueOf(64);

        // Extract from least significant to most significant
        for (int i = maxLevel - 1; i >= 0; i--) {
            BigInteger[] divRem = index.divideAndRemainder(sixtyFour);
            index = divRem[0];
            int value = divRem[1].intValue();

            // Extract type (lower 3 bits)
            types[i] = value % 8;

            // Extract coordinate bits (upper 3 bits)
            int coordBits = value / 8;
            coordX[i] = coordBits & 1;
            coordY[i] = (coordBits >> 1) & 1;
            coordZ[i] = (coordBits >> 2) & 1;
        }

        // Reconstruct coordinates from binary
        int x = 0, y = 0, z = 0;
        for (int i = 0; i < maxLevel; i++) {
            x = (x << 1) | coordX[i];
            y = (y << 1) | coordY[i];
            z = (z << 1) | coordZ[i];
        }

        // Current type is at position corresponding to level
        int type = level > 0 ? types[maxLevel - level] : 0;

        return new Tet(x, y, z, (byte) level, (byte) type);
    }

    /**
     * Build ancestor type array by traversing up to root
     */
    private int[] getAncestorTypes(Tet tet) {
        if (tet.l() == 0) {
            return new int[0];
        }

        List<Integer> types = new ArrayList<>();
        Tet current = tet;

        // Walk up the tree collecting types
        while (current.l() > 1) {
            current = parent(current);
            types.addFirst((int) current.type()); // Insert at beginning
        }

        // Convert to array
        int[] result = new int[types.size()];
        for (int i = 0; i < types.size(); i++) {
            result[i] = types.get(i);
        }
        return result;
    }

    /**
     * Helper: Get Bey child index from local (TM) index
     */
    private int getBeyChildFromLocal(int parentType, int localIndex) {
        for (int i = 0; i < 8; i++) {
            if (LOCAL_INDICES[parentType][i] == localIndex) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Algorithm 4.1: Get coordinates of all vertices of a tetrahedron
     */
    private int[][] getCoordinates(Tet t) {
        int[][] x = new int[4][3];
        x[0][0] = t.x();
        x[0][1] = t.y();
        x[0][2] = t.z();

        int h = 1 << (maxLevel - t.l());
        int i = t.type() / 2;
        int j;

        if (t.type() % 2 == 0) {
            j = (i + 2) % 3;
        } else {
            j = (i + 1) % 3;
        }

        x[1][0] = x[0][0];
        x[1][1] = x[0][1];
        x[1][2] = x[0][2];
        x[1][i] += h;

        x[2][0] = x[1][0];
        x[2][1] = x[1][1];
        x[2][2] = x[1][2];
        x[2][j] += h;

        x[3][0] = x[0][0] + h;
        x[3][1] = x[0][1] + h;
        x[3][2] = x[0][2] + h;

        return x;
    }

    /**
     * Algorithm 4.2: Compute cube-id at given level
     */
    private int getCubeId(Tet tet, int level) {
        int h = 1 << (maxLevel - level);
        int i = 0;
        if ((tet.x() & h) != 0) {
            i |= 1;
        }
        if ((tet.y() & h) != 0) {
            i |= 2;
        }
        if ((tet.z() & h) != 0) {
            i |= 4;
        }
        return i;
    }

    /**
     * Helper: Get cube-id from local index and parent type Using Table 6 from the paper
     */
    private int getCubeIdFromLocal(int parentType, int localIndex) {
        // Use the system's PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID table
        return Constants.PARENT_TYPE_LOCAL_INDEX_TO_CUBE_ID[parentType][localIndex];
    }

    /**
     * Helper: Get type from local index and parent type Using Table 8 from the paper
     */
    private int getTypeFromLocal(int parentType, int localIndex) {
        // Use the system's PARENT_TYPE_LOCAL_INDEX_TO_TYPE table which is identical
        return Constants.PARENT_TYPE_LOCAL_INDEX_TO_TYPE[parentType][localIndex];
    }

    private boolean[] toBinaryArray(int value, int bits) {
        boolean[] result = new boolean[bits];
        for (int i = 0; i < bits; i++) {
            result[i] = ((value >> (bits - 1 - i)) & 1) == 1;
        }
        return result;
    }
}
