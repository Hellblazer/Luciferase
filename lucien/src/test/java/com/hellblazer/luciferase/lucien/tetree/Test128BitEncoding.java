package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test to verify 128-bit encoding works correctly
 */
public class Test128BitEncoding {

    @Test
    public void testBitPackingOrder() {
        System.out.println("\n=== Testing Bit Packing Order ===");

        // Create a tet at level 3 with specific bit pattern
        // We want x=1, y=2, z=4 at grid level (before scaling)
        int gridX = 1, gridY = 2, gridZ = 4;
        byte level = 3;
        int scale = 1 << (MortonCurve.MAX_REFINEMENT_LEVEL - level);

        var tet = new Tet(gridX * scale, gridY * scale, gridZ * scale, level, (byte) 0);
        var key = tet.tmIndex();

        System.out.println("Grid coords: (" + gridX + ", " + gridY + ", " + gridZ + ")");
        System.out.println("Actual coords: " + tet);

        // For this coordinate pattern:
        // Level 0: All zeros -> child 0
        // Level 1: x=1, y=0, z=0 -> child 1
        // Level 2: x=0, y=1, z=0 -> child 2
        // (bits are extracted from MSB to LSB)

        long lowBits = key.getLowBits();
        System.out.println("\nExtracting bit pattern from low bits:");
        for (int i = 0; i < level; i++) {
            long sixBits = (lowBits >> (6 * i)) & 0x3F;
            int coords = (int) (sixBits >> 3) & 0x7;
            int type = (int) sixBits & 0x7;

            // Extract individual coordinate bits
            int xBit = coords & 1;
            int yBit = (coords >> 1) & 1;
            int zBit = (coords >> 2) & 1;

            System.out.printf("Level %d: coords=%d (x=%d,y=%d,z=%d) type=%d\n", i, coords, xBit, yBit, zBit, type);
        }
    }

    @Test
    public void testEncodingConsistency() {
        System.out.println("=== Testing 128-bit Encoding Consistency ===\n");

        // Test a simple case at level 2
        int x = 1, y = 2, z = 1;  // Reduce z to stay within bounds
        byte level = 2;
        byte type = 3;

        // Create a Tet and get its TM-index using proper grid alignment
        int cellSize = 1 << (MortonCurve.MAX_REFINEMENT_LEVEL - level);  // Cell size at level 2
        Tet tet = new Tet(x * cellSize, y * cellSize, z * cellSize, level, type);
        var key = tet.tmIndex();

        System.out.println("Test Tet: " + tet);
        System.out.println("ExtendedTetreeKey: " + key);

        // Manual calculation to verify
        // Level 0: x=0, y=0, z=0, type=0
        // Level 1: x=0, y=0, z=1, type should transform based on child index 4
        // For coords at level 2: x=1, y=2, z=4
        // Bit 0 (level 0): x=0, y=0, z=0 -> child 0
        // Bit 1 (level 1): x=1, y=0, z=0 -> child 1

        System.out.println("\n128-bit representation:");
        System.out.printf("Low bits:  0x%016X\n", key.getLowBits());
        System.out.printf("High bits: 0x%016X\n", key.getHighBits());

        // Extract the first few 6-bit groups from low bits
        for (int i = 0; i < Math.min(level, 10); i++) {
            long sixBits = (key.getLowBits() >> (6 * i)) & 0x3F;
            int coordBits = (int) (sixBits >> 3) & 0x7;
            int typeBits = (int) (sixBits & 0x7);
            System.out.printf("Level %d: sixBits=0x%02X coords=%d (x=%d,y=%d,z=%d) type=%d\n", i, sixBits, coordBits,
                              coordBits & 1, (coordBits >> 1) & 1, (coordBits >> 2) & 1, typeBits);
        }

        // Test consistency across multiple examples
        testConsistencyForMultipleTets();
    }

    private void testConsistencyForMultipleTets() {
        System.out.println("\n=== Testing Multiple Tets ===");

        // Test cases with known coordinates
        int[][] testCases = { { 0, 0, 0, 0, 0 },    // root
                              { 1, 0, 0, 1, 0 },    // level 1, child 1
                              { 0, 1, 0, 1, 0 },    // level 1, child 2
                              { 0, 0, 1, 1, 0 },    // level 1, child 4
                              { 1, 1, 1, 1, 0 },    // level 1, child 7
                              { 2, 3, 1, 3, 2 },    // level 3
                              { 7, 5, 3, 3, 1 },    // level 3
        };

        for (int[] tc : testCases) {
            int scale = 1 << (MortonCurve.MAX_REFINEMENT_LEVEL - tc[3]); // scale to actual coordinates
            Tet tet = new Tet(tc[0] * scale, tc[1] * scale, tc[2] * scale, (byte) tc[3], (byte) tc[4]);
            var key = tet.tmIndex();

            System.out.printf("\nTet(x=%d, y=%d, z=%d, l=%d, t=%d):\n", tc[0] * scale, tc[1] * scale, tc[2] * scale,
                              tc[3], tc[4]);
            System.out.printf("  Low bits:  0x%016X\n", key.getLowBits());
            System.out.printf("  High bits: 0x%016X\n", key.getHighBits());

            // Verify the TM-index is deterministic
            var key2 = tet.tmIndex();
            assertEquals(key, key2, "TM-index should be deterministic");
            assertEquals(key.getLowBits(), key2.getLowBits(), "Low bits should match");
            assertEquals(key.getHighBits(), key2.getHighBits(), "High bits should match");
        }
    }
}
