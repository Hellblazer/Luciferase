package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TetreeBits bitwise operations.
 *
 * @author hal.hildebrand
 */
public class TetreeBitsTest {

    @Test
    public void testBitArithmetic() {
        // Test fast modulo, division, and multiplication
        for (int value = 0; value < 100; value++) {
            assertEquals(value % 8, TetreeBits.mod8(value));
            assertEquals(value / 8, TetreeBits.div8(value));
            assertEquals(value * 8, TetreeBits.mul8(value));
        }

        // Test with larger values
        assertEquals(12345 % 8, TetreeBits.mod8(12345));
        assertEquals(12345 / 8, TetreeBits.div8(12345));
        assertEquals(12345 * 8, TetreeBits.mul8(12345));
    }

    @Test
    public void testComparePackedTets() {
        // Create test tetrahedra
        Tet tet1 = new Tet(0, 0, 0, (byte) 5, (byte) 0);
        Tet tet2 = new Tet(1024, 0, 0, (byte) 5, (byte) 0);
        Tet tet3 = new Tet(0, 0, 0, (byte) 6, (byte) 0);

        long packed1 = TetreeBits.packTet(tet1);
        long packed2 = TetreeBits.packTet(tet2);
        long packed3 = TetreeBits.packTet(tet3);

        // Same tetrahedron
        assertEquals(0, TetreeBits.comparePackedTets(packed1, packed1));

        // Different positions at same level
        assertTrue(TetreeBits.comparePackedTets(packed1, packed2) < 0);

        // Different levels
        assertTrue(TetreeBits.comparePackedTets(packed1, packed3) < 0);
    }

    @Test
    public void testCompareTets() {
        // Test SFC index comparison
        assertEquals(0, TetreeBits.compareTets(100L, 100L));
        assertTrue(TetreeBits.compareTets(50L, 100L) < 0);
        assertTrue(TetreeBits.compareTets(200L, 100L) > 0);

        // Test ordering preservation
        long index1 = 1000L;
        long index2 = 1001L;
        long index3 = 2000L;

        // Adjacent indices maintain order
        assertTrue(TetreeBits.compareTets(index1, index2) < 0);
        assertTrue(TetreeBits.compareTets(index2, index3) < 0);
        assertTrue(TetreeBits.compareTets(index1, index3) < 0);
    }

    @Test
    public void testComputeCubeLevel() {
        // Test cube level computation for various coordinates

        // Root level coordinates
        assertEquals(0, TetreeBits.computeCubeLevel(0, 0, 0));

        // Test various coordinate values
        assertEquals(Constants.getMaxRefinementLevel(), TetreeBits.computeCubeLevel(1, 0, 0));
        assertEquals(Constants.getMaxRefinementLevel() - 1, TetreeBits.computeCubeLevel(2, 0, 0));
        assertEquals(Constants.getMaxRefinementLevel() - 2, TetreeBits.computeCubeLevel(4, 0, 0));
        assertEquals(Constants.getMaxRefinementLevel() - 3, TetreeBits.computeCubeLevel(8, 0, 0));

        // Test with maximum coordinate in different dimensions
        assertEquals(Constants.getMaxRefinementLevel() - 1, TetreeBits.computeCubeLevel(0, 2, 0));
        assertEquals(Constants.getMaxRefinementLevel() - 1, TetreeBits.computeCubeLevel(0, 0, 2));
        assertEquals(Constants.getMaxRefinementLevel() - 2, TetreeBits.computeCubeLevel(3, 4, 2));

        // Test level boundaries
        int cellSize5 = Constants.lengthAtLevel((byte) 5);
        byte level5 = TetreeBits.computeCubeLevel(cellSize5, 0, 0);
        assertTrue(level5 <= 5, "Level should be appropriate for coordinate size");

        // Test with large coordinates
        int largeCoord = 1 << 10; // 1024
        byte levelLarge = TetreeBits.computeCubeLevel(largeCoord, 0, 0);
        assertTrue(levelLarge >= 0 && levelLarge <= Constants.getMaxRefinementLevel());

        // Test symmetry - same result regardless of which coordinate is maximum
        assertEquals(TetreeBits.computeCubeLevel(16, 8, 4), TetreeBits.computeCubeLevel(8, 16, 4));
        assertEquals(TetreeBits.computeCubeLevel(16, 8, 4), TetreeBits.computeCubeLevel(4, 8, 16));
    }

    @Test
    public void testCoordinateXor() {
        // Test XOR of coordinates
        Tet tet1 = new Tet(1024, 2048, 4096, (byte) 5, (byte) 0);
        Tet tet2 = new Tet(1024, 2048, 4096, (byte) 5, (byte) 0);
        Tet tet3 = new Tet(2048, 2048, 4096, (byte) 5, (byte) 0);

        // Same coordinates
        assertEquals(0L, TetreeBits.coordinateXor(tet1, tet2));

        // Different X coordinate
        long xor = TetreeBits.coordinateXor(tet1, tet3);
        assertTrue(xor > 0);

        // Verify XOR properties
        assertEquals(TetreeBits.coordinateXor(tet1, tet3), TetreeBits.coordinateXor(tet3, tet1)); // Commutative
    }

    @Test
    public void testExtractType() {
        // Test type extraction at level 0
        assertEquals(0, TetreeBits.extractType(0L, (byte) 0));

        // Test consistency with Tet.tetrahedron for various indices
        // The SFC index encodes the full path from root, not just local indices
        for (long index : new long[] { 0 }) {
            Tet tet = Tet.tetrahedron(index, (byte) 0);
            byte extractedType = TetreeBits.extractType(index, (byte) 0);
            assertEquals(tet.type(), extractedType,
                         "Type mismatch for index " + index + ", expected " + tet.type() + " but got " + extractedType);
        }
        // Test consistency with Tet.tetrahedron for various indices
        // The SFC index encodes the full path from root, not just local indices
        for (long index : new long[] { 1, 2, 3, 4, 5, 6, 7 }) {
            Tet tet = Tet.tetrahedron(index, (byte) 1);
            byte extractedType = TetreeBits.extractType(index, (byte) 1);
            assertEquals(tet.type(), extractedType,
                         "Type mismatch for index " + index + ", expected " + tet.type() + " but got " + extractedType);
        }
        // Test consistency with Tet.tetrahedron for various indices
        // The SFC index encodes the full path from root, not just local indices
        for (long index : new long[] { 50, 55, 64 }) {
            Tet tet = Tet.tetrahedron(index, (byte) 2);
            byte extractedType = TetreeBits.extractType(index, (byte) 2);
            assertEquals(tet.type(), extractedType,
                         "Type mismatch for index " + index + ", expected " + tet.type() + " but got " + extractedType);
        }
    }

    @Test
    public void testIsAlignedToLevel() {
        // Test coordinate alignment
        for (byte level = 0; level <= 10; level++) {
            int cellSize = Constants.lengthAtLevel(level);

            // Aligned coordinates
            assertTrue(TetreeBits.isAlignedToLevel(0, level));
            assertTrue(TetreeBits.isAlignedToLevel(cellSize, level));
            assertTrue(TetreeBits.isAlignedToLevel(5 * cellSize, level));

            // Misaligned coordinates
            if (cellSize > 1) {
                assertFalse(TetreeBits.isAlignedToLevel(cellSize + 1, level));
                assertFalse(TetreeBits.isAlignedToLevel(cellSize / 2, level));
            }
        }
    }

    @Test
    public void testIsValidIndex() {
        // Test index validation
        assertTrue(TetreeBits.isValidIndex(0L));
        assertTrue(TetreeBits.isValidIndex(1L));
        assertTrue(TetreeBits.isValidIndex(1000L));

        // Negative indices are invalid
        assertFalse(TetreeBits.isValidIndex(-1L));
        assertFalse(TetreeBits.isValidIndex(-1000L));

        // Test boundary cases
        long maxLevel = Constants.getMaxRefinementLevel();
        long approxMaxIndex = (1L << (3 * (maxLevel + 1))) / 7;
        assertTrue(TetreeBits.isValidIndex(approxMaxIndex / 2));
    }

    @Test
    public void testLocalityHash() {
        // Test locality-sensitive hashing
        // Use coordinates that are actually in different grid cells at level 5
        int cellSize5 = Constants.lengthAtLevel((byte) 5);

        Tet tet1 = new Tet(0, 0, 0, (byte) 5, (byte) 0);
        Tet tet2 = new Tet(0, 0, 0, (byte) 5, (byte) 1);
        Tet tet3 = new Tet(cellSize5, cellSize5, cellSize5, (byte) 5, (byte) 0);

        int hash1 = TetreeBits.localityHash(tet1);
        int hash2 = TetreeBits.localityHash(tet2);
        int hash3 = TetreeBits.localityHash(tet3);

        // Same position, different type - hashes should be different
        assertNotEquals(hash1, hash2); // Different due to type

        // Different positions - hashes should be different
        assertNotEquals(hash1, hash3);

        // Verify level and type are encoded in high bits
        assertEquals(5, (hash1 >> 24) & 0xFF); // Level
        assertEquals(0, (hash1 >> 28) & 0xF);  // Type
        assertEquals(1, (hash2 >> 28) & 0xF);  // Type
    }

    @Test
    public void testLowestCommonAncestorLevel() {
        // Test LCA level calculation
        Tet tet1 = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        Tet tet2 = new Tet(0, 0, 0, (byte) 10, (byte) 0);

        // Same tetrahedron
        assertEquals(10, TetreeBits.lowestCommonAncestorLevel(tet1, tet2));

        // Siblings at level 5
        int cellSize5 = Constants.lengthAtLevel((byte) 5);
        Tet tet3 = new Tet(0, 0, 0, (byte) 5, (byte) 0);
        Tet tet4 = new Tet(cellSize5, 0, 0, (byte) 5, (byte) 0);
        assertEquals(4, TetreeBits.lowestCommonAncestorLevel(tet3, tet4));

        // Different levels
        Tet tet5 = new Tet(0, 0, 0, (byte) 3, (byte) 0);
        Tet tet6 = new Tet(0, 0, 0, (byte) 7, (byte) 0);
        assertTrue(TetreeBits.lowestCommonAncestorLevel(tet5, tet6) <= 3);
    }

    @Test
    public void testPackUnpackTet() {
        // Test packing and unpacking various tetrahedra
        Tet tet1 = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        long packed1 = TetreeBits.packTet(tet1);
        Tet unpacked1 = TetreeBits.unpackTet(packed1);
        assertEquals(tet1, unpacked1);

        // Test with different coordinates and levels
        Tet tet2 = new Tet(1024, 2048, 4096, (byte) 5, (byte) 3);
        long packed2 = TetreeBits.packTet(tet2);
        Tet unpacked2 = TetreeBits.unpackTet(packed2);
        assertEquals(tet2, unpacked2);

        // Test at maximum refinement level
        int cellSize = Constants.lengthAtLevel((byte) 21);
        Tet tet3 = new Tet(cellSize * 100, cellSize * 200, cellSize * 300, (byte) 21, (byte) 5);
        long packed3 = TetreeBits.packTet(tet3);
        Tet unpacked3 = TetreeBits.unpackTet(packed3);
        assertEquals(tet3, unpacked3);
    }

    @Test
    public void testParentCoordinate() {
        // Test parent coordinate calculation
        byte childLevel = 5;
        int childCellSize = Constants.lengthAtLevel(childLevel);

        // Test aligned coordinates
        int childCoord1 = 5 * childCellSize;
        int parentCoord1 = TetreeBits.parentCoordinate(childCoord1, childLevel);
        assertEquals((childCoord1 / (childCellSize * 2)) * (childCellSize * 2), parentCoord1);

        // Test offset coordinates
        int childCoord2 = 5 * childCellSize + childCellSize;
        int parentCoord2 = TetreeBits.parentCoordinate(childCoord2, childLevel);
        // Using t8code algorithm: parent->x = t->x & ~h
        // For 6 * cellSize, the parent should be 6 * cellSize (not 5 * cellSize)
        assertEquals(6 * childCellSize, parentCoord2);

        // Test with various levels
        for (byte level = 1; level <= 10; level++) {
            int cellSize = Constants.lengthAtLevel(level);
            int coord = 7 * cellSize;
            int parentCoord = TetreeBits.parentCoordinate(coord, level);
            assertEquals(0, parentCoord % (cellSize * 2), "Parent coordinate not aligned to parent grid");
        }
    }

}
